package top.sparkfade.webdavplayer.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

            val response = client.newCall(request).execute()
            if (!response.isSuccessful || response.body == null) {
                emit(DownloadStatus.Error)
                return@flow
            }

            val body = response.body!!
            val contentLength = body.contentLength()
            val inputStream = body.byteStream()

            val dir = File(context.getExternalFilesDir("music_downloads"), "")
            if (!dir.exists()) dir.mkdirs()
            
            // 使用临时文件，避免下载一半被误读
            val file = File(dir, "${song.id}_${song.displayName.replace("/", "_")}")
            val tempFile = File(dir, "${file.name}.tmp")
            
            val outputStream = FileOutputStream(tempFile)
            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            var totalBytesRead = 0L
            
            // 进度发射限流 (避免卡死 UI)
            var lastEmitTime = 0L

            try {
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    
                    if (contentLength > 0) {
                        val currentTime = System.currentTimeMillis()
                        // 每 100ms 更新一次进度
                        if (currentTime - lastEmitTime > 100) {
                            val progress = totalBytesRead.toFloat() / contentLength.toFloat()
                            emit(DownloadStatus.Progress(progress))
                            lastEmitTime = currentTime
                        }
                    }
                }
                
                outputStream.flush()
                // 成功后重命名
                if (file.exists()) file.delete()
                tempFile.renameTo(file)
                
                // 更新数据库路径
                val updatedSong = song.copy(localPath = file.absolutePath)
                songDao.update(updatedSong)
                
                emit(DownloadStatus.Success)

            } catch (e: Exception) {
                tempFile.delete() // 失败删除临时文件
                e.printStackTrace()
                emit(DownloadStatus.Error)
            } finally {
                outputStream.close()
                inputStream.close()
                response.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emit(DownloadStatus.Error)
        }
    }.flowOn(Dispatchers.IO)
}