package com.kadir.linkdnm

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** One-screen, big-button player designed for people unfamiliar with apps.
 * No second screen, settings, API key, login or codec jargon in the UI. */
@androidx.media3.common.util.UnstableApi
class Mp3ConverterActivity : Activity() {
    private companion object {
        const val ENDPOINT = "https://link-mp3-api-production.up.railway.app/convert"
        const val CAPTCHA_CONFIG = "https://link-mp3-api-production.up.railway.app/captcha-config"
        const val CAPTCHA_PAGE = "https://link-mp3-api-production.up.railway.app/captcha"
        val BACKGROUND = Color.rgb(243, 248, 246)
        val GREEN = Color.rgb(11, 108, 83)
        val INK = Color.rgb(25, 57, 47)
        val GRAY = Color.rgb(87, 111, 103)
        val PALE = Color.rgb(221, 240, 233)
    }

    private lateinit var link: EditText
    private lateinit var startButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var status: TextView
    private lateinit var musicList: LinearLayout
    private lateinit var playerStatus: TextView
    private lateinit var storage: Mp3Storage
    private lateinit var videoPanel: VideoPanel
    private lateinit var captchaBox: LinearLayout
    private lateinit var captchaStatus: TextView
    private var captchaWeb: WebView? = null
    @Volatile private var captchaRequired = false
    @Volatile private var captchaToken: String? = null

    private val busy = AtomicBoolean(false)
    @Volatile private var cancelled = false
    @Volatile private var request: HttpURLConnection? = null
    private var player: MediaPlayer? = null
    private var playing: AudioEntry? = null
    private var latestDownload: AudioEntry? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun rounded(color: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun label(content: String, size: Float = 17f, color: Int = INK,
                      strong: Boolean = false): TextView = TextView(this).apply {
        text = content
        textSize = size
        setTextColor(color)
        if (strong) setTypeface(null, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun column(padding: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    private fun add(parent: LinearLayout, view: View, bottom: Int = 12) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(bottom)
        })
    }
    private fun button(content: String, primary: Boolean = false, height: Int = 58): Button =
        Button(this).apply {
            text = content
            isAllCaps = false
            textSize = 18f
            minHeight = dp(height)
            setTextColor(if (primary) Color.WHITE else GREEN)
            background = rounded(if (primary) GREEN else PALE, 15)
            contentDescription = content
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Mp3Storage(this)
        val root = column(16)

        val header = column(20).apply { background = rounded(INK, 24) }
        add(header, label("♫  MÜZİK İNDİR", 28f, Color.WHITE, true), 11)
        add(header, label("Bağlantıyı yapıştır, indir, dinle.", 17f, Color.WHITE), 0)
        add(root, header, 17)

        val form = column(17).apply { background = rounded(Color.WHITE, 20) }
        add(form, label("1. YouTube bağlantısı", 20f, INK, true), 9)
        link = EditText(this).apply {
            hint = "YouTube bağlantısı buraya gelecek"
            setSingleLine(true)
            textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(INK)
            setHintTextColor(GRAY)
            setPadding(dp(12), dp(17), dp(12), dp(17))
            background = rounded(BACKGROUND, 14)
            contentDescription = "YouTube bağlantısı"
        }
        add(form, link, 13)
        add(form, button("1  ·  BAĞLANTIYI YAPIŞTIR").apply {
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val copied = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(this@Mp3ConverterActivity)?.toString()
                if (copied.isNullOrBlank()) {
                    status.text = "Önce YouTube'da Paylaş → Bağlantıyı kopyala seç."
                } else {
                    val value = copied.trim().split(Regex("\\s+")).firstOrNull {
                        it.startsWith("https://")
                    } ?: copied.trim()
                    link.setText(value)
                    link.setSelection(link.text.length)
                    status.text = "Bağlantı hazır. Şimdi yeşil düğmeye bas."
                }
            }
        }, 13)
        add(form, label("2. Müziği indir", 20f, INK, true), 9)
        captchaBox = column(4).apply { visibility = View.GONE }
        captchaStatus = label("Ben robot değilim doğrulaması yükleniyor...", 16f, GRAY)
        add(captchaBox, captchaStatus, 4)
        add(form, captchaBox, 8)
        startButton = button("2  ·  MP3 İNDİR", primary = true, height = 68).apply {
            textSize = 21f
            setOnClickListener { download() }
        }
        add(form, startButton, 11)
        cancelButton = button("İptal Et", height = 48).apply {
            textSize = 16f
            visibility = View.GONE
            setOnClickListener {
                cancelled = true
                status.text = "İndirme iptal ediliyor..."
                request?.disconnect()
            }
        }
        add(form, cancelButton, 8)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
            isIndeterminate = true
            progressTintList = ColorStateList.valueOf(GREEN)
        }
        add(form, progressBar, 9)
        status = label("Hazır. Bağlantıyı yapıştırabilirsin.", 16f, GRAY)
        add(form, status, 0)
        add(root, form, 15)

        val songs = column(16).apply { background = rounded(Color.WHITE, 20) }
        add(songs, label("3. İndirilen müziklerim", 21f, INK, true), 6)
        playerStatus = label("Dinlemek için bir müziğin Dinle düğmesine bas.", 14f, GRAY)
        add(songs, playerStatus, 14)
        musicList = column(0)
        add(songs, musicList, 0)
        add(root, songs, 12)

        videoPanel = VideoPanel(this, storage)
        add(root, videoPanel.createView(), 14)

        add(root, label("Müzikler telefonda İndirilenler / LinkMP3 klasörüne kaydedilir.",
            14f, GRAY), 0)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            isFillViewport = true
            addView(root)
        })
        readSharedLink(intent)
        refreshMusic()
        loadCaptchaConfig()
    }

    override fun onResume() {
        super.onResume()
        if (::musicList.isInitialized) refreshMusic()
        if (::videoPanel.isInitialized) videoPanel.refreshMusic()
    }

    private fun readSharedLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { raw ->
                val found = raw.trim().split(Regex("\\s+")).firstOrNull {
                    it.startsWith("https://")
                } ?: raw.trim()
                link.setText(found)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readSharedLink(intent)
    }

    @Deprecated("Android picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::videoPanel.isInitialized) videoPanel.result(requestCode, resultCode, data)
    }

    private fun render(block: () -> Unit) {
        runOnUiThread { if (!isDestroyed && !isFinishing) block() }
    }


    /** Cloudflare Turnstile runs in a WebView. Its token is checked by the server. */
    private fun loadCaptchaConfig() {
        Thread {
            try {
                val http = (URL(CAPTCHA_CONFIG).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = false
                }
                val needed = try {
                    if (http.responseCode != 200) return@Thread
                    JSONObject(http.inputStream.bufferedReader().use { it.readText().take(512) })
                        .optBoolean("required", false)
                } finally { http.disconnect() }
                captchaRequired = needed
                if (needed) render { showCaptcha() }
            } catch (_: Exception) {
                // If credentials are enabled on the server but this config is blocked,
                // /convert will still enforce the challenge. Never bypass server verification.
            }
        }.start()
    }

    private fun showCaptcha() {
        if (captchaWeb != null || isDestroyed) return
        captchaBox.visibility = View.VISIBLE
        captchaStatus.text = "Ben robot değilim doğrulamasını tamamla."
        val web = WebView(this)
        captchaWeb = web
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun onVerified(token: String) {
                if (token.length !in 3..2048) return
                captchaToken = token
                render { captchaStatus.text = "✓ Ben robot değilim doğrulaması tamamlandı." }
            }
            @JavascriptInterface
            fun onExpired() {
                captchaToken = null
                render { captchaStatus.text = "Doğrulama süresi doldu. Tekrar onayla." }
            }
        }, "AndroidCaptcha")
        captchaBox.addView(web, LinearLayout.LayoutParams(-1, dp(140)))
        web.loadUrl(CAPTCHA_PAGE)
    }

    private fun resetCaptcha() {
        if (!captchaRequired) return
        captchaToken = null
        captchaStatus.text = "Tekrar indirmek için doğrulamayı yenile."
        captchaWeb?.loadUrl(CAPTCHA_PAGE)
    }

    private fun download() {
        if (!busy.compareAndSet(false, true)) return
        val url = try {
            YoutubeLink.canonical(link.text.toString())
        } catch (e: IllegalArgumentException) {
            busy.set(false)
            status.text = "Bağlantı geçersiz. YouTube bağlantısı yapıştır."
            return
        }
        if (captchaRequired && captchaToken.isNullOrBlank()) {
            busy.set(false)
            status.text = "Önce Ben robot değilim doğrulamasını tamamla."
            return
        }
        cancelled = false
        startButton.isEnabled = false
        cancelButton.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        status.text = "Müzik hazırlanıyor. Biraz bekle..."
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 330_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "audio/mpeg")
                }
                request = connection
                val payload = JSONObject()
                    .put("url", url)
                    .put("quality", Mp3Options.DEFAULT_BITRATE)
                    .apply { if (captchaRequired) put("captcha_token", captchaToken ?: "") }
                    .toString().toByteArray(Charsets.UTF_8)
                connection.outputStream.use { it.write(payload) }
                if (cancelled) throw IOException("İptal")
                if (connection.responseCode != 200) {
                    val body = connection.errorStream?.bufferedReader()
                        ?.use { it.readText().take(1200) } ?: ""
                    val explanation = try { JSONObject(body).optString("error") }
                        catch (_: Exception) { "" }
                    throw IOException(if (explanation.isNotBlank()) explanation
                        else "Sunucu yanıt vermedi. Biraz sonra tekrar dene.")
                }
                if (connection.contentType?.substringBefore(";")?.trim() != "audio/mpeg")
                    throw IOException("Sunucu müzik dosyası göndermedi.")
                val size = connection.contentLengthLong
                if (size !in 1..Mp3Storage.MAX_BYTES)
                    throw IOException("Müzik dosyasının boyutu uygun değil.")
                val name = Mp3Options.outputName(url.substringAfter("v="),
                    Mp3Options.DEFAULT_BITRATE, System.currentTimeMillis())

                render {
                    progressBar.isIndeterminate = false
                    progressBar.progress = 0
                    status.text = "Müzik indiriliyor: %0"
                }
                val result = connection.inputStream.use { stream ->
                    storage.save(stream, size, name, { cancelled }) { current, total ->
                        val percent = (current * 100 / total).toInt().coerceIn(0, 100)
                        render {
                            progressBar.progress = percent
                            status.text = "Müzik indiriliyor: %" + percent
                        }
                    }
                }
                latestDownload = result
                render {
                    status.text = "✓ Müzik indirildi! Aşağıdan Dinle'ye bas."
                    refreshMusic()
                    videoPanel.refreshMusic(result)
                    Toast.makeText(this, "Müzik hazır!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                render {
                    status.text = if (cancelled) "İndirme iptal edildi."
                        else "Müzik indirilemedi: " + (e.message ?: "İnternet bağlantısını kontrol et.")
                }
            } finally {
                connection?.disconnect()
                request = null
                busy.set(false)
                render {
                    startButton.isEnabled = true
                    cancelButton.visibility = View.GONE
                    progressBar.visibility = View.GONE
                    resetCaptcha()
                }
            }
        }.start()
    }

    private fun refreshMusic() {
        if (!::musicList.isInitialized) return
        musicList.removeAllViews()
        val records = try { storage.list() } catch (_: Exception) { emptyList() }
        val newest = latestDownload
        val entries = if (newest != null && records.none { it.uri == newest.uri })
            listOf(newest) + records else records

        if (entries.isEmpty()) {
            val empty = label("Henüz müzik yok.\nİndirdiğin müzikler burada görünecek.", 16f, GRAY)
            empty.setPadding(dp(6), dp(14), dp(6), dp(14))
            add(musicList, empty, 0)
            return
        }

        entries.take(50).forEachIndexed { index, entry ->
            val item = column(12).apply { background = rounded(BACKGROUND, 15) }
            add(item, label("♫  Müzik " + (index + 1), 18f, INK, true), 4)
            add(item, label(
                String.format(Locale.getDefault(), "%.1f MB", entry.bytes / 1048576.0),
                13f, GRAY), 11)
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val play = button(if (playing?.uri == entry.uri && player?.isPlaying == true)
                "Duraklat" else "▶ Dinle", height = 48).apply {
                textSize = 16f
                setOnClickListener { playOrPause(entry) }
            }
            actions.addView(play, LinearLayout.LayoutParams(0, dp(52), 1.25f)
                .apply { rightMargin = dp(6) })
            val share = button("Paylaş", height = 48).apply {
                textSize = 15f
                setOnClickListener { share(entry) }
            }
            actions.addView(share, LinearLayout.LayoutParams(0, dp(52), 1f)
                .apply { rightMargin = dp(6) })
            val remove = button("Sil", height = 48).apply {
                textSize = 15f
                setOnClickListener { confirmDelete(entry) }
            }
            actions.addView(remove, LinearLayout.LayoutParams(0, dp(52), 0.65f))
            add(item, actions, 0)
            add(musicList, item, 10)
        }
    }

    private fun playOrPause(entry: AudioEntry) {
        val existing = player
        if (playing?.uri == entry.uri && existing != null) {
            try {
                if (existing.isPlaying) {
                    existing.pause()
                    playerStatus.text = "Müzik duraklatıldı."
                } else {
                    existing.start()
                    playerStatus.text = "Müzik çalıyor..."
                }
                refreshMusic()
            } catch (_: Exception) {
                stopPlayer()
            }
            return
        }
        stopPlayer()
        try {
            val media = MediaPlayer()
            media.setDataSource(this, entry.uri)
            player = media
            playing = entry
            playerStatus.text = "Müzik açılıyor..."
            media.setOnPreparedListener {
                if (player === it) {
                    it.start()
                    playerStatus.text = "Müzik çalıyor. Durdurmak için Duraklat'a bas."
                    refreshMusic()
                }
            }
            media.setOnCompletionListener { stopPlayer() }
            media.setOnErrorListener { _, _, _ ->
                stopPlayer()
                Toast.makeText(this, "Müzik açılamadı.", Toast.LENGTH_LONG).show()
                true
            }
            media.prepareAsync()
        } catch (_: Exception) {
            stopPlayer()
            Toast.makeText(this, "Müzik açılamadı.", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopPlayer() {
        val previous = player
        player = null
        playing = null
        try { previous?.release() } catch (_: Exception) { }
        if (::playerStatus.isInitialized) playerStatus.text = "Dinlemek için Dinle'ye bas."
        if (::musicList.isInitialized) refreshMusic()
    }

    private fun share(entry: AudioEntry) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mpeg"
                putExtra(Intent.EXTRA_STREAM, entry.uri)
                clipData = ClipData.newUri(contentResolver, entry.name, entry.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Müziği paylaş"))
        } catch (_: Exception) {
            Toast.makeText(this, "Müzik paylaşılamadı.", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(entry: AudioEntry) {
        AlertDialog.Builder(this)
            .setTitle("Müzik silinsin mi?")
            .setMessage("Bu müzik telefondan silinecek.")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Sil") { _, _ ->
                if (playing?.uri == entry.uri) stopPlayer()
                try {
                    if (storage.delete(entry)) {
                        if (latestDownload?.uri == entry.uri) latestDownload = null
                        refreshMusic()
                    } else {
                        Toast.makeText(this, "Müzik silinemedi.", Toast.LENGTH_LONG).show()
                    }
                } catch (_: Exception) {
                    Toast.makeText(this, "Müzik silinemedi.", Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    override fun onDestroy() {
        cancelled = true
        request?.disconnect()
        stopPlayer()
        if (::videoPanel.isInitialized) videoPanel.release()
        captchaWeb?.removeJavascriptInterface("AndroidCaptcha")
        captchaWeb?.destroy()
        captchaWeb = null
        super.onDestroy()
    }
}
