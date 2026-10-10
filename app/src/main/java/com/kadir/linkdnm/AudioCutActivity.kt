package com.kadir.linkdnm

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.media.MediaExtractor
import android.media.MediaCodec
import android.media.MediaFormat
import android.graphics.RectF
import java.nio.ByteOrder
import kotlin.math.sqrt
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
    private val darkBg = Color.rgb(14, 18, 18)
    private val darkCard = Color.rgb(34, 42, 39)
    private val bright = Color.rgb(55, 211, 157)
    private val paleText = Color.rgb(175, 194, 185)
    private val amplitudes = FloatArray(160) { 0.08f }
    @Volatile private var alive = true
    @Volatile private var waveformReady = false
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
            val p = (18 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            setBackgroundColor(darkBg)
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 19f
            minHeight = (65 * resources.displayMetrics.density).toInt()
            setTextColor(if(label.contains("KAYDET")) Color.WHITE else bright)
            background = GradientDrawable().apply {
                setColor(if(label.contains("KAYDET")) Color.rgb(8, 116, 85) else darkCard)
                cornerRadius = 18 * resources.displayMetrics.density
            }
            setOnClickListener { action() }
        }
        root.addView(TextView(this).apply {
            text = "✂ ŞARKIYI KES"; textSize = 28f; setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        status = TextView(this).apply {
            textSize = 18f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(darkCard);cornerRadius=18*resources.displayMetrics.density }
            val p = (16*resources.displayMetrics.density).toInt();setPadding(p,p,p,p)
            setPadding(0, 18, 0, 16)
        }
        root.addView(status)
        waveform = RangeView(this)
        root.addView(waveform, LinearLayout.LayoutParams(-1, (210 * resources.displayMetrics.density).toInt()))
        preview = button("▶ SEÇİLEN BÖLÜMÜ DİNLE") {
            if (player == null) playSelection() else stopPreview()
        }
        root.addView(preview)
        save = button("💾 SEÇİLEN BÖLÜMÜ KAYDET") { saveSelection() }
        root.addView(save)
        root.addView(button("↩ GERİ") { finish() })
        setContentView(root)
        refresh()
        Thread {
            val decoded = decodeAmplitudes()
            runOnUiThread {
                if (!alive) return@runOnUiThread
                if (decoded != null) {
                    decoded.copyInto(amplitudes)
                    waveformReady = true
                } else {
                    status.text = status.text.toString() + "\nSes dalgası okunamadı."
                }
                waveform.invalidate()
            }
        }.start()
    }

    private fun refresh() {
        status.text = "🎵 Toplam  ${format(durationMs)}\n\n✂ Başlangıç  ${format(startMs)}\n✂ Bitiş  ${format(endMs)}\n\n✓ Seçilen bölüm  ${format(endMs - startMs)}"
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
            val usable = (width - 32f).coerceAtLeast(1f)
            paint.style=Paint.Style.FILL
            paint.color=darkCard
            canvas.drawRoundRect(RectF(0f,0f,width.toFloat(),height.toFloat()),20f,20f,paint)
            val left=x(startMs);val right=x(endMs)
            paint.color=Color.rgb(35,79,61)
            canvas.drawRoundRect(RectF(left,9f,right,height-9f),9f,9f,paint)
            if (!waveformReady) {
                paint.color=paleText;paint.textSize=16f*resources.displayMetrics.scaledDensity
                paint.textAlign=Paint.Align.CENTER
                canvas.drawText("Ses dalgası hazırlanıyor...",width/2f,center+5f,paint)
                paint.textAlign=Paint.Align.LEFT
            }
            for(i in amplitudes.indices) {
                if (!waveformReady) break
                val xx=16f + usable*(i+.5f)/amplitudes.size
                val magnitude=if(waveformReady)amplitudes[i] else .08f
                val h=5f + magnitude.coerceIn(0f,1f)*height*.29f
                paint.strokeWidth=(usable/amplitudes.size*.64f).coerceIn(1.5f,5f)
                paint.strokeCap=Paint.Cap.ROUND
                paint.color=if(xx in left..right)bright else Color.rgb(94,113,104)
                canvas.drawLine(xx,center-h,xx,center+h,paint)
            }
            for(xx in arrayOf(left,right)){
                paint.color=bright;paint.strokeWidth=5f
                canvas.drawLine(xx,12f,xx,height-12f,paint)
                paint.style=Paint.Style.FILL
                canvas.drawCircle(xx,center,12f,paint)
                paint.color=darkBg;canvas.drawCircle(xx,center,5f,paint)
            }
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

    /** Generates real RMS peaks from decoded MP3 PCM instead of fake equal bars. */
    private fun decodeAmplitudes(): FloatArray? {
        val extractor=MediaExtractor()
        var codec:MediaCodec?=null
        val result=FloatArray(amplitudes.size)
        val counts=IntArray(amplitudes.size)
        try {
            contentResolver.openFileDescriptor(source,"r")?.use {
                extractor.setDataSource(it.fileDescriptor)
            } ?: return null
            val track=(0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true
            } ?: return null
            extractor.selectTrack(track)
            val fmt=extractor.getTrackFormat(track)
            val hz=fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            val channel=fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val mime=fmt.getString(MediaFormat.KEY_MIME) ?: return null
            val dec=MediaCodec.createDecoderByType(mime)
            codec=dec
            dec.configure(fmt,null,null,0);dec.start()
            var inputEnded=false
            val info=MediaCodec.BufferInfo()
            for(iteration in 0 until 130000) {
                if(!alive)break
                if(!inputEnded) {
                    val j=dec.dequeueInputBuffer(10000)
                    if(j>=0) {
                        val buf=dec.getInputBuffer(j) ?: break
                        buf.clear()
                        val n=extractor.readSampleData(buf,0)
                        if(n<0) {
                            dec.queueInputBuffer(j,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded=true
                        } else {
                            dec.queueInputBuffer(j,0,n,extractor.sampleTime,0)
                            extractor.advance()
                        }
                    }
                }
                val j=dec.dequeueOutputBuffer(info,10000)
                if(j>=0) {
                    if(info.size>=2) {
                        val pcm=dec.getOutputBuffer(j)?.duplicate()
                        if(pcm!=null) {
                            pcm.order(ByteOrder.LITTLE_ENDIAN)
                            val samples=info.size/2
                            val step=(samples/96).coerceAtLeast(1)
                            var n=0
                            while(n<samples) {
                                val ms=info.presentationTimeUs/1000L +
                                    ((n/channel).toLong()*1000/hz)
                                val bin=(ms*result.size/durationMs).toInt().coerceIn(0,result.lastIndex)
                                val value=pcm.getShort(info.offset+n*2).toDouble()/32768.0
                                result[bin]+=(value*value).toFloat()
                                counts[bin]++
                                n+=step
                            }
                        }
                    }
                    val done=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    dec.releaseOutputBuffer(j,false)
                    if(done)break
                }
            }
            val rms=FloatArray(result.size){i->if(counts[i]>0)sqrt(result[i]/counts[i]) else 0f}
            val peak=(rms.maxOrNull()?:0f).coerceAtLeast(.001f)
            return FloatArray(rms.size){i->sqrt(rms[i]/peak).coerceIn(.04f,1f)}
        }catch(_:Exception){return null}
        finally {
            try{codec?.stop()}catch(_:Exception){}
            try{codec?.release()}catch(_:Exception){}
            extractor.release()
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
                val saved = Mp3Storage(this).save(ByteArrayInputStream(trimmed), trimmed.size.toLong(),
                    filename, { false }) { _, _ -> }
                runOnUiThread {
                    saving = false
                    refresh()
                    Toast.makeText(this, "Müzik kesildi. Video için hazır!", Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK, Intent().setData(saved.uri))
                    finish()
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
        alive=false
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
