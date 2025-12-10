package top.sparkfade.webdavplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import top.sparkfade.webdavplayer.ui.components.MarqueeText
import top.sparkfade.webdavplayer.ui.viewmodel.MainViewModel
import top.sparkfade.webdavplayer.ui.components.PlaylistSelectionDialog 
import top.sparkfade.webdavplayer.ui.components.SongDetailDialog
import top.sparkfade.webdavplayer.ui.components.SongListItem
import top.sparkfade.webdavplayer.ui.components.SongSelectionDialog
import top.sparkfade.webdavplayer.ui.components.formatTime
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val currentSong by viewModel.currentPlayingSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isBuffering by viewModel.isBuffering.collectAsState()
    val realProgress by viewModel.playbackProgress.collectAsState()
    val duration by viewModel.playbackDuration.collectAsState()
    val controller = viewModel.playerController.collectAsState().value
    val playbackMode by viewModel.playbackMode.collectAsState()
    
    val isFavorite by viewModel.isCurrentSongFavorite.collectAsState()
    val playlist by viewModel.currentPlaylist.collectAsState()
    val allSongs by viewModel.allSongs.collectAsState()

    var showPlaylist by remember { mutableStateOf(false) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showAddSongToQueueDialog by remember { mutableStateOf(false) }

    // 计算当前队列中已有的歌曲 ID，用于在弹窗中置灰
    val currentQueueIds = remember(playlist) {
        playlist.map { it.id }.toSet()
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    
    val playlistListState = rememberLazyListState()
    
    var isDragging by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableStateOf(0f) }

    if (!isDragging) {
        sliderPosition = realProgress.toFloat()
    }

    if (currentSong == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No Music") }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer() 
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // 1. Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 54.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Back")
                }
                IconButton(onClick = { showDetailDialog = true }) {
                    Icon(Icons.Default.Info, "Details") 
                }
            }

            // 2. Main Content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(Modifier.height(12.dp))

                // Cover
                Surface(
                    modifier = Modifier
                        .size(300.dp)
                        .graphicsLayer {
                            shadowElevation = 16.dp.toPx()
                            shape = RoundedCornerShape(16.dp)
                            clip = true
                        },
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (currentSong!!.artworkPath != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(File(currentSong!!.artworkPath!!))
                                    .crossfade(true)
                                    .size(1000, 1000) 
                                    .build(),
                                contentDescription = "Album Art",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.MusicNote, 
                                contentDescription = null, 
                                modifier = Modifier.size(80.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                
                Spacer(Modifier.height(32.dp))
                
                // Title Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 4.dp, start = 8.dp)
                    ) {
                        MarqueeText(
                            text = currentSong!!.title,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Start
                        )
                        MarqueeText(
                            text = currentSong!!.artist,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            textAlign = TextAlign.Start
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.offset(x = 12.dp)
                    ) {
                        IconButton(onClick = { viewModel.toggleFavorite() }) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Like",
                                tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Box {
                            var menuExpanded by remember { mutableStateOf(false) }
                            val isDownloaded = currentSong!!.localPath != null

                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Options",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                if (isDownloaded) {
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            menuExpanded = false
                                            viewModel.deleteLocalSong(currentSong!!)
                                        }
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("Download") },
                                        leadingIcon = { Icon(Icons.Default.Download, null) },
                                        onClick = {
                                            menuExpanded = false
                                            viewModel.downloadSong(currentSong!!)
                                        }
                                    )
                                }
                                
                                DropdownMenuItem(
                                    text = { Text("Add to Playlist") },
                                    leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) },
                                    onClick = {
                                        menuExpanded = false
                                        showAddToPlaylistDialog = true
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                
                // Slider
                Slider(
                    value = sliderPosition,
                    valueRange = 0f..duration.toFloat(),
                    onValueChange = { 
                        isDragging = true
                        sliderPosition = it 
                    },
                    onValueChangeFinished = {
                        viewModel.seekTo(sliderPosition.toLong())
                        isDragging = false
                    }
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(sliderPosition.toLong())) 
                    Text(formatTime(duration))
                }

                Spacer(Modifier.height(24.dp))

                // Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.togglePlaybackMode() }, modifier = Modifier.size(36.dp)) {
                        val icon = when(playbackMode) {
                            1 -> Icons.Default.Shuffle
                            2 -> Icons.Default.RepeatOne
                            else -> Icons.Default.Repeat
                        }
                        val tint = if (playbackMode == 0) LocalContentColor.current.copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary
                        Icon(icon, null, modifier = Modifier.size(28.dp), tint = tint)
                    }
                    
                    IconButton(onClick = { viewModel.skipToPrevious() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.SkipPrevious, null, modifier = Modifier.fillMaxSize())
                    }
                    
                    Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                        if (isBuffering) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                        } else {
                            IconButton(
                                onClick = { if (isPlaying) controller?.pause() else controller?.play() },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                    null,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                    
                    IconButton(onClick = { viewModel.skipToNext() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.SkipNext, null, modifier = Modifier.fillMaxSize())
                    }
                    
                    IconButton(onClick = { showPlaylist = true }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.List, 
                            "Playlist", 
                            modifier = Modifier.size(28.dp),
                            tint = LocalContentColor.current.copy(alpha = 0.6f)
                        )
                    }
                }
                
                Spacer(Modifier.height(48.dp))
            }
        }

        // Dialogs
        if (showDetailDialog) {
            SongDetailDialog(
                song = currentSong!!,
                duration = duration,
                onDismiss = { showDetailDialog = false }
            )
        }

        if (showAddToPlaylistDialog && currentSong != null) {
            PlaylistSelectionDialog(
                song = currentSong!!,
                viewModel = viewModel,
                onDismiss = { showAddToPlaylistDialog = false }
            )
        }

        if (showAddSongToQueueDialog) {
            SongSelectionDialog(
                allSongs = allSongs,
                disabledSongIds = currentQueueIds, // 传入当前队列ID集合
                onSongSelected = { song -> 
                    viewModel.addToQueue(song)
                    showAddSongToQueueDialog = false
                },
                onDismiss = { showAddSongToQueueDialog = false }
            )
        }

        if (showPlaylist) {
            LaunchedEffect(Unit) {
                val index = playlist.indexOfFirst { it.id == currentSong!!.id }
                if (index != -1) {
                    playlistListState.scrollToItem(index)
                }
            }

            ModalBottomSheet(
                onDismissRequest = { showPlaylist = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.5f) 
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "Current Queue (${playlist.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.align(Alignment.Center)
                        )
                        
                        IconButton(
                            onClick = { showAddSongToQueueDialog = true },
                            modifier = Modifier.align(Alignment.CenterEnd).size(24.dp)
                        ) {
                            Icon(Icons.Default.Add, "Add Song")
                        }
                    }
                    
                    Divider()
                    
                    if (playlist.isEmpty()) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text("Queue is empty")
                        }
                    } else {
                        LazyColumn(
                            state = playlistListState,
                            contentPadding = PaddingValues(
                                bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                            ),
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        ) {
                            items(playlist.size) { index ->
                                val song = playlist[index]
                                val isSelected = song.id == currentSong?.id
                                
                                ListItem(
                                    leadingContent = {
                                        Text(
                                            text = "${index + 1}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if(isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.width(30.dp), 
                                            textAlign = TextAlign.Center
                                        )
                                    },
                                    headlineContent = { 
                                        Text(
                                            text = song.title, 
                                            maxLines = 1, 
                                            color = if(isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        ) 
                                    },
                                    supportingContent = { Text(song.artist, maxLines = 1) },
                                    
                                    trailingContent = {
                                        if (!isSelected) {
                                            IconButton(onClick = { viewModel.removeFromQueue(song) }) {
                                                Icon(
                                                    imageVector = Icons.Default.Close, 
                                                    contentDescription = "Remove",
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.clickable { viewModel.skipToQueueItem(index) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}