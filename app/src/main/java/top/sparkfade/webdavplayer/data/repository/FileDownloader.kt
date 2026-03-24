package top.sparkfade.webdavplayer.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import top.sparkfade.webdavplayer.data.local.SongDao
import top.sparkfade.webdavplayer.data.model.Song
import top.sparkfade.webdavplayer.di.NetworkModule
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
    // 定义下载状态
    sealed class DownloadStatus {
        data class Progress(val progress: Float) : DownloadStatus()
        data object Success : DownloadStatus()
        data object Error : DownloadStatus()
    }

    fun downloadSongFlow(context: Context, song: Song, skipSsl: Boolean): Flow<DownloadStatus> = flow {
        try {
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
                .header("User-Agent", "WebDavPlayer/1.0")
                .build()

            client.newCall(request).execute().use { response ->
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

                val dir = File(baseDir, "")
                if (!dir.exists()) dir.mkdirs()

                // 使用临时文件，避免下载一半被误读
                val safeName = song.displayName.replace("/", "_").replace("\\", "_")
                val file = File(dir, "${song.id}_$safeName")
                val tempFile = File(dir, "${file.name}.tmp")

                val buffer = ByteArray(8 * 1024)
                var totalBytesRead = 0L
                var lastEmitTime = 0L

                try {
                    body.byteStream().use { inputStream ->
                        FileOutputStream(tempFile).use { outputStream ->
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
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

                    val updatedSong = song.copy(localPath = file.absolutePath)
                    songDao.update(updatedSong)
                    emit(DownloadStatus.Success)
                } catch (e: Exception) {
                    tempFile.delete()
                    e.printStackTrace()
                    emit(DownloadStatus.Error)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emit(DownloadStatus.Error)
        }
    }.flowOn(Dispatchers.IO)
}
