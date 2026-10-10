package com.kadir.linkdnm

import java.net.URI

/** Accept only known YouTube single-video URLs; canonicalize before sending to server. */
object YoutubeLink {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
    private val shortHosts = setOf("youtu.be", "www.youtu.be")

    fun canonical(raw: String): String {
        require(raw.length <= 1024) { "Bağlantı çok uzun." }
        val uri = try { URI(raw.trim()) }
                  catch (_: Exception) { throw IllegalArgumentException("Geçersiz bağlantı.") }
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null
        ) { "HTTPS YouTube bağlantısı gerekli." }
        val host = uri.host?.lowercase() ?: ""
        val segments = uri.path.trim('/').split('/')
        val id = when {
            host in youtubeHosts && uri.path == "/watch" -> {
                val candidates = uri.rawQuery?.split('&')?.filter { it.startsWith("v=") }
                    ?: emptyList()
                if (candidates.size == 1) candidates.first().removePrefix("v=") else ""
            }
            host in youtubeHosts && segments.size == 2 &&
                segments[0] in setOf("shorts", "live", "embed") -> segments[1]
            host in shortHosts && segments.size == 1 -> segments[0]
            else -> ""
        }
        require(videoId.matches(id)) { "Geçerli bir YouTube video bağlantısı girin." }
        return "https://www.youtube.com/watch?v=" + id
    }
}
