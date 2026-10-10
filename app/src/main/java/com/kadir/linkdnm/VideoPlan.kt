package com.kadir.linkdnm

object VideoPlan {
    val minutes = listOf(1, 2, 3, 4)
    const val WIDTH = 720
    const val HEIGHT = 1280
    fun durationMs(minutes: Int): Long {
        require(minutes in this.minutes) { "Video süresi 1-4 dakika olmalı." }
        return minutes * 60_000L
    }
    const val MIN_VIDEO_MS = 5_000L
    const val MAX_VIDEO_MS = 20 * 60_000L

    fun durationMs(minutes: Int, seconds: Int): Long {
        require(minutes in 0..20 && seconds in 0..59) {
            "Dakika 0-20, saniye 0-59 arasında olmalı."
        }
        return validateDurationMs(minutes * 60_000L + seconds * 1000L)
    }

    fun validateDurationMs(duration: Long): Long {
        require(duration in MIN_VIDEO_MS..MAX_VIDEO_MS) {
            "Video 5 saniye ile 20 dakika arasında olmalı."
        }
        return duration
    }

    fun introMs(requested: Long, audioDuration: Long): Long =
        if (audioDuration <= 1500) 0L else requested.coerceIn(0L, audioDuration - 1500L)
}
