package top.sparkfade.webdavplayer.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import top.sparkfade.webdavplayer.data.local.PlaylistDao
import top.sparkfade.webdavplayer.data.local.SongDao
import top.sparkfade.webdavplayer.data.local.WebDavAccountDao
import top.sparkfade.webdavplayer.data.model.Playlist
import top.sparkfade.webdavplayer.data.model.PlaylistSongCrossRef
import top.sparkfade.webdavplayer.data.model.Song
import top.sparkfade.webdavplayer.data.model.WebDavAccount
import top.sparkfade.webdavplayer.data.remote.WebDavXmlParser
import top.sparkfade.webdavplayer.di.NetworkModule
import top.sparkfade.webdavplayer.utils.Constants
import top.sparkfade.webdavplayer.utils.CurrentSession
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songDao: SongDao,
    private val accountDao: WebDavAccountDao,
    private val playlistDao: PlaylistDao,
    @NetworkModule.SafeClient private val safeClient: OkHttpClient,
    @NetworkModule.UnsafeClient private val unsafeClient: OkHttpClient
) {
    private val TAG = "WebDavPlayer"
    private val parser = WebDavXmlParser()
    private val USER_AGENT = "WebDavMusicPlayer/1.0 (Android; ExoPlayer)"

    val allSongs: Flow<List<Song>> = songDao.getAllSongs()
    val allAccounts: Flow<List<WebDavAccount>> = accountDao.getAllAccounts()
    val allPlaylists: Flow<List<Playlist>> = playlistDao.getAllPlaylists()

    init {
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    sealed class SyncState {
        data object Idle : SyncState()
        data object Loading : SyncState()
        data class Progress(val count: Int) : SyncState()
        data class Success(val count: Int) : SyncState()
        data class Error(val message: String) : SyncState()
    }

    // --- 账号与歌曲管理 ---
    suspend fun addAccount(account: WebDavAccount): Long = accountDao.insert(account)
    suspend fun updateAccount(account: WebDavAccount) = accountDao.update(account)
    suspend fun updateSong(song: Song) = songDao.update(song)
    suspend fun deleteAccount(account: WebDavAccount) {
        accountDao.delete(account)
        songDao.clearByAccountId(account.id)
    }
    suspend fun getSongsByAccountId(accountId: Long): List<Song> = songDao.getSongsByAccountId(accountId)
    suspend fun getSongById(id: Long): Song? = songDao.getSongById(id)
    suspend fun clearLocalPaths() = songDao.clearAllLocalPaths()
    suspend fun clearArtworkPaths() = songDao.clearAllArtworkPaths()

    // --- 歌单管理 ---
    suspend fun initDefaultPlaylists() {
        val favorites = Playlist(id = 1, name = "Favorites", isSystem = true)
        playlistDao.insertPlaylist(favorites)
        val downloads = Playlist(id = 2, name = "Downloads", isSystem = true)
        playlistDao.insertPlaylist(downloads)
        val queue = Playlist(id = 3, name = "Queue", isSystem = true)
        playlistDao.insertPlaylist(queue)
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
            playlistDao.clearPlaylist(playlist.id)
            playlistDao.deletePlaylist(playlist)
        }
    }
    
    suspend fun clearPlaylist(playlistId: Long) {
        playlistDao.clearPlaylist(playlistId)
    }
    
    suspend fun addToPlaylist(playlistId: Long, songId: Long) {
        playlistDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId, songId))
    }
    
    suspend fun removeFromPlaylist(playlistId: Long, songId: Long) {
        playlistDao.removeSongFromPlaylist(playlistId, songId)
    }
    
    suspend fun updateQueue(songs: List<Song>) {
        playlistDao.clearPlaylist(3)
        val refs = songs.mapIndexed { index, song ->
            PlaylistSongCrossRef(playlistId = 3, songId = song.id, addedAt = System.currentTimeMillis() + index)
        }
        playlistDao.insertPlaylistSongCrossRefs(refs)
    }

    suspend fun getQueueSync(): List<Song> = playlistDao.getSongsForPlaylistSync(3)
    
    fun getPlaylistSongs(playlistId: Long): Flow<List<Song>> = playlistDao.getSongsForPlaylist(playlistId)
    
    fun isFavorite(songId: Long): Flow<Boolean> = playlistDao.isSongInPlaylist(1, songId)
    
    fun getPlaylistIdsForSong(songId: Long): Flow<List<Long>> = playlistDao.getPlaylistIdsForSong(songId)

    // --- 网络与同步 ---
    suspend fun testConnection(url: String, user: String, pass: String, skipSsl: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = if (skipSsl) unsafeClient else safeClient
            val auth = okhttp3.Credentials.basic(user, pass)
            val request = Request.Builder()
                .url(url).header("Authorization", auth).header("User-Agent", USER_AGENT)
                .header("Depth", "0").method("PROPFIND", null).build()
            val response = client.newCall(request).execute()
            val isSuccess = response.isSuccessful
            response.close()
            isSuccess
        } catch (e: Exception) { 
            Log.e(TAG, "Test connection failed: ${e.message}")
            false 
        }
    }

    // 主动嗅探单曲元数据 (供播放时调用)
    suspend fun sniffSongMetadata(song: Song): Song = withContext(Dispatchers.IO) {
        val safeUrl = try { 
            song.remotePath.toHttpUrlOrNull()?.toString() ?: song.remotePath 
        } catch(e:Exception) { 
            song.remotePath 
        }
        
        val auth = CurrentSession.getAuthForUrl(safeUrl) ?: return@withContext song
        val ext = song.remotePath.substringAfterLast('.', "").lowercase()
        
        // 播放时读取 4MB
        val meta = smartSniffMetadata(unsafeClient, safeUrl, auth, ext, 4 * 1024 * 1024L)
        
        if (meta.title != "Unknown") {
            return@withContext song.copy(
                title = meta.title,
                artist = meta.artist,
                album = meta.album,
                // [核心修复] 既然已经强力嗅探过了，就锁定它！
                isMetadataVerified = true 
            )
        }
        return@withContext song
    }

    fun syncAccount(account: WebDavAccount, useDeepScan: Boolean = false): Flow<SyncState> = flow {
        emit(SyncState.Loading)
        try {
            val auth = okhttp3.Credentials.basic(account.username, account.password)
            CurrentSession.updateAuth(account.url, auth)
            val client = if (account.skipSsl) unsafeClient else safeClient
            
            val baseUrl = if (account.url.endsWith("/")) account.url else "${account.url}/"
            
            val existingSongs = songDao.getSongsByAccountId(account.id)
            val existingMap = existingSongs.associateBy { it.remotePath }
            val existingPaths = existingMap.keys.toHashSet()
            
            val visitedUrls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
            val pendingTasks = mutableListOf<ItemTask>()
            crawlFolders(client, baseUrl, auth, 0, account.scanDepth, visitedUrls, pendingTasks)
            
            val semaphore = Semaphore(5) 
            var processedCount = 0
            val allFoundPaths = pendingTasks.map { it.fullUrl }.toHashSet()
            val tasksByFolder = pendingTasks.groupBy { it.fullUrl.substringBeforeLast('/') }

            coroutineScope {
                tasksByFolder.forEach { (folderUrl, tasks) ->
                    val deferredSongs = tasks.map { task ->
                        async {
                            semaphore.withPermit { 
                                val existing = existingMap[task.fullUrl]
                                processSingleItem(client, auth, task, account.id, useDeepScan, existing) 
                            }
                        }
                    }
                    val songs = deferredSongs.awaitAll().filterNotNull()
                    
                    val folderName = guessAlbumFromUrl(folderUrl)
                    val detectedAlbum = songs.firstNotNullOfOrNull { 
                        if (it.album != "Unknown" && it.album != folderName) it.album else null 
                    }
                    val finalAlbum = detectedAlbum ?: folderName
                    val scannedSongs = songs.map { song -> song.copy(album = finalAlbum) }

                    if (scannedSongs.isNotEmpty()) {
                        val toInsert = mutableListOf<Song>()
                        val toUpdate = mutableListOf<Song>()

                        for (newSong in scannedSongs) {
                            val oldSong = existingMap[newSong.remotePath]
                            
                            if (oldSong != null) {
                                // [核心修复] 锁定策略
                                // 如果 oldSong 已经被验证过 (isMetadataVerified=true)，
                                // 则忽略本次扫描的 Title/Artist/Album，强制使用旧数据。
                                val isLocked = oldSong.isMetadataVerified

                                val merged = oldSong.copy(
                                    // 锁定字段：如果锁定，用旧的；否则用新的
                                    title = if (isLocked) oldSong.title else newSong.title,
                                    artist = if (isLocked) oldSong.artist else newSong.artist,
                                    album = if (isLocked) oldSong.album else newSong.album,
                                    
                                    // 技术字段：总是更新 (防止文件变了但元数据没变的情况，或者只更新了大小)
                                    size = newSong.size,
                                    mimeType = newSong.mimeType,
                                    
                                    // 关联字段：强制保留
                                    id = oldSong.id, 
                                    localPath = oldSong.localPath, 
                                    artworkPath = oldSong.artworkPath, 
                                    isCached = oldSong.isCached,
                                    isMetadataVerified = oldSong.isMetadataVerified // 保持锁定状态
                                )
                                toUpdate.add(merged)
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
                val pathsToDelete = existingPaths.filter { !allFoundPaths.contains(it) }
                if (pathsToDelete.isNotEmpty()) {
                    pathsToDelete.forEach { songDao.deleteByPath(account.id, it) }
                }
            }
            emit(SyncState.Success(processedCount))
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            emit(SyncState.Error(e.message ?: "Unknown Error"))
        }
    }.flowOn(Dispatchers.IO)

    data class ItemTask(val fullUrl: String, val displayName: String, val size: Long, val contentType: String)

    private suspend fun crawlFolders(client: OkHttpClient, currentUrl: String, auth: String, depth: Int, maxDepth: Int, visitedUrls: MutableSet<String>, results: MutableList<ItemTask>) {
        if (depth > maxDepth) return
        val normalizedUrl = currentUrl.trimEnd('/')
        if (!visitedUrls.add(normalizedUrl)) return 

        val request = Request.Builder()
            .url(currentUrl)
            .header("Authorization", auth)
            .header("User-Agent", USER_AGENT)
            .header("Depth", "1")
            .method("PROPFIND", null)
            .build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) { response.close(); return }
            val bodyStream = response.body?.byteStream()
            if (bodyStream != null) {
                val resources = parser.parse(bodyStream)
                for (res in resources) {
                    val rawHref = res.href
                    val fullUrl = resolveUrl(currentUrl, rawHref)
                    val normChild = fullUrl.trimEnd('/')
                    if (normChild == normalizedUrl) continue
                    if (visitedUrls.contains(normChild)) continue 

                    if (res.isCollection) {
                        crawlFolders(client, fullUrl, auth, depth + 1, maxDepth, visitedUrls, results)
                    } else {
                        val decodedHref = try { URLDecoder.decode(rawHref, "UTF-8") } catch (e: Exception) { rawHref }
                        val ext = decodedHref.substringAfterLast('.', "").lowercase()
                        if (Constants.SUPPORTED_EXTENSIONS.contains(ext)) {
                            results.add(ItemTask(fullUrl, res.displayName, res.contentLength, res.contentType))
                            visitedUrls.add(normChild)
                        }
                    }
                }
            }
            response.close()
        } catch (e: Exception) { Log.e(TAG, "Crawl Error: $currentUrl", e) }
    }

    private suspend fun processSingleItem(client: OkHttpClient, auth: String, task: ItemTask, accountId: Long, useDeepScan: Boolean, existingSong: Song?): Song? {
        try {
            val ext = task.fullUrl.substringAfterLast('.', "").lowercase()
            var (title, artist) = parseMetadata(task.displayName)
            var album = "Unknown" 
            
            if (useDeepScan) {
                val meta = smartSniffMetadata(client, task.fullUrl, auth, ext, 512 * 1024L)
                if (meta.title != "Unknown") title = meta.title
                if (meta.artist != "Unknown") artist = meta.artist
                if (meta.album != "Unknown") album = meta.album
            } else if (existingSong != null) {
                title = existingSong.title
                artist = existingSong.artist
                album = existingSong.album
            }

            return if (existingSong != null) {
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
                    isMetadataVerified = existingSong.isMetadataVerified // 保持验证状态
                )
            } else {
                Song(0, accountId, task.fullUrl, task.displayName, title, artist, album, null, task.size, task.contentType)
            }
        } catch (e: Exception) { return null }
    }

    private suspend fun smartSniffMetadata(client: OkHttpClient, url: String, auth: String, ext: String, limit: Long): SongMetadata {
        var tempFile: File? = null
        try {
            val downloadSize = limit 
            val request = Request.Builder()
                .url(url)
                .header("Authorization", auth)
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=0-${downloadSize - 1}")
                .build()
            
            var response = try { client.newCall(request).execute() } catch (e: Exception) { delay(1000); client.newCall(request).execute() }

            if (!response.isSuccessful) { response.close(); return SongMetadata("Unknown", "Unknown", "Unknown", null) }

            tempFile = File.createTempFile("scan_", ".$ext", context.cacheDir)
            val inputStream: InputStream = response.body!!.byteStream()
            val fileOutput = FileOutputStream(tempFile)
            try {
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var bytesRead: Int
                while (totalRead < downloadSize) {
                    bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    fileOutput.write(buffer, 0, bytesRead)
                    totalRead += bytesRead.toLong()
                }
            } catch (e: Exception) { } finally { fileOutput.close(); inputStream.close(); response.close() }

            val audioFile = AudioFileIO.read(tempFile)
            val tag = audioFile.tag
            val title = tag?.getFirst(FieldKey.TITLE) ?: "Unknown"
            val artist = tag?.getFirst(FieldKey.ARTIST) ?: "Unknown"
            val album = tag?.getFirst(FieldKey.ALBUM) ?: "Unknown"
            
            if (title != "Unknown" && title.isNotBlank()) return SongMetadata(title, artist, album, null)
            return SongMetadata("Unknown", "Unknown", "Unknown", null)

        } catch (e: Exception) { return SongMetadata("Unknown", "Unknown", "Unknown", null) } 
        finally { tempFile?.delete() }
    }

    data class SongMetadata(val title: String, val artist: String, val album: String, val artworkPath: String?)

    private fun guessAlbumFromUrl(url: String): String {
        try {
            val decoded = URLDecoder.decode(url, "UTF-8").trimEnd('/')
            val name = decoded.substringAfterLast('/')
            if (name.equals("dav", true) || name.equals("webdav", true) || name.equals("public", true) || name.isBlank()) {
                return "Unknown Album"
            }
            return if (name.contains("http")) "Unknown Album" else name
        } catch (e: Exception) { return "Unknown Album" }
    }

    private fun parseMetadata(fileName: String): Pair<String, String> {
        val decodedName = try { URLDecoder.decode(fileName, "UTF-8") } catch(e:Exception){ fileName }
        val nameWithoutExt = decodedName.substringBeforeLast('.')
        val separators = listOf(" - ", "_")
        for (sep in separators) {
            if (nameWithoutExt.contains(sep)) {
                val parts = nameWithoutExt.split(sep, limit = 2)
                if (parts.size == 2) {
                    val p1 = parts[0].trim()
                    val p2 = parts[1].trim()
                    if (!p1.matches(Regex("^\\d+$"))) return Pair(p1, p2)
                }
            }
        }
        val cleanTitle = nameWithoutExt.replace(Regex("^\\d+[\\.\\s]+"), "")
        return Pair(cleanTitle, "Unknown")
    }

    private fun resolveUrl(baseUrl: String, href: String): String {
        if (href.startsWith("http")) return href
        try {
            val uri = URI(baseUrl)
            val hostRoot = "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
            return if (href.startsWith("/")) "$hostRoot$href" else if (baseUrl.endsWith("/")) "$baseUrl$href" else "$baseUrl/$href"
        } catch (e: Exception) { return if (baseUrl.endsWith("/")) "$baseUrl$href" else "$baseUrl/$href" }
    }
}