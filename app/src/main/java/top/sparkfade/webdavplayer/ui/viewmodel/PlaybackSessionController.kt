package top.sparkfade.webdavplayer.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.sparkfade.webdavplayer.data.model.Song
import top.sparkfade.webdavplayer.data.model.WebDavAccount
import top.sparkfade.webdavplayer.data.repository.CacheRepository
import top.sparkfade.webdavplayer.data.repository.CoverArtStore
import top.sparkfade.webdavplayer.data.repository.FileDownloader
import top.sparkfade.webdavplayer.data.repository.MusicRepository
import top.sparkfade.webdavplayer.service.PlaybackService
import top.sparkfade.webdavplayer.utils.Constants
import top.sparkfade.webdavplayer.utils.CurrentSession
import top.sparkfade.webdavplayer.utils.dataStore

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackSessionController(
        private val app: Application,
        private val scope: CoroutineScope,
        private val repository: MusicRepository,
        private val downloader: FileDownloader,
        private val cacheRepository: CacheRepository,
        private val coverArtStore: CoverArtStore,
        private val allSongs: StateFlow<List<Song>>
) {
    private val _downloadProgressMap = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val downloadProgressMap = _downloadProgressMap.asStateFlow()

    private val _playerController = MutableStateFlow<Player?>(null)
    val playerController = _playerController.asStateFlow()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val _currentPlayingSong = MutableStateFlow<Song?>(null)
    val currentPlayingSong = _currentPlayingSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()
    private val _isBuffering = MutableStateFlow(false)
    val isBuffering = _isBuffering.asStateFlow()
    private val _playbackProgress = MutableStateFlow(0L)
    val playbackProgress = _playbackProgress.asStateFlow()
    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition = _bufferedPosition.asStateFlow()
    private val _playbackDuration = MutableStateFlow(1L)
    val playbackDuration = _playbackDuration.asStateFlow()
    private val _playbackMode = MutableStateFlow(0)
    val playbackMode = _playbackMode.asStateFlow()
    private var bufferingTimeoutJob: Job? = null
    private var consecutiveErrorCount = 0
    private var lastSavedPlaybackBucket = -1L

    private val _playbackError =
            kotlinx.coroutines.channels.Channel<String>(
                    kotlinx.coroutines.channels.Channel.CONFLATED
            )
    val playbackError = _playbackError.receiveAsFlow()

    private val _currentPlaylist = MutableStateFlow<List<Song>>(emptyList())
    val currentPlaylist = _currentPlaylist.asStateFlow()

    val isCurrentSongFavorite: StateFlow<Boolean> =
            _currentPlayingSong
                    .flatMapLatest { song ->
                        if (song == null) flowOf(false) else repository.isFavorite(song.id)
                    }
                    .stateIn(scope, SharingStarted.Lazily, false)

    val cacheSize = MutableStateFlow(0L)
    val coverCacheSize = MutableStateFlow(0L)

    private val _albumRenameChannel =
            kotlinx.coroutines.channels.Channel<Pair<String, String>>(
                    kotlinx.coroutines.channels.Channel.BUFFERED
            )
    val albumRenameFlow = _albumRenameChannel.receiveAsFlow()

    private val metadataMerger =
            MetadataMerger(
                    repository = repository,
                    coverArtStore = coverArtStore,
                    allSongs = allSongs,
                    onAlbumRenamed = { old, new -> _albumRenameChannel.trySend(old to new) },
                    onStorageChanged = { refreshStorageInfo() }
            )

    init {
        initController(app)
        startProgressUpdater()
        refreshStorageInfo()
    }

    fun initializeSession(accounts: List<WebDavAccount>) {
        CurrentSession.clear()
        accounts.forEach { account ->
            val auth = okhttp3.Credentials.basic(account.username, account.password)
            CurrentSession.updateAuth(account.url, auth, account.skipSsl)
        }
    }

    fun restorePlaybackState() {
        scope.launch {
            val prefs = app.dataStore.data.first()
            val lastSongId = prefs[longPreferencesKey(Constants.PREF_LAST_SONG_ID)] ?: -1L
            val lastPos = prefs[longPreferencesKey(Constants.PREF_LAST_POSITION)] ?: 0L

            if (lastSongId != -1L) {
                val queue = repository.getQueueSync()
                if (queue.isNotEmpty()) {
                    _currentPlaylist.value = queue
                }
                val song = repository.getSongById(lastSongId)
                if (song != null) {
                    _currentPlayingSong.value = song
                    _playbackProgress.value = lastPos
                    _playbackDuration.value = 1L
                    try {
                        withTimeout(10_000) {
                            while (_playerController.value == null) {
                                delay(100)
                            }
                        }
                    } catch (_: TimeoutCancellationException) {
                        return@launch
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

    fun addToQueue(song: Song) {
        val currentList = _currentPlaylist.value
        if (currentList.any { it.id == song.id }) return
        _playerController.value?.addMediaItem(buildMediaItem(song))
        _currentPlaylist.value = currentList + song
        scope.launch { repository.addToPlaylist(Constants.PLAYLIST_ID_QUEUE, song.id) }
    }

    fun toggleFavorite() {
        val song = _currentPlayingSong.value ?: return
        val isFav = isCurrentSongFavorite.value
        scope.launch {
            if (isFav) repository.removeFromPlaylist(Constants.PLAYLIST_ID_FAVORITES, song.id)
            else repository.addToPlaylist(Constants.PLAYLIST_ID_FAVORITES, song.id)
        }
    }

    fun playSong(song: Song, playlist: List<Song>) {
        val controller = _playerController.value ?: return
        consecutiveErrorCount = 0
        _currentPlaylist.value = playlist
        scope.launch { repository.updateQueue(playlist) }
        val index = playlist.indexOfFirst { it.id == song.id }
        if (index == -1) return
        _currentPlayingSong.value = song
        _playbackProgress.value = 0L
        _bufferedPosition.value = 0L
        _playbackDuration.value = 1L
        _isBuffering.value = true
        prepareMediaItems(controller, playlist, index, 0L, true)
    }

    fun skipToQueueItem(index: Int) {
        val controller = _playerController.value ?: return
        consecutiveErrorCount = 0
        if (index in 0 until _currentPlaylist.value.size) {
            val song = _currentPlaylist.value[index]
            _currentPlayingSong.value = song
            _isBuffering.value = true
            _playbackProgress.value = 0L
            controller.seekToDefaultPosition(index)
            controller.play()
        }
    }

    fun skipToNext() {
        val player = _playerController.value ?: return
        consecutiveErrorCount = 0
        if (player.hasNextMediaItem()) {
            _isBuffering.value = true
            player.seekToNext()
        } else {
            _isBuffering.value = false
        }
    }

    fun skipToPrevious() {
        val player = _playerController.value ?: return
        consecutiveErrorCount = 0
        _isBuffering.value = true
        player.seekToPrevious()
    }

    fun togglePlayPause() {
        val player = _playerController.value ?: return
        consecutiveErrorCount = 0
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.play()
        }
    }

    fun togglePlaybackMode() {
        val controller = _playerController.value ?: return
        val next = (_playbackMode.value + 1) % 3
        _playbackMode.value = next
        when (next) {
            0 -> {
                controller.shuffleModeEnabled = false
                controller.repeatMode = Player.REPEAT_MODE_ALL
            }
            1 -> {
                controller.shuffleModeEnabled = true
                controller.repeatMode = Player.REPEAT_MODE_ALL
            }
            2 -> {
                controller.shuffleModeEnabled = false
                controller.repeatMode = Player.REPEAT_MODE_ONE
            }
        }
    }

    fun seekTo(pos: Long) {
        val player = _playerController.value ?: return
        player.seekTo(pos)
        _isBuffering.value = true
        _playbackProgress.value = pos
    }

    fun downloadSong(song: Song) {
        if (_downloadProgressMap.value.containsKey(song.id)) return

        scope.launch {
            val skipSsl = repository.getAccountById(song.accountId)?.skipSsl == true
            repository.addToPlaylist(Constants.PLAYLIST_ID_DOWNLOADS, song.id)
            downloader.downloadSongFlow(app, song, skipSsl).collect { status ->
                when (status) {
                    is FileDownloader.DownloadStatus.Progress -> {
                        _downloadProgressMap.value =
                                _downloadProgressMap.value + (song.id to status.progress)
                    }
                    is FileDownloader.DownloadStatus.Success -> {
                        _downloadProgressMap.value = _downloadProgressMap.value - song.id
                        refreshStorageInfo()
                    }
                    is FileDownloader.DownloadStatus.Error -> {
                        _downloadProgressMap.value = _downloadProgressMap.value - song.id
                        repository.removeFromPlaylist(Constants.PLAYLIST_ID_DOWNLOADS, song.id)
                    }
                }
            }
        }
    }

    fun deleteLocalSong(song: Song) {
        scope.launch(Dispatchers.IO) {
            try {
                song.localPath?.let { File(it).delete() }
                val updated = song.copy(localPath = null)
                repository.updateSong(updated)
                repository.removeFromPlaylist(Constants.PLAYLIST_ID_DOWNLOADS, song.id)
                cacheRepository.removeResource(song.id.toString())

                if (_currentPlayingSong.value?.id == song.id) {
                    withContext(Dispatchers.Main) { _currentPlayingSong.value = updated }
                }
                refreshStorageInfo()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun prepareForAccountDeletion(account: WebDavAccount, songsToDelete: List<Song>) {
        val current = _currentPlayingSong.value
        if (current != null && current.accountId == account.id) {
            withContext(Dispatchers.Main) {
                _playerController.value?.stop()
                _playerController.value?.clearMediaItems()
                _currentPlayingSong.value = null
                _isPlaying.value = false
                _playbackProgress.value = 0L
                _playbackDuration.value = 1L
            }
        }

        val currentQueue = _currentPlaylist.value
        val newQueue = currentQueue.filter { it.accountId != account.id }
        if (newQueue.size != currentQueue.size) {
            replaceQueue(newQueue)
        }

        songsToDelete.forEach { song ->
            try {
                song.localPath?.let { path -> File(path).delete() }
                coverArtStore.deleteCoversFor(song)
                cacheRepository.removeResource(song.id.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeFromQueue(song: Song) {
        if (_currentPlayingSong.value?.id == song.id) return

        // 以播放器队列为事实源查找索引，避免与 _currentPlaylist 错位时删错歌
        val player = _playerController.value
        if (player != null) {
            val playerIndex =
                    (0 until player.mediaItemCount).firstOrNull {
                        player.getMediaItemAt(it).mediaId == song.id.toString()
                    }
            if (playerIndex != null) {
                player.removeMediaItem(playerIndex)
            }
        }

        val currentList = _currentPlaylist.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == song.id }
        if (index != -1) {
            currentList.removeAt(index)
            _currentPlaylist.value = currentList
        }
        scope.launch { repository.removeFromPlaylist(Constants.PLAYLIST_ID_QUEUE, song.id) }
    }

    fun refreshStorageInfo() {
        scope.launch(Dispatchers.IO) {
            val audio = cacheRepository.getCacheSize()
            val covers = cacheRepository.getCoverCacheSize(app.cacheDir)
            cacheSize.value = audio
            coverCacheSize.value = covers
        }
    }

    fun clearAudioCache() {
        cacheRepository.clearAudioCache()
        refreshStorageInfo()
    }

    fun clearImageCache() {
        scope.launch(Dispatchers.IO) {
            cacheRepository.clearCoverCache(app.cacheDir)
            repository.clearArtworkPaths()
            refreshStorageInfo()
        }
    }

    fun clearDownloads() {
        scope.launch(Dispatchers.IO) {
            allSongs.value.filter { it.localPath != null }.forEach { song ->
                cacheRepository.removeResource(song.id.toString())
            }

            cacheRepository.clearDownloads(app.getExternalFilesDir("music_downloads"))

            val coversDir = File(app.cacheDir, "covers")
            if (coversDir.exists()) {
                coversDir.deleteRecursively()
                coversDir.mkdirs()
            }

            repository.clearLocalPaths()
            repository.clearArtworkPaths()
            repository.clearPlaylist(Constants.PLAYLIST_ID_DOWNLOADS)

            val current = _currentPlayingSong.value
            if (current?.localPath != null) {
                val updated = current.copy(localPath = null, artworkPath = null)
                withContext(Dispatchers.Main) { _currentPlayingSong.value = updated }
            }

            refreshStorageInfo()
        }
    }

    fun onCleared() {
        val pos = _playbackProgress.value
        val song = _currentPlayingSong.value
        controllerFuture?.let { MediaController.releaseFuture(it) }
        // viewModelScope 已取消，使用 runBlocking 确保最后一次进度落盘
        if (song != null) {
            kotlinx.coroutines.runBlocking {
                app.dataStore.edit {
                    it[longPreferencesKey(Constants.PREF_LAST_SONG_ID)] = song.id
                    it[longPreferencesKey(Constants.PREF_LAST_POSITION)] = pos
                }
            }
        }
    }

    private suspend fun replaceQueue(newQueue: List<Song>) {
        _currentPlaylist.value = newQueue
        repository.updateQueue(newQueue)
        withContext(Dispatchers.Main) {
            _playerController.value?.let { controller ->
                val currentId = _currentPlayingSong.value?.id
                val retainedIndex = newQueue.indexOfFirst { it.id == currentId }
                val startIndex = retainedIndex.takeIf { it >= 0 } ?: 0
                val startPosition = if (retainedIndex >= 0) controller.currentPosition else 0L

                if (newQueue.isEmpty()) {
                    controller.clearMediaItems()
                } else {
                    prepareMediaItems(
                            controller = controller,
                            songs = newQueue,
                            startIndex = startIndex,
                            startPos = startPosition,
                            autoPlay = controller.isPlaying
                    )
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

    private fun prepareMediaItems(
            controller: Player,
            songs: List<Song>,
            startIndex: Int,
            startPos: Long,
            autoPlay: Boolean
    ) {
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

        val uri =
                if (item.localPath != null && File(item.localPath).exists()) {
                    Uri.fromFile(File(item.localPath))
                } else {
                    val safeUrl =
                            try {
                                item.remotePath.toHttpUrlOrNull()?.toString() ?: item.remotePath
                            } catch (e: Exception) {
                                item.remotePath
                            }
                    Uri.parse(safeUrl)
                }

        val mimeType =
                when {
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
                .setCustomCacheKey(item.id.toString())
                .build()
    }

    private fun savePlaybackState(pos: Long) {
        val song = _currentPlayingSong.value ?: return
        scope.launch {
            app.dataStore.edit {
                it[longPreferencesKey(Constants.PREF_LAST_SONG_ID)] = song.id
                it[longPreferencesKey(Constants.PREF_LAST_POSITION)] = pos
            }
        }
    }

    private fun initController(context: android.content.Context) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener(
                {
                    try {
                        val controller = controllerFuture?.get()
                        if (controller?.repeatMode == Player.REPEAT_MODE_OFF) {
                            controller.repeatMode = Player.REPEAT_MODE_ALL
                        }
                        _playerController.value = controller
                        setupPlayerListener(controller)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                },
                MoreExecutors.directExecutor()
        )
    }

    private fun findNearestOfflineIndex(currentIndex: Int): Int {
        val playlist = _currentPlaylist.value
        if (playlist.isEmpty()) return -1

        var forward = currentIndex + 1
        var backward = currentIndex - 1
        while (forward < playlist.size || backward >= 0) {
            if (forward < playlist.size && playlist[forward].localPath != null) return forward
            if (backward >= 0 && playlist[backward].localPath != null) return backward
            forward++
            backward--
        }
        return -1
    }

    private fun skipToOfflineOrStop(player: Player): Boolean {
        if (isNetworkAvailable()) return false

        val currentIndex = player.currentMediaItemIndex
        val offlineIdx = findNearestOfflineIndex(currentIndex)
        if (offlineIdx != -1 && offlineIdx < _currentPlaylist.value.size) {
            player.seekToDefaultPosition(offlineIdx)
            player.play()
            _currentPlayingSong.value = _currentPlaylist.value[offlineIdx]
        } else {
            player.stop()
            _isBuffering.value = false
            _playbackError.trySend("无可用离线歌曲")
        }
        return true
    }

    private fun setupPlayerListener(player: Player?) {
        player?.addListener(
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        this@PlaybackSessionController._isPlaying.value = isPlaying
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        updateCurrentSongById(mediaItem?.mediaId)
                        lastSavedPlaybackBucket = -1L
                        _playbackProgress.value = 0L
                        _bufferedPosition.value = 0L
                        _playbackDuration.value = 1L
                        _isBuffering.value = player.playbackState == Player.STATE_BUFFERING

                        val current = _currentPlayingSong.value
                        if (current != null) {
                            scope.launch(Dispatchers.IO) {
                                val sniffedSong = repository.sniffSongMetadata(current)

                                val newTitle = sniffedSong.title
                                val newArtist = sniffedSong.artist
                                val newAlbum = sniffedSong.album

                                fun shouldUpdate(newVal: String, oldVal: String): Boolean {
                                    return newVal.isNotEmpty() &&
                                            newVal != "Unknown" &&
                                            newVal != "Unknown Album" &&
                                            newVal != oldVal
                                }

                                if (shouldUpdate(newTitle, current.title) ||
                                                shouldUpdate(newArtist, current.artist) ||
                                                shouldUpdate(newAlbum, current.album)
                                ) {
                                    updateMetadataLogic(current, newTitle, newArtist, newAlbum, null)
                                } else if (!current.isMetadataVerified) {
                                    val verified = current.copy(isMetadataVerified = true)
                                    repository.updateSong(verified)
                                    withContext(Dispatchers.Main) {
                                        _currentPlayingSong.value = verified
                                    }
                                }
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            _playbackDuration.value = player.duration.coerceAtLeast(1L)
                            _isBuffering.value = false
                            consecutiveErrorCount = 0
                            bufferingTimeoutJob?.cancel()
                            bufferingTimeoutJob = null
                        } else if (playbackState == Player.STATE_ENDED) {
                            // REPEAT_MODE_ALL/ONE 由播放器自动处理循环，无需手动重播
                            bufferingTimeoutJob?.cancel()
                            bufferingTimeoutJob = null
                            _isBuffering.value = false
                        } else if (playbackState == Player.STATE_BUFFERING) {
                            _isBuffering.value = true
                            bufferingTimeoutJob?.cancel()
                            bufferingTimeoutJob =
                                    scope.launch {
                                        // 指数退避：连续失败时拉长等待
                                        delay(BUFFERING_TIMEOUT_MS * (consecutiveErrorCount + 1))
                                        if (_isBuffering.value &&
                                                        player.playbackState == Player.STATE_BUFFERING
                                        ) {
                                            consecutiveErrorCount++
                                            val noNetwork = !isNetworkAvailable()
                                            _playbackError.trySend(
                                                    if (noNetwork) "No network connection"
                                                    else "Playback timeout"
                                            )
                                            withContext(Dispatchers.Main) {
                                                if (consecutiveErrorCount >= MAX_RETRIES) {
                                                    player.stop()
                                                    _isBuffering.value = false
                                                    consecutiveErrorCount = 0
                                                } else if (noNetwork) {
                                                    skipToOfflineOrStop(player)
                                                } else if (player.hasNextMediaItem()) {
                                                    player.seekToNext()
                                                } else {
                                                    player.stop()
                                                    _isBuffering.value = false
                                                }
                                            }
                                        }
                                    }
                        } else {
                            _isBuffering.value = false
                            bufferingTimeoutJob?.cancel()
                            bufferingTimeoutJob = null
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        consecutiveErrorCount++
                        val noNetwork = !isNetworkAvailable()
                        _playbackError.trySend(if (noNetwork) "No network connection" else "Playback error")
                        _isBuffering.value = false
                        bufferingTimeoutJob?.cancel()
                        bufferingTimeoutJob = null
                        if (consecutiveErrorCount >= MAX_RETRIES) {
                            player.stop()
                            consecutiveErrorCount = 0
                        } else if (noNetwork) {
                            skipToOfflineOrStop(player)
                        } else if (player.hasNextMediaItem()) {
                            player.seekToNext()
                            player.prepare()
                            player.play()
                        } else {
                            player.stop()
                        }
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        val current = _currentPlayingSong.value ?: return
                        scope.launch(Dispatchers.IO) {
                            updateMetadataLogic(
                                    current,
                                    mediaMetadata.title?.toString(),
                                    mediaMetadata.artist?.toString(),
                                    mediaMetadata.albumTitle?.toString(),
                                    mediaMetadata.artworkData
                            )
                        }
                    }
                }
        )

        updateCurrentSongById(player?.currentMediaItem?.mediaId)
        _isPlaying.value = player?.isPlaying == true
        _isBuffering.value = player?.playbackState == Player.STATE_BUFFERING
        _playbackMode.value =
                if (player?.shuffleModeEnabled == true) 1
                else if (player?.repeatMode == Player.REPEAT_MODE_ONE) 2 else 0
    }

    private suspend fun updateMetadataLogic(
            targetSong: Song,
            realTitle: String?,
            realArtist: String?,
            realAlbum: String?,
            artworkData: ByteArray?
    ) {
        val finalSong =
                metadataMerger.merge(targetSong, realTitle, realArtist, realAlbum, artworkData)
                        ?: return

        if (_currentPlayingSong.value?.id == finalSong.id) {
            withContext(Dispatchers.Main) { _currentPlayingSong.value = finalSong }
        }

        val currentList = _currentPlaylist.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == finalSong.id }
        if (index != -1) {
            currentList[index] = finalSong
            _currentPlaylist.value = currentList
        }
    }

    private fun updateCurrentSongById(mediaId: String?) {
        if (mediaId == null) return
        val id = mediaId.toLongOrNull() ?: return
        val song = allSongs.value.find { it.id == id }
        if (song != null) _currentPlayingSong.value = song
    }

    private fun startProgressUpdater() {
        scope.launch {
            // 仅在播放期间激活轮询，暂停时休眠以省电
            _isPlaying.flatMapLatest { playing ->
                flow<Unit> {
                    while (true) {
                        _playerController.value?.let { player ->
                            if (playing &&
                                            !_isBuffering.value &&
                                            player.playbackState == Player.STATE_READY
                            ) {
                                val currentPos = player.currentPosition
                                _playbackProgress.value = currentPos
                                _playbackDuration.value = player.duration.coerceAtLeast(1L)
                                val bucket = currentPos / 5000
                                if (bucket != lastSavedPlaybackBucket) {
                                    lastSavedPlaybackBucket = bucket
                                    savePlaybackState(currentPos)
                                }
                            }
                            _bufferedPosition.value = player.bufferedPosition
                        }
                        delay(500)
                    }
                }
            }.collect()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
                app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val BUFFERING_TIMEOUT_MS = 5000L
        private const val MAX_RETRIES = 3
    }
}
