package com.example.voiceassistant

import android.util.Log

/**
 * LocalModelExecutor  (V2-addon — llama.cpp JNI backend)
 * -------------------------------------------------------
 * Replaces the MediaPipe/Gemma backend with llama.cpp JNI so we can run
 * any sub-1 GB GGUF model (Qwen2.5-1.5B, Llama-3.2-1B, SmolLM2, etc.).
 *
 * WHY llama.cpp INSTEAD OF MEDIAPIPE?
 *   MediaPipe LlmInference only supports Google’s proprietary .task format
 *   (Gemma). All sub-1 GB open-source models (Qwen, Llama-3, SmolLM2,
 *   DeepSeek-R1-Distill) are distributed as GGUF files and must be run via
 *   llama.cpp or a compatible JNI wrapper.
 *
 * SETUP: Add the llama.cpp Android AAR to app/build.gradle:
 *   implementation 'com.github.ggerganov:llama.cpp:b4300'
 *   // or use the pre-built JNI bindings from:
 *   // https://github.com/ggerganov/llama.cpp/releases
 *
 * THREADING: All public methods MUST run on an IO/background coroutine.
 *
 * FILE INTEGRITY: 5-stage check before attempting to load the model.
 */
class LocalModelExecutor(
    private val modelFilePath : String,
    private val modelConfig   : ModelConfig.ModelEntry,
    private val onTokenStream : (String) -> Unit,
    private val onError       : (String) -> Unit
) {
    companion object {
        private const val TAG           = "LocalModelExecutor"
        private const val MIN_MODEL_BYTES = 100_000_000L   // 100 MB sanity floor
        private const val N_THREADS       = 4              // CPU threads for inference
        private const val N_CTX           = 512            // context window size
        private const val MAX_NEW_TOKENS  = 200
        private const val TEMPERATURE     = 0.05f          // near-deterministic JSON

        // System prompt injected into every request
        private const val SYSTEM_PROMPT = """
You are a device assistant command parser. Respond ONLY with a valid JSON object.
Never add explanation, markdown, or any text outside the JSON object.

Available actions:
  {"action":"OPEN_APP","package":"<pkg>"}
  {"action":"GET_BATTERY"}
  {"action":"WIFI_ON"} | {"action":"WIFI_OFF"}
  {"action":"OPEN_WIFI_SETTINGS"}
  {"action":"OPEN_BT_SETTINGS"}
  {"action":"VOLUME_UP"} | {"action":"VOLUME_DOWN"}
  {"action":"TORCH_ON"} | {"action":"TORCH_OFF"}
  {"action":"SET_ALARM","hour":<0-23>,"minute":<0-59>}
  {"action":"WEB_SEARCH","query":"<terms>"}
  {"action":"SPEAK","reply":"<text>"}

Examples:
  open youtube   -> {"action":"OPEN_APP","package":"com.google.android.youtube"}
  battery level  -> {"action":"GET_BATTERY"}
  set alarm 7 30 -> {"action":"SET_ALARM","hour":7,"minute":30}

User command:"""
    }

    // ---- JNI handle (opaque long pointer to llama_context*) ----------------
    // These native methods are declared in the llama.cpp JNI bindings.
    // The actual implementation lives in the .so that ships with the AAR.
    private external fun nativeInit(
        modelPath : String,
        nThreads  : Int,
        nCtx      : Int
    ): Long   // returns context handle; 0 = failure

    private external fun nativeInfer(
        handle      : Long,
        prompt      : String,
        maxNewTokens: Int,
        temperature : Float,
        onToken     : TokenCallback
    ): String  // full response string

    private external fun nativeFree(handle: Long)

    // JNI callback interface for streaming tokens
    interface TokenCallback {
        fun onToken(token: String, done: Boolean)
    }

    @Volatile private var nativeHandle: Long = 0L
    @Volatile private var isReady     = false
    @Volatile private var isInferring = false

    init {
        // Load the llama.cpp native library bundled in the AAR
        try {
            System.loadLibrary("llama")
            PipelineLogger.log("LLM", "llama.cpp native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            PipelineLogger.log("LLM", "ERROR: llama native library not found: ${e.message}")
        }
    }

    // =========================================================================
    // Init — 5-stage file integrity check
    // =========================================================================

    fun init() {
        PipelineLogger.log("LLM", "init() called | model=${modelConfig.name}")
        isReady = false

        val file = java.io.File(modelFilePath)

        // Stage 1: path not blank
        if (modelFilePath.isBlank()) { fail("Model path is blank"); return }
        PipelineLogger.log("LLM", "Stage 1 PASS: path not blank")

        // Stage 2: file exists
        if (!file.exists()) {
            fail("GGUF model not found at:\n$modelFilePath\n\n" +
                 "Run the in-app download or copy the file manually."); return
        }
        PipelineLogger.log("LLM", "Stage 2 PASS: file exists")

        // Stage 3: is a file (not a dir)
        if (!file.isFile) { fail("Model path is a directory: $modelFilePath"); return }
        PipelineLogger.log("LLM", "Stage 3 PASS: is regular file")

        // Stage 4: size check
        val sizeMb = file.length() / 1_000_000
        if (file.length() < MIN_MODEL_BYTES) {
            fail("Model file is only ${sizeMb} MB. " +
                 "Minimum expected is ${MIN_MODEL_BYTES/1_000_000} MB. " +
                 "File may be incomplete or corrupt."); return
        }
        PipelineLogger.log("LLM", "Stage 4 PASS: size = ${sizeMb} MB")

        // Stage 5: GGUF magic bytes check (first 4 bytes = 0x47 0x47 0x55 0x46)
        try {
            val magic = file.inputStream().use { it.readNBytes(4) }
            val expected = byteArrayOf(0x47, 0x47, 0x55, 0x46)  // "GGUF"
            if (!magic.contentEquals(expected)) {
                fail("File does not have a valid GGUF header. " +
                     "Expected bytes: GGUF, got: ${magic.map { it.toUByte().toString(16) }}. " +
                     "The file may be corrupt or in the wrong format."); return
            }
            PipelineLogger.log("LLM", "Stage 5 PASS: GGUF magic bytes verified")
        } catch (e: Exception) {
            fail("Could not read file header: ${e.message}"); return
        }

        // Stage 6: JNI load
        PipelineLogger.log("LLM", "Stage 6: Loading model into llama.cpp (may take 5-20 s)...")
        nativeHandle = try {
            nativeInit(modelFilePath, N_THREADS, N_CTX)
        } catch (e: Exception) {
            fail("llama_init failed: ${e.message}"); return
        }

        if (nativeHandle == 0L) {
            fail("llama_init returned null handle. " +
                 "Check device RAM — you need ~${modelConfig.expectedBytes/1_000_000 * 2/1000} GB free.")
            return
        }

        isReady = true
        PipelineLogger.log("LLM", "Stage 6 PASS: model loaded, handle=$nativeHandle")
    }

    // =========================================================================
    // Inference
    // =========================================================================

    fun infer(userText: String): String? {
        if (!isReady) {
            PipelineLogger.log("LLM", "infer() called but model not ready")
            onError("Language model not loaded. Tap retry.")
            return null
        }
        if (isInferring) {
            PipelineLogger.log("LLM", "infer() re-entrancy blocked")
            return null
        }

        // Strip DeepSeek-R1 <think>...</think> block from user input if present
        val cleanInput = if (modelConfig.promptFormat == ModelConfig.PromptFormat.DEEPSEEK_R1) {
            userText.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
        } else userText

        val prompt = ModelConfig.buildPrompt(
            format       = modelConfig.promptFormat,
            systemPrompt = SYSTEM_PROMPT,
            userText     = cleanInput
        )
        PipelineLogger.log("LLM", "Prompt (${prompt.length} chars) sent to ${modelConfig.name}")

        isInferring = true
        return try {
            val callback = object : TokenCallback {
                override fun onToken(token: String, done: Boolean) {
                    PipelineLogger.log("LLM", "Token: \"$token\" done=$done")
                    onTokenStream(token)
                }
            }
            val response = nativeInfer(nativeHandle, prompt, MAX_NEW_TOKENS, TEMPERATURE, callback)
            PipelineLogger.log("LLM", "Response: ${response.take(150)}")

            // For DeepSeek-R1: strip the <think> reasoning block from the response
            if (modelConfig.promptFormat == ModelConfig.PromptFormat.DEEPSEEK_R1) {
                response.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
            } else response
        } catch (e: Exception) {
            val msg = "Inference error: ${e.message}"
            Log.e(TAG, msg, e)
            PipelineLogger.log("LLM", "ERROR: $msg")
            onError(msg)
            null
        } finally {
            isInferring = false
        }
    }

    // =========================================================================
    // Cleanup
    // =========================================================================

    fun release() {
        PipelineLogger.log("LLM", "release() called")
        if (nativeHandle != 0L) {
            try { nativeFree(nativeHandle) } catch (_: Exception) {}
            nativeHandle = 0L
        }
        isReady = false
        PipelineLogger.log("LLM", "llama.cpp context freed")
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        PipelineLogger.log("LLM", "FAIL: $message")
        onError(message)
    }
}
