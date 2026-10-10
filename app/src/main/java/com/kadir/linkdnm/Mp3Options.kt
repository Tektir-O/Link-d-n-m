package com.kadir.linkdnm

/** MP3 bitrates are encoder targets, never claims about source audio quality. */
object Mp3Options {
    val bitrates = listOf(128, 192, 320)
    const val DEFAULT_BITRATE = 192

    fun validBitrate(value: Int): Boolean = value in bitrates

    fun outputName(videoId: String, bitrate: Int, timestamp: Long): String {
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoId)) { "Geçersiz video kimliği" }
        require(validBitrate(bitrate)) { "Geçersiz bitrate" }
        return "youtube-${videoId}-${bitrate}k-${timestamp}.mp3"
    }
}
