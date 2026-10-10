package com.kadir.linkdnm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp3FrameCutterTest {
    private fun sampleFrames(count: Int): ByteArray {
        val frame = ByteArray(417)
        frame[0] = 0xFF.toByte()
        frame[1] = 0xFB.toByte()
        frame[2] = 0x90.toByte()
        frame[3] = 0x00
        return ByteArray(frame.size * count).also { output ->
            repeat(count) { i -> frame.copyInto(output, i * frame.size) }
        }
    }

    @Test fun fullRangePreservesFrames() {
        val source = sampleFrames(10)
        val result = Mp3FrameCutter.cut(source, listOf(0L to 1000L))
        assertEquals(source.size, result.size)
    }

    @Test fun rangeOutsideDurationProducesEmptyResult() {
        val result = Mp3FrameCutter.cut(sampleFrames(10), listOf(5000L to 6000L))
        assertTrue(result.isEmpty())
    }

    @Test fun id3HeaderIsSkipped() {
        val header = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 0)
        val frames = sampleFrames(4)
        val source = header + frames
        val result = Mp3FrameCutter.cut(source, listOf(0L to 1000L))
        assertEquals(frames.size, result.size)
    }

    @Test fun disjointRangesExcludeMiddleFrames() {
        val source = sampleFrames(20)
        val result = Mp3FrameCutter.cut(source, listOf(0L to 30L, 300L to 400L))
        assertTrue(result.isNotEmpty())
        assertTrue(result.size < source.size)
    }

    @Test fun partialRangeReturnsFewerFrames() {
        val source = sampleFrames(20)
        val result = Mp3FrameCutter.cut(source, listOf(0L to 50L))
        assertTrue(result.isNotEmpty())
        assertTrue(result.size < source.size)
        assertEquals(0, result.size % 417)
    }
}
