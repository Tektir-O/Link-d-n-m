package com.kadir.linkdnm

import org.junit.Assert.*
import org.junit.Test

class Mp3OptionsTest {
    @Test fun formatsSafeUniqueFileName() {
        assertEquals("youtube-dQw4w9WgXcQ-192k-123456.mp3",
            Mp3Options.outputName("dQw4w9WgXcQ", 192, 123456))
    }

    @Test fun invalidBitratesAndVideoIdsRejected() {
        for (rate in listOf(0, 96, 256, 999)) {
            try {
                Mp3Options.outputName("dQw4w9WgXcQ", rate, 1)
                fail("Invalid bitrate accepted")
            } catch (_: IllegalArgumentException) { }
        }
        try {
            Mp3Options.outputName("../../bad", 192, 1)
            fail("Invalid video id accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
