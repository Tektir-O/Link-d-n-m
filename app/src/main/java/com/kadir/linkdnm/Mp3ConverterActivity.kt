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
import java.net.URI
import java.net.URL

/** Optional online MP3 screen. Quran assets still remain offline. */
class Mp3ConverterActivity : Activity() {
    private val maxBytes = 100L * 1024L * 1024L
    private val bg = Color.rgb(245, 247, 244)
    private val darkGreen = Color.rgb(11, 72, 64)
    private val green = Color.rgb(22, 118, 97)
    private lateinit var linkField: EditText
    private lateinit var endpointField: EditText
    private lateinit var keyField: EditText
    private lateinit var status: TextView
    private lateinit var convertButton: Button

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun label(message: String, size: Float = 15f, color: Int = Color.DKGRAY): TextView =
        TextView(this).apply { text = message; textSize = size; setTextColor(color) }

    private fun field(hint: String, password: Boolean = false): EditText =
        EditText(this).apply {
            setSingleLine(true)
            this.hint = hint
            textSize = 15f
            inputType = if (password)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

    private fun add(body: LinearLayout, view: View, margin: Int = 13) {
        body.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(margin)
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("link_mp3", MODE_PRIVATE)
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(25), dp(20), dp(40))
        }
        add(body, label("BAĞLANTIDAN MP3", 24f, darkGreen), 7)
        add(body, label("YouTube video bağlantısını yapıştırıp MP3 olarak kaydet.", 15f), 18)
        add(body, label("YouTube bağlantısı"))
        linkField = field("https://youtu.be/...")
        add(body, linkField, 6)
        val paste = Button(this).apply {
            text = "Bağlantıyı Yapıştır"
            isAllCaps = false
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                val value = if (clip != null && clip.itemCount > 0)
                    clip.getItemAt(0).coerceToText(this@Mp3ConverterActivity).toString() else ""
                if (value.isNotBlank()) linkField.setText(value)
                else Toast.makeText(this@Mp3ConverterActivity, "Panoda bağlantı yok.", Toast.LENGTH_SHORT).show()
            }
        }
        add(body, paste, 22)

        add(body, label("MP3 sunucu adresi (HTTPS)"))
        endpointField = field("https://mp3.ornekalanadi.com").apply {
            setText(prefs.getString("endpoint", "") ?: "")
        }
        add(body, endpointField, 10)
        add(body, label("Sunucu API anahtarı"))
        keyField = field("Gizli sunucu anahtarı", password = true)
        add(body, keyField, 5)
        add(body, label("Gizli anahtar kaydedilmez, sadece bu açık ekranda tutulur.", 12f), 18)

        convertButton = Button(this).apply {
            text = "MP3'e Dönüştür ve İndir"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(green)
            setOnClickListener { startConversion() }
        }
        add(body, convertButton, 14)
        status = label("Hazır", 14f, darkGreen).apply { gravity = Gravity.CENTER }
        add(body, status, 16)
        add(body, label(
            "Yalnızca indirme izni olan içeriklerde kullanın. " +
            "İnternet ve ayrı bir MP3 sunucusu gerektirir. " +
            "Bu uygulama yalnızca MP3 bağlantı dönüştürücüsüdür.", 13f
        ))
        scroll.addView(body)
        setContentView(scroll)
    }

    private fun startConversion() {
        val canonical = try { YoutubeLink.canonical(linkField.text.toString()) }
                        catch (e: IllegalArgumentException) { status.text = e.message; return }
        val endpoint = endpointField.text.toString().trim().trimEnd('/')
        val validEndpoint = try {
            val parsed = URI(endpoint)
            parsed.scheme.equals("https", ignoreCase = true) &&
                !parsed.host.isNullOrBlank() && parsed.rawUserInfo == null &&
                parsed.rawQuery == null && parsed.rawFragment == null
        } catch (_: Exception) { false }
        if (!validEndpoint) {
            status.text = "Önce HTTPS MP3 sunucu adresini girin."
            return
        }
        val secret = keyField.text.toString()
        if (secret.length < 16) {
            status.text = "Sunucu API anahtarı en az 16 karakter olmalı."
            return
        }
        getSharedPreferences("link_mp3", MODE_PRIVATE).edit()
            .putString("endpoint", endpoint).apply()
        convertButton.isEnabled = false
        status.text = "Bağlanıyor ve ses hazırlanıyor..."
        Thread {
            try {
                val name = download(endpoint, secret, canonical)
                runOnUiThread {
                    if (!isDestroyed) {
                        status.text = "MP3 kaydedildi: " + name
                        Toast.makeText(this, "MP3 kaydedildi", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isDestroyed) status.text = "Hata: " + (e.message ?: "Dönüştürme başarısız.")
                }
            } finally {
                runOnUiThread { if (!isDestroyed) convertButton.isEnabled = true }
            }
        }.start()
    }

    private fun download(endpoint: String, key: String, canonical: String): String {
        val connection = URL(endpoint + "/convert").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 20_000
        connection.readTimeout = 330_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "audio/mpeg")
        connection.setRequestProperty("Authorization", "Bearer " + key)
        try {
            val payload = JSONObject().put("url", canonical).toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(payload) }
            if (connection.responseCode != 200) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText().take(1000) } ?: ""
                val message = try { JSONObject(errorText).optString("error", "") }
                              catch (_: Exception) { "" }
                throw IOException(message.ifBlank { "Sunucu yanıtı: " + connection.responseCode })
            }
            if (connection.contentType?.substringBefore(';')?.trim() != "audio/mpeg")
                throw IOException("Sunucu MP3 yerine farklı dosya gönderdi.")
            val size = connection.contentLengthLong
            if (size <= 0L || size > maxBytes)
                throw IOException("MP3 boyutu geçersiz veya çok büyük.")
            val name = "youtube-" + canonical.substringAfter("v=") + ".mp3"
            connection.inputStream.use { saveToDownloads(it, size, name) }
            return name
        } finally {
            connection.disconnect()
        }
    }

    private fun copyLimited(input: InputStream, output: java.io.OutputStream, length: Long) {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        var nextUpdate = 512 * 1024L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            if (copied > maxBytes) throw IOException("Dosya 100 MB sınırını geçti.")
            output.write(buffer, 0, read)
            if (copied >= nextUpdate) {
                val percent = (copied * 100 / length).coerceIn(0, 100)
                runOnUiThread { if (!isDestroyed) status.text = "MP3 indiriliyor: %" + percent }
                nextUpdate = copied + 512 * 1024L
            }
        }
        if (copied != length) throw IOException("MP3 indirmesi yarım kaldı.")
    }

    private fun saveToDownloads(input: InputStream, length: Long, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LinkMP3")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val target = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("İndirilenler klasörü açılamadı.")
            try {
                contentResolver.openOutputStream(target)?.use { copyLimited(input, it, length) }
                    ?: throw IOException("MP3 kaydedilemedi.")
                val finished = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                contentResolver.update(target, finished, null, null)
            } catch (e: Exception) {
                contentResolver.delete(target, null, null)
                throw e
            }
        } else {
            val folder = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: throw IOException("Müzik klasörü bulunamadı.")
            if (!folder.exists() && !folder.mkdirs()) throw IOException("Klasör oluşturulamadı.")
            val target = File(folder, name)
            try {
                FileOutputStream(target).use { copyLimited(input, it, length) }
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }
    }
}
