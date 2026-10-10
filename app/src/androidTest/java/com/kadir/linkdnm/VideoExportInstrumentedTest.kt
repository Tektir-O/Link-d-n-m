package com.kadir.linkdnm

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaCodec
import android.media.MediaFormat
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
import kotlin.math.sqrt

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
                // 0-500ms silence, then pure tone: choosing 500ms must remove silence.
                val amplitude = if (sample < sampleRate / 2) 0 else 9000
                buf.putShort((sin(sample * 2 * PI * 440 / sampleRate) * amplitude).toInt().toShort())
            }
            out.write(buf.array())
        }
        val result = AtomicReference<Uri?>()
        val failure = AtomicReference<String?>()
        val finished = CountDownLatch(1)
        val maker = VideoMaker(context)
        instrumentation.runOnMainSync {
            maker.export(
                // Verify a user-selected nonzero beginning is actually exported.
                Uri.fromFile(photo), Uri.fromFile(sound), 1, 500, 2000,
                onSaved = { uri -> result.set(uri); finished.countDown() },
                onError = { e -> failure.set(e); finished.countDown() },
                customDurationMs = VideoPlan.durationMs(0, 7)
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
            assertTrue("Expected custom 7-second video, got " + duration, duration in 6600L..7600L)
            assertEquals("yes", meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            assertEquals("yes", meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            assertTrue("Audio should be audible immediately after 500ms trim", firstAudioRms(context, uri) > 0.035)
        } finally {
            meta.release()
            uri?.let { context.contentResolver.delete(it, null, null) }
            photo.delete()
            sound.delete()
        }
    }

    /** Decode the opening AAC audio: output should contain tone, not the source's silent intro. */
    private fun firstAudioRms(context: android.content.Context, uri: Uri): Double {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw AssertionError("Cannot open MP4 for audio decode")
        try {
            descriptor.use { extractor.setDataSource(it.fileDescriptor) }
            val audioTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AssertionError("Export has no audio track")
            extractor.selectTrack(audioTrack)
            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw AssertionError("No audio mime")
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var eos = false
            var sum = 0.0
            var samples = 0
            val info = MediaCodec.BufferInfo()
            for (iteration in 0 until 600) {
                if (!eos) {
                    val inputIdx = decoder.dequeueInputBuffer(10_000)
                    if (inputIdx >= 0) {
                        val input = decoder.getInputBuffer(inputIdx) ?: break
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        } else {
                            val timestamp = extractor.sampleTime
                            decoder.queueInputBuffer(inputIdx, 0, size, timestamp, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIdx = decoder.dequeueOutputBuffer(info, 10_000)
                if (outputIdx >= 0) {
                    if (info.size > 1 && info.presentationTimeUs in 0..330_000) {
                        val bytes = decoder.getOutputBuffer(outputIdx)?.duplicate()
                        if (bytes != null) {
                            bytes.order(ByteOrder.LITTLE_ENDIAN)
                            var at = info.offset
                            val stop = info.offset + info.size
                            while (at + 1 < stop) {
                                val sample = bytes.getShort(at).toDouble() / 32768.0
                                sum += sample * sample
                                samples++
                                at += 2
                            }
                        }
                    }
                    val done = info.presentationTimeUs > 350_000 ||
                        (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    decoder.releaseOutputBuffer(outputIdx, false)
                    if (done) break
                }
            }
            assertTrue("No decoded opening PCM data", samples > 1000)
            return sqrt(sum / samples)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

}
