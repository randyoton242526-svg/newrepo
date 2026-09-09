package com.example.voiceassistant

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * LocalModelExecutor
 * ------------------
 * Parses voice commands into structured JSON actions using keyword matching.
 * No external LLM library required — compiles with zero extra dependencies.
 *
 * To upgrade to a real LLM later, replace the infer() body with llama.cpp
 * JNI calls once a compatible Android AAR is available.
 */
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

    // ── Init (5-stage check) ─────────────────────────────────────────────────
    fun init() {
        PipelineLogger.log("LLM", "init() path=$modelFilePath")
        isReady = false

        if (modelFilePath.isBlank()) { fail("Model path is blank"); return }
        PipelineLogger.log("LLM", "Stage 1 PASS")

        val f = File(modelFilePath)
        if (!f.exists()) {
            // Model not downloaded yet — not an error at this stage
            PipelineLogger.log("LLM", "Model file not yet downloaded — will use rule-based fallback")
            isReady = true
            return
        }
        PipelineLogger.log("LLM", "Stage 2 PASS: file exists")

        if (!f.isFile) { fail("Path is a directory"); return }
        PipelineLogger.log("LLM", "Stage 3 PASS")

        val sizeMb = f.length() / 1_000_000
        if (f.length() < MIN_MODEL_SIZE) {
            fail("File only ${sizeMb} MB — possibly incomplete"); return
        }
        PipelineLogger.log("LLM", "Stage 4 PASS: ${sizeMb} MB")

        isReady = true
        PipelineLogger.log("LLM", "Stage 5 PASS: executor ready")
    }

    // ── Inference ────────────────────────────────────────────────────────────
    fun infer(userText: String): String? {
        if (!isReady) { onError("Executor not initialised"); return null }
        val text = userText.lowercase().trim()
        PipelineLogger.log("LLM", "infer: \"$text\"")

        val json = matchCommand(text)
        onTokenStream(json)
        PipelineLogger.log("LLM", "response: $json")
        return json
    }

    fun release() {
        isReady = false
        PipelineLogger.log("LLM", "release()")
    }

    // ── Rule-based command matcher ───────────────────────────────────────────
    private fun matchCommand(text: String): String {
        return try {
            when {
                // Battery
                text.contains("battery") ->
                    action("GET_BATTERY")

                // Torch
                text.contains("torch") || text.contains("flashlight") ->
                    if (text.contains("off")) action("TORCH_OFF") else action("TORCH_ON")

                // Wi-Fi
                text.contains("wifi") || text.contains("wi-fi") ->
                    if (text.contains("off")) action("WIFI_OFF") else action("WIFI_ON")

                // Volume
                text.contains("volume up") || text.contains("louder") ->
                    action("VOLUME_UP")
                text.contains("volume down") || text.contains("quieter") ->
                    action("VOLUME_DOWN")

                // Alarm
                text.contains("alarm") -> {
                    val hour = extractNumber(text, listOf("at", "for")) ?: 7
                    val minute = extractMinute(text) ?: 0
                    JSONObject().apply {
                        put("action", "SET_ALARM")
                        put("hour", hour)
                        put("minute", minute)
                    }.toString()
                }

                // Open app
                text.contains("open") || text.contains("launch") -> {
                    val pkg = resolvePackage(text)
                    JSONObject().apply {
                        put("action", "OPEN_APP")
                        put("package", pkg)
                    }.toString()
                }

                // Web search
                text.contains("search") || text.contains("look up") || text.contains("google") -> {
                    val query = text
                        .replace(Regex("search(?: for)?|look up|google"), "").trim()
                    JSONObject().apply {
                        put("action", "WEB_SEARCH")
                        put("query", query.ifBlank { text })
                    }.toString()
                }

                // Time / date
                text.contains("time") || text.contains("date") ->
                    speak("The current time and date are shown in your status bar.")

                // Greeting
                text.contains("hello") || text.contains("hi ") || text.contains("hey") ->
                    speak("Hello! I'm your offline assistant. How can I help?")

                // Fallback
                else -> speak("I heard: \"$text\". Try saying open, search, battery, torch, wifi, volume, or alarm.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "matchCommand error", e)
            speak("Sorry, I couldn't process that command.")
        }
    }

    private fun action(name: String) =
        JSONObject().apply { put("action", name) }.toString()

    private fun speak(msg: String) =
        JSONObject().apply { put("action", "SPEAK"); put("reply", msg) }.toString()

    private fun extractNumber(text: String, after: List<String>): Int? {
        val words = text.split(" ")
        for (kw in after) {
            val idx = words.indexOf(kw)
            if (idx >= 0 && idx + 1 < words.size)
                return words[idx + 1].toIntOrNull()
        }
        return words.firstNotNullOfOrNull { it.toIntOrNull() }
    }

    private fun extractMinute(text: String): Int? {
        val m = Regex("""(\d{1,2})[: ](\d{2})""").find(text)
        return m?.groupValues?.get(2)?.toIntOrNull()
    }

    private fun resolvePackage(text: String): String = when {
        text.contains("youtube")  -> "com.google.android.youtube"
        text.contains("camera")   -> "com.android.camera2"
        text.contains("maps")     -> "com.google.android.apps.maps"
        text.contains("chrome")   -> "com.android.chrome"
        text.contains("settings") -> "com.android.settings"
        text.contains("phone")    -> "com.android.dialer"
        text.contains("messages") -> "com.google.android.apps.messaging"
        text.contains("spotify")  -> "com.spotify.music"
        text.contains("whatsapp") -> "com.whatsapp"
        text.contains("instagram")-> "com.instagram.android"
        else -> "com.android.settings"
    }
}
