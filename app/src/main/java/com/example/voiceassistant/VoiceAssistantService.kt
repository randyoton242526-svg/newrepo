package com.example.voiceassistant

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

/**
 * VoiceAssistantService  (FINAL — auto-download on first launch)
 * --------------------------------------------------------------
 * On startup this service:
 *   1. Calls startForeground() immediately (required by Android 14+).
 *   2. Checks if the GGUF model file already exists locally.
 *   3a. If MISSING  →  downloads it from the hardcoded Google Drive link,
 *       streaming live progress to the UI (status text + notification).
 *   3b. If PRESENT  →  skips straight to model init.
 *   4. Loads Vosk STT model.
 *   5. Loads the GGUF model via llama.cpp JNI.
 *   6. Transitions to IDLE — user can now tap the mic.
 *
 * No ADB, no manual file copying, no separate setup screen required.
 */
class VoiceAssistantService : Service() {

    companion object {
        private const val TAG              = "VAService"
        const val EXTRA_RETRY              = "extra_retry"
        const val MODEL_DIR_NAME           = "models"
        const val VOSK_MODEL_SUBDIR        = "vosk-model"
    }

    // ---- Binder -------------------------------------------------------------
    inner class LocalBinder : Binder() {
        fun getService(): VoiceAssistantService = this@VoiceAssistantService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ---- UI Callbacks -------------------------------------------------------
    interface UiCallbacks {
        fun onTranscript(text: String)
        fun onResponse(text: String)
        fun onSystemMessage(text: String)
        fun onAmplitude(value: Float)
        fun onDownloadProgress(pct: Int)          // NEW: live download progress
    }
    @Volatile var uiCallbacks: UiCallbacks? = null

    // ---- Core components ----------------------------------------------------
    private lateinit var voiceRecognizer  : VoiceRecognizerManager
    private lateinit var modelExecutor    : LocalModelExecutor
    private lateinit var actionDispatcher : ActionDispatcher
    private lateinit var downloadManager  : ModelDownloadManager
    private var tts: TextToSpeech? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        PipelineLogger.log("SERVICE", "onCreate()")

        downloadManager = ModelDownloadManager(this)

        // 1. Channel + foreground notification — MUST happen before slow work
        NotificationHelper.createChannel(this)
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            NotificationHelper.buildNotification(this, "Starting up…")
        )
        PipelineLogger.log("SERVICE", "startForeground() done")

        // 2. Wake lock
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VoiceAssistant::WakeLock"
        ).also { it.acquire(30 * 60 * 1_000L) }   // 30-min max

        // 3. TTS (must be on main thread)
        initTts()

        // 4. Start the full pipeline (download if needed, then init models)
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
    // Pipeline: download (if needed) → init models → IDLE
    // =========================================================================

    private fun startPipeline() {
        serviceScope.launch {
            val model    = ModelConfig.ACTIVE
            val modelDir = File(filesDir, MODEL_DIR_NAME)

            // Wire components
            actionDispatcher = ActionDispatcher(applicationContext)

            voiceRecognizer = VoiceRecognizerManager(
                modelPath   = File(modelDir, VOSK_MODEL_SUBDIR).absolutePath,
                onPartial   = { text -> postUi { uiCallbacks?.onSystemMessage("Hearing: \"$text\"") } },
                onFinal     = { text ->
                    postUi { uiCallbacks?.onTranscript(text) }
                    processTranscript(text)
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

            // ------------------------------------------------------------------
            // STEP A: Download model if not present
            // ------------------------------------------------------------------
            if (!downloadManager.isModelReady(model.fileName, minBytes = 100_000_000L)) {
                PipelineLogger.log("SERVICE", "Model not found — starting auto-download")
                updateNotification("Downloading model (0%)")
                AssistantStateManager.transitionTo(AssistantStateManager.State.LOADING_LLM)
                postUi {
                    uiCallbacks?.onSystemMessage(
                        "📥 Downloading AI model (~935 MB) from Google Drive…\n" +
                        "This only happens once. Please keep the app open."
                    )
                }

                var downloadOk = false
                downloadManager.downloadModel(
                    shareableLink  = "https://drive.google.com/file/d/${model.driveFileId}/view",
                    destFileName   = model.fileName,
                    expectedBytes  = model.expectedBytes,
                    onProgress     = { pct ->
                        updateNotification("Downloading model ($pct%)")
                        postUi { uiCallbacks?.onDownloadProgress(pct) }
                        // Show milestone messages in chat log
                        if (pct % 25 == 0 && pct > 0) {
                            postUi {
                                uiCallbacks?.onSystemMessage("📥 Download: $pct% complete")
                            }
                        }
                    },
                    onComplete     = { file ->
                        PipelineLogger.log("SERVICE",
                            "Download complete: ${file.length() / 1_000_000} MB")
                        postUi {
                            uiCallbacks?.onSystemMessage(
                                "✅ Model downloaded (${file.length() / 1_000_000} MB). Loading…"
                            )
                        }
                        downloadOk = true
                    },
                    onError        = { msg ->
                        AssistantStateManager.transitionTo(
                            AssistantStateManager.State.ERROR,
                            "Download failed: $msg"
                        )
                        postUi { uiCallbacks?.onSystemMessage("⚠️ Download error: $msg") }
                    }
                )

                if (!downloadOk) {
                    PipelineLogger.log("SERVICE", "Download failed — aborting pipeline")
                    return@launch
                }
            } else {
                PipelineLogger.log("SERVICE",
                    "Model already present (${downloadManager.modelPath(model.fileName)})")
            }

            // ------------------------------------------------------------------
            // STEP B: Load Vosk STT
            // ------------------------------------------------------------------
            AssistantStateManager.transitionTo(AssistantStateManager.State.LOADING_STT)
            updateNotification("Loading speech engine…")
            voiceRecognizer.init()
            if (AssistantStateManager.current == AssistantStateManager.State.ERROR) return@launch

            // ------------------------------------------------------------------
            // STEP C: Load GGUF model via llama.cpp
            // ------------------------------------------------------------------
            AssistantStateManager.transitionTo(AssistantStateManager.State.LOADING_LLM)
            updateNotification("Loading language model…")
            modelExecutor.init()
            if (AssistantStateManager.current == AssistantStateManager.State.ERROR) return@launch

            // ------------------------------------------------------------------
            // STEP D: Ready!
            // ------------------------------------------------------------------
            AssistantStateManager.transitionTo(AssistantStateManager.State.IDLE)
            updateNotification("Ready")
            postUi {
                uiCallbacks?.onSystemMessage("🟢 Assistant ready. Tap the mic button to speak!")
            }
            PipelineLogger.log("SERVICE", "Pipeline complete — IDLE")
        }
    }

    // =========================================================================
    // Public control API (called from MainActivity)
    // =========================================================================

    fun startListening() {
        if (AssistantStateManager.current != AssistantStateManager.State.IDLE) return
        AssistantStateManager.transitionTo(AssistantStateManager.State.LISTENING)
        updateNotification("Listening…")
        serviceScope.launch {
            voiceRecognizer.startListening()
            if (AssistantStateManager.current == AssistantStateManager.State.LISTENING) {
                AssistantStateManager.transitionTo(AssistantStateManager.State.IDLE)
                updateNotification("Ready")
            }
        }
    }

    fun stopListening() = voiceRecognizer.stopListening()

    // =========================================================================
    // Internal pipeline
    // =========================================================================

    private fun processTranscript(transcript: String) {
        serviceScope.launch {
            AssistantStateManager.transitionTo(AssistantStateManager.State.PROCESSING)
            updateNotification("Processing…")

            val raw = modelExecutor.infer(transcript)
            if (raw.isNullOrBlank()) {
                if (AssistantStateManager.current != AssistantStateManager.State.ERROR) {
                    AssistantStateManager.transitionTo(AssistantStateManager.State.IDLE)
                    updateNotification("Ready")
                }
                return@launch
            }

            AssistantStateManager.transitionTo(AssistantStateManager.State.EXECUTING)
            updateNotification("Executing…")
            val result = actionDispatcher.dispatch(raw)
            postUi { uiCallbacks?.onResponse(result.responseText) }
            tts?.speak(result.responseText, TextToSpeech.QUEUE_FLUSH, null, "tts")

            AssistantStateManager.transitionTo(AssistantStateManager.State.IDLE)
            updateNotification("Ready")
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                PipelineLogger.log("TTS", "TTS ready")
            }
        }
    }

    private fun postUi(block: () -> Unit) {
        serviceScope.launch(Dispatchers.Main) { block() }
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(
            NotificationHelper.NOTIFICATION_ID,
            NotificationHelper.buildNotification(this, status)
        )
    }
}
