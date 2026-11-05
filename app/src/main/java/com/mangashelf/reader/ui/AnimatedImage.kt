package com.mangashelf.reader.ui

import android.graphics.drawable.Drawable
import android.os.Build
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image as ComposeImage
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.mangashelf.reader.data.AnimationKind
import com.mangashelf.reader.data.GalleryStorage
import com.mangashelf.reader.data.ImageItem
import com.mangashelf.reader.data.UgoiraFrame
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SVGAParser
import com.opensource.svgaplayer.SVGAVideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Ugoira 超过这么多帧就按 30fps 降采样。
 * 1000 帧的 Ugoira 在手机上按原延时播放很容易掉帧，降到 30fps 观感几乎无差别。
 */
private const val UGOIRA_MAX_FPS_FRAMES = 500

/**
 * 统一的图片/动图渲染入口。
 *
 * - 静态图、GIF、动图 WebP、APNG：走 Coil。列表模式用系统默认 ImageLoader（不含 GifDecoder），
 *   只会解出首帧，滚动时不解码整段动画；预览模式播放时换成带 GifDecoder 的 loader。
 * - Lottie：直接读 JSON，列表只渲染第 0 帧（不播）。
 * - SVGA：走 AndroidView + SVGAParser。解析失败时显示占位，不抛异常。
 * - Ugoira：帧已经被解包到 `files/gallery/ugoira/<uuid>/`，按 delay 定时切帧。
 *
 * 所有分支都包在 runCatching / 失败占位里，任何格式坏掉都只影响这一格，不会让列表崩。
 */
@Composable
fun AnimatedImage(
    file: File,
    item: ImageItem?,
    playing: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    /** Ugoira 专用：已解包的帧（含每帧真实延时），由调用方从 ViewModel 提供。 */
    ugoiraFrames: List<UgoiraFrame> = emptyList()
) {
    val kind = remember(item?.id, item?.kind) {
        item?.kind?.let { AnimationKind.fromName(it) } ?: AnimationKind.STATIC
    }
    val model = remember(file.absolutePath) { file }

    when (kind) {
        AnimationKind.STATIC -> {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(model).crossfade(false).build(),
                contentDescription = null,
                modifier = modifier,
                contentScale = contentScale
            )
        }

        AnimationKind.GIF, AnimationKind.WEBP_ANIM, AnimationKind.APNG -> {
            if (playing) {
                val ctx = LocalContext.current
                val loader = remember(ctx) {
                    ImageLoader.Builder(ctx)
                        .components {
                            add(GifDecoder.Factory())
                            // ImageDecoder 解码器需要 API 28，低版本只用 GifDecoder
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                add(ImageDecoderDecoder.Factory())
                            }
                        }
                        .build()
                }
                AsyncImage(
                    model = ImageRequest.Builder(ctx)
                        .data(model)
                        // v1.6.3：列表页用默认 loader 把同一个 File 解成静态首帧并写进了磁盘缓存，
                        // 预览页再拿同一份缓存就永远不动。播放时禁掉磁盘缓存并换一个内存 key，
                        // 强制走带动图解码器的那条路。
                        .memoryCacheKey("anim:${file.absolutePath}:${file.lastModified()}")
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build(),
                    imageLoader = loader,
                    contentDescription = null,
                    modifier = modifier,
                    contentScale = contentScale
                )
            } else {
                // 列表里只看首帧：默认 loader 不含动图解码器
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(model).build(),
                    contentDescription = null,
                    modifier = modifier,
                    contentScale = contentScale
                )
            }
        }

        AnimationKind.LOTTIE -> {
            // Lottie 6.4 的 File spec 收的是文件名而不是 File 对象，这里直接读 JSON 串，行为最确定
            val json = remember(file.absolutePath, file.length()) {
                runCatching { file.readText() }.getOrDefault("")
            }
            if (json.isBlank()) {
                AnimatedPlaceholder(kind, modifier)
            } else {
                val composition by rememberLottieComposition(
                    LottieCompositionSpec.JsonString(json)
                )
                LottieAnimation(
                    composition = composition,
                    iterations = if (playing) LottieConstants.IterateForever else 1,
                    modifier = modifier
                )
            }
        }

        AnimationKind.SVGA -> {
            SvgaView(file = file, playing = playing, modifier = modifier)
        }

        AnimationKind.UGOIRA -> {
            // v1.6.2：按 frames.json 里的逐帧真实延时播放（不再用「总时长 ÷ 帧数」平均），
            // 帧数超过 [UGOIRA_MAX_FPS_FRAMES] 时按 30fps 降采样，避免手机端追不上时间轴。
            val all = ugoiraFrames
            val sampled = remember(all) {
                if (all.size <= UGOIRA_MAX_FPS_FRAMES) all
                else {
                    // 保留首尾，中间等间隔抽取，保证总时长不变
                    val step = (all.size + UGOIRA_MAX_FPS_FRAMES - 1) / UGOIRA_MAX_FPS_FRAMES
                    all.filterIndexed { i, _ -> i % step == 0 }.ifEmpty { listOf(all.first()) }
                }
            }
            var index by remember(sampled) { mutableIntStateOf(0) }
            LaunchedEffect(sampled, playing) {
                if (!playing || sampled.size <= 1) return@LaunchedEffect
                while (true) {
                    val frame = sampled[index]
                    delay(frame.delayMs.coerceIn(10, 10_000).toLong())
                    index = (index + 1) % sampled.size
                }
            }
            val current = sampled.getOrNull(index)
            if (current != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(current.file).build(),
                    contentDescription = null,
                    modifier = modifier,
                    contentScale = contentScale
                )
            } else {
                AnimatedPlaceholder(kind, modifier)
            }
        }
    }
}

@Composable
private fun AnimatedPlaceholder(kind: AnimationKind, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = kind.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/** SVGA 需要真正的 View，用 AndroidView 包一层；解析失败保持空白。 */
@Composable
private fun SvgaView(file: File, playing: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    var failed by remember(file.absolutePath) { mutableStateOf(false) }
    val imageView = remember(file.absolutePath) {
        SVGAImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            loops = if (playing) -1 else 1
            isClickable = false
        }
    }

    DisposableEffect(file.absolutePath) {
        // SVGAParser.parse 按路径解析（本地绝对路径也走这里），回调里拿不到就置 failed
        runCatching {
            SVGAParser(context).parse(file.absolutePath, object : SVGAParser.ParseCompletion {
                override fun onComplete(videoItem: SVGAVideoEntity) {
                    imageView.setVideoItem(videoItem)
                    if (playing) imageView.startAnimation()
                }

                override fun onError() {
                    failed = true
                }
            })
        }.onFailure { failed = true }
        onDispose {
            runCatching { imageView.stopAnimation() }
        }
    }

    if (failed) {
        AnimatedPlaceholder(AnimationKind.SVGA, modifier)
    } else {
        AndroidView(factory = { imageView }, modifier = modifier)
    }
}

/** 列表缩略：动图加一个播放角标，让用户知道点开才会动。 */
@Composable
fun AnimatedBadge(kind: AnimationKind, modifier: Modifier = Modifier) {
    if (kind == AnimationKind.STATIC) return
    Box(modifier, contentAlignment = Alignment.TopStart) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
    }
}
