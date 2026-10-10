package com.kadir.linkdnm

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlanTest {
    @Test fun supportedDurations() {
        assertEquals(60000L, VideoPlan.durationMs(1))
        assertEquals(120000L, VideoPlan.durationMs(2))
        assertEquals(180000L, VideoPlan.durationMs(3))
        assertEquals(240000L, VideoPlan.durationMs(4))
    }
    @Test fun customMinuteSecondDurations() {
        assertEquals(5_000L, VideoPlan.durationMs(0, 5))
        assertEquals(7_000L, VideoPlan.durationMs(0, 7))
        assertEquals(155_000L, VideoPlan.durationMs(2, 35))
        assertEquals(1_200_000L, VideoPlan.durationMs(20, 0))
    }
    @Test(expected = IllegalArgumentException::class)
    fun zeroSecondsRejected() { VideoPlan.durationMs(0, 0) }
    @Test(expected = IllegalArgumentException::class)
    fun fourSecondsRejected() { VideoPlan.durationMs(0, 4) }
    @Test(expected = IllegalArgumentException::class)
    fun durationAboveTwentyMinutesRejected() { VideoPlan.durationMs(20, 1) }
    @Test(expected = IllegalArgumentException::class)
    fun invalidSecondsRejected() { VideoPlan.durationMs(0, 60) }
    @Test(expected = IllegalArgumentException::class)
    fun invalidDurationRejected() { VideoPlan.durationMs(5) }
    @Test fun beginningIsClamped() {
        assertEquals(0L, VideoPlan.introMs(-5000L, 70000L))
        assertEquals(25000L, VideoPlan.introMs(25000L, 70000L))
        assertEquals(68500L, VideoPlan.introMs(75000L, 70000L))
        assertEquals(0L, VideoPlan.introMs(3000L, 0L))
    }
}
