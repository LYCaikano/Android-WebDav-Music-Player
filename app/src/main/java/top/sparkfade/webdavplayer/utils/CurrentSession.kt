package top.sparkfade.webdavplayer.utils

import java.net.URI

object CurrentSession {
    private val authMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun updateAuth(url: String, auth: String) {
        try {
            normalizeBaseUrl(url)?.let { authMap[it] = auth }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getAuthForUrl(url: String): String? {
        return try {
            val target = normalizeBaseUrl(url) ?: return null
            authMap.entries
                .filter { (baseUrl, _) -> target == baseUrl || target.startsWith("$baseUrl/") }
                .maxByOrNull { it.key.length }
                ?.value
        } catch (e: Exception) {
            null
        }
    }

    fun clear() = authMap.clear()

    private fun normalizeBaseUrl(url: String): String? {
        val uri = URI(url)
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val path = (uri.path ?: "").trimEnd('/')
        return "$scheme://$host$port$path"
    }
}
