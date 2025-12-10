package top.sparkfade.webdavplayer.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.sparkfade.webdavplayer.data.model.Playlist
import top.sparkfade.webdavplayer.data.model.Song
import top.sparkfade.webdavplayer.data.model.WebDavAccount
import top.sparkfade.webdavplayer.data.repository.CacheRepository
import top.sparkfade.webdavplayer.data.repository.FileDownloader
import top.sparkfade.webdavplayer.data.repository.MusicRepository
import top.sparkfade.webdavplayer.service.PlaybackService
import top.sparkfade.webdavplayer.utils.CurrentSession
import top.sparkfade.webdavplayer.utils.dataStore
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    app: Application,
    private val repository: MusicRepository,
    private val downloader: FileDownloader,
    private val cacheRepository: CacheRepository
) : AndroidViewModel(app) {

    private val _startDestination = MutableStateFlow<String?>(null)
    val startDestination = _startDestination.asStateFlow()

    val allSongs: StateFlow<List<Song>> = repository.allSongs
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    val allAccounts: StateFlow<List<WebDavAccount>> = repository.allAccounts
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _downloadProgressMap = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val downloadProgressMap = _downloadProgressMap.asStateFlow()

    fun onSearchQueryChanged(query: String) { _searchQuery.value = query }

    val filteredSongs: StateFlow<List<Song>> = combine(allSongs, _searchQuery) { songs, query ->
        if (query.isBlank()) songs
        else songs.filter { matchSearch(it, query) }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val albums = combine(allSongs, _searchQuery) { songs, query ->
        songs.groupBy { it.album }
            .map { (name, list) ->
                val art = list.find { it.artworkPath != null }?.artworkPath
                AlbumData(name, list.firstOrNull()?.artist ?: "Unknown", art, list.size, list)
            }
            .filter { it.name != "Unknown" }
            .filter { 
                if (query.isBlank()) true 
                else it.name.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
            }
            .sortedBy { it.name }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val artists = combine(allSongs, _searchQuery) { songs, query ->
        val artistMap = mutableMapOf<String, MutableList<Song>>()

        songs.forEach { song ->
            val splitNames = song.artist.split(artistSeparatorRegex)
                .map { it.trim() }
                .filter { it.isNotBlank() && it != "Unknown" }

            if (splitNames.isEmpty()) {
                artistMap.getOrPut("Unknown") { mutableListOf() }.add(song)
            } else {
                splitNames.forEach { name ->
                    artistMap.getOrPut(name) { mutableListOf() }.add(song)
                }
            }
        }

        artistMap.map { (name, list) -> ArtistData(name, list.size, list) }
            .filter { 
                if (query.isBlank()) true
                else it.name.contains(query, ignoreCase = true)
            }
            .sortedBy { it.name }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val playlists: StateFlow<List<Playlist>> = combine(repository.allPlaylists, _searchQuery) { list, query ->
        val visibleList = list.filter { it.id != 3L } 
        if (query.isBlank()) visibleList
        else visibleList.filter { it.name.contains(query, ignoreCase = true) }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private fun matchSearch(song: Song, query: String): Boolean {
        return song.title.contains(query, ignoreCase = true) ||
               song.artist.contains(query, ignoreCase = true) ||
               song.displayName.contains(query, ignoreCase = true)
    }

    private val _playerController = MutableStateFlow<Player?>(null)
    val playerController = _playerController.asStateFlow()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val _currentPlayingSong = MutableStateFlow<Song?>(null)
    val currentPlayingSong = _currentPlayingSong.asStateFlow()

    val isPlaying = MutableStateFlow(false)
    val isBuffering = MutableStateFlow(false)
    val playbackProgress = MutableStateFlow(0L)
    val playbackDuration = MutableStateFlow(1L)
    val playbackMode = MutableStateFlow(0)

    private val _currentPlaylist = MutableStateFlow<List<Song>>(emptyList())
    val currentPlaylist = _currentPlaylist.asStateFlow()
    // val renamedAlbumRedirects = androidx.compose.runtime.mutableStateMapOf<String, String>()

    // private val _redirectChannel = kotlinx.coroutines.channels.Channel<Pair<String, String>>(kotlinx.coroutines.channels.Channel.BUFFERED)
    // val redirectFlow = _redirectChannel.receiveAsFlow()

    val isCurrentSongFavorite: StateFlow<Boolean> = _currentPlayingSong.flatMapLatest { song ->
        if (song == null) flowOf(false)
        else repository.isFavorite(song.id)
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun getPlaylistsWithStatus(songId: Long): Flow<List<Pair<Playlist, Boolean>>> {
        return combine(repository.allPlaylists, repository.getPlaylistIdsForSong(songId)) { all, currentIds ->
            all.filter { it.id != 2L && it.id != 3L }.map { playlist ->
                playlist to currentIds.contains(playlist.id)
            }
        }
    }

    fun addToQueue(song: Song) {
        val currentList = _currentPlaylist.value.toMutableList()
        // 防止重复添加
        if (currentList.none { it.id == song.id }) {
            val controller = _playerController.value
            if (controller != null) {
                val item = buildMediaItem(song)
                controller.addMediaItem(item)
            }

            currentList.add(song)
            _currentPlaylist.value = currentList

            // 更新数据库 (ID=3 是 Queue播放列表)
            viewModelScope.launch {
                repository.addToPlaylist(3, song.id)
            }
        }
    }

    fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        viewModelScope.launch {
            songIds.forEach { songId -> repository.addToPlaylist(playlistId, songId) }
        }
    }

    fun toggleSongInPlaylist(playlistId: Long, songId: Long, shouldAdd: Boolean) {
        viewModelScope.launch {
            if (shouldAdd) repository.addToPlaylist(playlistId, songId)
            else repository.removeFromPlaylist(playlistId, songId)
        }
    }

    fun toggleFavorite() {
        val song = _currentPlayingSong.value ?: return
        val isFav = isCurrentSongFavorite.value
        viewModelScope.launch {
            if (isFav) repository.removeFromPlaylist(1, song.id)
            else repository.addToPlaylist(1, song.id)
        }
    }

//    fun addSongToPlaylist(playlistId: Long, songId: Long) {
//        viewModelScope.launch { repository.addToPlaylist(playlistId, songId) }
//    }

    fun createPlaylist(name: String) {
        viewModelScope.launch { repository.createPlaylist(name) }
    }
    
    fun renamePlaylist(playlist: Playlist, newName: String) {
        viewModelScope.launch { repository.renamePlaylist(playlist, newName) }
    }
    
    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch { repository.deletePlaylist(playlist) }
    }

    fun getSongsForList(type: String, idOrName: String): Flow<List<Song>> {
        return when (type) {
            "album" -> allSongs.map { list -> list.filter { it.album == idOrName } }
            "artist" -> allSongs.map { list -> 
                list.filter { song -> 
                    // 使用相同的正则切割，判断当前歌手是否包含在其中
                    val splitNames = song.artist.split(artistSeparatorRegex)
                        .map { it.trim() }
                    
                    // 只要切割后的列表中包含目标歌手名就认为匹配
                    splitNames.any { it.equals(idOrName, ignoreCase = true) } 
                    // 完全相等作为兜底
                    || song.artist.equals(idOrName, ignoreCase = true)
                } 
            }
            "playlist" -> repository.getPlaylistSongs(idOrName.toLongOrNull() ?: -1L)
            else -> flowOf(emptyList())
        }
    }

    private val _isLoggedIn = MutableStateFlow(false)
    // val isLoggedIn = _isLoggedIn.asStateFlow()
    val cacheSize = MutableStateFlow(0L)
    val coverCacheSize = MutableStateFlow(0L)
    private val _themeMode = MutableStateFlow(0)
    val themeMode = _themeMode.asStateFlow()
    private val _accountToEdit = MutableStateFlow<WebDavAccount?>(null)
    val accountToEdit = _accountToEdit.asStateFlow()
    private val _accountSyncStatus = MutableStateFlow<Map<Long, String>>(emptyMap())
    val accountSyncStatus = _accountSyncStatus.asStateFlow()
    private val _isSyncingMap = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val isSyncingMap = _isSyncingMap.asStateFlow()

    // 如果有任意账号正在同步，返回其状态文字；否则返回 null
    val scanningStatus: StateFlow<String?> = combine(_isSyncingMap, _accountSyncStatus) { syncingMap, statusMap ->
        // 找到第一个正在同步的账号 ID
        val syncingId = syncingMap.entries.find { it.value }?.key
        if (syncingId != null) {
            statusMap[syncingId]
        } else {
            null
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _albumRenameChannel = kotlinx.coroutines.channels.Channel<Pair<String, String>>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val albumRenameFlow = _albumRenameChannel.receiveAsFlow()
    private val artistSeparatorRegex = Regex("[,;&/|、＆，；]|\\s+(?i)(feat\\.?|ft\\.?|vs\\.?|cv\\.?)\\s+")

    init {
        viewModelScope.launch {
            repository.initDefaultPlaylists()
            val prefs = getApplication<Application>().dataStore.data.first()
            _themeMode.value = prefs[intPreferencesKey("theme_mode")] ?: 0
            val accounts = repository.allAccounts.first()
            CurrentSession.clear()
            accounts.forEach { acc ->
                val auth = okhttp3.Credentials.basic(acc.username, acc.password)
                CurrentSession.updateAuth(acc.url, auth)
            }
            if (accounts.isNotEmpty()) {
                _isLoggedIn.value = true
                _startDestination.value = "main"
                restorePlaybackState()
            } else {
                _startDestination.value = "login"
            }
        }
        initController(app)
        startProgressUpdater()
        refreshStorageInfo()
    }

    private fun restorePlaybackState() {
        viewModelScope.launch {
            val prefs = getApplication<Application>().dataStore.data.first()
            val lastSongId = prefs[longPreferencesKey("last_song_id")] ?: -1L
            val lastPos = prefs[longPreferencesKey("last_pos")] ?: 0L

            if (lastSongId != -1L) {
                val queue = repository.getQueueSync()
                if (queue.isNotEmpty()) {
                    _currentPlaylist.value = queue
                }
                val song = repository.getSongById(lastSongId)
                if (song != null) {
                    _currentPlayingSong.value = song
                    playbackProgress.value = lastPos
                    playbackDuration.value = 1L
                    while (_playerController.value == null) {
                        delay(100)
                    }
                    val controller = _playerController.value!!
                    if (queue.isNotEmpty()) {
                        val index = queue.indexOfFirst { it.id == song.id }
                        if (index != -1) {
                            prepareMediaItems(controller, queue, index, lastPos, false)
                        } else {
                            prepareMediaItemForRestoration(controller, song, lastPos)
                        }
                    } else {
                        prepareMediaItemForRestoration(controller, song, lastPos)
                    }
                }
            }
        }
    }

    private fun prepareMediaItemForRestoration(controller: Player, song: Song, pos: Long) {
        val item = buildMediaItem(song)
        controller.setMediaItem(item)
        controller.prepare()
        controller.seekTo(pos)
        controller.pause()
    }
    
    private fun prepareMediaItems(controller: Player, songs: List<Song>, startIndex: Int, startPos: Long, autoPlay: Boolean) {
        val items = songs.map { buildMediaItem(it) }
        controller.setMediaItems(items, startIndex, startPos)
        controller.prepare()
        if (autoPlay) controller.play() else controller.pause()
    }

    private fun buildMediaItem(item: Song): MediaItem {
        val metaBuilder = MediaMetadata.Builder()
        if (item.title != "Unknown" && item.title != item.displayName) metaBuilder.setTitle(item.title)
        if (item.artist != "Unknown") metaBuilder.setArtist(item.artist)
        if (item.album != "Unknown Album") metaBuilder.setAlbumTitle(item.album)

        val uri = if (item.localPath != null && File(item.localPath).exists()) {
            Uri.fromFile(File(item.localPath))
        } else {
            val safeUrl = try {
                // 使用扩展函数处理 URL
                item.remotePath.toHttpUrlOrNull()?.toString() ?: item.remotePath
            } catch (e: Exception) {
                item.remotePath
            }
            Uri.parse(safeUrl)
        }
        
        val mimeType = when {
            item.remotePath.endsWith(".mp3", true) -> MimeTypes.AUDIO_MPEG
            item.remotePath.endsWith(".flac", true) -> MimeTypes.AUDIO_FLAC
            item.remotePath.endsWith(".wav", true) -> MimeTypes.AUDIO_WAV
            item.remotePath.endsWith(".m4a", true) -> MimeTypes.AUDIO_MP4
            item.remotePath.endsWith(".aac", true) -> MimeTypes.AUDIO_AAC
            item.remotePath.endsWith(".ogg", true) -> MimeTypes.AUDIO_OGG
            item.remotePath.endsWith(".opus", true) -> MimeTypes.AUDIO_OPUS
            else -> MimeTypes.AUDIO_MPEG 
        }

        return MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setUri(uri)
            .setMimeType(mimeType)
            .setMediaMetadata(metaBuilder.build())
            // 强制使用 Song ID 作为缓存键。
            .setCustomCacheKey(item.id.toString()) 
            .build()
    }

    private fun savePlaybackState(pos: Long) {
        val song = _currentPlayingSong.value ?: return
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { 
                it[longPreferencesKey("last_song_id")] = song.id
                it[longPreferencesKey("last_pos")] = pos
            }
        }
    }

    private fun initController(context: android.content.Context) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                val controller = controllerFuture?.get()
                if (controller?.repeatMode == Player.REPEAT_MODE_OFF) {
                    controller.repeatMode = Player.REPEAT_MODE_ALL
                }
                _playerController.value = controller
                setupPlayerListener(controller)
            } catch (e: Exception) { e.printStackTrace() }
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlayerListener(player: Player?) {
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                this@MainViewModel.isPlaying.value = isPlaying
            }
            
            // 切歌逻辑
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCurrentSongById(mediaItem?.mediaId)
                playbackProgress.value = 0L
                playbackDuration.value = 1L
                isBuffering.value = player.playbackState == Player.STATE_BUFFERING
                
                val current = _currentPlayingSong.value
                if (current != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        val sniffedSong = repository.sniffSongMetadata(current)
                        
                        val newTitle = sniffedSong.title
                        val newArtist = sniffedSong.artist
                        val newAlbum = sniffedSong.album

                        // 只要新值不是 Unknown 且与当前不同，就认为是真实值
                        fun shouldUpdate(newVal: String, oldVal: String): Boolean {
                            return newVal.isNotEmpty() && 
                                   newVal != "Unknown" && 
                                   newVal != "Unknown Album" && 
                                   newVal != oldVal
                        }

                        // 只要有任何一项元数据不匹配且新数据有效，就触发更新
                        if (shouldUpdate(newTitle, current.title) || 
                            shouldUpdate(newArtist, current.artist) || 
                            shouldUpdate(newAlbum, current.album)) {
                                
                            // 调用通用逻辑进行更新当前歌曲
                            updateMetadataLogic(
                                targetSong = current, 
                                realTitle = newTitle, 
                                realArtist = newArtist, 
                                realAlbum = newAlbum, 
                                artworkData = null
                            )
                        }
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    playbackDuration.value = player.duration.coerceAtLeast(1L)
                    isBuffering.value = false
                } else if (playbackState == Player.STATE_ENDED) {
                    if (player.mediaItemCount > 0) {
                        if (player.shuffleModeEnabled) {
                            player.shuffleModeEnabled = false
                            player.shuffleModeEnabled = true
                        }
                        player.seekTo(0, 0)
                        player.prepare()
                        player.play()
                        isBuffering.value = false
                        isPlaying.value = true
                    }
                } else {
                    isBuffering.value = playbackState == Player.STATE_BUFFERING
                }
            }
            
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val realTitle = mediaMetadata.title?.toString()
                val realArtist = mediaMetadata.artist?.toString()
                val realAlbum = mediaMetadata.albumTitle?.toString()
                val artworkData = mediaMetadata.artworkData
                
                val current = _currentPlayingSong.value ?: return

                viewModelScope.launch(Dispatchers.IO) {
                    updateMetadataLogic(current, realTitle, realArtist, realAlbum, artworkData)
                }
            }
        })
        
        updateCurrentSongById(player?.currentMediaItem?.mediaId)
        this.isPlaying.value = player?.isPlaying == true
        this.isBuffering.value = player?.playbackState == Player.STATE_BUFFERING
        val mode = if (player?.shuffleModeEnabled == true) 1 else if (player?.repeatMode == Player.REPEAT_MODE_ONE) 2 else 0
        playbackMode.value = mode
    }

    // 1.4.3 元数据更新逻辑,优化储存空间
    private suspend fun updateMetadataLogic(
        targetSong: Song, 
        realTitle: String?, 
        realArtist: String?, 
        realAlbum: String?, 
        artworkData: ByteArray?
    ) {
        var updatedSong = targetSong
        var dataChanged = false

        fun isValid(s: String?): Boolean {
            return !s.isNullOrEmpty() && s != "Unknown" && s != "Unknown Album"
        }

        fun isPlaceholder(album: String): Boolean {
            val folderName = try {
                val path = targetSong.remotePath.substringBeforeLast('/')
                URLDecoder.decode(path, "UTF-8").substringAfterLast('/')
            } catch (e: Exception) { "" }
            
            return album == "Unknown" || 
                   album == "Unknown Album" || 
                   album.equals("dav", true) || 
                   album.equals("webdav", true) || 
                   album == folderName
        }

        fun isImageValid(file: File): Boolean {
            return try {
                val options = android.graphics.BitmapFactory.Options()
                options.inJustDecodeBounds = true
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                options.outWidth > 0 && options.outHeight > 0
            } catch (e: Exception) { false }
        }

        // --- 文本元数据更新 ---
        val safeTitle = realTitle?.trim()
        val safeArtist = realArtist?.trim()
        val safeAlbum = realAlbum?.trim()

        if (isValid(safeTitle) && safeTitle != targetSong.title) {
            updatedSong = updatedSong.copy(title = safeTitle!!)
            dataChanged = true
        }
        
        if (isValid(safeArtist) && safeArtist != targetSong.artist) {
            updatedSong = updatedSong.copy(artist = safeArtist!!)
            dataChanged = true
        }

        val currentIsPlaceholder = isPlaceholder(targetSong.album)
        val newIsPlaceholder = safeAlbum.isNullOrEmpty() || isPlaceholder(safeAlbum)

        if ((!newIsPlaceholder || currentIsPlaceholder) && safeAlbum != targetSong.album && !safeAlbum.isNullOrEmpty()) {
            updatedSong = updatedSong.copy(album = safeAlbum)
            dataChanged = true

            if (!newIsPlaceholder) {
                val folderPath = try {
                    val path = targetSong.remotePath.substringBeforeLast('/')
                    URLDecoder.decode(path, "UTF-8")
                } catch (e: Exception) { "" }
                val oldAlbum = targetSong.album
                
                if (currentIsPlaceholder) {
                    val siblings = allSongs.value.filter { 
                        val itFolder = try {
                            URLDecoder.decode(it.remotePath.substringBeforeLast('/'), "UTF-8")
                        } catch (e:Exception) { "" }
                        itFolder == folderPath && it.album == oldAlbum && it.id != targetSong.id
                    }
                    if (siblings.isNotEmpty()) {
                        siblings.forEach { sibling ->
                            repository.updateSong(sibling.copy(album = safeAlbum))
                        }
                        _albumRenameChannel.trySend(oldAlbum to safeAlbum)
                    }
                }
            }
        }

        // --- 封面处理 ---
        // 1.4.3 优化储存空间
        if (dataChanged || artworkData != null || targetSong.artworkPath == null) {
            try {
                var finalArtworkPath = updatedSong.artworkPath
                var artChanged = false
                
                val coversDir = File(getApplication<Application>().cacheDir, "covers")
                if (!coversDir.exists()) coversDir.mkdirs()

                // 计算路径哈希
                val folderPathHash = try {
                    val path = targetSong.remotePath.substringBeforeLast('/')
                    URLDecoder.decode(path, "UTF-8").hashCode()
                } catch (e: Exception) { 0 }
                
                // 文件定义
                // 文件夹默认图
                val folderCoverFile = File(coversDir, "dir_$folderPathHash.jpg")
                
                // 专辑专属图 (优先)
                val targetAlbumName = updatedSong.album
                val hasValidAlbum = !isPlaceholder(targetAlbumName)
                val albumCoverFile = if (hasValidAlbum) File(coversDir, "alb_${targetAlbumName.hashCode()}.jpg") else null

                // 互斥写入
                if (artworkData != null) {
                    val tempFile = File(coversDir, "temp_${System.currentTimeMillis()}.tmp")
                    try {
                        val fos = FileOutputStream(tempFile)
                        fos.write(artworkData)
                        fos.flush()
                        fos.fd.sync()
                        fos.close()

                        if (isImageValid(tempFile)) {
                            // 如果有专辑名，就存为专辑图；否则存为文件夹图
                            if (albumCoverFile != null) {
                                tempFile.renameTo(albumCoverFile)
                                finalArtworkPath = albumCoverFile.absolutePath
                            } else {
                                tempFile.renameTo(folderCoverFile)
                                finalArtworkPath = folderCoverFile.absolutePath
                            }
                            artChanged = true
                        } else {
                            tempFile.delete()
                        }
                    } catch (e: Exception) {
                        tempFile.delete()
                        e.printStackTrace()
                    }
                } 
                
                // 读取逻辑 (层级回退)
                else if (finalArtworkPath == null) {
                    // 优先找专辑图
                    if (albumCoverFile != null && albumCoverFile.exists() && isImageValid(albumCoverFile)) {
                        finalArtworkPath = albumCoverFile.absolutePath
                        artChanged = true
                    }
                    // 找不到专辑图或者专辑名无效，就找文件夹默认图
                    else if (folderCoverFile.exists() && isImageValid(folderCoverFile)) {
                        finalArtworkPath = folderCoverFile.absolutePath
                        artChanged = true
                    }
                }

                if (dataChanged || artChanged) {
                    val finalSong = updatedSong.copy(
                        artworkPath = finalArtworkPath,
                        isMetadataVerified = true 
                    )
                    
                    repository.updateSong(finalSong)
                    
                    if (_currentPlayingSong.value?.id == finalSong.id) {
                        withContext(Dispatchers.Main) { 
                            _currentPlayingSong.value = finalSong 
                        }
                    }
                    
                    val currentList = _currentPlaylist.value.toMutableList()
                    val index = currentList.indexOfFirst { it.id == finalSong.id }
                    if (index != -1) {
                        currentList[index] = finalSong
                        _currentPlaylist.value = currentList
                    }

                    if (artChanged) refreshStorageInfo()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun updateCurrentSongById(mediaId: String?) {
        if (mediaId == null) return
        val id = mediaId.toLongOrNull() ?: return
        val song = allSongs.value.find { it.id == id }
        if (song != null) _currentPlayingSong.value = song
    }

    fun playSong(song: Song, playlist: List<Song>) {
        val controller = _playerController.value ?: return
        _currentPlaylist.value = playlist
        viewModelScope.launch { repository.updateQueue(playlist) }
        val index = playlist.indexOfFirst { it.id == song.id }
        if (index == -1) return
        _currentPlayingSong.value = song
        playbackProgress.value = 0L
        playbackDuration.value = 1L
        isBuffering.value = true
        prepareMediaItems(controller, playlist, index, 0L, true)
    }

    fun skipToQueueItem(index: Int) {
        val controller = _playerController.value ?: return
        if (index in 0 until _currentPlaylist.value.size) {
            val song = _currentPlaylist.value[index]
            _currentPlayingSong.value = song
            isBuffering.value = true
            playbackProgress.value = 0L
            controller.seekToDefaultPosition(index)
            controller.play()
        }
    }

    fun skipToNext() {
        val player = _playerController.value ?: return
        if (player.hasNextMediaItem()) { isBuffering.value = true; player.seekToNext() } else { isBuffering.value = false }
    }

    fun skipToPrevious() {
        val player = _playerController.value ?: return
        isBuffering.value = true
        player.seekToPrevious()
    }
    
    fun togglePlaybackMode() {
        val controller = _playerController.value ?: return
        val current = playbackMode.value
        val next = (current + 1) % 3
        playbackMode.value = next
        when (next) {
            0 -> { controller.shuffleModeEnabled = false; controller.repeatMode = Player.REPEAT_MODE_ALL }
            1 -> { controller.shuffleModeEnabled = true; controller.repeatMode = Player.REPEAT_MODE_ALL }
            2 -> { controller.shuffleModeEnabled = false; controller.repeatMode = Player.REPEAT_MODE_ONE }
        }
    }
    
    fun seekTo(pos: Long) { _playerController.value?.seekTo(pos); isBuffering.value = true; playbackProgress.value = pos }
    
    fun downloadSong(song: Song) { 
        if (_downloadProgressMap.value.containsKey(song.id)) return // 防止重复点击

        viewModelScope.launch { 
            // 立即加入Downloads歌单 (ID=2)，让用户能在下载列表中看到
            repository.addToPlaylist(2, song.id)
            
            // 开始下载并收集进度流
            downloader.downloadSongFlow(getApplication(), song, true).collect { status ->
                when(status) {
                    is FileDownloader.DownloadStatus.Progress -> {
                        // 更新进度 Map
                        _downloadProgressMap.value = _downloadProgressMap.value + (song.id to status.progress)
                    }
                    is FileDownloader.DownloadStatus.Success -> {
                        // 下载完成，移除进度条，刷新存储信息
                        _downloadProgressMap.value = _downloadProgressMap.value - song.id
                        refreshStorageInfo()
                    }
                    is FileDownloader.DownloadStatus.Error -> {
                        // 下载失败，移除进度条，并从下载歌单中移除
                        _downloadProgressMap.value = _downloadProgressMap.value - song.id
                        repository.removeFromPlaylist(2, song.id)
                    }
                }
            }
        } 
    }
    
    fun deleteLocalSong(s: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 删除显式下载的文件
                s.localPath?.let { File(it).delete() }
                
                // 更新数据库状态
                val updated = s.copy(localPath = null)
                repository.updateSong(updated)
                
                // 从下载歌单移除
                repository.removeFromPlaylist(2, s.id)
                
                // 1.4.2 移除 ExoPlayer 的在线播放缓存 ，解决缓存泄露
                cacheRepository.removeResource(s.id.toString())

                if (_currentPlayingSong.value?.id == s.id) {
                    withContext(Dispatchers.Main) { _currentPlayingSong.value = updated }
                }
                refreshStorageInfo()
            } catch(e:Exception){ e.printStackTrace() }
        }
    }

    fun setEditingAccount(a: WebDavAccount?) { _accountToEdit.value = a }
    fun saveAccount(id: Long, n: String, u: String, us: String, p: String, s: Boolean, d: Int, cb: () -> Unit) {
        viewModelScope.launch {
            val acc = WebDavAccount(id, n, u, us, p, s, d)
            val sid = if (id == 0L) repository.addAccount(acc) else { repository.updateAccount(acc); id }
            refreshAccount(acc.copy(id = sid))
            cb()
        }
    }

    fun deleteAccount(account: WebDavAccount) { 
        viewModelScope.launch(Dispatchers.IO) { 
            // 获取该账户下所有的歌曲
            val songsToDelete = repository.getSongsByAccountId(account.id)
            val coversDir = File(getApplication<Application>().cacheDir, "covers")
            
            // 停止播放器
            val current = _currentPlayingSong.value
            if (current != null && current.accountId == account.id) {
                withContext(Dispatchers.Main) {
                    _playerController.value?.stop()
                    _playerController.value?.clearMediaItems()
                    _currentPlayingSong.value = null
                    isPlaying.value = false
                    playbackProgress.value = 0L
                    playbackDuration.value = 1L
                }
            }

            // 3. 清理播放队列
            val currentQueue = _currentPlaylist.value
            val newQueue = currentQueue.filter { it.accountId != account.id }
            if (newQueue.size != currentQueue.size) {
                _currentPlaylist.value = newQueue
                repository.updateQueue(newQueue)
            }

            // 穷举清理应该删除的文件
            songsToDelete.forEach { song ->
                try {
                    // 删除下载的音频文件
                    song.localPath?.let { path -> File(path).delete() }
                    
                    // 删除数据库记录的封面路径
                    song.artworkPath?.let { path -> File(path).delete() }
                    
                    // 重新计算并删除可能存在的关联封面
                    if (coversDir.exists()) {
                        // 删除单曲封面
                        File(coversDir, "song_${song.id}.jpg").delete()
                        
                        // 删除专辑图
                        if (song.album != "Unknown" && song.album != "Unknown Album") {
                            File(coversDir, "alb_${song.album.hashCode()}.jpg").delete()
                        }
                        
                        // 删除文件夹默认图
                        val folderPathHash = try {
                            URLDecoder.decode(song.remotePath.substringBeforeLast('/'), "UTF-8").hashCode()
                        } catch (e: Exception) { 0 }
                        File(coversDir, "dir_$folderPathHash.jpg").delete()
                    }
                    
                    // 删除在线播放缓存
                    cacheRepository.removeResource(song.id.toString())
                    
                } catch (e: Exception) { e.printStackTrace() }
            }

            // 删除数据库记录
            repository.deleteAccount(account)
            
            refreshStorageInfo() 
        } 
    }

    fun refreshAccount(a: WebDavAccount, deepScan: Boolean = false) { 
        if (_isSyncingMap.value[a.id] == true) return
        viewModelScope.launch {
            _isSyncingMap.value = _isSyncingMap.value.toMutableMap().apply { put(a.id, true) }
            try {
                repository.syncAccount(a, deepScan).collect { state ->
                    val msg = when(state) {
                        is MusicRepository.SyncState.Loading -> if(deepScan) "Deep Scanning..." else "Scanning..."
                        is MusicRepository.SyncState.Progress -> "Found ${state.count}"
                        is MusicRepository.SyncState.Success -> "Done (${state.count})"
                        is MusicRepository.SyncState.Error -> "Error: ${state.message}"
                        else -> ""
                    }
                    if(msg.isNotEmpty()) _accountSyncStatus.value = _accountSyncStatus.value.toMutableMap().apply { put(a.id, msg) }
                }
            } finally {
                _isSyncingMap.value = _isSyncingMap.value.toMutableMap().apply { put(a.id, false) }
            }
        }
    }

    fun removeFromQueue(song: Song) {
        // 禁止移除正在播放的歌曲
        if (_currentPlayingSong.value?.id == song.id) return

        val currentList = _currentPlaylist.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == song.id }

        if (index != -1) {
            _playerController.value?.removeMediaItem(index)

            currentList.removeAt(index)
            _currentPlaylist.value = currentList

            viewModelScope.launch {
                repository.removeFromPlaylist(3, song.id)
            }
        }
    }

    suspend fun testConnection(u: String, n: String, p: String, s: Boolean) = repository.testConnection(u, n, p, s)
    fun refreshStorageInfo() { viewModelScope.launch { cacheSize.value = cacheRepository.getCacheSize(); coverCacheSize.value = cacheRepository.getCoverCacheSize(getApplication<Application>().cacheDir) } }
    fun clearAudioCache() {
        cacheRepository.clearAudioCache()
        // -----清空所有在线播放产生的缓存: 待解决
        try {
            val cacheDir = File(getApplication<Application>().cacheDir, "media_cache")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
        } catch (e: Exception) { e.printStackTrace() }
        
        refreshStorageInfo() 
    }
    fun clearImageCache() { viewModelScope.launch(Dispatchers.IO) { cacheRepository.clearCoverCache(getApplication<Application>().cacheDir); repository.clearArtworkPaths(); refreshStorageInfo() } }
    fun clearDownloads() { 
        viewModelScope.launch { 
            cacheRepository.clearDownloads(getApplication<Application>().getExternalFilesDir("music_downloads"))
            repository.clearLocalPaths()
            repository.clearPlaylist(2)
            refreshStorageInfo() 
        } 
    }
    fun setThemeMode(mode: Int) { _themeMode.value = mode; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[intPreferencesKey("theme_mode")] = mode } } }

    private fun startProgressUpdater() {
        viewModelScope.launch {
            while (true) {
                _playerController.value?.let { 
                    if (it.isPlaying && !isBuffering.value && it.playbackState == Player.STATE_READY) {
                        val currentPos = it.currentPosition
                        playbackProgress.value = currentPos
                        playbackDuration.value = it.duration.coerceAtLeast(1L)
                        if (currentPos % 5000 < 1000) { 
                            savePlaybackState(currentPos)
                        }
                    }
                }
                delay(1000)
            }
        }
    }
    override fun onCleared() { 
        playbackProgress.value.let { savePlaybackState(it) }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared() 
    }

    data class AlbumData(val name: String, val artist: String, val artPath: String?, val count: Int, val songs: List<Song>)
    data class ArtistData(val name: String, val count: Int, val songs: List<Song>)
}