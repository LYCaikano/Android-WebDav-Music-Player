package top.sparkfade.webdavplayer.utils

import java.net.URI

object CurrentSession {
    private val authMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun updateAuth(url: String, auth: String) {
        try {
            val host = URI(url).host
            if (host != null) authMap[host] = auth
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getAuthForUrl(url: String): String? {
        return try {
            val host = URI(url).host
            authMap[host]
        } catch (e: Exception) { null }
    }

    fun clear() = authMap.clear()
}