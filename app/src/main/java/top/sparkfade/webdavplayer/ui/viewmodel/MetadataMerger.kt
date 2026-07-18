package top.sparkfade.webdavplayer.ui.viewmodel

import android.util.Log
import java.net.URLDecoder
import kotlinx.coroutines.flow.StateFlow
import top.sparkfade.webdavplayer.data.model.Song
import top.sparkfade.webdavplayer.data.repository.CoverArtStore
import top.sparkfade.webdavplayer.data.repository.MusicRepository

/**
 * 播放期元数据合并策略：
 * - 丢弃占位值（Unknown / 文件夹名推断的专辑名）
 * - 专辑从占位升级为真实值时，同步更新同文件夹的兄弟歌曲并广播重命名
 * - 封面图落盘并复用
 */
class MetadataMerger(
    private val repository: MusicRepository,
    private val coverArtStore: CoverArtStore,
    private val allSongs: StateFlow<List<Song>>,
    private val onAlbumRenamed: (oldName: String, newName: String) -> Unit,
    private val onStorageChanged: () -> Unit
) {
    /**
     * 合并元数据并持久化。
     * @return 有变更时返回最终 Song，无变更返回 null
     */
    suspend fun merge(
        targetSong: Song,
        realTitle: String?,
        realArtist: String?,
        realAlbum: String?,
        artworkData: ByteArray?
    ): Song? {
        var updatedSong = targetSong
        var dataChanged = false

        val safeTitle = realTitle?.trim()
        val safeArtist = realArtist?.trim()
        val safeAlbum = realAlbum?.trim()

        if (isMeaningful(safeTitle) && safeTitle != targetSong.title) {
            updatedSong = updatedSong.copy(title = safeTitle!!)
            dataChanged = true
        }

        if (isMeaningful(safeArtist) && safeArtist != targetSong.artist) {
            updatedSong = updatedSong.copy(artist = safeArtist!!)
            dataChanged = true
        }

        val currentIsPlaceholder = isPlaceholderAlbum(targetSong, targetSong.album)
        val newIsPlaceholder = safeAlbum.isNullOrEmpty() || isPlaceholderAlbum(targetSong, safeAlbum)

        if ((!newIsPlaceholder || currentIsPlaceholder) &&
                !safeAlbum.isNullOrEmpty() &&
                safeAlbum != targetSong.album
        ) {
            updatedSong = updatedSong.copy(album = safeAlbum)
            dataChanged = true

            if (!newIsPlaceholder && currentIsPlaceholder) {
                propagateAlbumToSiblings(targetSong, safeAlbum)
            }
        }

        if (!dataChanged && artworkData == null && targetSong.artworkPath != null) {
            return null
        }

        return try {
            var finalArtworkPath = updatedSong.artworkPath
            var artChanged = false

            val hasValidAlbum = !isPlaceholderAlbum(updatedSong, updatedSong.album)
            val albumCoverFile =
                if (hasValidAlbum) coverArtStore.coverFileForAlbum(updatedSong.album) else null

            if (artworkData != null) {
                val saved = coverArtStore.saveArtwork(
                    artworkData,
                    albumCoverFile,
                    coverArtStore.coverFileForFolder(targetSong.remotePath)
                )
                if (saved != null) {
                    finalArtworkPath = saved
                    artChanged = true
                }
            } else if (finalArtworkPath == null) {
                val existing = coverArtStore.findExistingCover(targetSong, albumCoverFile)
                if (existing != null) {
                    finalArtworkPath = existing
                    artChanged = true
                }
            }

            if (!dataChanged && !artChanged) return null

            val finalSong = updatedSong.copy(
                artworkPath = finalArtworkPath,
                isMetadataVerified = true
            )
            repository.updateSong(finalSong)
            if (artChanged) onStorageChanged()
            finalSong
        } catch (e: Exception) {
            Log.w(TAG, "Failed to merge metadata for ${targetSong.displayName}", e)
            null
        }
    }

    private suspend fun propagateAlbumToSiblings(targetSong: Song, newAlbum: String) {
        val folderPath = folderPathOf(targetSong.remotePath)
        val oldAlbum = targetSong.album
        val siblings = allSongs.value.filter {
            folderPathOf(it.remotePath) == folderPath &&
                it.album == oldAlbum &&
                it.id != targetSong.id
        }
        if (siblings.isNotEmpty()) {
            siblings.forEach { repository.updateSong(it.copy(album = newAlbum)) }
            onAlbumRenamed(oldAlbum, newAlbum)
        }
    }

    private fun isMeaningful(s: String?): Boolean {
        return !s.isNullOrEmpty() && s != "Unknown" && s != "Unknown Album"
    }

    private fun isPlaceholderAlbum(song: Song, album: String): Boolean {
        val folderName = folderPathOf(song.remotePath).substringAfterLast('/')
        return album == "Unknown" ||
            album == "Unknown Album" ||
            album.equals("dav", true) ||
            album.equals("webdav", true) ||
            album == folderName
    }

    private fun folderPathOf(remotePath: String): String {
        return try {
            URLDecoder.decode(remotePath.substringBeforeLast('/'), "UTF-8")
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private const val TAG = "MetadataMerger"
    }
}
