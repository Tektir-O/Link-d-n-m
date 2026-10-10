package com.kadir.linkdnm

import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeLinkTest {
    private val canonical = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    @Test fun acceptsWatchAndShortLinks() {
        assertEquals(canonical, YoutubeLink.canonical(canonical))
        assertEquals(canonical, YoutubeLink.canonical("https://youtu.be/dQw4w9WgXcQ?t=30"))
        assertEquals(canonical, YoutubeLink.canonical("https://m.youtube.com/shorts/dQw4w9WgXcQ"))
    }

    @Test fun rejectsOtherSitesAndInvalidVideoIds() {
        val rejected = listOf(
            "https://youtube.com.evil.org/watch?v=dQw4w9WgXcQ",
            "http://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/playlist?list=PL123",
            "https://www.youtube.com/watch?v=invalid",
            "https://youtube.com@evil.org/watch?v=dQw4w9WgXcQ"
        )
        for (link in rejected) {
            try {
                YoutubeLink.canonical(link)
                throw AssertionError("Accepted invalid URL: " + link)
            } catch (_: IllegalArgumentException) { }
        }
    }
}
