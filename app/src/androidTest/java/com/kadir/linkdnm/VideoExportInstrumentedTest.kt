package com.kadir.linkdnm

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin

/** Export a real 3-second MP4 from local PNG + WAV and validate the produced tracks. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class VideoExportInstrumentedTest {
    @Test fun photoAndAudioReallyBecomePlayableMp4() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val photo = File(context.cacheDir, "test-video-photo.png")
        val sound = File(context.cacheDir, "test-video-audio.wav")
        val picture = Bitmap.createBitmap(240, 426, Bitmap.Config.ARGB_8888)
        picture.eraseColor(Color.rgb(34, 127, 90))
        FileOutputStream(photo).use { assertTrue(picture.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        picture.recycle()

        // Exactly 2 seconds of generated 44.1kHz PCM mono audio.
        val sampleRate = 44100
        val frames = sampleRate * 2
        val pcmSize = frames * 2
        FileOutputStream(sound).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(pcmSize + 36)
            header.put("WAVEfmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)
            header.putShort(1)
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray())
            header.putInt(pcmSize)
            out.write(header.array())
            val buf = ByteBuffer.allocate(pcmSize).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { sample ->
                buf.putShort((sin(sample * 2 * PI * 440 / sampleRate) * 9000).toInt().toShort())
            }
            out.write(buf.array())
        }
        val result = AtomicReference<Uri?>()
        val failure = AtomicReference<String?>()
        val finished = CountDownLatch(1)
        val maker = VideoMaker(context)
        instrumentation.runOnMainSync {
            maker.export(
                Uri.fromFile(photo), Uri.fromFile(sound), 1, 0, 2000,
                onSaved = { uri -> result.set(uri); finished.countDown() },
                onError = { e -> failure.set(e); finished.countDown() },
                testDurationMs = 3000
            )
        }
        assertTrue("Video export timed out", finished.await(160, TimeUnit.SECONDS))
        assertNull("MP4 export failed: " + failure.get(), failure.get())
        val uri = result.get()
        assertNotNull("No video URI produced", uri)
        val meta = MediaMetadataRetriever()
        try {
            meta.setDataSource(context, uri!!)
            val duration = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            assertTrue("Expected about 3 seconds of video, got " + duration, duration in 2500L..4500L)
            assertEquals("yes", meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            assertEquals("yes", meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
        } finally {
            meta.release()
            uri?.let { context.contentResolver.delete(it, null, null) }
            photo.delete()
            sound.delete()
        }
    }
}
