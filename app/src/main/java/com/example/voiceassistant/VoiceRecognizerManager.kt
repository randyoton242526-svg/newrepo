package com.example.voiceassistant

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException
import java.io.File

/**
 * VoiceRecognizerManager
 * ----------------------
 * Encapsulates all audio-recording and offline STT logic using:
 *   • Android’s [AudioRecord] for raw PCM capture
 *   • Vosk for chunk-by-chunk on-device transcription
 *
 * LIFECYCLE:
 *   1. Call [init] once (from a coroutine) to unpack and load the Vosk model.
 *   2. Call [startListening] to begin recording + transcription.
 *   3. Listen for results via [onResultCallback].
 *   4. Call [stopListening] or [release] when done.
 *
 * THREADING:
 *   All blocking work runs on Dispatchers.IO. UI callbacks are posted back
 *   via [onResultCallback] which the caller should forward to the main thread.
 *
 * MODEL PLACEMENT:
 *   Place the Vosk model folder (e.g. vosk-model-small-en-us-0.15) in:
 *     app/src/main/assets/model/
 *   The folder structure inside it should be:
 *     am/final.mdl
 *     conf/model.conf
 *     graph/HCLG.fst (or similar)
 *     ivector/final.ie (optional for small models)
 */
class VoiceRecognizerManager(
    private val storageService: StorageService?,   // pass null to load model from assets manually
    private val modelPath: String                   // absolute path to unpacked Vosk model dir
) {

    companion object {
        private const val TAG         = "VoiceRecognizerManager"
        private const val SAMPLE_RATE = 16_000      // Vosk requires 16 kHz
        private const val CHANNEL_IN  = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING    = AudioFormat.ENCODING_PCM_16BIT
        /** ms of silence before we consider speech finished */
        private const val SILENCE_MS  = 1_500L
    }

    // ---- State --------------------------------------------------------------

    enum class State { IDLE, INITIALISING, READY, LISTENING, STOPPED, ERROR }

    @Volatile var state: State = State.IDLE
        private set

    // ---- Callbacks ----------------------------------------------------------

    /** Invoked (from IO thread) when Vosk produces a final transcript. */
    var onResultCallback: ((transcript: String) -> Unit)? = null

    /** Invoked when a partial (intermediate) result is available. */
    var onPartialCallback: ((partial: String) -> Unit)? = null

    /** Invoked when the recognizer encounters an error. */
    var onErrorCallback: ((error: String) -> Unit)? = null

    // ---- Internal -----------------------------------------------------------

    private var voskModel: Model?      = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var listeningJob: Job?     = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- Public API ---------------------------------------------------------

    /**
     * Loads the Vosk model from [modelPath].
     * Must be called before [startListening]. Safe to call from any thread.
     *
     * @throws IOException if the model directory is missing or corrupt.
     */
    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        state = State.INITIALISING
        return@withContext try {
            val modelDir = File(modelPath)
            if (!modelDir.exists()) {
                Log.e(TAG, "Vosk model directory not found at $modelPath")
                onErrorCallback?.invoke("STT model not found. Please copy the Vosk model to $modelPath")
                state = State.ERROR
                false
            } else {
                voskModel   = Model(modelPath)
                recognizer  = Recognizer(voskModel, SAMPLE_RATE.toFloat())
                state       = State.READY
                Log.i(TAG, "Vosk model loaded successfully from $modelPath")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise Vosk: ${e.message}", e)
            onErrorCallback?.invoke("STT init error: ${e.message}")
            state = State.ERROR
            false
        }
    }

    /**
     * Begins audio capture and real-time STT transcription.
     * Silently ignored if the recognizer is not in [State.READY].
     */
    fun startListening() {
        if (state != State.READY) {
            Log.w(TAG, "startListening called while state=$state, ignoring.")
            return
        }
        state = State.LISTENING
        listeningJob = scope.launch { recordLoop() }
    }

    /**
     * Stops recording. The final partial result is emitted as a complete
     * result if Vosk hasn’t already done so. State returns to [State.READY]
     * so [startListening] can be called again.
     */
    fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        audioRecord?.let {
            if (it.state == AudioRecord.STATE_INITIALIZED) {
                it.stop()
                it.release()
            }
        }
        audioRecord = null
        // Emit whatever was partially recognized
        recognizer?.let { rec ->
            val finalResult = rec.finalResult
            val transcript  = parseVoskResult(finalResult)
            if (transcript.isNotBlank()) {
                onResultCallback?.invoke(transcript)
            }
        }
        // Reset Vosk recognizer for the next session
        recognizer?.reset()
        state = State.READY
    }

    /** Releases all resources. Call from onDestroy or equivalent. */
    fun release() {
        stopListening()
        scope.cancel()
        recognizer?.close()
        recognizer = null
        voskModel?.close()
        voskModel = null
        state = State.STOPPED
        Log.i(TAG, "VoiceRecognizerManager released.")
    }

    // ---- Core record loop ---------------------------------------------------

    /**
     * The main recording coroutine.
     * Runs entirely on Dispatchers.IO so the main thread is never touched.
     *
     * Chunk size is calculated from [SAMPLE_RATE] for ~40 ms of audio per read,
     * balancing latency vs. CPU load on constrained hardware.
     */
    private suspend fun recordLoop() = withContext(Dispatchers.IO) {
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val bufferSize = maxOf(minBufSize, SAMPLE_RATE / 25 * 2) // ~40 ms window

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_IN,
            ENCODING,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise (state=${recorder.state})")
            onErrorCallback?.invoke("Microphone initialisation failed.")
            state = State.READY
            return@withContext
        }

        audioRecord = recorder
        recorder.startRecording()
        Log.i(TAG, "AudioRecord started (bufferSize=$bufferSize bytes)")

        val buffer       = ShortArray(bufferSize / 2)
        var silenceStart = 0L
        var speechDetected = false

        try {
            while (isActive && state == State.LISTENING) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                // Feed raw PCM to Vosk
                val accepted = recognizer?.acceptWaveForm(buffer, read) ?: false

                if (accepted) {
                    // Vosk has a complete sentence
                    val result = recognizer?.result ?: continue
                    val text   = parseVoskResult(result)
                    if (text.isNotBlank()) {
                        Log.d(TAG, "Final result: $text")
                        onResultCallback?.invoke(text)
                        speechDetected = false
                        silenceStart   = 0L
                    }
                } else {
                    // Partial result — useful for live UI updates
                    val partial = recognizer?.partialResult ?: continue
                    val text    = parseVoskPartial(partial)
                    if (text.isNotBlank()) {
                        onPartialCallback?.invoke(text)
                        speechDetected = true
                        silenceStart   = System.currentTimeMillis()
                    } else if (speechDetected && silenceStart > 0L) {
                        // Check silence duration; auto-stop after SILENCE_MS
                        if (System.currentTimeMillis() - silenceStart >= SILENCE_MS) {
                            Log.d(TAG, "Silence threshold reached. Auto-stopping.")
                            break  // exit loop; caller’s stopListening() will clean up
                        }
                    }
                }
            }
        } finally {
            // Always release the hardware resource
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
            recorder.release()
            audioRecord = null
            Log.i(TAG, "AudioRecord released in recordLoop finally block.")
        }
    }

    // ---- JSON result parsing ------------------------------------------------

    /**
     * Vosk final result format: {"text": "hello world"}
     * We parse this simply rather than pulling in a full JSON library.
     */
    private fun parseVoskResult(json: String): String {
        return try {
            val start = json.indexOf('"', json.indexOf("text") + 4) + 1
            val end   = json.lastIndexOf('"')
            if (start in 1 until end) json.substring(start, end).trim() else ""
        } catch (e: Exception) { "" }
    }

    /**
     * Vosk partial result format: {"partial": "hell"}
     */
    private fun parseVoskPartial(json: String): String {
        return try {
            val start = json.indexOf('"', json.indexOf("partial") + 7) + 1
            val end   = json.lastIndexOf('"')
            if (start in 1 until end) json.substring(start, end).trim() else ""
        } catch (e: Exception) { "" }
    }
}
