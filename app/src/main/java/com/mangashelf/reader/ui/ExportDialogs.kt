package com.mangashelf.reader.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mangashelf.reader.export.Exporter

/**
 * 导出格式选择。
 *
 * 「原始 PDF」只在书里真的留了 `source.pdf` 时才可点——图片/文件夹导入的书没有原件，
 * 点了也只会得到一句报错，不如直接置灰。
 */
@Composable
fun ExportFormatDialog(
    title: String,
    hasOriginalPdf: Boolean,
    onPick: (Exporter.Format) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出《$title》") },
        text = {
            Column {
                Text(
                    "导出会按页面管理里的当前顺序与已删除状态生成文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                FormatRow(
                    label = Exporter.Format.PDF_ORIGINAL.label,
                    hint = if (hasOriginalPdf) "直接复制原件，最快且无损" else "这本书没有保留原始 PDF",
                    enabled = hasOriginalPdf
                ) { onPick(Exporter.Format.PDF_ORIGINAL) }
                FormatRow(
                    label = Exporter.Format.PDF_REBUILT.label,
                    hint = "按当前页序重新合成，删掉的页不会出现"
                ) { onPick(Exporter.Format.PDF_REBUILT) }
                FormatRow(
                    label = Exporter.Format.ZIP.label,
                    hint = "打包原始页面图，不重新编码"
                ) { onPick(Exporter.Format.ZIP) }
                FormatRow(
                    label = Exporter.Format.LONG_IMAGE.label,
                    hint = "纵向拼接为长图；过高会自动分成多张"
                ) { onPick(Exporter.Format.LONG_IMAGE) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun FormatRow(
    label: String,
    hint: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 导出进行中：进度 + 取消 */
@Composable
fun ExportProgressDialog(progress: ExportProgress, onCancel: () -> Unit) {
    val fraction = if (progress.total > 0) {
        (progress.current.toFloat() / progress.total).coerceIn(0f, 1f)
    } else 0f
    AlertDialog(
        onDismissRequest = { /* 导出中不允许点外部关闭，避免状态错乱 */ },
        title = { Text("正在导出") },
        text = {
            Column {
                Text(
                    if (progress.bookTotal > 1) {
                        "第 ${progress.bookIndex} / ${progress.bookTotal} 本：${progress.title}"
                    } else {
                        progress.title
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        progress.phase,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (progress.total > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${progress.current} / ${progress.total}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        }
    )
}

/** 导出结束：逐本结果汇总，成功项可直接打开 */
@Composable
fun ExportResultDialog(results: List<ExportItemResult>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val okCount = results.count { it.ok }
    val failed = results.filterNot { it.ok }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    results.isEmpty() -> "没有导出任何文件"
                    failed.isEmpty() -> "导出完成"
                    okCount == 0 -> "导出失败"
                    else -> "部分成功"
                }
            )
        },
        text = {
            Column {
                Text(
                    "成功 $okCount / 共 ${results.size} 本",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.height(240.dp)) {
                    items(results) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (item.ok) "✓" else "✗",
                                color = if (item.ok) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                Text(
                                    item.message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                            if (item.ok && item.uris.isNotEmpty()) {
                                TextButton(onClick = {
                                    runCatching {
                                        val uri = Uri.parse(item.uris.first())
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, "*/*")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    }
                                }) { Text("打开") }
                            }
                        }
                    }
                }
                if (failed.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "失败项可重试：重新长按选择导出即可。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

/** 少量场景（批量导出多个文件）需要一个转圈提示 */
@Composable
fun ExportPreparingDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("准备中") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("正在读取目标目录…", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}
