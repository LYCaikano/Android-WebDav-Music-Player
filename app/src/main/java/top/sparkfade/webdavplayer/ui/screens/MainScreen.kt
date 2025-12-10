package top.sparkfade.webdavplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import top.sparkfade.webdavplayer.ui.components.MiniPlayer
import top.sparkfade.webdavplayer.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToPlayer: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDetail: (String, String) -> Unit
) {
    val tabNavController = rememberNavController()
    val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val currentSong by viewModel.currentPlayingSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isBuffering by viewModel.isBuffering.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    val scanningStatus by viewModel.scanningStatus.collectAsState()
    // 监听所有歌曲，用于判断是否是"首次/空库"状态
    val allSongs by viewModel.allSongs.collectAsState()

    var isTabTransitioning by remember { mutableStateOf(false) }
    LaunchedEffect(currentRoute) {
        if (currentRoute != null) {
            isTabTransitioning = true
            delay(100)
            isTabTransitioning = false
        }
    }

    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            focusRequester.requestFocus()
        } else {
            focusManager.clearFocus()
        }
    }

    val closeSearch = {
        isSearchActive = false
        viewModel.onSearchQueryChanged("")
        focusManager.clearFocus()
    }

    BackHandler(enabled = isSearchActive) {
        closeSearch()
    }

    Scaffold(
        topBar = {
            if (isSearchActive) {
                TopAppBar(
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.onSearchQueryChanged(it) },
                            placeholder = { Text("Search...") },
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
                        IconButton(onClick = closeSearch) { Icon(Icons.Default.ArrowBack, "Back") }
                    },
                    actions = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(Icons.Default.Close, "Clear")
                            }
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(
                        when(currentRoute) {
                            "tab_albums" -> "Albums"
                            "tab_artists" -> "Artists"
                            "tab_playlists" -> "Playlists"
                            else -> "Songs"
                        }
                    ) },
                    actions = {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, "Search")
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, "Settings")
                        }
                    }
                )
            }
        },
        bottomBar = {
            Column(modifier = Modifier.animateContentSize()) {
                if (currentSong != null) {
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                    MiniPlayer(
                        song = currentSong!!,
                        isPlaying = isPlaying,
                        isBuffering = isBuffering,
                        onTogglePlay = { if (isPlaying) viewModel.playerController.value?.pause() else viewModel.playerController.value?.play() },
                        onClick = onNavigateToPlayer
                    )
                }

                NavigationBar(modifier = Modifier.height(80.dp)) {
                    val items = listOf("Songs", "Albums", "Artists", "Playlists")
                    val icons = listOf(Icons.Default.MusicNote, Icons.Default.Album, Icons.Default.Person, Icons.Default.QueueMusic)

                    items.forEachIndexed { index, item ->
                        val route = "tab_${item.lowercase()}"
                        NavigationBarItem(
                            icon = { Icon(icons[index], contentDescription = item, modifier = Modifier.size(26.dp)) },
                            selected = currentRoute == route,
                            onClick = {
                                if (currentRoute != route) {
                                    tabNavController.navigate(route) {
                                        popUpTo(tabNavController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (currentRoute == "tab_playlists") {
                FloatingActionButton(onClick = { showCreatePlaylistDialog = true }) {
                    Icon(Icons.Default.Add, "Create")
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            NavHost(
                navController = tabNavController,
                startDestination = "tab_songs",
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { fadeOut() }
            ) {
                composable("tab_songs") {
                    BackHandler(enabled = isSearchActive, onBack = closeSearch)
                    LibraryScreen(viewModel, onNavigateToPlayer, onNavigateToSettings)
                }
                composable("tab_albums") {
                    BackHandler(enabled = isSearchActive, onBack = closeSearch)
                    AlbumsPage(viewModel) { onNavigateToDetail("album", it) }
                }
                composable("tab_artists") {
                    BackHandler(enabled = isSearchActive, onBack = closeSearch)
                    ArtistsPage(viewModel) { onNavigateToDetail("artist", it) }
                }
                composable("tab_playlists") {
                    BackHandler(enabled = isSearchActive, onBack = closeSearch)
                    PlaylistsPage(viewModel) { onNavigateToDetail("playlist", it) }
                }
            }

            if (isTabTransitioning) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(99f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { }
                        )
                )
            }

            // 1.4.3 扫描逻辑更新
            if (scanningStatus != null && allSongs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(100f)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { /* 阻断点击 */ }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        shadowElevation = 8.dp,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                strokeWidth = 4.dp
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Initializing Library",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = scanningStatus ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreatePlaylistDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("New Playlist") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Name") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        viewModel.createPlaylist(newName)
                        showCreatePlaylistDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreatePlaylistDialog = false }) { Text("Cancel") } }
        )
    }
}