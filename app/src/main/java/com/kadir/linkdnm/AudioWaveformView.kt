package com.kadir.linkdnm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Interactive waveform for precise manual start selection.
 * Full range is the first 90 seconds, zoom view spans 12 seconds. A waveform
 * shows sound intensity, not a classifier that can identify vocals reliably.
 */
class AudioWaveformView(
    context: Context,
    private val onSeek: (Long) -> Unit
) : View(context) {
    private val waveform = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(10, 120, 94)
        strokeWidth = context.resources.displayMetrics.density * 2f
        strokeCap = Paint.Cap.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(215, 80, 55)
        strokeWidth = context.resources.displayMetrics.density * 2.5f
    }
    private val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(85, 103, 100)
        textSize = 12f * context.resources.displayMetrics.scaledDensity
    }
    private val bg = Paint().apply { color = Color.rgb(237, 247, 242) }

    private var levels = FloatArray(360)
    private var totalMs = 90_000L
    private var selectedMs = 0L
    private var zoomed = false
    private val pad: Float get() = 14f * resources.displayMetrics.density

    init {
        minimumHeight = (118 * resources.displayMetrics.density).toInt()
        contentDescription = "Ses dalgası. Başlangıç seçmek için dokun."
        isFocusable = true
    }

    fun updateAudio(amplitudes: FloatArray, durationMs: Long) {
        levels = amplitudes.copyOf()
        totalMs = min(90_000L, max(1_000L, durationMs))
        selectedMs = 0
        invalidate()
    }
    fun updatePosition(ms: Long) {
        selectedMs = ms.coerceIn(0L, totalMs)
        invalidate()
    }
    fun setZoom(enabled: Boolean) {
        zoomed = enabled
        invalidate()
    }

    private fun window(): Pair<Long, Long> {
        if (!zoomed || totalMs <= 12_000L) return 0L to totalMs
        val begin = (selectedMs - 6_000L).coerceIn(0L, totalMs - 12_000L)
        return begin to 12_000L
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 12f, 12f, bg)
        val (first, span) = window()
        val left = pad
        val available = max(1f, width - 2 * pad)
        val mid = height * .47f
        val half = height * .31f
        // Draw one column per on-screen 0.25-second bin. The zoomed view still
        // maps into the same original sample timestamps.
        val count = max(1, (span / IntroFinder.BIN_MS).toInt())
        for (i in 0..count) {
            val ms = first + (i.toLong() * span / count)
            val bin = (ms / IntroFinder.BIN_MS).toInt()
            val energy = (levels.getOrNull(bin) ?: 0f).coerceIn(0f, 1f)
            val x = left + available * i / count
            val amplitude = max(2f, energy * half)
            canvas.drawLine(x, mid - amplitude, x, mid + amplitude, waveform)
        }
        val marker = left + available * ((selectedMs - first).toFloat() / span).coerceIn(0f, 1f)
        canvas.drawLine(marker, 4f, marker, height - pad, line)
        canvas.drawText(IntroSelection.label(first), left, height - 7f, labels)
        val endLabel = IntroSelection.label(first + span)
        canvas.drawText(endLabel, width - pad - labels.measureText(endLabel), height - 7f, labels)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val (first, span) = window()
                val widthUsable = max(1f, width - 2 * pad)
                val fraction = ((event.x - pad) / widthUsable).coerceIn(0f, 1f)
                val ms = first + (span * fraction).roundToLong()
                val chosen = (ms / 100L) * 100L
                onSeek(chosen)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
