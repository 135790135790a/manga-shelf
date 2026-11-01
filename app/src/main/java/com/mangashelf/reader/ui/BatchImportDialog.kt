package com.mangashelf.reader.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * 分批导入里已经添加进来的一个文件夹。
 *
 * 每个 [BatchPick] 对应 `ShelfViewModel.importFolders` 里的一个批次：
 * 该文件夹下每个子文件夹是一本书。
 */
data class BatchPick(
    val uri: Uri,
    val name: String,
    val bookCount: Int,
    val pageCount: Int,
    val bytes: Long,
    val truncated: Boolean
)

/**
 * 分批导入对话框。
 *
 * 为什么不直接多选文件夹：Android 的 SAF 里 `ACTION_OPEN_DOCUMENT` 只能选文件，
 * 能选目录的 `ACTION_OPEN_DOCUMENT_TREE` 只允许单选。所以这里改成
 * 「反复点『添加文件夹』逐个加进来 → 列出已选 → 再点开始导入」，
 * 目录多选由 App 自己承担，不依赖系统选择器。
 */
@Composable
fun BatchImportDialog(
    picks: List<BatchPick>,
    scanning: Boolean,
    onAddFolder: () -> Unit,
    onRemove: (Uri) -> Unit,
    onClear: () -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分批导入") },
        text = {
            Column {
                Text(
                    "每个文件夹成为一批，文件夹里每个子文件夹是一本书。" +
                        "批次按顺序导入，可暂停、跳过或单独重试。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                if (scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(Modifier.size(18.dp))
                        Spacer(Modifier.size(10.dp))
                        Text("正在读取文件夹…", fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (picks.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "还没添加文件夹。\n点下面的「添加文件夹」选一个父目录，" +
                                "确认里面的子文件夹就是你要的每一本。",
                            fontSize = 13.sp,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(picks, key = { it.uri.toString() }) { pick ->
                            PickRow(pick = pick, onRemove = { onRemove(pick.uri) })
                        }
                    }
                }

                if (picks.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    val totalBooks = picks.sumOf { it.bookCount }
                    val totalBytes = picks.sumOf { it.bytes }
                    Text(
                        "共 ${picks.size} 批 · ${totalBooks} 本 · 约 ${humanSize(totalBytes)}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onStart, enabled = picks.isNotEmpty() && !scanning) {
                Text(if (picks.isEmpty()) "开始导入" else "开始导入（${picks.size} 批）")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear, enabled = picks.isNotEmpty() && !scanning) {
                    Text("清空")
                }
                TextButton(onClick = onAddFolder, enabled = !scanning) {
                    Text("添加文件夹")
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun PickRow(pick: BatchPick, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    pick.name,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val meta = buildString {
                    append("${pick.bookCount} 本")
                    if (pick.pageCount > 0) append(" · ${pick.pageCount} 页")
                    append(" · 约 ${humanSize(pick.bytes)}")
                    if (pick.truncated) append(" · 统计已截断")
                }
                Text(
                    meta,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onRemove) { Text("移除", fontSize = 13.sp) }
        }
    }
}

/** 1024 进制，保留一位小数；给界面看的粗略大小，不需要精确到字节 */
internal fun humanSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}
