package com.example.voiceassistant

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.math.abs

class VoiceRecognizerManager(
    private val modelPath: String,
    private val onPartial: ((String) -> Unit)? = null,
    private val onFinal: ((String) -> Unit)? = null,
    private val onAmplitude: ((Float) -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG         = "VoiceRecognizer"
        private const val SAMPLE_RATE = 16_000
        private const val SILENCE_MS  = 1_500L
    }

    enum class State { IDLE, INITIALISING, READY, LISTENING, STOPPED, ERROR }

    @Volatile var state: State = State.IDLE
        private set

    private var voskModel: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var listeningJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        state = State.INITIALISING
        return@withContext try {
            val modelDir = File(modelPath)
            if (!modelDir.exists()) {
                Log.e(TAG, "Vosk model not found at $modelPath")
                onError?.invoke("STT model not found at $modelPath")
                state = State.ERROR
                false
            } else {
                voskModel  = Model(modelPath)
                recognizer = Recognizer(voskModel, SAMPLE_RATE.toFloat())
                state      = State.READY
                Log.i(TAG, "Vosk model loaded from $modelPath")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vosk init failed: ${e.message}", e)
            onError?.invoke("STT init error: ${e.message}")
            state = State.ERROR
            false
        }
    }

    fun startListening() {
        if (state != State.READY) return
        state = State.LISTENING
        listeningJob = scope.launch { recordLoop() }
    }

    fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        audioRecord?.let { if (it.state == AudioRecord.STATE_INITIALIZED) { it.stop(); it.release() } }
        audioRecord = null
        recognizer?.let {
            val text = parseResult(it.finalResult)
            if (text.isNotBlank()) onFinal?.invoke(text)
        }
        recognizer?.reset()
        state = State.READY
    }

    fun release() {
        stopListening()
        scope.cancel()
        recognizer?.close(); recognizer = null
        voskModel?.close();  voskModel = null
        state = State.STOPPED
    }

    private suspend fun recordLoop() = withContext(Dispatchers.IO) {
        val minBuf     = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBuf, SAMPLE_RATE / 25 * 2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            onError?.invoke("Microphone initialisation failed.")
            state = State.READY
            return@withContext
        }
        audioRecord = recorder
        recorder.startRecording()

        val buffer = ShortArray(bufferSize / 2)
        var silenceStart = 0L
        var speechSeen   = false

        try {
            while (isActive && state == State.LISTENING) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                // Amplitude for waveform
                val amp = buffer.take(read).maxOfOrNull { abs(it.toInt()) }?.toFloat() ?: 0f
                onAmplitude?.invoke(amp)

                val accepted = recognizer?.acceptWaveForm(buffer, read) ?: false
                if (accepted) {
                    val text = parseResult(recognizer?.result ?: continue)
                    if (text.isNotBlank()) { onFinal?.invoke(text); speechSeen = false; silenceStart = 0L }
                } else {
                    val partial = parsePartial(recognizer?.partialResult ?: continue)
                    if (partial.isNotBlank()) {
                        onPartial?.invoke(partial); speechSeen = true; silenceStart = System.currentTimeMillis()
                    } else if (speechSeen && silenceStart > 0 && System.currentTimeMillis() - silenceStart >= SILENCE_MS) {
                        break
                    }
                }
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            recorder.release(); audioRecord = null
        }
    }

    private fun parseResult(json: String): String = try {
        val s = json.indexOf('"', json.indexOf("text") + 4) + 1
        val e = json.lastIndexOf('"')
        if (s in 1 until e) json.substring(s, e).trim() else ""
    } catch (_: Exception) { "" }

    private fun parsePartial(json: String): String = try {
        val s = json.indexOf('"', json.indexOf("partial") + 7) + 1
        val e = json.lastIndexOf('"')
        if (s in 1 until e) json.substring(s, e).trim() else ""
    } catch (_: Exception) { "" }
}
