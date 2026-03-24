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
import java.io.FileOutputStream
import java.net.URLDecoder
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
import top.sparkfade.webdavplayer.data.repository.FileDownloader
import top.sparkfade.webdavplayer.data.repository.MusicRepository
import top.sparkfade.webdavplayer.service.PlaybackService
import top.sparkfade.webdavplayer.utils.CurrentSession
import top.sparkfade.webdavplayer.utils.dataStore

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackSessionController(
        private val app: Application,
        private val scope: CoroutineScope,
        private val repository: MusicRepository,
        private val downloader: FileDownloader,
        private val cacheRepository: CacheRepository,
        private val allSongs: StateFlow<List<Song>>
) {
    private val _downloadProgressMap = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val downloadProgressMap = _downloadProgressMap.asStateFlow()

    private val _playerController = MutableStateFlow<Player?>(null)
    val playerController = _playerController.asStateFlow()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val _currentPlayingSong = MutableStateFlow<Song?>(null)
    val currentPlayingSong = _currentPlayingSong.asStateFlow()

    val isPlaying = MutableStateFlow(false)
    val isBuffering = MutableStateFlow(false)
    val playbackProgress = MutableStateFlow(0L)
    val bufferedPosition = MutableStateFlow(0L)
    val playbackDuration = MutableStateFlow(1L)
    val playbackMode = MutableStateFlow(0)
    private var bufferingTimeoutJob: Job? = null
    private var consecutiveErrorCount = 0

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

    init {
        initController(app)
        startProgressUpdater()
        refreshStorageInfo()
    }

    fun initializeSession(accounts: List<WebDavAccount>) {
        CurrentSession.clear()
        accounts.forEach { account ->
            val auth = okhttp3.Credentials.basic(account.username, account.password)
            CurrentSession.updateAuth(account.url, auth)
        }
    }

    fun restorePlaybackState() {
        scope.launch {
            val prefs = app.dataStore.data.first()
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
        val currentList = _currentPlaylist.value.toMutableList()
        if (currentList.none { it.id == song.id }) {
            _playerController.value?.addMediaItem(buildMediaItem(song))
            currentList.add(song)
            _currentPlaylist.value = currentList
            scope.launch { repository.addToPlaylist(3, song.id) }
        }
    }

    fun toggleFavorite() {
        val song = _currentPlayingSong.value ?: return
        val isFav = isCurrentSongFavorite.value
        scope.launch {
            if (isFav) repository.removeFromPlaylist(1, song.id)
            else repository.addToPlaylist(1, song.id)
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
        playbackProgress.value = 0L
        bufferedPosition.value = 0L
        playbackDuration.value = 1L
        isBuffering.value = true
        prepareMediaItems(controller, playlist, index, 0L, true)
    }

    fun skipToQueueItem(index: Int) {
        val controller = _playerController.value ?: return
        consecutiveErrorCount = 0
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
        consecutiveErrorCount = 0
        if (player.hasNextMediaItem()) {
            isBuffering.value = true
            player.seekToNext()
        } else {
            isBuffering.value = false
        }
    }

    fun skipToPrevious() {
        val player = _playerController.value ?: return
        consecutiveErrorCount = 0
        isBuffering.value = true
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
        val next = (playbackMode.value + 1) % 3
        playbackMode.value = next
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
        isBuffering.value = true
        playbackProgress.value = pos
    }

    fun downloadSong(song: Song) {
        if (_downloadProgressMap.value.containsKey(song.id)) return

        scope.launch {
            val skipSsl = repository.getAccountById(song.accountId)?.skipSsl == true
            repository.addToPlaylist(2, song.id)
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
                        repository.removeFromPlaylist(2, song.id)
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
                repository.removeFromPlaylist(2, song.id)
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
        val coversDir = File(app.cacheDir, "covers")

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

        val currentQueue = _currentPlaylist.value
        val newQueue = currentQueue.filter { it.accountId != account.id }
        if (newQueue.size != currentQueue.size) {
            replaceQueue(newQueue)
        }

        songsToDelete.forEach { song ->
            try {
                song.localPath?.let { path -> File(path).delete() }
                song.artworkPath?.let { path -> File(path).delete() }

                if (coversDir.exists()) {
                    File(coversDir, "song_${song.id}.jpg").delete()

                    if (song.album != "Unknown" && song.album != "Unknown Album") {
                        File(coversDir, "alb_${song.album.hashCode()}.jpg").delete()
                    }

                    val folderPathHash =
                            try {
                                URLDecoder.decode(song.remotePath.substringBeforeLast('/'), "UTF-8")
                                        .hashCode()
                            } catch (e: Exception) {
                                0
                            }
                    File(coversDir, "dir_$folderPathHash.jpg").delete()
                }

                cacheRepository.removeResource(song.id.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeFromQueue(song: Song) {
        if (_currentPlayingSong.value?.id == song.id) return

        val currentList = _currentPlaylist.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == song.id }

        if (index != -1) {
            _playerController.value?.removeMediaItem(index)
            currentList.removeAt(index)
            _currentPlaylist.value = currentList
            scope.launch { repository.removeFromPlaylist(3, song.id) }
        }
    }

    fun refreshStorageInfo() {
        scope.launch {
            cacheSize.value = cacheRepository.getCacheSize()
            coverCacheSize.value = cacheRepository.getCoverCacheSize(app.cacheDir)
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
            repository.clearPlaylist(2)

            val current = _currentPlayingSong.value
            if (current?.localPath != null) {
                val updated = current.copy(localPath = null, artworkPath = null)
                withContext(Dispatchers.Main) { _currentPlayingSong.value = updated }
            }

            refreshStorageInfo()
        }
    }

    fun onCleared() {
        playbackProgress.value.let { savePlaybackState(it) }
        controllerFuture?.let { MediaController.releaseFuture(it) }
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
                it[longPreferencesKey("last_song_id")] = song.id
                it[longPreferencesKey("last_pos")] = pos
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

        fun Song.isAvailableOffline(): Boolean = localPath != null || isCached

        var forward = currentIndex + 1
        var backward = currentIndex - 1
        while (forward < playlist.size || backward >= 0) {
            if (forward < playlist.size && playlist[forward].isAvailableOffline()) return forward
            if (backward >= 0 && playlist[backward].isAvailableOffline()) return backward
            forward++
            backward--
        }
        return -1
    }

    private fun skipToOfflineOrStop(player: Player): Boolean {
        if (isNetworkAvailable()) return false

        val currentIndex = player.currentMediaItemIndex
        val offlineIdx = findNearestOfflineIndex(currentIndex)
        if (offlineIdx != -1) {
            player.seekToDefaultPosition(offlineIdx)
            player.prepare()
            player.play()
            _currentPlayingSong.value = _currentPlaylist.value[offlineIdx]
        } else {
            player.stop()
            isBuffering.value = false
            _playbackError.trySend("无可用离线歌曲")
        }
        return true
    }

    private fun setupPlayerListener(player: Player?) {
        player?.addListener(
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        this@PlaybackSessionController.isPlaying.value = isPlaying
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        updateCurrentSongById(mediaItem?.mediaId)
                        playbackProgress.value = 0L
                        bufferedPosition.value = 0L
                        playbackDuration.value = 1L
                        isBuffering.value = player.playbackState == Player.STATE_BUFFERING

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
                            playbackDuration.value = player.duration.coerceAtLeast(1L)
                            isBuffering.value = false
                            consecutiveErrorCount = 0
                            bufferingTimeoutJob?.cancel()
                            bufferingTimeoutJob = null
                        } else if (playbackState == Player.STATE_ENDED) {
                            bufferingTimeoutJob?.cancel()
                            bufferingTimeoutJob = null
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
                        } else if (playbackState == Player.STATE_BUFFERING) {
                            isBuffering.value = true
                            bufferingTimeoutJob?.cancel()
                            bufferingTimeoutJob =
                                    scope.launch {
                                        delay(5000)
                                        if (isBuffering.value &&
                                                        player.playbackState == Player.STATE_BUFFERING
                                        ) {
                                            consecutiveErrorCount++
                                            val noNetwork = !isNetworkAvailable()
                                            _playbackError.trySend(
                                                    if (noNetwork) "No network connection"
                                                    else "Playback timeout"
                                            )
                                            withContext(Dispatchers.Main) {
                                                val maxRetries =
                                                        _currentPlaylist.value.size.coerceAtMost(3)
                                                if (consecutiveErrorCount >= maxRetries) {
                                                    player.stop()
                                                    isBuffering.value = false
                                                    consecutiveErrorCount = 0
                                                } else if (noNetwork) {
                                                    skipToOfflineOrStop(player)
                                                } else if (player.hasNextMediaItem()) {
                                                    player.seekToNext()
                                                } else {
                                                    player.stop()
                                                    isBuffering.value = false
                                                }
                                            }
                                        }
                                    }
                        } else {
                            isBuffering.value = false
                            bufferingTimeoutJob?.cancel()
                            bufferingTimeoutJob = null
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        consecutiveErrorCount++
                        val noNetwork = !isNetworkAvailable()
                        _playbackError.trySend(if (noNetwork) "No network connection" else "Playback error")
                        isBuffering.value = false
                        bufferingTimeoutJob?.cancel()
                        bufferingTimeoutJob = null
                        val maxRetries = _currentPlaylist.value.size.coerceAtMost(3)
                        if (consecutiveErrorCount >= maxRetries) {
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
        isPlaying.value = player?.isPlaying == true
        isBuffering.value = player?.playbackState == Player.STATE_BUFFERING
        playbackMode.value =
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
        var updatedSong = targetSong
        var dataChanged = false

        fun isValid(s: String?): Boolean {
            return !s.isNullOrEmpty() && s != "Unknown" && s != "Unknown Album"
        }

        fun isPlaceholder(album: String): Boolean {
            val folderName =
                    try {
                        val path = targetSong.remotePath.substringBeforeLast('/')
                        URLDecoder.decode(path, "UTF-8").substringAfterLast('/')
                    } catch (e: Exception) {
                        ""
                    }

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
            } catch (e: Exception) {
                false
            }
        }

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

        if ((!newIsPlaceholder || currentIsPlaceholder) &&
                        safeAlbum != targetSong.album &&
                        !safeAlbum.isNullOrEmpty()
        ) {
            updatedSong = updatedSong.copy(album = safeAlbum)
            dataChanged = true

            if (!newIsPlaceholder) {
                val folderPath =
                        try {
                            val path = targetSong.remotePath.substringBeforeLast('/')
                            URLDecoder.decode(path, "UTF-8")
                        } catch (e: Exception) {
                            ""
                        }
                val oldAlbum = targetSong.album

                if (currentIsPlaceholder) {
                    val siblings =
                            allSongs.value.filter {
                                val itFolder =
                                        try {
                                            URLDecoder.decode(
                                                    it.remotePath.substringBeforeLast('/'),
                                                    "UTF-8"
                                            )
                                        } catch (e: Exception) {
                                            ""
                                        }
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

        if (dataChanged || artworkData != null || targetSong.artworkPath == null) {
            try {
                var finalArtworkPath = updatedSong.artworkPath
                var artChanged = false

                val coversDir = File(app.cacheDir, "covers")
                if (!coversDir.exists()) coversDir.mkdirs()

                val folderPathHash =
                        try {
                            val path = targetSong.remotePath.substringBeforeLast('/')
                            URLDecoder.decode(path, "UTF-8").hashCode()
                        } catch (e: Exception) {
                            0
                        }

                val folderCoverFile = File(coversDir, "dir_$folderPathHash.jpg")

                val targetAlbumName = updatedSong.album
                val hasValidAlbum = !isPlaceholder(targetAlbumName)
                val albumCoverFile =
                        if (hasValidAlbum) File(coversDir, "alb_${targetAlbumName.hashCode()}.jpg")
                        else null

                if (artworkData != null) {
                    val tempFile = File(coversDir, "temp_${System.currentTimeMillis()}.tmp")
                    try {
                        val fos = FileOutputStream(tempFile)
                        fos.write(artworkData)
                        fos.flush()
                        fos.fd.sync()
                        fos.close()

                        if (isImageValid(tempFile)) {
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
                } else if (finalArtworkPath == null) {
                    if (albumCoverFile != null &&
                                    albumCoverFile.exists() &&
                                    isImageValid(albumCoverFile)
                    ) {
                        finalArtworkPath = albumCoverFile.absolutePath
                        artChanged = true
                    } else if (folderCoverFile.exists() && isImageValid(folderCoverFile)) {
                        finalArtworkPath = folderCoverFile.absolutePath
                        artChanged = true
                    }
                }

                if (dataChanged || artChanged) {
                    val finalSong =
                            updatedSong.copy(
                                    artworkPath = finalArtworkPath,
                                    isMetadataVerified = true
                            )

                    repository.updateSong(finalSong)

                    if (_currentPlayingSong.value?.id == finalSong.id) {
                        withContext(Dispatchers.Main) { _currentPlayingSong.value = finalSong }
                    }

                    val currentList = _currentPlaylist.value.toMutableList()
                    val index = currentList.indexOfFirst { it.id == finalSong.id }
                    if (index != -1) {
                        currentList[index] = finalSong
                        _currentPlaylist.value = currentList
                    }

                    if (artChanged) refreshStorageInfo()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
            while (true) {
                _playerController.value?.let { player ->
                    if (player.isPlaying &&
                                    !isBuffering.value &&
                                    player.playbackState == Player.STATE_READY
                    ) {
                        val currentPos = player.currentPosition
                        playbackProgress.value = currentPos
                        playbackDuration.value = player.duration.coerceAtLeast(1L)
                        if (currentPos % 5000 < 500) {
                            savePlaybackState(currentPos)
                        }
                    }
                    bufferedPosition.value = player.bufferedPosition
                }
                delay(500)
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
                app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
