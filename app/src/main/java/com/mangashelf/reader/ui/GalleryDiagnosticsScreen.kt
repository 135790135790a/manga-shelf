package com.mangashelf.reader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mangashelf.reader.MangaShelfApp
import com.mangashelf.reader.data.GalleryDiagnostics
import kotlinx.coroutines.launch

/**
 * 图片工作栏诊断面板（v1.6.3，常驻入口：设置页 → 关于 → 图片诊断）。
 *
 * 面板只读不写，列出每张图在磁盘上的真实状态，用来回答「为什么这张 Gif 不动」
 * 「为什么相簿是空的」这类在无 adb 设备上无法查的问题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryDiagnosticsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as MangaShelfApp
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("正在收集…") }
    var busy by remember { mutableStateOf(true) }

    fun reload() {
        busy = true
        scope.launch {
            text = runCatching {
                GalleryDiagnostics.collect(
                    context = ctx,
                    items = app.galleryRepository.allItems(),
                    storage = app.galleryStorage
                )
            }.getOrElse { "收集失败：${it.javaClass.simpleName}: ${it.message}" }
            busy = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图片诊断") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { reload() }, enabled = !busy) { Text("刷新") }
                    TextButton(
                        onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("图片诊断", text))
                            Toast.makeText(ctx, "已复制诊断文本", Toast.LENGTH_SHORT).show()
                        }
                    ) { Text("复制") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                "这个面板只读，不会修改任何文件。若某张动图不播，把下面的文字复制出来发给我即可定位。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Start) {
                Text(if (busy) "收集状态：进行中…" else "收集状态：完成", style = MaterialTheme.typography.labelSmall)
            }
            HorizontalDivider()
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
    }
}
