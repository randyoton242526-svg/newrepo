package com.example.voiceassistant

import android.util.Log

object PipelineLogger {
    private const val TAG = "Pipeline"
    private const val MAX = 200
    private val buffer = ArrayDeque<String>(MAX)

    @Synchronized
    fun log(tag: String, message: String) {
        Log.d(TAG, "[$tag] $message")
        if (buffer.size >= MAX) buffer.removeFirst()
        buffer.addLast("[$tag] $message")
    }

    @Synchronized
    fun tail(n: Int = 50): String = buffer.takeLast(n).joinToString("\n")

    @Synchronized
    fun clear() = buffer.clear()
}
