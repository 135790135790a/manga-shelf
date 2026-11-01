package com.mangashelf.reader.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangashelf.reader.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SPLASH_BG_DAY = Color(0xFFF6F4EF)
private val SPLASH_BG_NIGHT = Color(0xFF16161B)
private val SPLASH_TEXT_DAY = Color(0xFF1B1B1F)
private val SPLASH_TEXT_NIGHT = Color(0xFFE9E9F0)

/**
 * v1.5.9：开场界面（鲸鱼娘书架）。
 *
 * 冷启动链路：
 * 1. 系统画 `windowBackground`（layer-list = 同图 + 同底色），这时 Compose 还没起来；
 * 2. Compose 首帧渲染这个 Composable，图片位置/缩放与 layer-list 完全一致；
 * 3. 停留 [durationMs] 后淡出，露出书架。
 *
 * 因为 1 和 2 是同一张图、同一个位置，视觉上是连续的，中间不会跳一下。
 *
 * 只在真正的冷启动显示一次（由 [com.mangashelf.reader.MangaShelfApp.splashShown] 控制），
 * 切后台回前台、配置变化重建都不会重播。
 *
 * 预览模式（设置页「重新查看开场」）：不走计时，按钮是「关闭」，点了回到设置页。
 */
@Composable
fun SplashOverlay(
    durationMs: Int,
    night: Boolean,
    preview: Boolean = false,
    onFinish: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }

    val alpha by animateFloatAsState(
        targetValue = if (leaving) 0f else 1f,
        animationSpec = tween(durationMillis = 220),
        label = "splashAlpha"
    )

    // 退出统一走这里：先淡出，再通知外层
    fun finish() {
        if (leaving) return
        leaving = true
        scope.launch {
            delay(230)
            onFinish()
        }
    }

    LaunchedEffect(durationMs, preview) {
        if (!preview && durationMs > 0) {
            delay(durationMs.toLong())
            finish()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .alpha(alpha)
            .background(if (night) SPLASH_BG_NIGHT else SPLASH_BG_DAY)
    ) {
        Image(
            painter = painterResource(
                if (night) R.drawable.splash_whale_night else R.drawable.splash_whale
            ),
            contentDescription = "鲸鱼娘书架",
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        )

        TextButton(
            onClick = { finish() },
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 56.dp)
        ) {
            Text(
                if (preview) "关闭" else "跳过",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (night) SPLASH_TEXT_NIGHT else SPLASH_TEXT_DAY
            )
        }
    }
}
