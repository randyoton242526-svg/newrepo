package com.example.voiceassistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1976D2.toInt()
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }
    private val amplitudes = ArrayDeque<Float>()
    private var isAnimating = false
    private var phase = 0f
    private val ticker = object : Runnable {
        override fun run() { phase += 0.15f; invalidate(); if (isAnimating) postDelayed(this, 50) }
    }

    fun addAmplitude(v: Float) { if (amplitudes.size > 60) amplitudes.removeFirst(); amplitudes.addLast(v); invalidate() }
    fun startIdleAnimation() { if (!isAnimating) { isAnimating = true; post(ticker) } }
    fun stopAnimation() { isAnimating = false; removeCallbacks(ticker); invalidate() }
    fun reset() { amplitudes.clear(); phase = 0f; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val cy = h / 2f
        val bars = 30; val bw = w / (bars * 2f)
        for (i in 0 until bars) {
            val x = (i * 2 + 1) * bw
            val amp = if (amplitudes.isNotEmpty()) {
                val idx = (i * amplitudes.size / bars).coerceIn(0, amplitudes.size - 1)
                (amplitudes[idx] / 32768f).coerceIn(0.05f, 1f)
            } else (0.15f + 0.1f * abs(sin((i * 0.4f + phase).toDouble()))).toFloat()
            val bh = amp * cy * 0.85f
            canvas.drawLine(x, cy - bh, x, cy + bh, paint)
        }
    }
}
