package com.mangashelf.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangashelf.reader.data.StorageStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页「存储空间」：环形占比图 + 图例（可点选高亮）+ 设备剩余。
 *
 * 和「导入历史」里的那张饼图是两回事：那张按批次数/书册数，这张按字节。
 * 颜色固定按 key 取，不随排序变，用户扫一眼就能对上。
 */
private val STORAGE_COLORS = mapOf(
    "pages" to Color(0xFF2E86FF),
    "thumbs" to Color(0xFF3EC5C0),
    "database" to Color(0xFFF5B800),
    "backup" to Color(0xFFFF7A45),
    "other" to Color(0xFF8A8A99)
)

private val storageTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

@Composable
fun StoragePieSection(
    sample: StorageStats.Sample?,
    loading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableStateOf<String?>(null) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("存储空间", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "按真实目录大小统计，和导入历史的数量占比不是一回事",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onRefresh, enabled = !loading) { Text("刷新") }
            }
            Spacer(Modifier.height(12.dp))

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("正在统计目录大小…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }

            if (sample == null) {
                Text("还没有统计数据", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }

            val slices = sample.slices.filter { it.bytes > 0 }
            val total = slices.sumOf { it.bytes }

            if (total <= 0L) {
                Text(
                    "还没有导入任何漫画，暂时没有可统计的内容。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StorageDonut(
                        slices = slices,
                        total = total,
                        selected = selected,
                        modifier = Modifier.size(132.dp)
                    )
                    Spacer(Modifier.width(18.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        slices.forEach { slice ->
                            StorageLegendRow(
                                slice = slice,
                                total = total,
                                dimmed = selected != null && selected != slice.key,
                                onClick = { selected = if (selected == slice.key) null else slice.key }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "应用内合计 ${humanSize(total)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (sample.totalBytes > 0L) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "设备可用 ${humanSize(sample.freeBytes)} / 共 ${humanSize(sample.totalBytes)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "上次统计：${storageTimeFormat.format(Date(sample.at))}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StorageDonut(
    slices: List<StorageStats.Slice>,
    total: Long,
    selected: String?,
    modifier: Modifier = Modifier
) {
    val ring = 26f
    Canvas(modifier = modifier) {
        if (total <= 0L) return@Canvas
        val diameter = minOf(size.width, size.height) - ring
        val radius = diameter / 2f
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        var startAngle = -90f
        slices.forEach { slice ->
            val sweep = slice.bytes.toFloat() / total.toFloat() * 360f
            val dim = selected != null && selected != slice.key
            drawArc(
                color = (STORAGE_COLORS[slice.key] ?: Color.Gray).copy(alpha = if (dim) 0.25f else 1f),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = if (selected == slice.key) ring + 8f else ring)
            )
            startAngle += sweep
        }
    }
}

@Composable
private fun StorageLegendRow(
    slice: StorageStats.Slice,
    total: Long,
    dimmed: Boolean,
    onClick: () -> Unit
) {
    val percent = if (total > 0L) slice.bytes * 100f / total.toFloat() else 0f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Surface(
            shape = CircleShape,
            color = (STORAGE_COLORS[slice.key] ?: Color.Gray).copy(alpha = if (dimmed) 0.3f else 1f),
            modifier = Modifier.size(10.dp)
        ) {}
        Spacer(Modifier.width(8.dp))
        Text(
            slice.label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dimmed) 0.45f else 1f),
            modifier = Modifier.weight(1f)
        )
        Text(
            "${String.format(Locale.US, "%.1f", percent)}%",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dimmed) 0.45f else 1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            humanSize(slice.bytes),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (dimmed) 0.45f else 1f)
        )
    }
}
