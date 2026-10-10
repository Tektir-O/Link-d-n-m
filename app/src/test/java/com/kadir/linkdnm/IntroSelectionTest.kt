package com.kadir.linkdnm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroSelectionTest {
    @Test fun avoidsCuttingTheFirstWord() {
        assertEquals(3200L, IntroSelection.proposal(5000L, 180000L))
        assertEquals(11200L, IntroSelection.proposal(13000L, 180000L))
    }

    @Test fun firstThreeSecondsAreNeverAutomaticallyCut() {
        assertNull(IntroSelection.proposal(1200L, 60000L))
        assertNull(IntroSelection.proposal(0L, 60000L))
        assertNull(IntroSelection.proposal(40000L, 60000L))
    }

    @Test fun noSongIsSkippedByDefault() {
        val originalStart = 0L
        val optionalSuggestion = IntroSelection.proposal(21000L, 120000L)
        assertEquals(0L, originalStart)
        assertEquals(19200L, optionalSuggestion)
    }

    @Test fun halfSecondManualAdjustmentWorks() {
        val duration=90000L
        assertEquals(500L, IntroSelection.offset(0,500,duration))
        assertEquals(1000L, IntroSelection.offset(1500,-500,duration))
        assertEquals(0L, IntroSelection.offset(0,-2000,duration))
        assertEquals(88500L, IntroSelection.offset(90000,2000,duration))
    }

    @Test fun clampsToSafeAudioEndAndSliderLimit() {
        assertEquals(48500L, IntroSelection.maxStart(50000L))
        assertEquals(90000L, IntroSelection.maxStart(500000L))
        assertEquals(0L, IntroSelection.maxStart(1000L))
        assertEquals(0L, IntroSelection.clamp(-300,50000L))
        assertEquals(48500L, IntroSelection.clamp(65000,50000L))
    }

    @Test fun previewAndExportUseIdenticalSelectedMillis() {
        val selected = IntroSelection.clamp(12500L,180000L)
        val previewMillis = IntroSelection.clamp(selected,180000L)
        val exportedMillis = VideoPlan.introMs(selected,180000L)
        assertEquals(12500L,previewMillis)
        assertEquals(previewMillis,exportedMillis)
    }

    @Test fun labelsAreReadable() {
        assertEquals("00:00.0", IntroSelection.label(0))
        assertEquals("00:01.5", IntroSelection.label(1500))
        assertEquals("01:02.5", IntroSelection.label(62500))
        assertTrue(IntroSelection.label(90000).startsWith("01:30"))
    }
}
