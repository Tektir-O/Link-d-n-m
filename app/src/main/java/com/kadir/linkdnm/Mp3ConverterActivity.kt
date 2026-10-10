package com.kadir.linkdnm

import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Only the public HTTPS server address ships in the APK. No API secret is included.
 * The server performs its own usage limits; Android never asks for a key.
 */
class Mp3ConverterActivity : Activity() {
    private companion object {
        const val CONVERT_ENDPOINT = "https://link-mp3-api-production.up.railway.app/convert"
        const val MAX_MP3_BYTES = 100L * 1024L * 1024L
    }

    private val background = Color.rgb(245, 247, 244)
    private val green = Color.rgb(22, 118, 97)
    private val darkGreen = Color.rgb(11, 72, 64)
    private lateinit var linkField: EditText
    private lateinit var status: TextView
    private lateinit var convertButton: Button

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun label(message: String, size: Float = 15f, color: Int = Color.DKGRAY) =
        TextView(this).apply { text = message; textSize = size; setTextColor(color) }

    private fun add(body: LinearLayout, view: View, bottom: Int = 14) {
        body.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(25), dp(20), dp(40))
        }
        add(body, label("BAĞLANTIDAN MP3", 25f, darkGreen), 8)
        add(body, label("YouTube bağlantısını yapıştır, MP3 olarak telefonuna kaydet.", 16f), 22)
        add(body, label("YouTube video bağlantısı"), 9)
        linkField = EditText(this).apply {
            hint = "https://youtu.be/..."
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        add(body, linkField, 8)
        val paste = Button(this).apply {
            text = "Bağlantıyı Yapıştır"
            isAllCaps = false
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                val link = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(this@Mp3ConverterActivity).toString()
                } else ""
                if (link.isBlank()) {
                    Toast.makeText(this@Mp3ConverterActivity, "Panoda bağlantı yok.", Toast.LENGTH_SHORT).show()
                } else linkField.setText(link.trim())
            }
        }
        add(body, paste, 22)
        convertButton = Button(this).apply {
            text = "MP3'e Dönüştür ve İndir"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(green)
            setOnClickListener { startConversion() }
        }
        add(body, convertButton, 13)
        status = label("Hazır. Sunucu otomatik bağlıdır.", 14f, darkGreen).apply {
            gravity = Gravity.CENTER
        }
        add(body, status, 20)
        add(body, label(
            "Sunucu adresi otomatik ayarlıdır. API anahtarı girmen gerekmez. " +
                "Kullanım kotası ve YouTube erişim sınırlamaları geçerlidir. " +
                "Yalnızca indirme iznin olan içerikleri kullan.", 13f
        ))
        setContentView(ScrollView(this).apply {
            setBackgroundColor(background)
            addView(body)
        })
    }

    private fun startConversion() {
        val canonical = try {
            YoutubeLink.canonical(linkField.text.toString())
        } catch (e: IllegalArgumentException) {
            status.text = e.message
            return
        }

        convertButton.isEnabled = false
        status.text = "MP3 hazırlanıyor, lütfen bekle..."
        Thread {
            try {
                val fileName = downloadMp3(canonical)
                runOnUiThread {
                    if (!isDestroyed) {
                        status.text = "MP3 kaydedildi: " + fileName
                        Toast.makeText(this, "MP3 kaydedildi", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isDestroyed) {
                        status.text = "Hata: " + (e.message ?: "Dönüştürülemedi.")
                    }
                }
            } finally {
                runOnUiThread { if (!isDestroyed) convertButton.isEnabled = true }
            }
        }.start()
    }

    private fun downloadMp3(link: String): String {
        val request = URL(CONVERT_ENDPOINT).openConnection() as HttpURLConnection
        request.requestMethod = "POST"
        request.instanceFollowRedirects = false
        request.connectTimeout = 20_000
        request.readTimeout = 330_000
        request.doOutput = true
        request.setRequestProperty("Content-Type", "application/json")
        request.setRequestProperty("Accept", "audio/mpeg")
        try {
            val payload = JSONObject().put("url", link).toString().toByteArray(Charsets.UTF_8)
            request.outputStream.use { it.write(payload) }
            if (request.responseCode != 200) {
                val response = request.errorStream?.bufferedReader()?.use { it.readText().take(1000) } ?: ""
                val message = try { JSONObject(response).optString("error", "") }
                              catch (_: Exception) { "" }
                throw IOException(message.ifBlank { "Sunucu hatası: " + request.responseCode })
            }
            if (request.contentType?.substringBefore(';')?.trim() != "audio/mpeg") {
                throw IOException("Sunucu MP3 verisi göndermedi.")
            }
            val total = request.contentLengthLong
            if (total <= 0L || total > MAX_MP3_BYTES) {
                throw IOException("Dosya boyutu geçersiz veya 100 MB'dan büyük.")
            }
            val fileName = "youtube-" + link.substringAfter("v=") + ".mp3"
            request.inputStream.use { saveMp3(it, total, fileName) }
            return fileName
        } finally {
            request.disconnect()
        }
    }

    private fun copyWithLimit(input: InputStream, output: java.io.OutputStream, total: Long) {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        var lastUpdate = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            if (copied > MAX_MP3_BYTES) throw IOException("100 MB sınırı aşıldı.")
            output.write(buffer, 0, count)
            if (copied - lastUpdate >= 512 * 1024L) {
                val percent = (copied * 100 / total).coerceIn(0, 100)
                runOnUiThread {
                    if (!isDestroyed) status.text = "MP3 indiriliyor: %" + percent
                }
                lastUpdate = copied
            }
        }
        if (copied != total) throw IOException("İndirme tamamlanamadı.")
    }

    private fun saveMp3(input: InputStream, total: Long, fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val metadata = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LinkMP3")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val output = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, metadata)
                ?: throw IOException("İndirilenler klasörü açılamadı.")
            try {
                contentResolver.openOutputStream(output)?.use { copyWithLimit(input, it, total) }
                    ?: throw IOException("MP3 dosyası kaydedilemedi.")
                val finished = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                contentResolver.update(output, finished, null, null)
            } catch (e: Exception) {
                contentResolver.delete(output, null, null)
                throw e
            }
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: throw IOException("Müzik klasörü açılamadı.")
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Klasör oluşturulamadı.")
            val target = File(dir, fileName)
            try {
                FileOutputStream(target).use { copyWithLimit(input, it, total) }
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }
    }
}
