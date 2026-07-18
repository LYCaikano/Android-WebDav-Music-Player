package top.sparkfade.webdavplayer.data.remote

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import top.sparkfade.webdavplayer.di.NetworkModule
import top.sparkfade.webdavplayer.utils.Constants

/**
 * WebDAV 网络数据源：PROPFIND 目录爬取、连接测试、音频元数据 Range 嗅探。
 * 不涉及任何数据库操作。
 */
@Singleton
class WebDavDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @NetworkModule.SafeClient private val safeClient: OkHttpClient,
    @NetworkModule.UnsafeClient private val unsafeClient: OkHttpClient
) {
    private val parser = WebDavXmlParser()

    data class ItemTask(
        val fullUrl: String,
        val displayName: String,
        val size: Long,
        val contentType: String
    )

    data class CrawlResult(
        val tasks: List<ItemTask>,
        /** 爬取失败（网络错误或非 2xx）的目录，供调用方决定是否跳过删除阶段 */
        val failedFolders: Set<String>
    )

    data class SongMetadata(val title: String, val artist: String, val album: String)

    /** 连接测试的细分结果 */
    sealed interface ConnectionResult {
        data object Success : ConnectionResult

        /** 用户名或密码错误（HTTP 401/403） */
        data object AuthFailed : ConnectionResult

        /** 地址无法连接（DNS 解析失败、连接拒绝、超时等） */
        data object Unreachable : ConnectionResult

        /** SSL 证书校验失败 */
        data object SslError : ConnectionResult

        /** 其他失败：HTTP 状态码，非 HTTP 异常时 code 为 -1 */
        data class OtherError(val code: Int) : ConnectionResult
    }

    fun clientFor(skipSsl: Boolean): OkHttpClient = if (skipSsl) unsafeClient else safeClient

    suspend fun testConnection(
        url: String,
        user: String,
        pass: String,
        skipSsl: Boolean
    ): ConnectionResult = withContext(Dispatchers.IO) {
        try {
            val auth = okhttp3.Credentials.basic(user, pass)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", auth)
                .header("User-Agent", Constants.USER_AGENT)
                .header("Depth", "0")
                .method("PROPFIND", null)
                .build()
            clientFor(skipSsl).newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> ConnectionResult.Success
                    response.code == 401 || response.code == 403 -> ConnectionResult.AuthFailed
                    else -> ConnectionResult.OtherError(response.code)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Test connection failed", e)
            classifyConnectionError(e)
        }
    }

    private fun classifyConnectionError(e: Throwable): ConnectionResult {
        var cause: Throwable? = e
        while (cause != null) {
            when (cause) {
                is javax.net.ssl.SSLHandshakeException,
                is javax.net.ssl.SSLPeerUnverifiedException,
                is java.security.cert.CertificateException ->
                    return ConnectionResult.SslError
                is java.net.UnknownHostException,
                is java.net.ConnectException,
                is java.net.SocketTimeoutException,
                is java.net.NoRouteToHostException ->
                    return ConnectionResult.Unreachable
            }
            cause = cause.cause
        }
        return ConnectionResult.OtherError(-1)
    }

    suspend fun crawl(baseUrl: String, auth: String, maxDepth: Int, skipSsl: Boolean): CrawlResult =
        withContext(Dispatchers.IO) {
            val visitedUrls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
            val failedFolders = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
            val pendingTasks = mutableListOf<ItemTask>()
            crawlFolders(
                clientFor(skipSsl),
                baseUrl,
                auth,
                0,
                maxDepth,
                visitedUrls,
                failedFolders,
                pendingTasks
            )
            CrawlResult(pendingTasks, failedFolders)
        }

    private fun crawlFolders(
        client: OkHttpClient,
        currentUrl: String,
        auth: String,
        depth: Int,
        maxDepth: Int,
        visitedUrls: MutableSet<String>,
        failedFolders: MutableSet<String>,
        results: MutableList<ItemTask>
    ) {
        if (depth > maxDepth) return
        val normalizedUrl = currentUrl.trimEnd('/')
        if (!visitedUrls.add(normalizedUrl)) return

        val baseOrigin = originOf(currentUrl)
        val request = Request.Builder()
            .url(currentUrl)
            .header("Authorization", auth)
            .header("User-Agent", Constants.USER_AGENT)
            .header("Depth", "1")
            .method("PROPFIND", null)
            .build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                failedFolders.add(normalizedUrl)
                return
            }
            val bodyStream = response.body?.byteStream()
            if (bodyStream != null) {
                val resources = parser.parse(bodyStream)
                for (res in resources) {
                    val rawHref = res.href
                    val fullUrl = resolveUrl(currentUrl, rawHref)
                    val normChild = fullUrl.trimEnd('/')
                    if (normChild == normalizedUrl) continue
                    if (visitedUrls.contains(normChild)) continue

                    // 安全：仅爬取与当前目录同源的 URL，
                    // 防止恶意服务器通过伪造 href 窃取 Authorization 凭证
                    if (originOf(fullUrl) != baseOrigin) {
                        Log.w(TAG, "Skip cross-origin href: $fullUrl")
                        continue
                    }

                    if (res.isCollection) {
                        crawlFolders(
                            client,
                            fullUrl,
                            auth,
                            depth + 1,
                            maxDepth,
                            visitedUrls,
                            failedFolders,
                            results
                        )
                    } else {
                        val decodedHref = try {
                            URLDecoder.decode(rawHref, "UTF-8")
                        } catch (e: Exception) {
                            rawHref
                        }
                        val ext = decodedHref.substringAfterLast('.', "").lowercase()
                        if (Constants.SUPPORTED_EXTENSIONS.contains(ext)) {
                            results.add(
                                ItemTask(fullUrl, res.displayName, res.contentLength, res.contentType)
                            )
                            visitedUrls.add(normChild)
                        }
                    }
                }
            }
            response.close()
        } catch (e: Exception) {
            Log.e(TAG, "Crawl Error: $currentUrl", e)
            failedFolders.add(normalizedUrl)
        }
    }

    /** 通过 Range 请求下载文件头部并解析音频标签 */
    suspend fun sniffMetadata(
        url: String,
        auth: String,
        ext: String,
        limit: Long,
        skipSsl: Boolean
    ): SongMetadata = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", auth)
                .header("User-Agent", Constants.USER_AGENT)
                .header("Range", "bytes=0-${limit - 1}")
                .build()

            val client = clientFor(skipSsl)
            var response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                delay(1000)
                client.newCall(request).execute()
            }

            if (!response.isSuccessful) {
                response.close()
                return@withContext SongMetadata("Unknown", "Unknown", "Unknown")
            }

            tempFile = File.createTempFile("scan_", ".$ext", context.cacheDir)
            val body = response.body
            if (body == null) {
                response.close()
                return@withContext SongMetadata("Unknown", "Unknown", "Unknown")
            }
            val inputStream: InputStream = body.byteStream()
            val fileOutput = FileOutputStream(tempFile)
            try {
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var bytesRead: Int
                while (totalRead < limit) {
                    bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    fileOutput.write(buffer, 0, bytesRead)
                    totalRead += bytesRead.toLong()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sniff read interrupted: $url", e)
            } finally {
                fileOutput.close()
                inputStream.close()
                response.close()
            }

            val audioFile = AudioFileIO.read(tempFile)
            val tag = audioFile.tag
            val title = tag?.getFirst(FieldKey.TITLE) ?: "Unknown"
            val artist = tag?.getFirst(FieldKey.ARTIST) ?: "Unknown"
            val album = tag?.getFirst(FieldKey.ALBUM) ?: "Unknown"

            if (title != "Unknown" && title.isNotBlank()) {
                return@withContext SongMetadata(title, artist, album)
            }
            SongMetadata("Unknown", "Unknown", "Unknown")
        } catch (e: Exception) {
            SongMetadata("Unknown", "Unknown", "Unknown")
        } finally {
            tempFile?.delete()
        }
    }

    private fun originOf(url: String): String? {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            val port = if (uri.port != -1) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveUrl(baseUrl: String, href: String): String {
        if (href.startsWith("http")) return href
        try {
            val uri = URI(baseUrl)
            val hostRoot =
                "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
            return if (href.startsWith("/")) "$hostRoot$href"
            else if (baseUrl.endsWith("/")) "$baseUrl$href" else "$baseUrl/$href"
        } catch (e: Exception) {
            return if (baseUrl.endsWith("/")) "$baseUrl$href" else "$baseUrl/$href"
        }
    }

    companion object {
        private const val TAG = "WebDavDataSource"
    }
}
