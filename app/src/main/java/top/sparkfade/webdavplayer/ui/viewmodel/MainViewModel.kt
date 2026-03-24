package top.sparkfade.webdavplayer.ui.viewmodel

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.sparkfade.webdavplayer.data.model.Playlist
import top.sparkfade.webdavplayer.data.model.Song
import top.sparkfade.webdavplayer.data.model.WebDavAccount
import top.sparkfade.webdavplayer.data.repository.CacheRepository
import top.sparkfade.webdavplayer.data.repository.FileDownloader
import top.sparkfade.webdavplayer.data.repository.MusicRepository
import top.sparkfade.webdavplayer.utils.dataStore

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel
@Inject
constructor(
        app: Application,
        private val repository: MusicRepository,
        downloader: FileDownloader,
        cacheRepository: CacheRepository
) : AndroidViewModel(app) {

    private val _startDestination = MutableStateFlow<String?>(null)
    val startDestination = _startDestination.asStateFlow()

    val allSongs: StateFlow<List<Song>> =
            repository.allSongs.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allAccounts: StateFlow<List<WebDavAccount>> =
            repository.allAccounts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val playbackSession =
            PlaybackSessionController(
                    app = app,
                    scope = viewModelScope,
                    repository = repository,
                    downloader = downloader,
                    cacheRepository = cacheRepository,
                    allSongs = allSongs
            )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    val downloadProgressMap = playbackSession.downloadProgressMap

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    val filteredSongs: StateFlow<List<Song>> =
            combine(allSongs, _searchQuery) { songs, query ->
                        if (query.isBlank()) songs else songs.filter { matchSearch(it, query) }
                    }
                    .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val albums: StateFlow<List<AlbumData>> =
            combine(allSongs, _searchQuery) { songs, query ->
                        songs.groupBy { it.album }
                                .map { (name, list) ->
                                    AlbumData(
                                            name = name,
                                            artist = list.firstOrNull()?.artist ?: "Unknown",
                                            artPath = list.find { it.artworkPath != null }?.artworkPath,
                                            count = list.size,
                                            songs = list
                                    )
                                }
                                .filter { it.name != "Unknown" }
                                .filter {
                                    query.isBlank() ||
                                            it.name.contains(query, ignoreCase = true) ||
                                            it.artist.contains(query, ignoreCase = true)
                                }
                                .sortedBy { it.name }
                    }
                    .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val artists: StateFlow<List<ArtistData>> =
            combine(allSongs, _searchQuery) { songs, query ->
                        val artistMap = mutableMapOf<String, MutableList<Song>>()

                        songs.forEach { song ->
                            val splitNames =
                                    song.artist.split(artistSeparatorRegex)
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
                                .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                                .sortedBy { it.name }
                    }
                    .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val playlists: StateFlow<List<Playlist>> =
            combine(repository.allPlaylists, _searchQuery) { list, query ->
                        val visibleList = list.filter { it.id != 3L }
                        if (query.isBlank()) visibleList
                        else visibleList.filter { it.name.contains(query, ignoreCase = true) }
                    }
                    .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val playerController = playbackSession.playerController
    val currentPlayingSong = playbackSession.currentPlayingSong
    val isPlaying = playbackSession.isPlaying
    val isBuffering = playbackSession.isBuffering
    val playbackProgress = playbackSession.playbackProgress
    val bufferedPosition = playbackSession.bufferedPosition
    val playbackDuration = playbackSession.playbackDuration
    val playbackMode = playbackSession.playbackMode
    val playbackError = playbackSession.playbackError
    val currentPlaylist = playbackSession.currentPlaylist
    val isCurrentSongFavorite = playbackSession.isCurrentSongFavorite
    val cacheSize = playbackSession.cacheSize
    val coverCacheSize = playbackSession.coverCacheSize
    val albumRenameFlow = playbackSession.albumRenameFlow

    private val _themeMode = MutableStateFlow(0)
    val themeMode = _themeMode.asStateFlow()
    private val _accountToEdit = MutableStateFlow<WebDavAccount?>(null)
    val accountToEdit = _accountToEdit.asStateFlow()
    private val _accountSyncStatus = MutableStateFlow<Map<Long, String>>(emptyMap())
    val accountSyncStatus = _accountSyncStatus.asStateFlow()
    private val _isSyncingMap = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val isSyncingMap = _isSyncingMap.asStateFlow()

    val scanningStatus: StateFlow<String?> =
            combine(_isSyncingMap, _accountSyncStatus) { syncingMap, statusMap ->
                        val syncingId = syncingMap.entries.find { it.value }?.key
                        if (syncingId != null) statusMap[syncingId] else null
                    }
                    .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val artistSeparatorRegex =
            Regex("[,;&/|、＆，；]|\\s+(?i)(feat\\.?|ft\\.?|vs\\.?|cv\\.?)\\s+")

    init {
        viewModelScope.launch {
            repository.initDefaultPlaylists()
            val prefs = getApplication<Application>().dataStore.data.first()
            _themeMode.value = prefs[intPreferencesKey("theme_mode")] ?: 0
            val accounts = repository.allAccounts.first()
            playbackSession.initializeSession(accounts)
            if (accounts.isNotEmpty()) {
                _startDestination.value = "main"
                playbackSession.restorePlaybackState()
            } else {
                _startDestination.value = "login"
            }
        }
    }

    fun getPlaylistsWithStatus(songId: Long): Flow<List<Pair<Playlist, Boolean>>> {
        return combine(repository.allPlaylists, repository.getPlaylistIdsForSong(songId)) {
                all,
                currentIds ->
            all.filter { it.id != 2L && it.id != 3L }.map { playlist ->
                playlist to currentIds.contains(playlist.id)
            }
        }
    }

    fun addToQueue(song: Song) = playbackSession.addToQueue(song)

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

    fun toggleFavorite() = playbackSession.toggleFavorite()

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
            "artist" ->
                    allSongs.map { list ->
                        list.filter { song ->
                            val splitNames = song.artist.split(artistSeparatorRegex).map { it.trim() }
                            splitNames.any { it.equals(idOrName, ignoreCase = true) } ||
                                    song.artist.equals(idOrName, ignoreCase = true)
                        }
                    }
            "playlist" -> repository.getPlaylistSongs(idOrName.toLongOrNull() ?: -1L)
            else -> flowOf(emptyList())
        }
    }

    fun playSong(song: Song, playlist: List<Song>) = playbackSession.playSong(song, playlist)

    fun skipToQueueItem(index: Int) = playbackSession.skipToQueueItem(index)

    fun skipToNext() = playbackSession.skipToNext()

    fun skipToPrevious() = playbackSession.skipToPrevious()

    fun togglePlayPause() = playbackSession.togglePlayPause()

    fun togglePlaybackMode() = playbackSession.togglePlaybackMode()

    fun seekTo(pos: Long) = playbackSession.seekTo(pos)

    fun downloadSong(song: Song) = playbackSession.downloadSong(song)

    fun deleteLocalSong(song: Song) = playbackSession.deleteLocalSong(song)

    fun setEditingAccount(account: WebDavAccount?) {
        _accountToEdit.value = account
    }

    fun saveAccount(
            id: Long,
            name: String,
            url: String,
            username: String,
            password: String,
            skipSsl: Boolean,
            scanDepth: Int,
            callback: () -> Unit
    ) {
        viewModelScope.launch {
            val account = WebDavAccount(id, name, url, username, password, skipSsl, scanDepth)
            val savedId =
                    if (id == 0L) repository.addAccount(account)
                    else {
                        repository.updateAccount(account)
                        id
                    }
            val savedAccount = account.copy(id = savedId)
            playbackSession.initializeSession(repository.getAllAccountsList())
            refreshAccount(savedAccount)
            callback()
        }
    }

    fun deleteAccount(account: WebDavAccount) {
        viewModelScope.launch(Dispatchers.IO) {
            val songsToDelete = repository.getSongsByAccountId(account.id)
            playbackSession.prepareForAccountDeletion(account, songsToDelete)
            repository.deleteAccount(account)
            playbackSession.initializeSession(repository.getAllAccountsList())
            refreshStorageInfo()
        }
    }

    fun refreshAccount(account: WebDavAccount, deepScan: Boolean = false) {
        if (_isSyncingMap.value[account.id] == true) return
        viewModelScope.launch {
            _isSyncingMap.value = _isSyncingMap.value.toMutableMap().apply { put(account.id, true) }
            try {
                repository.syncAccount(account, deepScan).collect { state ->
                    val message =
                            when (state) {
                                is MusicRepository.SyncState.Loading ->
                                        if (deepScan) "Deep Scanning..." else "Scanning..."
                                is MusicRepository.SyncState.Progress -> "Found ${state.count}"
                                is MusicRepository.SyncState.Success -> "Done (${state.count})"
                                is MusicRepository.SyncState.Error -> "Error: ${state.message}"
                                else -> ""
                            }
                    if (message.isNotEmpty()) {
                        _accountSyncStatus.value =
                                _accountSyncStatus.value.toMutableMap().apply {
                                    put(account.id, message)
                                }
                    }
                }
            } finally {
                _isSyncingMap.value = _isSyncingMap.value.toMutableMap().apply { put(account.id, false) }
            }
        }
    }

    fun removeFromQueue(song: Song) = playbackSession.removeFromQueue(song)

    suspend fun testConnection(url: String, username: String, password: String, skipSsl: Boolean) =
            repository.testConnection(url, username, password, skipSsl)

    fun refreshStorageInfo() = playbackSession.refreshStorageInfo()

    fun clearAudioCache() = playbackSession.clearAudioCache()

    fun clearImageCache() = playbackSession.clearImageCache()

    fun clearDownloads() = playbackSession.clearDownloads()

    fun setThemeMode(mode: Int) {
        _themeMode.value = mode
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit {
                it[intPreferencesKey("theme_mode")] = mode
            }
        }
    }

    private fun matchSearch(song: Song, query: String): Boolean {
        return song.title.contains(query, ignoreCase = true) ||
                song.artist.contains(query, ignoreCase = true) ||
                song.displayName.contains(query, ignoreCase = true)
    }

    override fun onCleared() {
        playbackSession.onCleared()
        super.onCleared()
    }

    data class AlbumData(
            val name: String,
            val artist: String,
            val artPath: String?,
            val count: Int,
            val songs: List<Song>
    )

    data class ArtistData(val name: String, val count: Int, val songs: List<Song>)
}
