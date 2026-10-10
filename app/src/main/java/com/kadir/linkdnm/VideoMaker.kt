package com.kadir.linkdnm

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.min

/** Image + MP3 => local H.264/AAC MP4, entirely on-device. */
@UnstableApi
class VideoMaker(private val context: Context) {
    private var transformer: Transformer? = null
    private var imageFile: File? = null
    private var videoFile: File? = null
    private var working = false
    private var serial = 0
    val busy get() = working

    private fun normalizePhoto(uri: Uri): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Fotoğraf açılamadı.")
        // inJustDecodeBounds intentionally returns null; image dimensions are in bounds.
        input.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth < 1 || bounds.outHeight < 1) throw IOException("Fotoğraf bozuk.")
        val ratio = maxOf(1, maxOf(bounds.outWidth / 1600, bounds.outHeight / 1600))
        val original = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = ratio })
        } ?: throw IOException("Fotoğraf okunamadı.")
        val canvasBitmap = Bitmap.createBitmap(VideoPlan.WIDTH, VideoPlan.HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(canvasBitmap)
            canvas.drawColor(Color.BLACK)
            val scale = min(VideoPlan.WIDTH.toFloat() / original.width, VideoPlan.HEIGHT.toFloat() / original.height)
            val width = original.width * scale
            val height = original.height * scale
            val rect = android.graphics.RectF(
                (VideoPlan.WIDTH - width) / 2f, (VideoPlan.HEIGHT - height) / 2f,
                (VideoPlan.WIDTH + width) / 2f, (VideoPlan.HEIGHT + height) / 2f
            )
            canvas.drawBitmap(original, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            val file = File(context.cacheDir, "video-photo-${System.currentTimeMillis()}.png")
            FileOutputStream(file).use {
                if (!canvasBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                    throw IOException("Fotoğraf hazırlanamadı.")
                }
            }
            return file
        } finally {
            original.recycle()
            canvasBitmap.recycle()
        }
    }

    fun export(
        image: Uri, audio: Uri, minutes: Int, introMs: Long, songDurationMs: Long,
        onSaved: (Uri) -> Unit, onError: (String) -> Unit,
        testDurationMs: Long? = null,
        customDurationMs: Long? = null
    ) {
        check(!working) { "Başka video hazırlanıyor." }
        // Custom production duration is validated before starting a resource-heavy export.
        // Tests may explicitly request short clips when the APK is debuggable.
        val duration = if (testDurationMs != null && testDurationMs in 1_000L..10_000L &&
            (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        ) testDurationMs else customDurationMs?.let { VideoPlan.validateDurationMs(it) }
            ?: VideoPlan.durationMs(minutes)
        val start = VideoPlan.introMs(introMs, songDurationMs)
        working = true
        val job = ++serial
        try {
            val normalized = normalizePhoto(image)
            imageFile = normalized
            val target = File(context.cacheDir, "export-$job.mp4")
            videoFile = target
            val picture = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(Uri.fromFile(normalized))
                    .setImageDurationMs(duration).build()
            ).setFrameRate(15).build()
            val sound = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(audio).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).build()
                ).build()
            ).build()
            val imageSequence = EditedMediaItemSequence.withVideoFrom(listOf(picture))
            val audioSequence = EditedMediaItemSequence.withAudioFrom(listOf(sound))
                .buildUpon().setIsLooping(true).build()
            val composition = Composition.Builder(imageSequence, audioSequence).build()
            val runner = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (serial != job) return
                        try {
                            if (target.length() < 512) throw IOException("Boş video oluşturuldu.")
                            val uri = saveVideo(target)
                            cleanup()
                            onSaved(uri)
                        } catch (e: Exception) {
                            cleanup()
                            onError(e.message ?: "Video kaydedilemedi.")
                        }
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (serial != job) return
                        android.util.Log.e("VideoMaker", "MP4 export failed", exportException)
                        cleanup()
                        onError("Video oluşturulamadı. Fotoğraf veya ses biçimi desteklenmiyor olabilir.")
                    }
                }).build()
            transformer = runner
            runner.start(composition, target.absolutePath)
        } catch (e: Exception) {
            cleanup()
            onError(e.message ?: "Video başlatılamadı.")
        }
    }

    fun progress(): Int {
        val runner = transformer ?: return 0
        val progress = ProgressHolder()
        return if (runner.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE)
            progress.progress.coerceIn(0, 99) else 0
    }

    fun cancel() {
        ++serial
        transformer?.cancel()
        cleanup()
    }

    private fun cleanup() {
        working = false
        transformer = null
        imageFile?.delete()
        videoFile?.delete()
        imageFile = null
        videoFile = null
    }

    private fun saveVideo(input: File): Uri {
        val fileName = "MuzikVideo-${System.currentTimeMillis()}.mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MuzikVideolarim")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Galeriye kayıt açılamadı.")
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    input.inputStream().use { it.copyTo(output) }
                } ?: throw IOException("Video kaydedilemedi.")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                return uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
        val folder = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: throw IOException("Video klasörü açılamadı.")
        if (!folder.exists() && !folder.mkdirs()) throw IOException("Klasör oluşturulamadı.")
        val destination = File(folder, fileName)
        input.copyTo(destination)
        return androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", destination
        )
    }
}
