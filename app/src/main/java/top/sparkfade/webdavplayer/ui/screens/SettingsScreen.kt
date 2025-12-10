package top.sparkfade.webdavplayer.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.sparkfade.webdavplayer.data.model.WebDavAccount
import top.sparkfade.webdavplayer.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onEditAccount: (WebDavAccount?) -> Unit
) {
    val context = LocalContext.current
    val cacheSize by viewModel.cacheSize.collectAsState()
    val coverCacheSize by viewModel.coverCacheSize.collectAsState() // [新增] 监听封面缓存
    val accounts by viewModel.allAccounts.collectAsState(initial = emptyList())
    val syncStatusMap by viewModel.accountSyncStatus.collectAsState()
    val isSyncingMap by viewModel.isSyncingMap.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()

    var deepScanEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshStorageInfo()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            
            Text("Appearance", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ThemeOption("System", themeMode == 0) { viewModel.setThemeMode(0) }
                ThemeOption("Light", themeMode == 1) { viewModel.setThemeMode(1) }
                ThemeOption("Dark", themeMode == 2) { viewModel.setThemeMode(2) }
            }

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically, 
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Text(
                    text = "Accounts",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onEditAccount(null) }) {
                    Icon(Icons.Default.Add, "Add Account")
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Deep Scan Mode",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Fetch accurate Title, Artist, Album & Cover from files. Takes longer and consumes data.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = deepScanEnabled,
                        onCheckedChange = { deepScanEnabled = it }
                    )
                }
            }

            if (accounts.isEmpty()) {
                Text("No accounts added", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            } else {
                accounts.forEach { account ->
                    val status = syncStatusMap[account.id] ?: ""
                    val isSyncing = isSyncingMap[account.id] == true
                    
                    SettingsAccountItem(
                        account = account,
                        status = status,
                        isSyncing = isSyncing,
                        onRefresh = { viewModel.refreshAccount(account, deepScan = deepScanEnabled) },
                        onEdit = { onEditAccount(account) },
                        onDelete = { viewModel.deleteAccount(account) }
                    )
                }
            }

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Downloads", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
            SettingsItem(
                icon = Icons.Default.Delete,
                title = "Clear All Downloads",
                subtitle = "Delete all downloaded files",
                onClick = { viewModel.clearDownloads() },
                titleColor = MaterialTheme.colorScheme.error
            )

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Cache", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
            SettingsItem(
                icon = Icons.Default.MusicNote,
                title = "Clear Audio Cache",
                subtitle = "Used: ${Formatter.formatFileSize(context, cacheSize)} (ExoPlayer)",
                onClick = { viewModel.clearAudioCache() }
            )
            SettingsItem(
                icon = Icons.Default.Image,
                title = "Clear Cover Images",
                // [修改] 显示具体大小
                subtitle = "Used: ${Formatter.formatFileSize(context, coverCacheSize)} (Album Art)",
                onClick = { viewModel.clearImageCache() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeOption(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        leadingIcon = if (selected) {
            { 
                val icon = when (text) {
                    "Light" -> Icons.Default.WbSunny
                    "Dark" -> Icons.Default.DarkMode
                    else -> Icons.Default.Settings
                }
                Icon(icon, null, modifier = Modifier.size(16.dp)) 
            }
        } else null
    )
}

@Composable
fun SettingsAccountItem(
    account: WebDavAccount,
    status: String,
    isSyncing: Boolean,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(text = account.name.ifEmpty { "Unnamed" }, style = MaterialTheme.typography.bodyLarge)
            Text(text = account.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status.isNotEmpty()) {
                Text(text = status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        
        if (isSyncing) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(4.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh) { 
                Icon(Icons.Default.Refresh, "Refresh") 
            }
        }

        IconButton(onClick = onDelete, enabled = !isSyncing) { 
            Icon(Icons.Default.Delete, "Delete", tint = if(isSyncing) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error) 
        }
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) trailing()
    }
}