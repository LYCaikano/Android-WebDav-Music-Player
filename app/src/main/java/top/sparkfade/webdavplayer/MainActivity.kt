package top.sparkfade.webdavplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import top.sparkfade.webdavplayer.ui.screens.DetailScreen
import top.sparkfade.webdavplayer.ui.screens.LoginScreen
import top.sparkfade.webdavplayer.ui.screens.MainScreen
import top.sparkfade.webdavplayer.ui.screens.PlayerScreen
import top.sparkfade.webdavplayer.ui.screens.SettingsScreen
import top.sparkfade.webdavplayer.ui.theme.WebDavPlayerTheme
import top.sparkfade.webdavplayer.ui.viewmodel.MainViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val mainViewModel: MainViewModel = hiltViewModel()
            val themeMode by mainViewModel.themeMode.collectAsState()
            
            val startDestination by mainViewModel.startDestination.collectAsState()

            WebDavPlayerTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (startDestination == null) {
                        return@Surface
                    }

                    val rootNavController = rememberNavController()
                    val accountToEdit by mainViewModel.accountToEdit.collectAsState()

                    var isRootTransitioning by remember { mutableStateOf(false) }

                    DisposableEffect(rootNavController) {
                        val listener = NavController.OnDestinationChangedListener { _, _, _ ->
                            isRootTransitioning = true
                        }
                        rootNavController.addOnDestinationChangedListener(listener)
                        onDispose { rootNavController.removeOnDestinationChangedListener(listener) }
                    }

                    LaunchedEffect(isRootTransitioning) {
                        if (isRootTransitioning) {
                            delay(180) 
                            isRootTransitioning = false
                        }
                    }

                    var lastActionTime by remember { mutableLongStateOf(0L) }

                    fun canAction(): Boolean {
                        val now = System.currentTimeMillis()
                        return if (now - lastActionTime > 200) {
                            lastActionTime = now
                            true
                        } else false
                    }

                    val safeBack: () -> Unit = {
                        if (canAction()) rootNavController.popBackStack()
                    }

                    val safeNavigateToPlayer: () -> Unit = {
                        if (canAction()) {
                            rootNavController.navigate("player") { launchSingleTop = true }
                        }
                    }

                    val safeNavigateToDetail: (String, String) -> Unit = { type, id ->
                        if (canAction()) {
                            rootNavController.navigate("detail/$type/$id") { launchSingleTop = true }
                        }
                    }

                    val safeNavigateToSettings: () -> Unit = {
                        if (canAction()) {
                            rootNavController.navigate("settings") { launchSingleTop = true }
                        }
                    }

                    val fadeSpec = tween<Float>(200)

                    val playerSlideSpec = tween<IntOffset>(160)

                    Box(modifier = Modifier.fillMaxSize()) {
                        
                        NavHost(
                            navController = rootNavController,
                            startDestination = startDestination!!,
                            enterTransition = { fadeIn(animationSpec = fadeSpec) },
                            exitTransition = { fadeOut(animationSpec = fadeSpec) },
                            popEnterTransition = { fadeIn(animationSpec = fadeSpec) },
                            popExitTransition = { fadeOut(animationSpec = fadeSpec) }
                        ) {
                            composable("login",
                                popExitTransition = {
                                fadeOut(animationSpec = tween(250))// 单独的退出动画增强视觉效果
                            }) {
                                LoginScreen(
                                    accountToEdit = accountToEdit,
                                    onSaveAccount = { id, name, url, user, pass, ssl, depth -> 
                                        mainViewModel.saveAccount(id, name, url, user, pass, ssl, depth) {
                                            val previousRoute = rootNavController.previousBackStackEntry?.destination?.route
                                            if (previousRoute == "settings") {
                                                rootNavController.popBackStack()
                                            } else {
                                                rootNavController.navigate("main") {
                                                    popUpTo("login") { inclusive = true }
                                                }
                                            }
                                        }
                                    },
                                    onTestConnection = { url, user, pass, ssl ->
                                        mainViewModel.testConnection(url, user, pass, ssl)
                                    }
                                )
                            }
                            
                            composable("main") {
                                MainScreen(
                                    viewModel = mainViewModel,
                                    onNavigateToPlayer = safeNavigateToPlayer,
                                    onNavigateToSettings = safeNavigateToSettings,
                                    onNavigateToDetail = safeNavigateToDetail
                                )
                            }
                            
                            composable(
                                "detail/{type}/{id}",
                                arguments = listOf(
                                    navArgument("type") { type = NavType.StringType },
                                    navArgument("id") { type = NavType.StringType }
                                )
                            ) { backStackEntry ->
                                val type = backStackEntry.arguments?.getString("type") ?: ""
                                val id = backStackEntry.arguments?.getString("id") ?: ""
                                DetailScreen(
                                    type = type,
                                    idOrName = id,
                                    viewModel = mainViewModel,
                                    onBack = safeBack,
                                    onNavigateToPlayer = safeNavigateToPlayer
                                )
                            }
                            
                            composable("settings") {
                                SettingsScreen(
                                    viewModel = mainViewModel,
                                    onBack = safeBack,
                                    onEditAccount = { account ->
                                        mainViewModel.setEditingAccount(account)
                                        rootNavController.navigate("login") 
                                    }
                                )
                            }
                            
                            composable(
                                "player",
                                enterTransition = { 
                                    androidx.compose.animation.slideInVertically(
                                        initialOffsetY = { it }, 
                                        animationSpec = playerSlideSpec 
                                    ) 
                                },
                                exitTransition = { 
                                    androidx.compose.animation.slideOutVertically(
                                        targetOffsetY = { it }, 
                                        animationSpec = playerSlideSpec 
                                    ) 
                                },
                                popEnterTransition = { 
                                    androidx.compose.animation.slideInVertically(
                                        initialOffsetY = { it }, 
                                        animationSpec = playerSlideSpec 
                                    ) 
                                },
                                popExitTransition = { 
                                    androidx.compose.animation.slideOutVertically(
                                        targetOffsetY = { it }, 
                                        animationSpec = playerSlideSpec 
                                    ) 
                                }
                            ) {
                                PlayerScreen(
                                    viewModel = mainViewModel,
                                    onBack = safeBack
                                )
                            }
                        }

                        if (isRootTransitioning) {
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
                    }
                }
            }
        }
    }
}