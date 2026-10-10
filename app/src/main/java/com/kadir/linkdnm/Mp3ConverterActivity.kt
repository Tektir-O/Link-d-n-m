package com.kadir.linkdnm

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** No secret in the APK. Multiple phones can use the public HTTPS endpoint. */
class Mp3ConverterActivity : Activity() {
    private companion object {
        const val ENDPOINT = "https://link-mp3-api-production.up.railway.app/convert"
        const val PREFS = "link_mp3_v2"
        val BG = Color.rgb(241, 247, 245)
        val INK = Color.rgb(24, 55, 48)
        val GREEN = Color.rgb(13, 116, 91)
        val PALE = Color.rgb(224, 243, 236)
        val GREY = Color.rgb(96, 117, 110)
    }
    private lateinit var link: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var start: Button
    private lateinit var cancel: Button
    private lateinit var qualityButtons: Map<Int, Button>
    private val busy = AtomicBoolean(false)
    @Volatile private var stopped = false
    @Volatile private var connection: HttpURLConnection? = null
    private var quality = Mp3Options.DEFAULT_BITRATE
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(i: Int) = (resources.displayMetrics.density * i + 0.5f).toInt()
    private fun rounded(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun label(value: String, size: Float = 14f, color: Int = INK, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    private fun stack(pad: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }
    private fun add(parent: LinearLayout, item: View, gap: Int = 12) {
        parent.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(gap) })
    }
    private fun action(caption: String, primary: Boolean = false) = Button(this).apply {
        text = caption
        isAllCaps = false
        textSize = 15f
        minHeight = dp(48)
        setTextColor(if (primary) Color.WHITE else GREEN)
        background = rounded(if (primary) GREEN else PALE, 12)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        quality = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("quality", 192)
            .takeIf { Mp3Options.validBitrate(it) } ?: 192
        val root = stack(16)
        val hero = stack(19).apply { background = rounded(INK, 24) }
        add(hero, label("◉  LINK MP3 PRO", 24f, Color.WHITE, true), 8)
        add(hero, label("Bağlantıyı yapıştır, sesi kaydet.", 15f, Color.rgb(205, 231, 221)), 6)
        add(hero, label("v2.0  •  Reklamsız  •  Üyeliksiz", 12f, Color.rgb(175, 218, 200)), 0)
        add(root, hero, 17)

        val form = stack(16).apply { background = rounded(Color.WHITE, 20) }
        add(form, label("YouTube video bağlantısı", 15f, INK, true), 6)
        link = EditText(this).apply {
            hint = "https://youtu.be/..."
            setSingleLine(true)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = rounded(BG, 12)
            setPadding(dp(13), dp(12), dp(13), dp(12))
        }
        add(form, link, 8)
        add(form, action("Bağlantıyı Yapıştır").apply {
            setOnClickListener {
                val board = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val content = board.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(this@Mp3ConverterActivity)?.toString()
                if (content.isNullOrBlank()) {
                    Toast.makeText(this@Mp3ConverterActivity, "Panoda bağlantı yok.", Toast.LENGTH_SHORT).show()
                } else link.setText(content.trim())
            }
        }, 19)
        add(form, label("MP3 ses kalitesi", 15f, INK, true), 4)
        add(form, label("Seçilen değer kaynak sesin kalitesini yükseltmez.", 12f, GREY), 9)
        val choices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        qualityButtons = Mp3Options.bitrates.associateWith { rate ->
            action(rate.toString() + " kbps").also { b ->
                choices.addView(b, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    rightMargin = dp(if (rate == 320) 0 else 5)
                })
                b.setOnClickListener {
                    quality = rate
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("quality", rate).apply()
                    updateQuality()
                }
            }
        }
        updateQuality()
        add(form, choices, 15)
        start = action("↓  MP3'e Dönüştür ve İndir", true).apply {
            setOnClickListener { download() }
        }
        add(form, start, 8)
        cancel = action("İndirmeyi İptal Et").apply {
            visibility = View.GONE
            setOnClickListener {
                stopped = true
                status.text = "İptal ediliyor..."
                connection?.disconnect()
            }
        }
        add(form, cancel, 8)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            isIndeterminate = true
            max = 100
            progressTintList = ColorStateList.valueOf(GREEN)
        }
        add(form, progress, 8)
        status = label("Hazır. Bağlantı girip indir'e bas.", 13f, GREY)
        add(form, status, 0)
        add(root, form, 14)

        val libraryCard = stack(16).apply { background = rounded(Color.WHITE, 20) }
        add(libraryCard, label("♪  MP3 Arşivim", 19f, INK, true), 7)
        add(libraryCard, label("Dosyaları dinle, paylaş, sil veya yeniden aç.", 13f, GREY), 12)
        add(libraryCard, action("İndirilen MP3'leri Aç").apply {
            setOnClickListener { startActivity(Intent(this@Mp3ConverterActivity, Mp3LibraryActivity::class.java)) }
        }, 0)
        add(root, libraryCard, 14)
        add(root, label("Kayıt konumu: Dosyalarım → Download → LinkMP3\n" +
            "Sunucu otomatik bağlıdır. Sadece indirme hakkın olan içerikleri kullan.",
            12f, GREY), 0)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
            fillViewport = true
        })
        readShared(intent)
    }

    private fun updateQuality() {
        for ((rate, b) in qualityButtons) {
            val active = rate == quality
            b.background = rounded(if (active) GREEN else PALE, 11)
            b.setTextColor(if (active) Color.WHITE else GREEN)
        }
    }
    private fun readShared(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                val raw = it.trim().split(Regex("\\s+")).firstOrNull { part ->
                    part.startsWith("https://")
                } ?: it.trim()
                link.setText(raw)
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShared(intent)
    }
    private fun render(block: () -> Unit) {
        runOnUiThread { if (!isDestroyed && !isFinishing) block() }
    }
    private fun download() {
        if (!busy.compareAndSet(false, true)) return
        val youtube = try { YoutubeLink.canonical(link.text.toString()) }
            catch (e: IllegalArgumentException) {
                busy.set(false)
                status.text = e.message ?: "Bağlantı hatalı."
                return
            }
        val rate = quality
        stopped = false
        start.isEnabled = false
        cancel.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        status.text = "Ses hazırlanıyor..."
        Thread {
            var request: HttpURLConnection? = null
            try {
                request = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    instanceFollowRedirects = false
                    connectTimeout = 20000
                    readTimeout = 330000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "audio/mpeg")
                }
                connection = request
                val data = JSONObject().put("url", youtube).put("quality", rate)
                    .toString().toByteArray(Charsets.UTF_8)
                request.outputStream.use { it.write(data) }
                if (stopped) throw IOException("İptal edildi.")
                if (request.responseCode != 200) {
                    val message = request.errorStream?.bufferedReader()?.use { it.readText().take(1200) } ?: ""
                    val error = try { JSONObject(message).optString("error", "") } catch (_: Exception) { "" }
                    throw IOException(error.ifBlank { "Sunucu hatası: " + request.responseCode })
                }
                if (request.contentType?.substringBefore(";")?.trim() != "audio/mpeg")
                    throw IOException("Sunucu geçerli MP3 göndermedi.")
                val size = request.contentLengthLong
                if (size !in 1..Mp3Storage.MAX_BYTES) throw IOException("MP3 boyutu geçersiz.")
                val id = youtube.substringAfter("v=")
                val fileName = Mp3Options.outputName(id, rate, System.currentTimeMillis())
                render {
                    progress.isIndeterminate = false
                    progress.progress = 0
                    status.text = "MP3 indiriliyor: %0"
                }
                request.inputStream.use { stream ->
                    Mp3Storage(this).save(stream, size, fileName, { stopped }) { done, total ->
                        val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                        render {
                            progress.progress = percent
                            status.text = "MP3 indiriliyor: %" + percent
                        }
                    }
                }
                render {
                    status.text = "✓ Kaydedildi: " + fileName
                    Toast.makeText(this, "MP3 başarıyla kaydedildi.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                render {
                    status.text = if (stopped) "İndirme iptal edildi."
                        else "Hata: " + (e.message ?: "Bağlantı sorunu")
                }
            } finally {
                request?.disconnect()
                connection = null
                busy.set(false)
                render {
                    start.isEnabled = true
                    cancel.visibility = View.GONE
                    progress.visibility = View.GONE
                }
            }
        }.start()
    }
    override fun onDestroy() {
        stopped = true
        connection?.disconnect()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
