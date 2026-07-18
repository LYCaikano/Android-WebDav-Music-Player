package top.sparkfade.webdavplayer.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton
import top.sparkfade.webdavplayer.data.model.Song

/**
 * 封面图文件存取：保存播放器提取的 artwork、按专辑/文件夹维度复用封面、
 * 账号删除时清理关联封面。
 */
@Singleton
class CoverArtStore @Inject constructor(@ApplicationContext context: Context) {

    private val coversDir = File(context.cacheDir, "covers")

    fun isImageValid(file: File): Boolean {
        return try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0
        } catch (e: Exception) {
            false
        }
    }

    fun coverFileForFolder(remotePath: String): File {
        val folderPathHash = try {
            val path = remotePath.substringBeforeLast('/')
            URLDecoder.decode(path, "UTF-8").hashCode()
        } catch (e: Exception) {
            0
        }
        return File(ensureDir(), "dir_$folderPathHash.jpg")
    }

    fun coverFileForAlbum(album: String): File {
        return File(ensureDir(), "alb_${album.hashCode()}.jpg")
    }

    /**
     * 保存 artwork 字节到专辑封面（优先）或文件夹封面，成功返回绝对路径。
     */
    fun saveArtwork(data: ByteArray, albumCover: File?, folderCover: File): String? {
        ensureDir()
        val tempFile = File(coversDir, "temp_${System.currentTimeMillis()}.tmp")
        return try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(data)
                fos.flush()
                fos.fd.sync()
            }
            if (!isImageValid(tempFile)) {
                tempFile.delete()
                return null
            }
            val target = albumCover ?: folderCover
            if (tempFile.renameTo(target)) target.absolutePath else null
        } catch (e: Exception) {
            tempFile.delete()
            Log.w(TAG, "Failed to save artwork", e)
            null
        }
    }

    /** 复用已有封面：优先专辑封面，其次文件夹封面 */
    fun findExistingCover(song: Song, albumCover: File?): String? {
        if (albumCover != null && albumCover.exists() && isImageValid(albumCover)) {
            return albumCover.absolutePath
        }
        val folderCover = coverFileForFolder(song.remotePath)
        if (folderCover.exists() && isImageValid(folderCover)) {
            return folderCover.absolutePath
        }
        return null
    }

    /** 删除某首歌曲可能关联的所有封面文件 */
    fun deleteCoversFor(song: Song) {
        try {
            if (!coversDir.exists()) return
            File(coversDir, "song_${song.id}.jpg").delete()
            if (song.album != "Unknown" && song.album != "Unknown Album") {
                coverFileForAlbum(song.album).delete()
            }
            coverFileForFolder(song.remotePath).delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete covers for song ${song.id}", e)
        }
    }

    private fun ensureDir(): File {
        if (!coversDir.exists()) coversDir.mkdirs()
        return coversDir
    }

    companion object {
        private const val TAG = "CoverArtStore"
    }
}
