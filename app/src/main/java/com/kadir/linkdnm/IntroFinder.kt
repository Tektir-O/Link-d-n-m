package com.kadir.linkdnm

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Approximate vocal entrance only. Instrumental sounds can be mistaken for singing.
 * An adjustable start slider and preview are always offered.
 * Decodes max 40 seconds locally; never sends music to a server.
 */
object IntroFinder {
    fun estimateMs(context: Context, uri: Uri): Long {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val selected = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return 0L
            extractor.selectTrack(selected)
            val format = extractor.getTrackFormat(selected)
            val type = format.getString(MediaFormat.KEY_MIME) ?: return 0L
            val codec = MediaCodec.createDecoderByType(type)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            val measurements = ArrayList<Pair<Long, Double>>()
            var inputFinished = false
            var outputFinished = false
            val info = MediaCodec.BufferInfo()
            var loops = 0
            while (!outputFinished && loops++ < 1600) {
                if (!inputFinished) {
                    val inputIndex = codec.dequeueInputBuffer(6_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: break
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        val position = extractor.sampleTime
                        if (size < 0 || position > 40_000_000) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputFinished = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, position, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 6_000)
                if (index >= 0) {
                    if (info.size >= 128 && info.presentationTimeUs >= 0) {
                        val output = codec.getOutputBuffer(index)
                        if (output != null) {
                            val b = output.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            val stop = info.offset + info.size
                            var last = 0.0
                            var squares = 0.0
                            var differences = 0.0
                            var samples = 0
                            var pos = info.offset
                            while (pos + 1 < stop) {
                                val signal = b.getShort(pos).toDouble() / 32768.0
                                val change = signal - last
                                squares += signal * signal
                                differences += change * change
                                samples++
                                last = signal
                                pos += 16 // sample every eighth mono sample
                            }
                            if (samples > 0) {
                                val loudness = sqrt(squares / samples)
                                val brightness = sqrt(differences / samples)
                                val measure = if (loudness > 0.017) brightness else 0.0
                                measurements.add(info.presentationTimeUs / 1000 to measure)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputFinished = true
                }
            }
            return estimate(measurements)
        } catch (_: Exception) {
            return 0L
        } finally {
            try { decoder?.stop() } catch (_: Exception) { }
            try { decoder?.release() } catch (_: Exception) { }
            extractor.release()
        }
    }

    private fun estimate(segments: List<Pair<Long, Double>>): Long {
        if (segments.size < 30) return 0
        val base = segments.filter { it.first in 700..3500 }
            .map { it.second }.sorted()
        if (base.isEmpty()) return 0
        val baseline = base[base.size / 2].coerceAtLeast(0.0005)
        var first = 0L
        var count = 0
        for ((ms, value) in segments) {
            if (ms < 3500 || ms > 37_000) continue
            if (value > baseline * 2.2 && value > 0.012) {
                if (count == 0) first = ms
                count++
                if (count >= 12 && ms - first >= 300) return first.coerceIn(0, 35_000)
            } else count = 0
        }
        return 0L
    }
}
