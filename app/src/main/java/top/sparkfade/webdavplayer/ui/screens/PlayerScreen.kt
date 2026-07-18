package top.sparkfade.webdavplayer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import top.sparkfade.webdavplayer.data.model.Song
import top.sparkfade.webdavplayer.ui.components.MarqueeText
import top.sparkfade.webdavplayer.ui.components.PlaylistBottomSheet
import top.sparkfade.webdavplayer.ui.components.PlaylistSelectionDialog
import top.sparkfade.webdavplayer.ui.components.SongDetailDialog
import top.sparkfade.webdavplayer.ui.components.SongSelectionDialog
import top.sparkfade.webdavplayer.ui.components.formatTime
import top.sparkfade.webdavplayer.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(viewModel: MainViewModel, onBack: () -> Unit) {
        val currentSong by viewModel.currentPlayingSong.collectAsState()
        val isFavorite by viewModel.isCurrentSongFavorite.collectAsState()
        val playlist by viewModel.currentPlaylist.collectAsState()
        val allSongs by viewModel.allSongs.collectAsState()

        var showPlaylist by remember { mutableStateOf(false) }
        var showDetailDialog by remember { mutableStateOf(false) }
        var showAddToPlaylistDialog by remember { mutableStateOf(false) }
        var showAddSongToQueueDialog by remember { mutableStateOf(false) }

        val song = currentSong
        if (song == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No Music")
                }
                return
        }

        Box(modifier = Modifier.fillMaxSize().graphicsLayer()) {
                Column(modifier = Modifier.fillMaxSize()) {

                        // 1. Toolbar
                        Row(
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .padding(
                                                        top = 54.dp,
                                                        bottom = 16.dp,
                                                        start = 16.dp,
                                                        end = 16.dp
                                                ),
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
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .weight(1f)
                                                .padding(horizontal = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                        ) {
                                Spacer(Modifier.height(12.dp))

                                PlayerCover(artworkPath = song.artworkPath)

                                Spacer(Modifier.height(32.dp))

                                PlayerTitleActions(
                                        song = song,
                                        isFavorite = isFavorite,
                                        onToggleFavorite = { viewModel.toggleFavorite() },
                                        onDownload = { viewModel.downloadSong(song) },
                                        onDelete = { viewModel.deleteLocalSong(song) },
                                        onAddToPlaylist = { showAddToPlaylistDialog = true }
                                )

                                Spacer(Modifier.height(24.dp))

                                // 进度区独立订阅高频流，避免整页每秒重组
                                PlayerProgressSection(
                                        viewModel = viewModel,
                                        isLocalSong = song.localPath != null
                                )

                                Spacer(Modifier.height(24.dp))

                                PlayerControls(
                                        viewModel = viewModel,
                                        onShowPlaylist = { showPlaylist = true }
                                )

                                Spacer(Modifier.height(48.dp))
                        }
                }

                // Dialogs
                if (showDetailDialog) {
                        val duration by viewModel.playbackDuration.collectAsState()
                        SongDetailDialog(
                                song = song,
                                duration = duration,
                                onDismiss = { showDetailDialog = false }
                        )
                }

                if (showAddToPlaylistDialog) {
                        PlaylistSelectionDialog(
                                song = song,
                                viewModel = viewModel,
                                onDismiss = { showAddToPlaylistDialog = false }
                        )
                }

                if (showAddSongToQueueDialog) {
                        // 仅在弹窗显示时计算队列 ID 集合
                        val currentQueueIds = remember(playlist) { playlist.map { it.id }.toSet() }
                        SongSelectionDialog(
                                allSongs = allSongs,
                                disabledSongIds = currentQueueIds,
                                onSongSelected = { selected ->
                                        viewModel.addToQueue(selected)
                                        showAddSongToQueueDialog = false
                                },
                                onDismiss = { showAddSongToQueueDialog = false }
                        )
                }

                if (showPlaylist) {
                        PlaylistBottomSheet(
                                viewModel = viewModel,
                                onDismiss = { showPlaylist = false },
                                onAddSong = { showAddSongToQueueDialog = true }
                        )
                }
        }
}

@Composable
private fun PlayerCover(artworkPath: String?) {
        val context = LocalContext.current
        Surface(
                modifier =
                        Modifier.size(300.dp).graphicsLayer {
                                shadowElevation = 16.dp.toPx()
                                shape = RoundedCornerShape(16.dp)
                                clip = true
                        },
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp)
        ) {
                Box(contentAlignment = Alignment.Center) {
                        if (artworkPath != null) {
                                AsyncImage(
                                        model =
                                                ImageRequest.Builder(context)
                                                        .data(File(artworkPath))
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
}

@Composable
private fun PlayerTitleActions(
        song: Song,
        isFavorite: Boolean,
        onToggleFavorite: () -> Unit,
        onDownload: () -> Unit,
        onDelete: () -> Unit,
        onAddToPlaylist: () -> Unit
) {
        Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
        ) {
                Column(modifier = Modifier.weight(1f).padding(end = 4.dp, start = 8.dp)) {
                        MarqueeText(
                                text = song.title,
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Start
                        )
                        MarqueeText(
                                text = song.artist,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                textAlign = TextAlign.Start
                        )
                }

                Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.offset(x = 12.dp)
                ) {
                        IconButton(onClick = onToggleFavorite) {
                                Icon(
                                        imageVector =
                                                if (isFavorite) Icons.Default.Favorite
                                                else Icons.Default.FavoriteBorder,
                                        contentDescription = "Like",
                                        tint =
                                                if (isFavorite) MaterialTheme.colorScheme.error
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                        }

                        Box {
                                var menuExpanded by remember { mutableStateOf(false) }
                                val isDownloaded = song.localPath != null

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
                                                        text = {
                                                                Text(
                                                                        "Delete",
                                                                        color =
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .error
                                                                )
                                                        },
                                                        leadingIcon = {
                                                                Icon(
                                                                        Icons.Default.Delete,
                                                                        null,
                                                                        tint =
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .error
                                                                )
                                                        },
                                                        onClick = {
                                                                menuExpanded = false
                                                                onDelete()
                                                        }
                                                )
                                        } else {
                                                DropdownMenuItem(
                                                        text = { Text("Download") },
                                                        leadingIcon = {
                                                                Icon(Icons.Default.Download, null)
                                                        },
                                                        onClick = {
                                                                menuExpanded = false
                                                                onDownload()
                                                        }
                                                )
                                        }

                                        DropdownMenuItem(
                                                text = { Text("Add to Playlist") },
                                                leadingIcon = {
                                                        Icon(Icons.Default.PlaylistAdd, null)
                                                },
                                                onClick = {
                                                        menuExpanded = false
                                                        onAddToPlaylist()
                                                }
                                        )
                                }
                        }
                }
        }
}

@Composable
private fun PlayerProgressSection(viewModel: MainViewModel, isLocalSong: Boolean) {
        val realProgress by viewModel.playbackProgress.collectAsState()
        val duration by viewModel.playbackDuration.collectAsState()
        val bufferedPosition by viewModel.bufferedPosition.collectAsState()

        var dragPosition by remember { mutableStateOf<Float?>(null) }
        val sliderValue = dragPosition ?: realProgress.toFloat()

        Column(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.fillMaxWidth()) {
                        // 底层 - 背景轨道
                        LinearProgressIndicator(
                                progress = 0f,
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .height(4.dp)
                                                .align(Alignment.Center)
                                                .padding(horizontal = 10.dp),
                                color = androidx.compose.ui.graphics.Color.Transparent,
                                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                        )
                        // 中层 - 缓冲进度 (本地歌曲不显示)
                        val safeBuffered =
                                if (!isLocalSong && duration > 0)
                                        (bufferedPosition.toFloat() / duration).coerceIn(0f, 1f)
                                else 0f
                        if (safeBuffered > 0f) {
                                LinearProgressIndicator(
                                        progress = safeBuffered,
                                        modifier =
                                                Modifier.fillMaxWidth()
                                                        .height(4.dp)
                                                        .align(Alignment.Center)
                                                        .padding(horizontal = 10.dp),
                                        color =
                                                MaterialTheme.colorScheme.primary.copy(
                                                        alpha = 0.25f
                                                ),
                                        trackColor = androidx.compose.ui.graphics.Color.Transparent,
                                )
                        }
                        // 上层 - 播放进度 Slider
                        Slider(
                                value = sliderValue,
                                valueRange = 0f..duration.toFloat(),
                                onValueChange = { dragPosition = it },
                                onValueChangeFinished = {
                                        dragPosition?.let { viewModel.seekTo(it.toLong()) }
                                        dragPosition = null
                                },
                                colors =
                                        SliderDefaults.colors(
                                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                                inactiveTrackColor =
                                                        androidx.compose.ui.graphics.Color
                                                                .Transparent
                                        )
                        )
                }

                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                ) {
                        Text(formatTime(sliderValue.toLong()))
                        Text(formatTime(duration))
                }
        }
}

@Composable
private fun PlayerControls(viewModel: MainViewModel, onShowPlaylist: () -> Unit) {
        val isPlaying by viewModel.isPlaying.collectAsState()
        val isBuffering by viewModel.isBuffering.collectAsState()
        val playbackMode by viewModel.playbackMode.collectAsState()

        Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
        ) {
                IconButton(
                        onClick = { viewModel.togglePlaybackMode() },
                        modifier = Modifier.size(36.dp)
                ) {
                        val icon =
                                when (playbackMode) {
                                        1 -> Icons.Default.Shuffle
                                        2 -> Icons.Default.RepeatOne
                                        else -> Icons.Default.Repeat
                                }
                        val tint =
                                if (playbackMode == 0)
                                        LocalContentColor.current.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.primary
                        Icon(icon, null, modifier = Modifier.size(28.dp), tint = tint)
                }

                IconButton(
                        onClick = { viewModel.skipToPrevious() },
                        modifier = Modifier.size(48.dp)
                ) {
                        Icon(Icons.Default.SkipPrevious, null, modifier = Modifier.fillMaxSize())
                }

                Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                        if (isBuffering) {
                                CircularProgressIndicator(
                                        modifier = Modifier.size(32.dp),
                                        strokeWidth = 3.dp
                                )
                        } else {
                                IconButton(
                                        onClick = { viewModel.togglePlayPause() },
                                        modifier = Modifier.fillMaxSize()
                                ) {
                                        Icon(
                                                if (isPlaying) Icons.Default.PauseCircle
                                                else Icons.Default.PlayCircle,
                                                null,
                                                modifier = Modifier.fillMaxSize()
                                        )
                                }
                        }
                }

                IconButton(onClick = { viewModel.skipToNext() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.SkipNext, null, modifier = Modifier.fillMaxSize())
                }

                IconButton(onClick = onShowPlaylist, modifier = Modifier.size(36.dp)) {
                        Icon(
                                Icons.Default.List,
                                "Playlist",
                                modifier = Modifier.size(28.dp),
                                tint = LocalContentColor.current.copy(alpha = 0.6f)
                        )
                }
        }
}
