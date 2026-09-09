package com.example.voiceassistant

import android.util.Log
import org.json.JSONObject
import java.io.File

class LocalModelExecutor(
    private val modelFilePath: String,
    private val modelConfig: ModelConfig.ModelEntry,
    private val onTokenStream: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "LocalModelExecutor"
        private const val MIN_MODEL_SIZE = 100_000_000L
    }

    @Volatile private var isReady = false

    fun init() {
        PipelineLogger.log("LLM", "init() path=$modelFilePath")
        isReady = false
        if (modelFilePath.isBlank()) { onError("Model path is blank"); return }
        val f = File(modelFilePath)
        if (!f.exists()) {
            PipelineLogger.log("LLM", "Model not yet downloaded — rule-based mode active")
            isReady = true; return
        }
        if (!f.isFile) { onError("Model path is a directory"); return }
        if (f.length() < MIN_MODEL_SIZE) {
            onError("Model file only ${f.length()/1_000_000} MB — possibly incomplete"); return
        }
        isReady = true
        PipelineLogger.log("LLM", "Executor ready (${f.length()/1_000_000} MB)")
    }

    fun infer(userText: String): String? {
        if (!isReady) { onError("Executor not initialised"); return null }
        val text = userText.lowercase().trim()
        val json = matchCommand(text)
        onTokenStream(json)
        return json
    }

    fun release() { isReady = false }

    private fun matchCommand(text: String): String = try {
        when {
            text.contains("battery") -> action("GET_BATTERY")
            text.contains("torch") || text.contains("flashlight") ->
                if (text.contains("off")) action("TORCH_OFF") else action("TORCH_ON")
            text.contains("wifi") || text.contains("wi-fi") ->
                if (text.contains("off")) action("WIFI_OFF") else action("WIFI_ON")
            text.contains("volume up") || text.contains("louder") -> action("VOLUME_UP")
            text.contains("volume down") || text.contains("quieter") -> action("VOLUME_DOWN")
            text.contains("open") || text.contains("launch") ->
                JSONObject().apply { put("action","OPEN_APP"); put("package", resolvePackage(text)) }.toString()
            text.contains("search") || text.contains("look up") || text.contains("google") ->
                JSONObject().apply { put("action","WEB_SEARCH"); put("query", text.replace(Regex("search(?: for)?|look up|google"),"").trim()) }.toString()
            text.contains("hello") || text.contains("hey") ->
                speak("Hello! I'm your offline assistant. How can I help?")
            else -> speak("I heard: \"$text\". Try: open, search, battery, torch, wifi, or volume.")
        }
    } catch (e: Exception) { speak("Sorry, I couldn't process that.") }

    private fun action(name: String) = JSONObject().apply { put("action", name) }.toString()
    private fun speak(msg: String) = JSONObject().apply { put("action","SPEAK"); put("reply", msg) }.toString()
    private fun resolvePackage(text: String) = when {
        text.contains("youtube")   -> "com.google.android.youtube"
        text.contains("maps")      -> "com.google.android.apps.maps"
        text.contains("chrome")    -> "com.android.chrome"
        text.contains("settings")  -> "com.android.settings"
        text.contains("camera")    -> "com.android.camera2"
        text.contains("whatsapp")  -> "com.whatsapp"
        text.contains("spotify")   -> "com.spotify.music"
        text.contains("instagram") -> "com.instagram.android"
        else -> "com.android.settings"
    }
}
