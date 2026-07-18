package top.sparkfade.webdavplayer.data.repository

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import top.sparkfade.webdavplayer.data.local.SongDao
import top.sparkfade.webdavplayer.data.model.Song
import top.sparkfade.webdavplayer.di.NetworkModule
import top.sparkfade.webdavplayer.utils.Constants
import top.sparkfade.webdavplayer.utils.CurrentSession
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileDownloader @Inject constructor(
    private val songDao: SongDao,
    @NetworkModule.SafeClient private val safeClient: OkHttpClient,
    @NetworkModule.UnsafeClient private val unsafeClient: OkHttpClient
) {
    private val TAG = "FileDownloader"

    sealed class DownloadStatus {
        data class Progress(val progress: Float) : DownloadStatus()
        data object Success : DownloadStatus()
        data object Error : DownloadStatus()
    }

    fun downloadSongFlow(context: Context, song: Song, skipSsl: Boolean): Flow<DownloadStatus> = flow {
        val call = try {
            val client = if (skipSsl) unsafeClient else safeClient

            val downloadUrl = try {
                song.remotePath.toHttpUrlOrNull()?.toString() ?: song.remotePath
            } catch (e: Exception) {
                song.remotePath
            }

            val auth = CurrentSession.getAuthForUrl(downloadUrl) ?: run {
                emit(DownloadStatus.Error)
                return@flow
            }

            val request = Request.Builder()
                .url(downloadUrl)
                .header("Authorization", auth)
                .header("User-Agent", Constants.USER_AGENT)
                .build()
            client.newCall(request)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build download request", e)
            emit(DownloadStatus.Error)
            return@flow
        }

        try {
            call.execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    emit(DownloadStatus.Error)
                    return@flow
                }

                val contentLength = body.contentLength()
                val baseDir = context.getExternalFilesDir("music_downloads")
                if (baseDir == null) {
                    emit(DownloadStatus.Error)
                    return@flow
                }
                if (!baseDir.exists()) baseDir.mkdirs()

                // 下载前检查剩余存储空间（预留 50MB 余量）
                if (contentLength > 0) {
                    val available = StatFs(baseDir.absolutePath).availableBytes
                    if (available < contentLength + 50L * 1024 * 1024) {
                        Log.w(TAG, "Not enough storage: need $contentLength, have $available")
                        emit(DownloadStatus.Error)
                        return@flow
                    }
                }

                val safeName = song.displayName.replace("/", "_").replace("\\", "_")
                val file = File(baseDir, "${song.id}_$safeName")
                val tempFile = File(baseDir, "${file.name}.tmp")

                val buffer = ByteArray(8 * 1024)
                var totalBytesRead = 0L
                var lastEmitTime = 0L

                try {
                    body.byteStream().use { inputStream ->
                        FileOutputStream(tempFile).use { outputStream ->
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                currentCoroutineContext().ensureActive()
                                outputStream.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead

                                if (contentLength > 0) {
                                    val currentTime = System.currentTimeMillis()
                                    if (currentTime - lastEmitTime > 100) {
                                        val progress =
                                                totalBytesRead.toFloat() / contentLength.toFloat()
                                        emit(DownloadStatus.Progress(progress))
                                        lastEmitTime = currentTime
                                    }
                                }
                            }
                            outputStream.flush()
                        }
                    }

                    if (contentLength > 0) {
                        emit(DownloadStatus.Progress(1f))
                    }

                    if (file.exists() && !file.delete()) {
                        tempFile.delete()
                        emit(DownloadStatus.Error)
                        return@flow
                    }
                    if (!tempFile.renameTo(file)) {
                        tempFile.delete()
                        emit(DownloadStatus.Error)
                        return@flow
                    }

                    // 仅更新 localPath 字段，避免覆盖下载期间的其他元数据变更
                    songDao.updateLocalPath(song.id, file.absolutePath)
                    emit(DownloadStatus.Success)
                } catch (e: Exception) {
                    tempFile.delete()
                    Log.e(TAG, "Download failed: ${song.displayName}", e)
                    emit(DownloadStatus.Error)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download request failed: ${song.displayName}", e)
            emit(DownloadStatus.Error)
        } finally {
            // 协程取消时中断底层阻塞读取
            if (!call.isCanceled()) call.cancel()
        }
    }.flowOn(Dispatchers.IO)
}
