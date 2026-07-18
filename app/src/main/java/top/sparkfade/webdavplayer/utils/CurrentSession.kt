package top.sparkfade.webdavplayer.utils

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程级凭证注册表：为 OkHttp 拦截器提供按 URL 前缀匹配的 Basic Auth，
 * 并记录各账号的 skipSsl 配置供播放器按主机动态选择 TLS 校验策略。
 */
object CurrentSession {
    private val authMap = ConcurrentHashMap<String, String>()
    private val skipSslHosts = ConcurrentHashMap.newKeySet<String>()

    fun updateAuth(url: String, auth: String, skipSsl: Boolean = false) {
        try {
            val normalized = normalizeBaseUrl(url) ?: return
            authMap[normalized] = auth
            val hostKey = hostKeyOf(url)
            if (hostKey != null) {
                if (skipSsl) skipSslHosts.add(hostKey) else skipSslHosts.remove(hostKey)
            }
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

    fun isSkipSslHost(host: String): Boolean {
        return skipSslHosts.contains(host.lowercase())
    }

    fun clear() {
        authMap.clear()
        skipSslHosts.clear()
    }

    private fun hostKeyOf(url: String): String? {
        return try {
            URI(url).host?.lowercase()
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeBaseUrl(url: String): String? {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val path = (uri.path ?: "").trimEnd('/')
        return "$scheme://$host$port$path"
    }
}
