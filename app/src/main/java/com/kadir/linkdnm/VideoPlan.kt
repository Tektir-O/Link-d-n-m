package com.kadir.linkdnm

object VideoPlan {
    val minutes = listOf(1, 2, 3, 4)
    const val WIDTH = 720
    const val HEIGHT = 1280
    fun durationMs(minutes: Int): Long {
        require(minutes in this.minutes) { "Video süresi 1-4 dakika olmalı." }
        return minutes * 60_000L
    }
    fun introMs(requested: Long, audioDuration: Long): Long =
        if (audioDuration <= 1500) 0L else requested.coerceIn(0L, audioDuration - 1500L)
}
