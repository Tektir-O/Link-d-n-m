package com.kadir.linkdnm

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Local MP3 library. Uses scoped storage on Android 10+ and FileProvider on older versions. */
class Mp3LibraryActivity : Activity() {
    private val green = Color.rgb(13, 116, 91)
    private val page = Color.rgb(241, 247, 245)
    private val ink = Color.rgb(24, 55, 48)
    private lateinit var rows: LinearLayout
    private lateinit var playback: TextView
    private lateinit var seek: SeekBar
    private lateinit var storage: Mp3Storage
    private var player: MediaPlayer? = null
    private var playing: AudioEntry? = null
    private val handler = Handler(Looper.getMainLooper())
    private val updater = object : Runnable {
        override fun run() {
            val p = player ?: return
            try {
                if (p.duration > 0) seek.progress = (p.currentPosition.toLong() * 1000 / p.duration).toInt()
                handler.postDelayed(this, 500)
            } catch (_: IllegalStateException) { }
        }
    }
    private fun dp(x: Int) = (x * resources.displayMetrics.density + 0.5f).toInt()
    private fun box(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(17).toFloat() }
    private fun col(padding: Int = 12) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    private fun label(value: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(ink)
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun add(parent: LinearLayout, child: View, margin: Int = 10) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(margin) })
    }
    private fun button(name: String) = Button(this).apply {
        text = name
        isAllCaps = false
        textSize = 13f
        setTextColor(green)
        background = box(Color.rgb(220, 242, 233))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Mp3Storage(this)
        val root = col(16)
        add(root, label("♪  MP3 ARŞİVİM", 24f, true), 10)
        playback = label("Bir MP3 seçip dinleyebilirsin.", 14f)
        add(root, playback, 5)
        seek = SeekBar(this).apply {
            max = 1000
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) player?.let { p ->
                        if (p.duration > 0) p.seekTo(p.duration * value / 1000)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { }
                override fun onStopTrackingTouch(bar: SeekBar?) { }
            })
        }
        add(root, seek, 5)
        add(root, button("■  Oynatmayı Durdur").apply { setOnClickListener { stopPlayback() } }, 15)
        add(root, label("İndirilen müzikler", 19f, true), 8)
        add(root, button("Listeyi Yenile").apply { setOnClickListener { refresh() } }, 10)
        rows = col(0)
        add(root, rows, 9)
        add(root, label("Android 10+ kayıt konumu: Download / LinkMP3", 12f), 0)
        setContentView(ScrollView(this).apply { setBackgroundColor(page); addView(root) })
        refresh()
    }
    private fun refresh() {
        rows.removeAllViews()
        val entries = try { storage.list() } catch (e: Exception) {
            Toast.makeText(this, "Dosya listesi okunamadı: " + e.message, Toast.LENGTH_LONG).show()
            emptyList()
        }
        if (entries.isEmpty()) {
            add(rows, label("Henüz indirilen MP3 yok."), 0)
            return
        }
        for (entry in entries) {
            val tile = col(12).apply { background = box(Color.WHITE) }
            add(tile, label("♫  " + entry.name, 13f, true), 4)
            add(tile, label(String.format(java.util.Locale.US, "%.2f MB", entry.bytes / 1048576.0), 12f), 6)
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(
                "▶ / ❚❚" to { playPause(entry) },
                "Paylaş" to { share(entry) },
                "Sil" to { confirmDelete(entry) }
            ).forEach { (caption, callback) ->
                actions.addView(button(caption).apply { setOnClickListener { callback() } },
                    LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(4) })
            }
            add(tile, actions, 0)
            add(rows, tile, 10)
        }
    }
    private fun playPause(entry: AudioEntry) {
        val current = player
        if (playing?.uri == entry.uri && current != null) {
            try {
                if (current.isPlaying) {
                    current.pause()
                    playback.text = "Duraklatıldı: " + entry.name
                } else {
                    current.start()
                    playback.text = "Çalıyor: " + entry.name
                }
            } catch (e: Exception) { stopPlayback() }
            return
        }
        stopPlayback()
        try {
            val next = MediaPlayer().apply {
                setDataSource(this@Mp3LibraryActivity, entry.uri)
                prepare()
                setOnCompletionListener { stopPlayback() }
            }
            player = next
            playing = entry
            seek.isEnabled = true
            playback.text = "Çalıyor: " + entry.name
            next.start()
            handler.post(updater)
        } catch (e: Exception) {
            stopPlayback()
            Toast.makeText(this, "MP3 oynatılamadı: " + e.message, Toast.LENGTH_LONG).show()
        }
    }
    private fun stopPlayback() {
        handler.removeCallbacks(updater)
        try { player?.release() } catch (_: Exception) { }
        player = null
        playing = null
        if (::seek.isInitialized) { seek.progress = 0; seek.isEnabled = false }
        if (::playback.isInitialized) playback.text = "Bir MP3 seçip dinleyebilirsin."
    }
    private fun share(entry: AudioEntry) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mpeg"
                putExtra(Intent.EXTRA_STREAM, entry.uri)
                clipData = ClipData.newUri(contentResolver, entry.name, entry.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "MP3 paylaş"))
        } catch (e: Exception) {
            Toast.makeText(this, "Paylaşım hatası: " + e.message, Toast.LENGTH_LONG).show()
        }
    }
    private fun confirmDelete(entry: AudioEntry) {
        AlertDialog.Builder(this).setTitle("Dosya silinsin mi?")
            .setMessage(entry.name)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Sil") { _, _ ->
                if (playing?.uri == entry.uri) stopPlayback()
                try {
                    if (storage.delete(entry)) refresh()
                    else Toast.makeText(this, "Dosya silinemedi.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Silme hatası: " + e.message, Toast.LENGTH_LONG).show()
                }
            }.show()
    }
    override fun onDestroy() {
        stopPlayback()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
