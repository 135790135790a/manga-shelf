package com.mangashelf.reader.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mangashelf.reader.data.AppDatabase
import com.mangashelf.reader.data.AutoBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「数据状态」面板（v1.6.4）。
 *
 * 回答三个问题：图片工作栏占了多少、数据库多大、自动快照还有几份。
 * 只读展示 + 一个「立即快照」按钮，真正的整库导出仍走设置页的「导出完整备份」。
 */
@Composable
fun DataStatusDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var imageCount by remember { mutableIntStateOf(0) }
    var imageBytes by remember { mutableLongStateOf(0L) }
    var dbBytes by remember { mutableLongStateOf(0L) }
    var snapCount by remember { mutableIntStateOf(0) }
    var lastSnapAt by remember { mutableLongStateOf(0L) }
    var snapshoting by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true
        withContext(Dispatchers.IO) {
            imageCount = runCatching { AppDatabase.get(ctx).imageDao().allItems().size }.getOrDefault(0)
            imageBytes = runCatching { dirSize(File(ctx.filesDir, "gallery")) }.getOrDefault(0L)
            dbBytes = runCatching {
                ctx.databaseList().sumOf { name -> ctx.getDatabasePath(name).length() }
            }.getOrDefault(0L)
            val snaps = runCatching { AutoBackup.list(ctx) }.getOrDefault(emptyList())
            snapCount = snaps.size
            lastSnapAt = runCatching { AutoBackup.lastAt(ctx) }.getOrDefault(0L)
        }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("数据状态") },
        text = {
            if (loading) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("图片工作栏：$imageCount 张 · ${humanSize(imageBytes)}", style = MaterialTheme.typography.bodyMedium)
                    Text("数据库：${humanSize(dbBytes)}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "自动快照：$snapCount 份" +
                            if (lastSnapAt > 0) " · 最近 ${fmtTime(lastSnapAt)}" else "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "快照只含数据库（书、页、图片记录），图片文件本身要靠「导出完整备份」才带走。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !snapshoting,
                onClick = {
                    scope.launch {
                        snapshoting = true
                        val ok = runCatching { AutoBackup.backupNow(ctx) }.getOrNull() != null
                        snapshoting = false
                        Toast.makeText(
                            ctx,
                            if (ok) "已生成快照" else "快照失败（可能是磁盘空间不足）",
                            Toast.LENGTH_SHORT
                        ).show()
                        load()
                    }
                }
            ) { Text(if (snapshoting) "快照中…" else "立即快照") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun dirSize(dir: File): Long {
    if (!dir.exists()) return 0L
    var total = 0L
    val stack = ArrayDeque<File>()
    stack.addLast(dir)
    while (stack.isNotEmpty()) {
        val cur = stack.removeLast()
        val children = cur.listFiles() ?: continue
        for (f in children) {
            if (f.isDirectory) stack.addLast(f) else total += f.length()
        }
    }
    return total
}

private fun fmtTime(ms: Long): String = runCatching {
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))
}.getOrDefault("—")
