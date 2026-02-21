package top.sparkfade.webdavplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.sparkfade.webdavplayer.data.model.Song

@Composable
fun MiniPlayer(
        song: Song,
        isPlaying: Boolean,
        isBuffering: Boolean,
        progress: Long,
        duration: Long,
        bufferedPosition: Long,
        onTogglePlay: () -> Unit,
        onClick: () -> Unit,
        onSkipNext: () -> Unit,
        onSkipPrevious: () -> Unit
) {
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val swipeThreshold = with(density) { 40.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // 记录入场方向: 1=从左入场, -1=从右入场, 0=不做动画
    var entryDirection by remember { mutableStateOf(0) }

    // 当 song 变化时 → 翻页入场动画
    LaunchedEffect(song.id) {
        val dir = entryDirection
        entryDirection = 0
        if (dir != 0) {
            // 从反方向滑入
            // dir=1(右滑→上一首) → 从左侧(-screenWidth)入场
            // dir=-1(左滑→下一首) → 从右侧(+screenWidth)入场
            offsetX.snapTo(-dir * screenWidthPx)
            offsetX.animateTo(0f, animationSpec = tween(200))
        } else {
            offsetX.snapTo(0f)
        }
    }

    Surface(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp
    ) {
        Column {
            // 主内容区域 (可滑动 - 翻页效果)
            Box(
                    modifier =
                            Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                        onDragEnd = {
                                            scope.launch {
                                                val current = offsetX.value
                                                if (abs(current) > swipeThreshold) {
                                                    // 超过阈值 → 翻页
                                                    val direction = if (current > 0) 1 else -1
                                                    entryDirection = direction
                                                    // 当前内容滑出屏幕
                                                    offsetX.animateTo(
                                                            direction.toFloat() * screenWidthPx,
                                                            animationSpec = tween(150)
                                                    )
                                                    // 触发切歌
                                                    if (direction > 0) onSkipPrevious()
                                                    else onSkipNext()
                                                } else {
                                                    // 没超过阈值 → 弹回原位
                                                    offsetX.animateTo(
                                                            0f,
                                                            animationSpec = tween(150)
                                                    )
                                                }
                                            }
                                        },
                                        onDragCancel = {
                                            scope.launch {
                                                offsetX.animateTo(0f, animationSpec = tween(150))
                                            }
                                        },
                                        onHorizontalDrag = { _, dragAmount ->
                                            scope.launch {
                                                offsetX.snapTo(offsetX.value + dragAmount)
                                            }
                                        }
                                )
                            }
            ) {
                // 点击导航到 PlayerScreen 的覆盖层
                Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        onClick = onClick
                ) {
                    Row(
                            modifier =
                                    Modifier.fillMaxSize()
                                            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                                            .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 封面缩略图
                        Surface(
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
                                color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            val coverPath = song.artworkPath
                            if (coverPath != null) {
                                AsyncImage(
                                        model = coverPath,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                            Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }

                        // 歌曲信息
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                    text = song.title.ifEmpty { song.displayName },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                    text = song.artist.ifEmpty { "未知歌手" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 播放/暂停按钮
                        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            if (isBuffering) {
                                CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                )
                            } else {
                                IconButton(onClick = onTogglePlay) {
                                    Icon(
                                            imageVector =
                                                    if (isPlaying) Icons.Default.Pause
                                                    else Icons.Default.PlayArrow,
                                            contentDescription = if (isPlaying) "暂停" else "播放"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 底部进度条 (三层: 背景 + 缓冲 + 播放进度)
            Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                val safeProgress =
                        if (duration > 0) (progress.toFloat() / duration).coerceIn(0f, 1f) else 0f

                // 底层 - 背景轨道
                LinearProgressIndicator(
                        progress = 0f,
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Transparent,
                        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                )
                // 中层 - 缓冲进度 (半透明主题色)
                // 本地歌曲不显示缓冲条
                val isLocalSong = song.localPath != null
                val safeBuffered =
                        if (!isLocalSong && duration > 0)
                                (bufferedPosition.toFloat() / duration).coerceIn(0f, 1f)
                        else 0f
                if (safeBuffered > 0f) {
                    LinearProgressIndicator(
                            progress = safeBuffered,
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                            trackColor = Color.Transparent,
                    )
                }
                // 上层 - 播放进度 (实色主题色)
                if (safeProgress > 0f) {
                    LinearProgressIndicator(
                            progress = safeProgress,
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.Transparent,
                    )
                }
            }
        }
    }
}
