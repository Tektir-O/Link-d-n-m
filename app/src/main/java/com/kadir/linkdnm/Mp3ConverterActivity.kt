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
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
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
        const val FILE_ENDPOINT = "https://link-mp3-api-production.up.railway.app/convert-file"
        const val PICK_FILE_MP3 = 5301
        const val MAX_UPLOAD_BYTES = 60L * 1024L * 1024L
        val BACKGROUND = Color.rgb(243, 248, 246)
        val GREEN = Color.rgb(11, 108, 83)
        val INK = Color.rgb(25, 57, 47)
        val GRAY = Color.rgb(87, 111, 103)
        val PALE = Color.rgb(221, 240, 233)
    }

    private lateinit var link: EditText
    private lateinit var startButton: Button
    private lateinit var uploadButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var status: TextView
    private lateinit var musicList: LinearLayout
    private lateinit var musicCard: LinearLayout
    private lateinit var videoCard: View
    private lateinit var videoToggle: Button
    private lateinit var playerStatus: TextView
    private lateinit var storage: Mp3Storage
    private lateinit var videoPanel: VideoPanel

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
        val root = column(14)
        val header = column(14).apply { background = rounded(INK, 22) }
        add(header, label("♫ MÜZİK İNDİR", 26f, Color.WHITE, true), 0)
        add(root, header, 14)

        val form = column(14).apply { background = rounded(Color.WHITE, 20) }
        link = EditText(this).apply {
            hint = "YouTube bağlantısı"
            setSingleLine(true)
            textSize = 18f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(INK)
            setHintTextColor(GRAY)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = rounded(BACKGROUND, 14)
            contentDescription = "YouTube bağlantısı"
        }
        add(form, link, 12)
        add(form, button("📋 BAĞLANTIYI YAPIŞTIR", height=70).apply {
            textSize=21f
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val copied = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(this@Mp3ConverterActivity)?.toString()
                if (copied.isNullOrBlank()) {
                    status.text = "Bağlantıyı kopyala."
                } else {
                    val value=copied.trim().split(Regex("\\s+")).firstOrNull { it.startsWith("https://") }
                        ?:copied.trim()
                    link.setText(value)
                    link.setSelection(link.text.length)
                    status.text = "Hazır"
                }
            }
        }, 12)
        startButton = button("⬇ MP3 İNDİR", primary=true, height=78).apply {
            textSize=24f
            setOnClickListener { download() }
        }
        add(form,startButton,8)
        uploadButton = button("📁 TELEFONDAN DOSYA SEÇ", height=60).apply {
            textSize=17f
            setOnClickListener { pickFileToConvert() }
        }
        add(form,uploadButton,8)
        cancelButton = button("İPTAL", height=50).apply {
            visibility=View.GONE
            setOnClickListener {
                cancelled=true
                status.text="İptal ediliyor..."
                request?.disconnect()
            }
        }
        add(form,cancelButton,5)
        progressBar = ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            visibility=View.GONE
            max=100
            isIndeterminate=true
            progressTintList=ColorStateList.valueOf(GREEN)
        }
        add(form,progressBar,5)
        status = label("Hazır",16f,GRAY)
        add(form,status,0)
        add(root,form,12)

        musicCard = column(12).apply { background=rounded(Color.WHITE,20); visibility=View.GONE }
        add(musicCard,label("🎵 MÜZİKLERİM",21f,INK,true),8)
        playerStatus=label("",14f,GRAY).apply { visibility=View.GONE }
        add(musicCard,playerStatus,2)
        musicList=column(0)
        add(musicCard,musicList,0)
        add(root,musicCard,12)

        videoToggle = button("🎬 VİDEO YAP",height=78).apply {
            textSize=23f
            setOnClickListener {
                videoCard.visibility = if(videoCard.visibility==View.VISIBLE) View.GONE else View.VISIBLE
                text = if(videoCard.visibility==View.VISIBLE) "🎬 VİDEOYU KAPAT" else "🎬 VİDEO YAP"
            }
        }
        add(root,videoToggle,10)
        videoPanel=VideoPanel(this,storage)
        videoCard=videoPanel.createView().apply { visibility=View.GONE }
        add(root,videoCard,10)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            isFillViewport = true
            addView(root)
        })
        readSharedLink(intent)
        refreshMusic()
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
        if (requestCode == PICK_FILE_MP3 && resultCode == RESULT_OK) {
            data?.data?.let { convertSelectedFile(it) }
        } else if (::videoPanel.isInitialized) videoPanel.result(requestCode, resultCode, data)
    }

    private fun render(block: () -> Unit) {
        runOnUiThread { if (!isDestroyed && !isFinishing) block() }
    }


    private fun pickFileToConvert() {
        if (busy.get()) return
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "video/mp4", "video/quicktime", "video/webm", "video/x-matroska", "video/3gpp",
                "audio/mpeg", "audio/mp4", "audio/wav", "audio/x-wav", "audio/webm", "audio/ogg"
            ))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(pick, PICK_FILE_MP3)
    }

    private fun convertSelectedFile(source: Uri) {
        if (!busy.compareAndSet(false, true)) return
        val allowed = setOf(
            "video/mp4", "video/quicktime", "video/webm", "video/x-matroska",
            "video/3gpp", "audio/mpeg", "audio/mp4", "audio/wav", "audio/x-wav",
            "audio/webm", "audio/ogg", "application/ogg"
        )
        val type = contentResolver.getType(source)?.substringBefore(";")?.lowercase(Locale.ROOT)
        if (type == null || type !in allowed) {
            busy.set(false)
            status.text = "MP3'e çevirmek için desteklenen video veya ses dosyası seç."
            return
        }
        cancelled = false
        startButton.isEnabled = false
        uploadButton.isEnabled = false
        cancelButton.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        status.text = "Dosya hazırlanıyor. Biraz bekle..."
        Thread {
            var connection: HttpURLConnection? = null
            val local = File(cacheDir, "upload-" + System.currentTimeMillis() + ".bin")
            try {
                var size = 0L
                contentResolver.openInputStream(source)?.use { input ->
                    FileOutputStream(local).use { dest ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled) throw IOException("İptal")
                            val amount = input.read(buffer)
                            if (amount < 0) break
                            size += amount
                            if (size > MAX_UPLOAD_BYTES) throw IOException("Dosya 60 MB sınırını aşıyor.")
                            dest.write(buffer, 0, amount)
                        }
                    }
                } ?: throw IOException("Telefon dosyayı açamadı.")
                if (size == 0L) throw IOException("Seçilen dosya boş.")
                val http = (URL(FILE_ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 220_000
                    doOutput = true
                    setRequestProperty("Content-Type", type)
                    setRequestProperty("Accept", "audio/mpeg")
                    setFixedLengthStreamingMode(size)
                }
                connection = http
                request = http
                render {
                    progressBar.isIndeterminate = false
                    progressBar.progress = 0
                    status.text = "Video/ses dosyası gönderiliyor..."
                }
                local.inputStream().use { input ->
                    http.outputStream.use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var sent = 0L
                        while (true) {
                            if (cancelled) throw IOException("İptal")
                            val amount = input.read(buffer)
                            if (amount < 0) break
                            output.write(buffer, 0, amount)
                            sent += amount
                            val percentage = ((sent * 100) / size).toInt()
                            render {
                                progressBar.progress = percentage
                                status.text = "Dosya gönderiliyor: %" + percentage
                            }
                        }
                    }
                }
                if (cancelled) throw IOException("İptal")
                val responseCode = http.responseCode
                if (responseCode != 200) {
                    val err = http.errorStream?.bufferedReader()
                        ?.use { it.readText().take(1200) } ?: ""
                    val msg = try { JSONObject(err).optString("error") } catch (_: Exception) { "" }
                    throw IOException(if (msg.isNotBlank()) msg
                        else "Dönüştürme başarısız (HTTP $responseCode).")
                }
                if (http.contentType?.substringBefore(";")?.trim() != "audio/mpeg")
                    throw IOException("Sunucu MP3 göndermedi.")
                val outputSize = http.contentLengthLong
                if (outputSize !in 1..Mp3Storage.MAX_BYTES)
                    throw IOException("Gelen MP3 boyutu geçersiz.")
                val name = "video-yerelmedya0-192k-" + System.currentTimeMillis() + ".mp3"
                render { progressBar.isIndeterminate = true; status.text = "MP3 telefona kaydediliyor..." }
                val entry = http.inputStream.use { stream ->
                    storage.save(stream, outputSize, name, { cancelled }) { _, _ -> }
                }
                latestDownload = entry
                render {
                    status.text = "✓ Video/ses MP3'e dönüştürüldü!"
                    refreshMusic()
                    videoPanel.refreshMusic(entry)
                    Toast.makeText(this, "MP3 hazır!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                render {
                    status.text = if (cancelled) "Dönüştürme iptal edildi."
                        else "MP3 oluşturulamadı: " + (e.message ?: "Dosya okunamadı.")
                }
            } finally {
                local.delete()
                connection?.disconnect()
                request = null
                busy.set(false)
                render {
                    startButton.isEnabled = true
                    uploadButton.isEnabled = true
                    cancelButton.visibility = View.GONE
                    progressBar.visibility = View.GONE
                }
            }
        }.start()
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
        cancelled = false
        startButton.isEnabled = false
        uploadButton.isEnabled = false
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
                    uploadButton.isEnabled = true
                    cancelButton.visibility = View.GONE
                    progressBar.visibility = View.GONE
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
            musicCard.visibility=View.GONE
            return
        }
        musicCard.visibility=View.VISIBLE

        entries.take(50).forEachIndexed { index, entry ->
            val item = column(12).apply { background = rounded(BACKGROUND, 15) }
            add(item, label("♫  Müzik " + (index + 1), 18f, INK, true), 4)

            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val play = button(if (playing?.uri == entry.uri && player?.isPlaying == true)
                "Duraklat" else "▶ Dinle", height = 48).apply {
                textSize = 16f
                setOnClickListener { playOrPause(entry) }
            }
            actions.addView(play, LinearLayout.LayoutParams(0, dp(52), 1.25f)
                .apply { rightMargin = dp(6) })
            val edit = button("✂ Kes", height = 48).apply {
                textSize = 15f
                setOnClickListener {
                    startActivity(Intent(this@Mp3ConverterActivity, AudioCutActivity::class.java)
                        .putExtra("audio_uri", entry.uri.toString()))
                }
            }
            actions.addView(edit, LinearLayout.LayoutParams(0, dp(52), 0.85f)
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
        super.onDestroy()
    }
}
