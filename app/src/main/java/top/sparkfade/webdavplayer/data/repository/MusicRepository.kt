package top.sparkfade.webdavplayer.data.repository

import android.util.Log
import androidx.room.withTransaction
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.sparkfade.webdavplayer.data.local.AppDatabase
import top.sparkfade.webdavplayer.data.local.PlaylistDao
import top.sparkfade.webdavplayer.data.local.SongDao
import top.sparkfade.webdavplayer.data.local.WebDavAccountDao
import top.sparkfade.webdavplayer.data.model.Playlist
import top.sparkfade.webdavplayer.data.model.PlaylistSongCrossRef
import top.sparkfade.webdavplayer.data.model.Song
import top.sparkfade.webdavplayer.data.model.WebDavAccount
import top.sparkfade.webdavplayer.data.remote.WebDavDataSource
import top.sparkfade.webdavplayer.security.CredentialCipher
import top.sparkfade.webdavplayer.utils.Constants
import top.sparkfade.webdavplayer.utils.CurrentSession

/**
 * 音乐仓库：账号、歌曲、歌单的数据库操作与同步编排。
 * 网络细节（PROPFIND 爬取、元数据嗅探）委托给 [WebDavDataSource]。
 */
@Singleton
class MusicRepository
@Inject
constructor(
        private val db: AppDatabase,
        private val songDao: SongDao,
        private val accountDao: WebDavAccountDao,
        private val playlistDao: PlaylistDao,
        private val webDavDataSource: WebDavDataSource,
        private val credentialCipher: CredentialCipher
) {
    private val TAG = "WebDavPlayer"
    private val DELETE_BATCH_SIZE = 400

    val allSongs: Flow<List<Song>> = songDao.getAllSongs()
    val allAccounts: Flow<List<WebDavAccount>> =
            accountDao.getAllAccounts().map { accounts -> accounts.map(::decryptAccount) }
    val allPlaylists: Flow<List<Playlist>> = playlistDao.getAllPlaylists()

    sealed class SyncState {
        data object Idle : SyncState()
        data object Loading : SyncState()
        data class Progress(val count: Int) : SyncState()
        data class Success(val count: Int) : SyncState()
        data class Error(val message: String) : SyncState()
    }

    // --- 账号与歌曲管理 ---
    suspend fun addAccount(account: WebDavAccount): Long = accountDao.insert(encryptAccount(account))
    suspend fun getAllAccountsList(): List<WebDavAccount> =
            accountDao.getAllAccountsList().map(::decryptAccount)
    suspend fun updateAccount(account: WebDavAccount) = accountDao.update(encryptAccount(account))
    suspend fun updateSong(song: Song) = songDao.update(song)
    suspend fun getAccountById(id: Long): WebDavAccount? =
            accountDao.getAccountById(id)?.let(::decryptAccount)

    suspend fun deleteAccount(account: WebDavAccount) {
        db.withTransaction {
            songDao.clearByAccountId(account.id)
            accountDao.delete(account)
        }
    }

    suspend fun getSongsByAccountId(accountId: Long): List<Song> =
            songDao.getSongsByAccountId(accountId)
    suspend fun getSongById(id: Long): Song? = songDao.getSongById(id)
    suspend fun clearLocalPaths() = songDao.clearAllLocalPaths()
    suspend fun clearArtworkPaths() = songDao.clearAllArtworkPaths()

    private fun encryptAccount(account: WebDavAccount): WebDavAccount =
            account.copy(password = credentialCipher.encrypt(account.password))

    private fun decryptAccount(account: WebDavAccount): WebDavAccount {
        val decrypted = credentialCipher.decrypt(account.password)
        if (decrypted == null) {
            Log.w(TAG, "Credential for account ${account.id} is undecryptable, re-login required")
            return account.copy(password = "")
        }
        return account.copy(password = decrypted)
    }

    // --- 歌单管理 ---
    suspend fun initDefaultPlaylists() {
        playlistDao.insertPlaylist(
                Playlist(id = Constants.PLAYLIST_ID_FAVORITES, name = "Favorites", isSystem = true)
        )
        playlistDao.insertPlaylist(
                Playlist(id = Constants.PLAYLIST_ID_DOWNLOADS, name = "Downloads", isSystem = true)
        )
        playlistDao.insertPlaylist(
                Playlist(id = Constants.PLAYLIST_ID_QUEUE, name = "Queue", isSystem = true)
        )
    }

    suspend fun createPlaylist(name: String) {
        playlistDao.insertPlaylist(Playlist(name = name))
    }

    suspend fun renamePlaylist(playlist: Playlist, newName: String) {
        if (!playlist.isSystem) {
            playlistDao.updatePlaylist(playlist.copy(name = newName))
        }
    }

    suspend fun deletePlaylist(playlist: Playlist) {
        if (!playlist.isSystem) {
            // 外键级联会自动清理 playlist_song_cross_ref
            playlistDao.deletePlaylist(playlist)
        }
    }

    suspend fun clearPlaylist(playlistId: Long) {
        playlistDao.clearPlaylist(playlistId)
    }

    suspend fun addToPlaylist(playlistId: Long, songId: Long) {
        playlistDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId, songId))
    }

    suspend fun addToPlaylist(playlistId: Long, songIds: List<Long>) {
        if (songIds.isEmpty()) return
        val baseAddedAt = System.currentTimeMillis()
        val refs =
                songIds.distinct().mapIndexed { index, songId ->
                    PlaylistSongCrossRef(
                            playlistId = playlistId,
                            songId = songId,
                            addedAt = baseAddedAt + index
                    )
                }
        playlistDao.addSongsToPlaylist(refs)
    }

    suspend fun removeFromPlaylist(playlistId: Long, songId: Long) {
        playlistDao.removeSongFromPlaylist(playlistId, songId)
    }

    suspend fun updateQueue(songs: List<Song>) {
        val baseAddedAt = System.currentTimeMillis()
        val refs =
                songs.take(Constants.QUEUE_PERSIST_LIMIT).mapIndexed { index, song ->
                    PlaylistSongCrossRef(
                            playlistId = Constants.PLAYLIST_ID_QUEUE,
                            songId = song.id,
                            addedAt = baseAddedAt + index
                    )
                }
        playlistDao.replacePlaylistSongs(Constants.PLAYLIST_ID_QUEUE, refs)
    }

    suspend fun getQueueSync(): List<Song> =
            playlistDao.getSongsForPlaylistSync(Constants.PLAYLIST_ID_QUEUE)

    fun getPlaylistSongs(playlistId: Long): Flow<List<Song>> =
            playlistDao.getSongsForPlaylist(playlistId)

    fun isFavorite(songId: Long): Flow<Boolean> =
            playlistDao.isSongInPlaylist(Constants.PLAYLIST_ID_FAVORITES, songId)

    fun getPlaylistIdsForSong(songId: Long): Flow<List<Long>> =
            playlistDao.getPlaylistIdsForSong(songId)

    // --- 网络与同步 ---
    suspend fun testConnection(
            url: String,
            user: String,
            pass: String,
            skipSsl: Boolean
    ): WebDavDataSource.ConnectionResult =
            webDavDataSource.testConnection(url, user, pass, skipSsl)

    /** 播放时主动嗅探单曲元数据；已验证或已下载的歌曲直接跳过 */
    suspend fun sniffSongMetadata(song: Song): Song =
            withContext(Dispatchers.IO) {
                if (song.isMetadataVerified || song.localPath != null) {
                    return@withContext song
                }

                val safeUrl =
                        try {
                            song.remotePath.toHttpUrlOrNull()?.toString() ?: song.remotePath
                        } catch (e: Exception) {
                            song.remotePath
                        }

                val auth = CurrentSession.getAuthForUrl(safeUrl) ?: return@withContext song
                val skipSsl = getAccountById(song.accountId)?.skipSsl == true
                val ext = song.remotePath.substringAfterLast('.', "").lowercase()

                val meta =
                        webDavDataSource.sniffMetadata(
                                safeUrl,
                                auth,
                                ext,
                                4 * 1024 * 1024L,
                                skipSsl
                        )

                if (meta.title != "Unknown") {
                    return@withContext song.copy(
                            title = meta.title,
                            artist = meta.artist,
                            album = meta.album,
                            isMetadataVerified = true
                    )
                }
                return@withContext song
            }

    fun syncAccount(account: WebDavAccount, useDeepScan: Boolean = false): Flow<SyncState> =
            flow {
                        emit(SyncState.Loading)
                        try {
                            val auth = okhttp3.Credentials.basic(account.username, account.password)
                            CurrentSession.updateAuth(account.url, auth, account.skipSsl)

                            val baseUrl =
                                    if (account.url.endsWith("/")) account.url
                                    else "${account.url}/"

                            val existingSongs = songDao.getSongsByAccountId(account.id)
                            val existingMap = existingSongs.associateBy { it.remotePath }
                            val existingPaths = existingMap.keys.toHashSet()

                            val crawlResult =
                                    webDavDataSource.crawl(
                                            baseUrl,
                                            auth,
                                            account.scanDepth,
                                            account.skipSsl
                                    )
                            val pendingTasks = crawlResult.tasks
                            val failedFolders = crawlResult.failedFolders

                            val semaphore = Semaphore(5)
                            var processedCount = 0
                            val allFoundPaths = pendingTasks.map { it.fullUrl }.toHashSet()
                            val tasksByFolder =
                                    pendingTasks.groupBy { it.fullUrl.substringBeforeLast('/') }

                            coroutineScope {
                                tasksByFolder.forEach { (folderUrl, tasks) ->
                                    val deferredSongs =
                                            tasks.map { task ->
                                                async {
                                                    semaphore.withPermit {
                                                        val existing = existingMap[task.fullUrl]
                                                        processSingleItem(
                                                                auth,
                                                                task,
                                                                account.id,
                                                                account.skipSsl,
                                                                useDeepScan,
                                                                existing
                                                        )
                                                    }
                                                }
                                            }
                                    val songs = deferredSongs.awaitAll().filterNotNull()

                                    val folderName = guessAlbumFromUrl(folderUrl)
                                    val detectedAlbum =
                                            songs.firstNotNullOfOrNull {
                                                if (it.album != "Unknown" && it.album != folderName)
                                                        it.album
                                                else null
                                            }
                                    val finalAlbum = detectedAlbum ?: folderName
                                    // 只对 album 为 "Unknown" 的歌曲应用文件夹推断的专辑名
                                    // 已验证或已有真实专辑名的歌曲不覆盖
                                    val scannedSongs =
                                            songs.map { song ->
                                                if (song.album == "Unknown")
                                                        song.copy(album = finalAlbum)
                                                else song
                                            }

                                    if (scannedSongs.isNotEmpty()) {
                                        val toInsert = mutableListOf<Song>()
                                        val toUpdate = mutableListOf<Song>()

                                        for (newSong in scannedSongs) {
                                            val oldSong = existingMap[newSong.remotePath]

                                            if (oldSong != null) {
                                                // 已验证（isMetadataVerified=true）的歌曲
                                                // 忽略本次扫描的 Title/Artist/Album，强制使用旧数据
                                                val isLocked = oldSong.isMetadataVerified

                                                val merged =
                                                        oldSong.copy(
                                                                title =
                                                                        if (isLocked) oldSong.title
                                                                        else newSong.title,
                                                                artist =
                                                                        if (isLocked) oldSong.artist
                                                                        else newSong.artist,
                                                                album =
                                                                        if (isLocked) oldSong.album
                                                                        else newSong.album,
                                                                size = newSong.size,
                                                                mimeType = newSong.mimeType,

                                                                // 关联字段：强制保留
                                                                id = oldSong.id,
                                                                localPath = oldSong.localPath,
                                                                artworkPath = oldSong.artworkPath,
                                                                isCached = oldSong.isCached,
                                                                isMetadataVerified =
                                                                        oldSong.isMetadataVerified
                                                        )
                                                if (hasSongContentChanged(oldSong, merged)) {
                                                    toUpdate.add(merged)
                                                }
                                            } else {
                                                toInsert.add(newSong)
                                            }
                                        }

                                        if (toInsert.isNotEmpty()) songDao.insertAll(toInsert)
                                        if (toUpdate.isNotEmpty()) songDao.updateAll(toUpdate)

                                        processedCount += scannedSongs.size
                                        emit(SyncState.Progress(processedCount))
                                    }
                                }
                            }

                            if (useDeepScan) {
                                if (failedFolders.isNotEmpty()) {
                                    // 有目录爬取失败时跳过删除阶段，
                                    // 避免网络抖动导致未扫描到的歌曲被误删
                                    Log.w(
                                            TAG,
                                            "Deep scan: skip deletion, ${failedFolders.size} folder(s) failed to crawl"
                                    )
                                } else {
                                    val pathsToDelete =
                                            existingPaths.filter { !allFoundPaths.contains(it) }
                                    if (pathsToDelete.isNotEmpty()) {
                                        pathsToDelete.chunked(DELETE_BATCH_SIZE).forEach { chunk ->
                                            songDao.deleteByPaths(account.id, chunk)
                                        }
                                    }
                                }
                            }
                            emit(SyncState.Success(processedCount))
                        } catch (e: Exception) {
                            Log.e(TAG, "Sync failed", e)
                            emit(SyncState.Error(e.message ?: "Unknown Error"))
                        }
                    }
                    .flowOn(Dispatchers.IO)

    private suspend fun processSingleItem(
            auth: String,
            task: WebDavDataSource.ItemTask,
            accountId: Long,
            skipSsl: Boolean,
            useDeepScan: Boolean,
            existingSong: Song?
    ): Song? {
        return try {
            val ext = task.fullUrl.substringAfterLast('.', "").lowercase()
            var (title, artist) = parseMetadata(task.displayName)
            var album = "Unknown"

            if (useDeepScan) {
                if (existingSong?.isMetadataVerified == true) {
                    // 已验证的歌曲直接复用已有元数据，跳过深度嗅探
                    title = existingSong.title
                    artist = existingSong.artist
                    album = existingSong.album
                } else {
                    val meta =
                            webDavDataSource.sniffMetadata(
                                    task.fullUrl,
                                    auth,
                                    ext,
                                    512 * 1024L,
                                    skipSsl
                            )
                    if (meta.title != "Unknown") title = meta.title
                    if (meta.artist != "Unknown") artist = meta.artist
                    if (meta.album != "Unknown") album = meta.album
                }
            } else if (existingSong != null) {
                title = existingSong.title
                artist = existingSong.artist
                album = existingSong.album
            }

            if (existingSong != null) {
                existingSong.copy(
                        title = title,
                        artist = artist,
                        album = album,
                        size = task.size,
                        mimeType = task.contentType,
                        id = existingSong.id,
                        localPath = existingSong.localPath,
                        artworkPath = existingSong.artworkPath,
                        isCached = existingSong.isCached,
                        isMetadataVerified = existingSong.isMetadataVerified
                )
            } else {
                Song(
                        0,
                        accountId,
                        task.fullUrl,
                        task.displayName,
                        title,
                        artist,
                        album,
                        null,
                        task.size,
                        task.contentType
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun hasSongContentChanged(oldSong: Song, newSong: Song): Boolean {
        return oldSong.displayName != newSong.displayName ||
                oldSong.title != newSong.title ||
                oldSong.artist != newSong.artist ||
                oldSong.album != newSong.album ||
                oldSong.size != newSong.size ||
                oldSong.mimeType != newSong.mimeType ||
                oldSong.localPath != newSong.localPath ||
                oldSong.artworkPath != newSong.artworkPath ||
                oldSong.isCached != newSong.isCached ||
                oldSong.isMetadataVerified != newSong.isMetadataVerified
    }

    private fun guessAlbumFromUrl(url: String): String {
        return try {
            val decoded = URLDecoder.decode(url, "UTF-8").trimEnd('/')
            val name = decoded.substringAfterLast('/')
            if (name.equals("dav", true) ||
                            name.equals("webdav", true) ||
                            name.equals("public", true) ||
                            name.isBlank()
            ) {
                return "Unknown Album"
            }
            if (name.contains("http")) "Unknown Album" else name
        } catch (e: Exception) {
            "Unknown Album"
        }
    }

    private fun parseMetadata(fileName: String): Pair<String, String> {
        val decodedName =
                try {
                    URLDecoder.decode(fileName, "UTF-8")
                } catch (e: Exception) {
                    fileName
                }
        val nameWithoutExt = decodedName.substringBeforeLast('.')
        val separators = listOf(" - ", "_")
        for (sep in separators) {
            if (nameWithoutExt.contains(sep)) {
                val parts = nameWithoutExt.split(sep, limit = 2)
                if (parts.size == 2) {
                    val p1 = parts[0].trim()
                    val p2 = parts[1].trim()
                    // 命名惯例为 "艺术家 - 标题"
                    if (!p1.matches(Regex("^\\d+$"))) return Pair(p2, p1)
                }
            }
        }
        val cleanTitle = nameWithoutExt.replace(Regex("^\\d+[\\.\\s]+"), "")
        return Pair(cleanTitle, "Unknown")
    }
}
