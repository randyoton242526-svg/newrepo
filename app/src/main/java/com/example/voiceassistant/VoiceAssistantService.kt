package com.example.voiceassistant

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

class VoiceAssistantService : Service() {

    companion object {
        private const val TAG           = "VAService"
        const val EXTRA_RETRY           = "extra_retry"
        const val MODEL_DIR_NAME        = "models"
        const val VOSK_MODEL_SUBDIR     = "vosk-model"
        private const val VOSK_ZIP_URL  =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceAssistantService = this@VoiceAssistantService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    interface UiCallbacks {
        fun onTranscript(text: String)
        fun onResponse(text: String)
        fun onSystemMessage(text: String)
        fun onAmplitude(value: Float)
        fun onDownloadProgress(pct: Int)
    }
    @Volatile var uiCallbacks: UiCallbacks? = null

    private lateinit var voiceRecognizer  : VoiceRecognizerManager
    private lateinit var modelExecutor    : LocalModelExecutor
    private lateinit var actionDispatcher : ActionDispatcher
    private lateinit var downloadManager  : ModelDownloadManager
    private var tts: TextToSpeech? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    // Prevents Vosk from processing its own TTS output
    @Volatile private var isSpeaking = false

    override fun onCreate() {
        super.onCreate()
        PipelineLogger.log("SERVICE", "onCreate()")
        downloadManager = ModelDownloadManager(this)
        NotificationHelper.createChannel(this)
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            NotificationHelper.buildNotification(this, "Starting up…")
        )
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceAssistant::WakeLock")
            .also { it.acquire(30 * 60 * 1_000L) }
        initTts()
        startPipeline()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_RETRY, false) == true) {
            PipelineLogger.clear()
            AssistantStateManager.forceReset()
            startPipeline()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.launch {
            if (::voiceRecognizer.isInitialized) voiceRecognizer.release()
            if (::modelExecutor.isInitialized)   modelExecutor.release()
        }
        serviceScope.cancel()
        tts?.stop(); tts?.shutdown()
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    // =========================================================================
    // Pipeline
    // =========================================================================

    private fun startPipeline() {
        serviceScope.launch {
            val model    = ModelConfig.ACTIVE
            val modelDir = File(filesDir, MODEL_DIR_NAME).also { it.mkdirs() }

            actionDispatcher = ActionDispatcher(applicationContext)

            voiceRecognizer = VoiceRecognizerManager(
                modelPath   = File(modelDir, VOSK_MODEL_SUBDIR).absolutePath,
                onPartial   = { text ->
                    // Ignore partials while TTS is speaking to avoid feedback
                    if (!isSpeaking)
                        postUi { uiCallbacks?.onSystemMessage("Hearing: \"$text\"") }
                },
                onFinal     = { text ->
                    if (!isSpeaking && text.isNotBlank()) {
                        postUi { uiCallbacks?.onTranscript(text) }
                        processTranscript(text)
                    }
                },
                onAmplitude = { amp -> postUi { uiCallbacks?.onAmplitude(amp) } },
                onError     = { msg ->
                    AssistantStateManager.transitionTo(AssistantStateManager.State.ERROR, msg)
                    postUi { uiCallbacks?.onSystemMessage("STT error: $msg") }
                }
            )

            modelExecutor = LocalModelExecutor(
                modelFilePath = File(modelDir, model.fileName).absolutePath,
                modelConfig   = model,
                onTokenStream = { token -> postUi { uiCallbacks?.onSystemMessage(token) } },
                onError       = { msg ->
                    AssistantStateManager.transitionTo(AssistantStateManager.State.ERROR, msg)
                    postUi { uiCallbacks?.onSystemMessage("LLM error: $msg") }
                }
            )

            // ── STEP A: Download GGUF model if not present ─────────────────
            if (!downloadManager.isModelReady(model.fileName, minBytes = 100_000_000L)) {
                PipelineLogger.log("SERVICE", "GGUF model missing — downloading")
                updateNotification("Downloading AI model (0%)")
                AssistantStateManager.transitionTo(AssistantStateManager.State.LOADING_LLM)
                postUi { uiCallbacks?.onSystemMessage("📥 Downloading AI model (~935 MB)…\nThis only happens once.") }

                var ok = false
                downloadManager.downloadModel(
                    shareableLink = "https://drive.google.com/file/d/${model.driveFileId}/view",
                    destFileName  = model.fileName,
                    expectedBytes = model.expectedBytes,
                    onProgress    = { pct ->
                        updateNotification("Downloading AI model ($pct%)")
                        postUi { uiCallbacks?.onDownloadProgress(pct) }
                        if (pct % 25 == 0 && pct > 0)
                            postUi { uiCallbacks?.onSystemMessage("📥 Download: $pct% complete") }
                    },
                    onComplete    = { file ->
                        postUi { uiCallbacks?.onSystemMessage("✅ Model downloaded (${file.length()/1_000_000} MB). Loading…") }
                        ok = true
                    },
                    onError       = { msg ->
                        AssistantStateManager.transitionTo(AssistantStateManager.State.ERROR, "Download failed: $msg")
                        postUi { uiCallbacks?.onSystemMessage("⚠️ Download error: $msg") }
                    }
                )
                if (!ok) return@launch
            }

            // ── STEP A2: Download Vosk model if not present ────────────────
            val voskDir = File(modelDir, VOSK_MODEL_SUBDIR)
            if (!voskDir.exists() || !File(voskDir, "am/final.mdl").exists()) {
                PipelineLogger.log("SERVICE", "Vosk model missing — downloading")
                AssistantStateManager.transitionTo(AssistantStateManager.State.LOADING_STT)
                updateNotification("Downloading speech model…")
                postUi { uiCallbacks?.onSystemMessage("📥 Downloading speech model (~40 MB)…") }
                downloadAndExtractVosk(modelDir)
                if (AssistantStateManager.current == AssistantStateManager.State.ERROR) return@launch
            }

            // ── STEP B: Init Vosk STT ──────────────────────────────────────
            AssistantStateManager.transitionTo(AssistantStateManager.State.LOADING_STT)
            updateNotification("Loading speech engine…")
            voiceRecognizer.init()
            if (AssistantStateManager.current == AssistantStateManager.State.ERROR) return@launch

            // ── STEP C: Init LLM executor ──────────────────────────────────
            AssistantStateManager.transitionTo(AssistantStateManager.State.LOADING_LLM)
            updateNotification("Loading language model…")
            modelExecutor.init()
            if (AssistantStateManager.current == AssistantStateManager.State.ERROR) return@launch

            // ── STEP D: Ready ──────────────────────────────────────────────
            AssistantStateManager.transitionTo(AssistantStateManager.State.IDLE)
            updateNotification("Ready — tap mic to speak")
            postUi { uiCallbacks?.onSystemMessage("🟢 Tap the mic button to speak a command!") }
            PipelineLogger.log("SERVICE", "Pipeline complete — IDLE")
        }
    }

    // =========================================================================
    // Vosk model download + extract
    // =========================================================================

    private suspend fun downloadAndExtractVosk(modelDir: File) = withContext(Dispatchers.IO) {
        val zipFile = File(modelDir, "vosk-model.zip")
        val destDir = File(modelDir, VOSK_MODEL_SUBDIR)
        try {
            val conn = URL(VOSK_ZIP_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout    = 60_000
            val total = conn.contentLengthLong
            var downloaded = 0L
            val buf = ByteArray(8192)

            BufferedInputStream(conn.inputStream).use { inp ->
                FileOutputStream(zipFile).use { out ->
                    var read: Int
                    while (inp.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt()
                            updateNotification("Downloading speech model ($pct%)")
                        }
                    }
                }
            }
            conn.disconnect()

            updateNotification("Extracting speech model…")
            postUi { uiCallbacks?.onSystemMessage("📦 Extracting speech model…") }
            destDir.mkdirs()

            ZipInputStream(FileInputStream(zipFile)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val parts   = entry.name.split("/", limit = 2)
                    val relPath = if (parts.size > 1) parts[1] else ""
                    if (relPath.isNotEmpty()) {
                        val outFile = File(destDir, relPath)
                        if (entry.isDirectory) outFile.mkdirs()
                        else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { zip.copyTo(it) }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            zipFile.delete()
            postUi { uiCallbacks?.onSystemMessage("✅ Speech model ready!") }
            PipelineLogger.log("SERVICE", "Vosk extracted to ${destDir.absolutePath}")

        } catch (e: Exception) {
            zipFile.delete()
            val msg = "Failed to download speech model: ${e.message}"
            Log.e(TAG, msg, e)
            AssistantStateManager.transitionTo(AssistantStateManager.State.ERROR, msg)
            postUi { uiCallbacks?.onSystemMessage("⚠️ $msg") }
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    fun startListening() {
        if (AssistantStateManager.current != AssistantStateManager.State.IDLE) return
        AssistantStateManager.transitionTo(AssistantStateManager.State.LISTENING)
        updateNotification("Listening… speak now")
        serviceScope.launch { voiceRecognizer.startListening() }
    }

    fun stopListening() {
        voiceRecognizer.stopListening()
        if (AssistantStateManager.current == AssistantStateManager.State.LISTENING) {
            AssistantStateManager.transitionTo(AssistantStateManager.State.IDLE)
            updateNotification("Ready — tap mic to speak")
        }
    }

    // =========================================================================
    // Command processing — stops mic FIRST, speaks AFTER, then returns to IDLE
    // =========================================================================

    private fun processTranscript(transcript: String) {
        serviceScope.launch {
            // ── 1. STOP LISTENING IMMEDIATELY (prevents TTS feedback loop) ──
            voiceRecognizer.stopListening()

            AssistantStateManager.transitionTo(AssistantStateManager.State.PROCESSING)
            updateNotification("Processing…")
            PipelineLogger.log("LLM", "Processing: \"$transcript\"")

            val raw = modelExecutor.infer(transcript)
            if (raw.isNullOrBlank()) {
                AssistantStateManager.transitionTo(AssistantStateManager.State.IDLE)
                updateNotification("Ready — tap mic to speak")
                return@launch
            }

            AssistantStateManager.transitionTo(AssistantStateManager.State.EXECUTING)
            updateNotification("Executing…")
            val result = actionDispatcher.dispatch(raw)
            postUi { uiCallbacks?.onResponse(result.responseText) }
            PipelineLogger.log("LLM", "Response: ${result.responseText}")

            // ── 2. SPEAK RESPONSE and WAIT until TTS fully finishes ────────
            speakAndWait(result.responseText)

            // ── 3. Only NOW return to IDLE (user must tap mic again) ────────
            delay(500) // extra buffer so mic doesn't catch end of speech
            AssistantStateManager.transitionTo(AssistantStateManager.State.IDLE)
            updateNotification("Ready — tap mic to speak")
            PipelineLogger.log("SERVICE", "Back to IDLE after response")
        }
    }

    /** Speaks text via TTS and suspends until the utterance is fully done. */
    private suspend fun speakAndWait(text: String) = suspendCancellableCoroutine { cont ->
        isSpeaking = true
        val uid = "utt_${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?)  {}
            override fun onDone(id: String?)   { isSpeaking = false; if (cont.isActive) cont.resume(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?)  { isSpeaking = false; if (cont.isActive) cont.resume(Unit) }
        })
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid)
        if (tts == null || result == TextToSpeech.ERROR) {
            isSpeaking = false
            if (cont.isActive) cont.resume(Unit)
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.95f)
                PipelineLogger.log("TTS", "TTS ready")
            }
        }
    }

    private fun postUi(block: () -> Unit) {
        serviceScope.launch(Dispatchers.Main) { block() }
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildNotification(this, status))
    }
}
