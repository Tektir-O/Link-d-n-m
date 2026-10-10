package com.kadir.linkdnm

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class AudioEntry(val name: String, val uri: Uri, val bytes: Long, val local: File? = null)

class Mp3Storage(private val context: Context) {
    companion object {
        const val MAX_BYTES = 100L * 1024L * 1024L
        private const val FOLDER = "LinkMP3"
    }

    private fun copyStream(
        input: InputStream,
        out: OutputStream,
        expected: Long,
        cancelled: () -> Boolean,
        update: (Long, Long) -> Unit
    ) {
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            if (cancelled()) throw IOException("İndirme iptal edildi.")
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            if (count > MAX_BYTES) throw IOException("MP3 100 MB sınırını aşıyor.")
            out.write(buffer, 0, read)
            update(count, expected)
        }
        if (cancelled()) throw IOException("İndirme iptal edildi.")
        if (count != expected) throw IOException("İndirme eksik tamamlandı.")
    }

    fun save(input: InputStream, expected: Long, name: String,
             cancelled: () -> Boolean, update: (Long, Long) -> Unit): AudioEntry {
        require(expected in 1..MAX_BYTES) { "Geçersiz dosya boyutu." }
        require(Regex("youtube-[A-Za-z0-9_-]{11}-(128|192|320)k-[0-9]+[.]mp3").matches(name)) {
            "Güvensiz dosya adı."
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("İndirilenler klasörü açılamadı.")
            try {
                context.contentResolver.openOutputStream(uri)?.use {
                    copyStream(input, it, expected, cancelled, update)
                } ?: throw IOException("MP3 kaydedilemedi.")
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                context.contentResolver.update(uri, done, null, null)
                return AudioEntry(name, uri, expected).also { remember(it) }
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: throw IOException("Müzik klasörü açılamadı.")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Müzik klasörü oluşturulamadı.")
        val file = File(dir, name)
        try {
            FileOutputStream(file).use { copyStream(input, it, expected, cancelled, update) }
            return AudioEntry(name, localUri(file), expected, file).also { remember(it) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    private fun localUri(file: File) = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )


    // A persistent URI list is needed because Android vendors disagree about folder
    // filtering in MediaStore Downloads. Keeping it avoids an empty list right
    // after a successful save, even on Samsung One UI builds.
    private fun index(): JSONArray {
        val raw = context.getSharedPreferences("link_mp3_index", Context.MODE_PRIVATE)
            .getString("saved", "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun remember(entry: AudioEntry) {
        val old = index()
        val result = JSONArray()
        result.put(JSONObject().put("name", entry.name)
            .put("uri", entry.uri.toString()).put("bytes", entry.bytes))
        for (i in 0 until old.length()) {
            val record = old.optJSONObject(i) ?: continue
            if (record.optString("uri") != entry.uri.toString() && result.length() < 80)
                result.put(record)
        }
        context.getSharedPreferences("link_mp3_index", Context.MODE_PRIVATE)
            .edit().putString("saved", result.toString()).commit()
    }

    private fun forget(entry: AudioEntry) {
        val old = index()
        val result = JSONArray()
        for (i in 0 until old.length()) {
            val record = old.optJSONObject(i) ?: continue
            if (record.optString("uri") != entry.uri.toString()) result.put(record)
        }
        context.getSharedPreferences("link_mp3_index", Context.MODE_PRIVATE)
            .edit().putString("saved", result.toString()).apply()
    }

    private fun indexedFiles(): List<AudioEntry> {
        val entries = mutableListOf<AudioEntry>()
        val saved = index()
        for (i in 0 until saved.length()) {
            val item = saved.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (!name.endsWith(".mp3", true)) continue
            val uri = Uri.parse(item.optString("uri"))
            val readable = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            } catch (_: Exception) { false }
            if (readable) entries.add(AudioEntry(name, uri, item.optLong("bytes", 0L)))
        }
        return entries
    }

    fun list(): List<AudioEntry> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?.listFiles()?.asSequence()?.filter { it.isFile && it.extension.equals("mp3", true) }
                ?.sortedByDescending { it.lastModified() }?.take(40)
                ?.map { AudioEntry(it.name, localUri(it), it.length(), it) }?.toList()
                ?: emptyList()
        }
        val merged = linkedMapOf<String, AudioEntry>()
        indexedFiles().forEach { merged[it.uri.toString()] = it }
        // Query owned Downloads by name instead of exact RELATIVE_PATH. Some Android
        // vendor builds normalize the path differently and return no folder matches.
        try {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val columns = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE)
            context.contentResolver.query(
                collection, columns, MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
                arrayOf("youtube-%.mp3"), MediaStore.Downloads.DATE_ADDED + " DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                while (cursor.moveToNext() && merged.size < 80) {
                    val name = cursor.getString(nameIndex) ?: continue
                    if (!name.startsWith("youtube-") || !name.endsWith(".mp3", true)) continue
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                    merged.putIfAbsent(uri.toString(), AudioEntry(name, uri, cursor.getLong(sizeIndex)))
                }
            }
        } catch (_: Exception) {
            // Saved URIs remain accessible even if MediaStore query is denied.
        }
        return merged.values.take(80)
    }

    fun delete(entry: AudioEntry): Boolean {
        val removed = if (entry.local != null) entry.local.delete()
        else context.contentResolver.delete(entry.uri, null, null) > 0
        if (removed) forget(entry)
        return removed
    }
}
