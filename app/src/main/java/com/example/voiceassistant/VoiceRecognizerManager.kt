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
        private const val TAG             = "VoiceRecognizer"
        private const val SAMPLE_RATE     = 16000
        private const val BUFFER_SIZE_FACTOR = 4
    }

    private var model: Model? = null

    @Volatile private var isListening = false
    @Volatile private var manualStop  = false

    fun init() {
        try {
            Log.i(TAG, "Loading Vosk model from $modelPath")
            model = Model(modelPath)
            Log.i(TAG, "Vosk model loaded from $modelPath")
        } catch (e: Exception) {
            val msg = "Vosk init failed: ${e.message}"
            Log.e(TAG, msg, e)
            onError(msg)
        }
    }

    suspend fun startListening() = withContext(Dispatchers.IO) {
        if (isListening) return@withContext
        val mdl = model ?: run { onError("Model not initialised"); return@withContext }

        // Fresh Recognizer every session — never reuse after stop
        val rec = try {
            Recognizer(mdl, SAMPLE_RATE.toFloat())
        } catch (e: Exception) {
            onError("Failed to create recognizer: ${e.message}")
            return@withContext
        }

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
            try { rec.close() } catch (_: Exception) {}
            return@withContext
        }

        isListening = true
        manualStop  = false
        ar.startRecording()
        Log.i(TAG, "Recording started")

        val buf   = ShortArray(bufSize / 2)
        val bytes = ByteArray(bufSize)    // reused buffer

        try {
            while (isListening) {
                val read = ar.read(buf, 0, buf.size)
                if (read <= 0) continue

                // Amplitude for waveform UI
                val sum = buf.take(read).sumOf { it.toDouble() * it }
                onAmplitude((Math.sqrt(sum / read) / Short.MAX_VALUE).toFloat())

                // Convert shorts → bytes
                for (i in 0 until read) {
                    bytes[i * 2]     = (buf[i].toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = (buf[i].toInt() shr 8 and 0xFF).toByte()
                }
                val byteLen = read * 2

                // Feed audio — always feed even if manualStop (so buffer is complete for result)
                if (rec.acceptWaveForm(bytes, byteLen)) {
                    // Natural end of utterance detected by Vosk
                    val text = extractText(rec.result)
                    Log.d(TAG, "Natural utterance: \"$text\"")
                    if (text.isNotBlank()) onFinal(text)
                } else {
                    if (!manualStop) {
                        val partial = extractPartial(rec.partialResult)
                        if (partial.isNotBlank()) onPartial(partial)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording loop error: ${e.message}", e)
            if (!manualStop) onError("Recording error: ${e.message}")
        } finally {
            // Stop audio hardware first
            try { ar.stop()    } catch (_: Exception) {}
            try { ar.release() } catch (_: Exception) {}

            // ── Get whatever Vosk recognized up to stop point ───────────
            // Use rec.result (NOT rec.finalResult) — safe, no FinalizeDecoding call
            try {
                val text = extractText(rec.result)
                Log.d(TAG, "Result on stop: \"$text\"")
                if (text.isNotBlank()) onFinal(text)
            } catch (e: Exception) {
                Log.w(TAG, "Could not read result on stop: ${e.message}")
            }

            // Close THIS session's recognizer — safe because loop fully exited
            try { rec.close() } catch (_: Exception) {}
            Log.i(TAG, "Recording stopped")
        }
    }

    fun stopListening() {
        manualStop = true
        isListening = false
        // Do NOT touch rec here — let the loop exit and close it in finally
        Log.i(TAG, "stopListening() called — will collect result on loop exit")
    }

    fun release() {
        isListening = false
        manualStop  = true
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
