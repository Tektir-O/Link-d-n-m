package com.kadir.linkdnm

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Manual two-boundary audio selection. Never modifies the source file. */
class AudioCutActivity : Activity() {
    private lateinit var source: Uri
    private var durationMs = 0L
    private var startMs = 0L
    private var endMs = 0L
    private lateinit var status: TextView
    private lateinit var waveform: RangeView
    private lateinit var save: Button
    private lateinit var preview: Button
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var previewGeneration = 0
    private var saving = false

    private fun format(ms: Long): String = "%02d:%02d.%01d".format(
        java.util.Locale.ROOT, ms / 60000, ms / 1000 % 60, ms / 100 % 10
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val value = intent.getStringExtra("audio_uri")
        if (value.isNullOrBlank()) { finish(); return }
        source = Uri.parse(value)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, source)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Toast.makeText(this, "Müzik süresi okunamadı.", Toast.LENGTH_LONG).show()
            finish(); return
        } finally { retriever.release() }
        if (durationMs < 200L) { finish(); return }
        startMs = 0L
        endMs = durationMs
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 28, 20, 20)
            setBackgroundColor(Color.rgb(243, 248, 246))
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 19f
            setOnClickListener { action() }
        }
        root.addView(TextView(this).apply {
            text = "✂ MÜZİĞİ KES"; textSize = 25f; setTextColor(Color.rgb(20, 57, 45))
        })
        status = TextView(this).apply {
            textSize = 17f; setTextColor(Color.rgb(20, 57, 45))
            setPadding(0, 18, 0, 16)
        }
        root.addView(status)
        waveform = RangeView(this)
        root.addView(waveform, LinearLayout.LayoutParams(-1, (170 * resources.displayMetrics.density).toInt()))
        preview = button("▶ SEÇİLEN BÖLÜMÜ DİNLE") {
            if (player == null) playSelection() else stopPreview()
        }
        root.addView(preview)
        save = button("💾 SEÇİLEN BÖLÜMÜ KAYDET") { saveSelection() }
        root.addView(save)
        root.addView(button("↩ GERİ") { finish() })
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        status.text = "Toplam: ${format(durationMs)}\nBaşlangıç: ${format(startMs)}\nBitiş: ${format(endMs)}\nSeçilen: ${format(endMs - startMs)}"
        save.isEnabled = !saving && endMs - startMs >= 200L
        waveform.invalidate()
    }

    private inner class RangeView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var dragging = -1
        private fun x(ms: Long) = 16f + (width - 32f) * ms.toFloat() / durationMs
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val center = height / 2f
            val usable = width - 32f
            paint.strokeWidth = 3f
            for (i in 0..180) {
                val xx = 16f + usable * i / 180f
                val amplitude = 12f + (abs(kotlin.math.sin(i * .41)) * 35).toFloat()
                paint.color = if (xx in x(startMs)..x(endMs)) Color.rgb(9, 106, 82)
                    else Color.rgb(166, 182, 174)
                canvas.drawLine(xx, center - amplitude, xx, center + amplitude, paint)
            }
            paint.color = Color.rgb(9, 106, 82)
            paint.strokeWidth = 7f
            canvas.drawLine(x(startMs), 10f, x(startMs), height - 10f, paint)
            canvas.drawLine(x(endMs), 10f, x(endMs), height - 10f, paint)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val at = ((event.x - 16f) / max(1f, width - 32f) * durationMs)
                .roundToInt().toLong().coerceIn(0L, durationMs)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = if (abs(at - startMs) <= abs(at - endMs)) 0 else 1
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    stopPreview()
                    if (dragging == 0) startMs = at.coerceIn(0L, endMs - 200L)
                    if (dragging == 1) endMs = at.coerceIn(startMs + 200L, durationMs)
                    refresh()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return true
        }
    }

    private fun playSelection() {
        stopPreview()
        val generation = ++previewGeneration
        try {
            val media = MediaPlayer()
            player = media
            media.setDataSource(this, source)
            media.setOnPreparedListener {
                if (generation != previewGeneration) return@setOnPreparedListener
                it.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
            }
            media.setOnSeekCompleteListener {
                if (generation != previewGeneration) return@setOnSeekCompleteListener
                it.start()
                preview.text = "■ DURDUR"
                val remaining = max(0L, endMs - startMs)
                handler.postDelayed({ if (generation == previewGeneration) stopPreview() }, remaining)
            }
            media.setOnErrorListener { _, _, _ -> stopPreview(); true }
            media.prepareAsync()
        } catch (e: Exception) {
            stopPreview()
            Toast.makeText(this, "Önizleme açılamadı.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPreview() {
        previewGeneration++
        handler.removeCallbacksAndMessages(null)
        try { player?.release() } catch (_: Exception) {}
        player = null
        if (::preview.isInitialized) preview.text = "▶ SEÇİLEN BÖLÜMÜ DİNLE"
    }

    private fun saveSelection() {
        if (saving || endMs - startMs < 200L) return
        stopPreview()
        saving = true
        refresh()
        val begin = startMs
        val finish = endMs
        Thread {
            try {
                val data = contentResolver.openInputStream(source)?.use { it.readBytes() }
                    ?: error("Müzik okunamadı.")
                val trimmed = Mp3FrameCutter.cut(data, listOf(begin to finish))
                if (trimmed.isEmpty()) error("Kesilecek MP3 verisi bulunamadı.")
                val filename = "video-yerelmedya0-192k-${System.currentTimeMillis()}.mp3"
                Mp3Storage(this).save(ByteArrayInputStream(trimmed), trimmed.size.toLong(),
                    filename, { false }) { _, _ -> }
                runOnUiThread {
                    saving = false
                    refresh()
                    Toast.makeText(this, "Yeni MP3 kaydedildi. Orijinal korundu.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    saving = false
                    refresh()
                    Toast.makeText(this, "Kaydedilemedi: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }
}

/** Frame-aligned MPEG Layer III cutting; no source-file modification. */
object Mp3FrameCutter {
    fun cut(data: ByteArray, ranges: List<Pair<Long, Long>>): ByteArray {
        var p = 0
        if (data.size >= 10 && data[0] == 73.toByte() && data[1] == 68.toByte() && data[2] == 51.toByte()) {
            p = 10 + ((data[6].toInt() and 127) shl 21) + ((data[7].toInt() and 127) shl 14) +
                ((data[8].toInt() and 127) shl 7) + (data[9].toInt() and 127)
        }
        val out = java.io.ByteArrayOutputStream()
        var elapsed = 0.0
        val rates = arrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        val rates2 = arrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
        val freqs = intArrayOf(44100, 48000, 32000)
        while (p + 4 <= data.size) {
            val a = data[p].toInt() and 255
            val b = data[p + 1].toInt() and 255
            val c = data[p + 2].toInt() and 255
            if (a != 255 || (b and 0xe0) != 0xe0) { p++; continue }
            val version = (b shr 3) and 3
            val layer = (b shr 1) and 3
            val br = (c shr 4) and 15
            val sr = (c shr 2) and 3
            if (version == 1 || layer != 1 || sr == 3 || br == 0 || br == 15) { p++; continue }
            val hz = freqs[sr] / (if (version == 3) 1 else if (version == 2) 2 else 4)
            val kbps = if (version == 3) rates[br] else rates2[br]
            val frame = (if (version == 3) 144 else 72) * kbps * 1000 / hz + (c shr 1 and 1)
            if (frame <= 4 || p + frame > data.size) break
            val frameMs = (if (version == 3) 1152.0 else 576.0) * 1000 / hz
            if (ranges.any { elapsed < it.second && elapsed + frameMs > it.first }) out.write(data, p, frame)
            elapsed += frameMs
            p += frame
        }
        return out.toByteArray()
    }
}
