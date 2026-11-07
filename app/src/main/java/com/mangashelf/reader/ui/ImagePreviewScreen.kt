package com.mangashelf.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.mangashelf.reader.data.AnimationKind
import com.mangashelf.reader.data.ImageItem
import com.mangashelf.reader.data.UgoiraFrame
import com.mangashelf.reader.data.WallpaperSetter
import kotlinx.coroutines.launch
import java.io.File

/**
 * 图片工作栏的全屏预览。
 *
 * - 左右翻页：HorizontalPager（一本书内的图片按当前排序翻）
 * - 缩放：1x–4x，双指捏合；放大后单指拖动平移，此时不翻页
 * - 双击：1x ↔ 2.5x
 * - 单击：切换顶部/底部信息栏的显隐
 * - 动图：进入即播放，离开该页自动停（由 AnimatedImage 的 playing 控制）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImagePreviewScreen(
    items: List<ImageItem>,
    initialIndex: Int,
    fileOf: (ImageItem) -> File,
    ugoiraFramesOf: (ImageItem) -> List<UgoiraFrame>,
    onClose: () -> Unit,
    /** 文件夹 id → 名称，用于底部信息条显示「所属文件夹」。 */
    folderNames: Map<Long, String> = emptyMap()
) {
    if (items.isEmpty()) {
        onClose()
        return
    }
    val start = initialIndex.coerceIn(0, items.lastIndex)
    val pagerState = rememberPagerState(initialPage = start) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    val appCtx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val current = items.getOrNull(pagerState.currentPage) ?: items.first()

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = items[page]
            ZoomablePage(
                item = item,
                file = fileOf(item),
                frames = ugoiraFramesOf(item),
                active = page == pagerState.currentPage,
                onZoomChange = { if (page == pagerState.currentPage) zoomed = it },
                onTap = { chrome = !chrome }
            )
        }

        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "关闭",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "${pagerState.currentPage + 1} / ${items.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Box(Modifier.weight(1f))
                    Text(
                        text = current.kindLabel(),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        text = current.name,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2
                    )
                    Text(
                        text = current.kindLabel() + buildString {
                            if (current.width > 0 && current.height > 0) append(" · ${current.width} × ${current.height}")
                            append(" · ${humanBytes(current.sizeBytes)}")
                            if (current.frames > 0) append(" · ${current.frames} 帧")
                            if (current.durationMs > 0) append(" · ${current.durationMs / 1000.0}s")
                        },
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    // v1.6.2：详情统一放这里（长按菜单只留动作，不再兼做详情卡）
                    Text(
                        text = buildString {
                            val folder = folderNames[current.folderId]
                            append("导入于 ${formatTime(current.createdAt)}")
                            if (!folder.isNullOrBlank()) append(" · 位于「$folder」")
                        },
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    // v1.6.4：设为壁纸。静态图直接铺；动图唤起系统动态壁纸选择器。
                    TextButton(
                        onClick = {
                            val kind = AnimationKind.fromName(current.kind)
                            if (WallpaperSetter.isLive(kind)) {
                                val r = WallpaperSetter.applyLive(appCtx, current.relPath, kind)
                                Toast.makeText(appCtx, r.message, Toast.LENGTH_LONG).show()
                            } else {
                                scope.launch {
                                    val r = WallpaperSetter.applyStill(appCtx, fileOf(current))
                                    Toast.makeText(appCtx, r.message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = "设为壁纸",
                            color = Color(0xFFFFB74D),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

private fun ImageItem.kindLabel(): String = runCatching {
    com.mangashelf.reader.data.AnimationKind.fromName(kind).label
}.getOrDefault("")

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "未知时间"
    return runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(ms))
    }.getOrDefault("未知时间")
}

@Composable
private fun ZoomablePage(
    item: ImageItem,
    file: File,
    frames: List<UgoiraFrame>,
    active: Boolean,
    onZoomChange: (Boolean) -> Unit,
    onTap: () -> Unit
) {
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(item.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(item.id) { mutableFloatStateOf(0f) }
    val currentOnZoomChange by rememberUpdatedState(onZoomChange)
    val currentOnTap by rememberUpdatedState(onTap)

    val maxScale = 4f
    val doubleTapScale = 2.5f

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { currentOnTap() },
                    onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = doubleTapScale
                        }
                        currentOnZoomChange(scale > 1.01f)
                    }
                )
            }
            .pointerInput(item.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var startScale = scale
                    var startX = offsetX
                    var startY = offsetY
                    var startCentroid = down.position
                    var moved = false
                    var active = true
                    while (active) {
                        val event = awaitPointerEvent()
                        if (event.changes.isEmpty()) break
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        if (zoom != 1f) {
                            scale = (scale * zoom).coerceIn(1f, maxScale)
                            if (scale <= 1f) {
                                offsetX = 0f
                                offsetY = 0f
                            }
                            moved = true
                        }
                        if (scale > 1.01f) {
                            val maxX = size.width * (scale - 1f) / 2f
                            val maxY = size.height * (scale - 1f) / 2f
                            offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                            offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                            if (pan != Offset.Zero) moved = true
                            if (zoom != 1f || event.changes.size > 1) {
                                event.changes.forEach { it.consume() }
                            }
                        }
                        if (event.changes.all { !it.pressed }) active = false
                        if (centroid != Offset.Unspecified) startCentroid = centroid
                        startScale = scale
                        startX = offsetX
                        startY = offsetY
                    }
                    if (moved) currentOnZoomChange(scale > 1.01f)
                }
            }
    ) {
        AnimatedImage(
            file = file,
            item = item,
            playing = active,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
            contentScale = ContentScale.Fit,
            ugoiraFrames = frames
        )
    }
}

private fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(java.util.Locale.US, "%.2f MB", mb)
}
