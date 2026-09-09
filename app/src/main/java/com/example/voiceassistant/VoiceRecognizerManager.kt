package com.example.voiceassistant

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

class VoiceRecognizerManager(
    private val modelPath: String,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onAmplitude: (Float) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG        = "VoiceRecognizer"
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE_FACTOR = 4
    }

    private var model      : Model?      = null
    private var recognizer : Recognizer? = null
    private var audioRecord: AudioRecord? = null

    @Volatile private var isListening = false
    // Flag: true when stopListening() was called by user (skip getFinalResult)
    @Volatile private var manualStop = false

    fun init() {
        try {
            Log.i(TAG, "Loading Vosk model from $modelPath")
            model      = Model(modelPath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            Log.i(TAG, "Vosk model loaded from $modelPath")
        } catch (e: Exception) {
            val msg = "Vosk init failed: ${e.message}"
            Log.e(TAG, msg, e)
            onError(msg)
        }
    }

    suspend fun startListening() = withContext(Dispatchers.IO) {
        if (isListening) return@withContext
        val rec = recognizer ?: run { onError("Recognizer not initialised"); return@withContext }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = minBuf * BUFFER_SIZE_FACTOR

        val ar = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            onError("AudioRecord failed to initialise")
            ar.release()
            return@withContext
        }

        audioRecord = ar
        isListening = true
        manualStop  = false
        rec.reset()   // clean slate before each session

        ar.startRecording()
        Log.i(TAG, "Recording started")

        val buf = ShortArray(bufSize / 2)
        try {
            while (isListening) {
                val read = ar.read(buf, 0, buf.size)
                if (read <= 0) continue

                // Amplitude for waveform UI
                val rms = Math.sqrt(buf.take(read).map { it.toDouble() * it }.average()).toFloat()
                onAmplitude(rms / Short.MAX_VALUE)

                val bytes = ByteArray(read * 2)
                for (i in 0 until read) {
                    bytes[i * 2]     = (buf[i].toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = (buf[i].toInt() shr 8 and 0xFF).toByte()
                }

                if (rec.acceptWaveForm(bytes, bytes.size)) {
                    // Full utterance result
                    if (!manualStop) {
                        val text = extractText(rec.result)
                        if (text.isNotBlank()) onFinal(text)
                    }
                } else {
                    // Partial result
                    val partial = extractPartial(rec.partialResult)
                    if (partial.isNotBlank()) onPartial(partial)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording loop error: ${e.message}", e)
            if (!manualStop) onError("Recording error: ${e.message}")
        } finally {
            ar.stop()
            ar.release()
            audioRecord = null
            Log.i(TAG, "Recording stopped")
        }
    }

    fun stopListening() {
        manualStop  = true   // skip getFinalResult — prevents Kaldi SIGABRT crash
        isListening = false
        // Reset recognizer state safely (no getFinalResult call)
        try { recognizer?.reset() } catch (_: Exception) {}
        Log.i(TAG, "stopListening() called — manual stop, recognizer reset")
    }

    fun release() {
        isListening = false
        manualStop  = true
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        try { model?.close() } catch (_: Exception) {}
        model = null
        Log.i(TAG, "VoiceRecognizerManager released")
    }

    private fun extractText(json: String): String = try {
        JSONObject(json).optString("text", "").trim()
    } catch (_: Exception) { "" }

    private fun extractPartial(json: String): String = try {
        JSONObject(json).optString("partial", "").trim()
    } catch (_: Exception) { "" }
}
