package top.sparkfade.webdavplayer.data.repository

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CacheRepository @Inject constructor(
    private val cache: Cache // ExoPlayer 的音频缓存
) {
    // 1. 清理 ExoPlayer 音频缓冲 (全部)
    @OptIn(UnstableApi::class)
    fun clearAudioCache() {
        val keys = cache.keys
        for (key in keys) {
            try {
                cache.removeResource(key)
            } catch (e: Exception) {
                android.util.Log.w("CacheRepository", "Failed to remove cached resource: $key", e)
            }
        }
    }

    // 按 Key (Song ID) 移除特定歌曲的缓存；
    // 缓存被播放器锁定时移除会抛异常，此处安全降级
    @OptIn(UnstableApi::class)
    fun removeResource(key: String) {
        try {
            cache.removeResource(key)
        } catch (e: Exception) {
            android.util.Log.w("CacheRepository", "Failed to remove cached resource: $key", e)
        }
    }

    // 2. 清理下载的歌曲 (显式下载)
    fun clearDownloads(downloadDir: File?) {
        downloadDir?.deleteRecursively()
        downloadDir?.mkdirs()
    }

    // 3. 清理封面图片缓存
    fun clearCoverCache(cacheDir: File) {
        val coversDir = File(cacheDir, "covers")
        if (coversDir.exists()) {
            coversDir.deleteRecursively()
            coversDir.mkdirs() // 重建空文件夹
        }
    }

    fun getCacheSize(): Long {
        return cache.cacheSpace
    }
    
    fun getCoverCacheSize(cacheDir: File): Long {
        val coversDir = File(cacheDir, "covers")
        return if (coversDir.exists()) {
            coversDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } else {
            0L
        }
    }
}