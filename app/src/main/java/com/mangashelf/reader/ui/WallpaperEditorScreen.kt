package com.mangashelf.reader.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangashelf.reader.data.AnimationKind
import com.mangashelf.reader.data.FillMode
import com.mangashelf.reader.data.ImageItem
import com.mangashelf.reader.data.UgoiraFrame
import com.mangashelf.reader.data.WallpaperSetter
import com.mangashelf.reader.data.WallpaperTarget
import com.mangashelf.reader.data.WallpaperTransform
import kotlinx.coroutines.launch
import java.io.File

/**
 * 视差安全区比例，必须与 `GalleryWallpaperService.GalleryEngine.PARALLAX_RATIO` 保持一致
 * （那边是 private inner class 的 companion，外部拿不到，这里复刻一份）。
 */
private const val PARALLAX_RATIO = 0.12f

/**
 * 壁纸裁剪编辑页（v1.6.5）。
 *
 * 画布恒等于屏幕比例（设置后就是全屏），视差开启时内容额外放大 [GalleryWallpaperService.PARALLAX_RATIO]×2，
 * 这样左右平移时不会露边；编辑时的位移/缩放边界与渲染时用的是同一套 [WallpaperTransform]，
 * 所见即所得。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperEditorScreen(
    file: File,
    item: ImageItem?,
    ugoiraFrames: List<UgoiraFrame>,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember { WallpaperTarget.current(context) }
    val kind = remember(item) { AnimationKind.fromName(item?.kind) }
    val relPath = item?.relPath

    var transform by remember { mutableStateOf(existing?.transform ?: WallpaperTransform.DEFAULT) }
    var parallax by remember { mutableStateOf(existing?.parallax ?: true) }
    var applying by remember { mutableStateOf(false) }

    val dm = context.resources.displayMetrics
    val screenRatio = if (dm.heightPixels > 0) dm.widthPixels.toFloat() / dm.heightPixels else 9f / 16f
    // 视差开启时，内容要额外放大，保证平移范围被覆盖
    val parallaxScale = if (parallax) 1f + 2f * PARALLAX_RATIO else 1f
    val parallaxRange = PARALLAX_RATIO

    val transition = rememberInfiniteTransition(label = "parallax")
    val demoOffset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offset"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("裁剪壁纸") },
                navigationIcon = { TextButton(onClick = onClose) { Text("取消") } },
                actions = {
                    IconButton(onClick = { transform = WallpaperTransform.DEFAULT }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "复位")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .aspectRatio(screenRatio)
                        .fillMaxHeight()
                        .clipToBounds()
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val halfW = (size.width / 2f).coerceAtLeast(1f)
                                val halfH = (size.height / 2f).coerceAtLeast(1f)
                                transform = transform.copy(
                                    scale = transform.scale * zoom,
                                    tx = transform.tx + pan.x / halfW,
                                    ty = transform.ty + pan.y / halfH
                                ).normalized()
                            }
                        }
                ) {
                    AnimatedImage(
                        file = file,
                        item = item,
                        playing = true,
                        ugoiraFrames = ugoiraFrames,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = (if (transform.flipX) -1f else 1f) * transform.scale * parallaxScale
                                scaleY = (if (transform.flipY) -1f else 1f) * transform.scale * parallaxScale
                                rotationZ = transform.rotation.toFloat()
                                translationX = transform.tx * size.width / 2f +
                                    if (parallax) (demoOffset - 0.5f) * 2f * parallaxRange * size.width else 0f
                                translationY = transform.ty * size.height / 2f
                            },
                        contentScale = ContentScale.Crop
                    )
                }
            }

            // 文件名与缩放倍率
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item?.name ?: file.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "%.2f×".format(transform.scale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }

            // 工具行：翻转 / 旋转 / 填充
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { transform = transform.copy(flipX = !transform.flipX) }) {
                    Text(if (transform.flipX) "水平翻转 ✓" else "水平翻转")
                }
                TextButton(onClick = { transform = transform.copy(flipY = !transform.flipY) }) {
                    Text(if (transform.flipY) "垂直翻转 ✓" else "垂直翻转")
                }
                TextButton(onClick = {
                    transform = transform.copy(rotation = (transform.rotation + 90) % 360)
                }) {
                    Text("旋转 ${transform.rotation}°")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FillMode.entries.forEach { mode ->
                    FilterChip(
                        selected = transform.fill == mode,
                        onClick = { transform = transform.copy(fill = mode) },
                        label = { Text(mode.label) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("视差效果", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (parallax) "桌面滑动时壁纸轻微位移（上图标配动画为模拟效果）"
                        else "壁纸固定不动",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
                Switch(checked = parallax, onCheckedChange = { parallax = it })
            }

            Text(
                text = "提示：静态图走系统壁纸时，视差由桌面启动器决定，应用无法控制；" +
                    "本应用默认用内置壁纸服务渲染，视差开关才真正生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (relPath == null) return@Button
                        scope.launch {
                            applying = true
                            val powerSave = WallpaperTarget.powerSave(context)
                            val r = WallpaperSetter.apply(
                                context = context,
                                relPath = relPath,
                                kind = kind,
                                transform = transform,
                                parallax = parallax,
                                powerSave = powerSave
                            )
                            applying = false
                            android.widget.Toast
                                .makeText(context, r.message, android.widget.Toast.LENGTH_LONG)
                                .show()
                            if (r.ok) onClose()
                        }
                    },
                    enabled = !applying && relPath != null,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    if (applying) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp).height(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (applying) "正在设置…" else "设为壁纸")
                }
            }
        }
    }
}
