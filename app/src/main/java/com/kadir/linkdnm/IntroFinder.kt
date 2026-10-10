package com.kadir.linkdnm

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Local-only waveform analysis and a tentative intro change. Instruments can
 * resemble vocals: this NEVER automatically edits the selected starting point.
 * Precision comes from the waveform, 0.1s trimming and listening to a preview.
 */
object IntroFinder {
    const val WAVEFORM_WINDOW_MS = 90_000L
    const val BIN_MS = 250L
    private const val BINS = 360
    data class Analysis(val suggestionMs: Long, val amplitudes: FloatArray)

    fun estimateMs(context: Context, uri: Uri): Long = analyze(context, uri).suggestionMs

    fun analyze(context: Context, uri: Uri): Analysis {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return Analysis(0L, FloatArray(BINS))
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return Analysis(0L, FloatArray(BINS))
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            val magnitudes = FloatArray(BINS)
            val waveCount = IntArray(BINS)
            val measurements = ArrayList<Pair<Long, Double>>()
            val info = MediaCodec.BufferInfo()
            var inputFinished = false
            var outputFinished = false
            var loops = 0
            while (!outputFinished && loops++ < 18000) {
                if (!inputFinished) {
                    val idx = codec.dequeueInputBuffer(6_000)
                    if (idx >= 0) {
                        val buffer = codec.getInputBuffer(idx) ?: break
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        val position = extractor.sampleTime
                        if (size < 0 || position > WAVEFORM_WINDOW_MS * 1000) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputFinished = true
                        } else {
                            codec.queueInputBuffer(idx, 0, size, position, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 6_000)
                if (index >= 0) {
                    if (info.size >= 128 && info.presentationTimeUs in 0..(WAVEFORM_WINDOW_MS * 1000)) {
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
                                pos += 16
                            }
                            if (samples > 0) {
                                val loudness = sqrt(squares / samples)
                                val brightness = sqrt(differences / samples)
                                val timeMs = info.presentationTimeUs / 1000
                                val bin = (timeMs / BIN_MS).toInt()
                                if (bin in 0 until BINS) {
                                    magnitudes[bin] += loudness.toFloat()
                                    waveCount[bin]++
                                }
                                if (timeMs <= 40_000) {
                                    measurements.add(timeMs to if (loudness > 0.017) brightness else 0.0)
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputFinished = true
                }
            }
            for (i in magnitudes.indices) {
                if (waveCount[i] > 0) magnitudes[i] /= waveCount[i].toFloat()
            }
            val peak = magnitudes.maxOrNull()?.coerceAtLeast(0.0001f) ?: 0.0001f
            for (i in magnitudes.indices) magnitudes[i] = (magnitudes[i] / peak).coerceIn(0f, 1f)
            return Analysis(estimate(measurements), magnitudes)
        } catch (_: Exception) {
            return Analysis(0L, FloatArray(BINS))
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun estimate(segments: List<Pair<Long, Double>>): Long {
        if (segments.size < 30) return 0L
        val base = segments.filter { it.first in 700..3500 }
            .map { it.second }.sorted()
        if (base.isEmpty()) return 0L
        val baseline = base[base.size / 2].coerceAtLeast(0.0005)
        var first = 0L
        var count = 0
        for ((ms, value) in segments) {
            if (ms < 3500 || ms > 37_000) continue
            if (value > baseline * 2.2 && value > 0.012) {
                if (count == 0) first = ms
                count++
                if (count >= 12 && ms - first >= 300) return first.coerceIn(0L, 35_000L)
            } else count = 0
        }
        return 0L
    }
}
