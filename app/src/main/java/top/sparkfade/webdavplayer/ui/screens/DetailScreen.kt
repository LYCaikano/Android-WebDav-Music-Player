package top.sparkfade.webdavplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.sparkfade.webdavplayer.data.model.Song
import top.sparkfade.webdavplayer.ui.components.BatchSongSelectionDialog
import top.sparkfade.webdavplayer.ui.components.MarqueeText
import top.sparkfade.webdavplayer.ui.components.MiniPlayer
import top.sparkfade.webdavplayer.ui.components.PlaylistSelectionDialog 
import top.sparkfade.webdavplayer.ui.components.SongDetailDialog
import top.sparkfade.webdavplayer.ui.components.SongListItem
import top.sparkfade.webdavplayer.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    type: String,
    idOrName: String,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onNavigateToPlayer: () -> Unit
) {
    // 初始值为导航参数传入的值 (例如 "Unknown Album")
    var currentIdOrName by rememberSaveable { mutableStateOf(idOrName) }

    // 监听重命名事件
    LaunchedEffect(Unit) {
        viewModel.albumRenameFlow.collect { (oldName, newName) ->
            // 如果当前页面显示的正是被重命名的专辑
            if (type == "album" && currentIdOrName == oldName) {
                // 更新当前 ID，触发下面的 songsFlow 重新查询
                currentIdOrName = newName
            }
        }
    }

    // 1.4.3 数据源依赖于动态的 currentIdOrName
    val songsFlow = remember(type, currentIdOrName) { viewModel.getSongsForList(type, currentIdOrName) }
    val allOriginalSongs by songsFlow.collectAsState(initial = emptyList())
    val allSongsForBatch by viewModel.allSongs.collectAsState()

    val currentSong by viewModel.currentPlayingSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isBuffering by viewModel.isBuffering.collectAsState()

    var songForPlaylist by remember { mutableStateOf<Song?>(null) }
    var songForDetails by remember { mutableStateOf<Song?>(null) }
    var showBatchAddDialog by remember { mutableStateOf(false) }

    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val downloadProgressMap by viewModel.downloadProgressMap.collectAsState()

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) focusRequester.requestFocus()
    }

    val displaySongs = remember(allOriginalSongs, searchQuery) {
        if (searchQuery.isBlank()) allOriginalSongs
        else allOriginalSongs.filter { 
            it.title.contains(searchQuery, true) || 
            it.artist.contains(searchQuery, true) ||
            it.displayName.contains(searchQuery, true)
        }
    }

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        searchQuery = ""
    }

    // 标题使用动态的 currentIdOrName
    val title = when(type) {
        "album" -> currentIdOrName
        "artist" -> currentIdOrName
        "playlist" -> {
             val pl = viewModel.playlists.collectAsState().value.find { it.id.toString() == currentIdOrName }
             pl?.name ?: "Playlist"
        }
        else -> "Details"
    }

    val isUserPlaylist = type == "playlist" && (currentIdOrName.toLongOrNull() ?: 0) > 3

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (isSearchActive) {
                TopAppBar(
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search in list...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { 
                            isSearchActive = false 
                            searchQuery = ""
                        }) { Icon(Icons.Default.ArrowBack, "Close") }
                    },
                    actions = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, "Clear")
                            }
                        }
                    }
                )
            } else {
                TopAppBar(
                    // 这里的 Title 会随着 currentIdOrName 的改变而自动更新
                    title = { MarqueeText(text = title, style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                    },
                    actions = {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, "Search")
                        }
                        if (isUserPlaylist) {
                            IconButton(onClick = { showBatchAddDialog = true }) {
                                Icon(Icons.Default.Add, "Add Songs")
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (currentSong != null) {
                Column {
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                    MiniPlayer(
                        song = currentSong!!,
                        isPlaying = isPlaying,
                        isBuffering = isBuffering,
                        onTogglePlay = { 
                            if (isPlaying) viewModel.playerController.value?.pause() 
                            else viewModel.playerController.value?.play() 
                        },
                        onClick = onNavigateToPlayer
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 80.dp) 
        ) {
            items(displaySongs) { song ->
                SongListItem(
                    song = song,
                    downloadProgress = downloadProgressMap[song.id],
                    onClick = { 
                        viewModel.playSong(song, displaySongs)
                        onNavigateToPlayer()
                    },
                    onDownload = { viewModel.downloadSong(song) },
                    onDelete = { viewModel.deleteLocalSong(song) },
                    onAddToPlaylist = { songForPlaylist = song },
                    onViewDetails = { songForDetails = song }
                )
            }
            if (displaySongs.isEmpty() && allOriginalSongs.isNotEmpty()) {
                item {
                    Text(
                        text = "No results found",
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }

    if (songForPlaylist != null) {
        PlaylistSelectionDialog(
            song = songForPlaylist!!,
            viewModel = viewModel,
            onDismiss = { songForPlaylist = null }
        )
    }
    
    if (songForDetails != null) {
        SongDetailDialog(
            song = songForDetails!!,
            onDismiss = { songForDetails = null }
        )
    }

    if (showBatchAddDialog) {
        val currentIds = remember(allOriginalSongs) { allOriginalSongs.map { it.id }.toSet() }
        
        BatchSongSelectionDialog(
            allSongs = allSongsForBatch,
            existingSongIds = currentIds,
            onConfirm = { selectedIds ->
                val playlistId = idOrName.toLongOrNull()
                if (playlistId != null) {
                    viewModel.addSongsToPlaylist(playlistId, selectedIds)
                }
            },
            onDismiss = { showBatchAddDialog = false }
        )
    }
}