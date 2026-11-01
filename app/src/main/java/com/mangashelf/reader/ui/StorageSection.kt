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
import androidx.compose.material3.FilterChip
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
 *
 * v1.5.9 追加：饼图可在「分类占比 / 按漫画占比」之间切换，按漫画走 Top8 + 其他，
 * 点图例能直接跳到「按漫画查看」页面并定位到那一本。
 */
enum class StorageView(val label: String) {
    CATEGORY("分类占比"),
    BY_BOOK("按漫画占比")
}

/** 分类固定的颜色，按 key 取，不随排序变 */
private val CATEGORY_COLORS = mapOf(
    "source" to Color(0xFF9B7BFF),
    "pages" to Color(0xFF2E86FF),
    "thumbs" to Color(0xFF3EC5C0),
    "database" to Color(0xFFF5B800),
    "backup" to Color(0xFFFF7A45),
    "other" to Color(0xFF8A8A99)
)

/** 按漫画模式下的调色板，Top 8 依次取；第 9 档「其他」固定灰 */
internal val BOOK_PALETTE = listOf(
    Color(0xFF2E86FF), Color(0xFF3EC5C0), Color(0xFFF5B800), Color(0xFFFF7A45),
    Color(0xFF9B7BFF), Color(0xFF4CAF50), Color(0xFFE91E63), Color(0xFF00BCD4)
)
internal val BOOK_OTHER_COLOR = Color(0xFF8A8A99)

private const val TOP_N = 8

private val storageTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

/** 把一本书压成饼图的一档 */
internal fun bookSlices(sample: StorageStats.Sample): List<StorageStats.Slice> {
    val list = sample.books.filter { it.totalBytes > 0L }
    if (list.isEmpty()) return emptyList()
    val top = list.take(TOP_N)
    val rest = list.drop(TOP_N)
    val out = ArrayList<StorageStats.Slice>(top.size + 1)
    top.forEach { b ->
        out += StorageStats.Slice("b:${b.bookId}", b.title, b.totalBytes)
    }
    if (rest.isNotEmpty()) {
        out += StorageStats.Slice("b:*", "其他 ${rest.size} 本", rest.sumOf { it.totalBytes })
    }
    return out
}

internal fun sliceColor(view: StorageView, key: String, index: Int): Color =
    if (view == StorageView.CATEGORY) {
        CATEGORY_COLORS[key] ?: Color.Gray
    } else {
        if (key == "b:*") BOOK_OTHER_COLOR else BOOK_PALETTE[index % BOOK_PALETTE.size]
    }

@Composable
fun StoragePieSection(
    sample: StorageStats.Sample?,
    loading: Boolean,
    onRefresh: () -> Unit,
    onOpenBooks: (bookId: Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    var view by remember { mutableStateOf(StorageView.CATEGORY) }
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

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StorageView.entries.forEach { v ->
                    FilterChip(
                        selected = view == v,
                        onClick = {
                            view = v
                            selected = null
                        },
                        label = { Text(v.label, fontSize = 12.sp) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

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

            val slices = when (view) {
                StorageView.CATEGORY -> sample.slices.filter { it.bytes > 0 }
                StorageView.BY_BOOK -> bookSlices(sample)
            }
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
                        view = view,
                        modifier = Modifier.size(132.dp)
                    )
                    Spacer(Modifier.width(18.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        slices.forEachIndexed { index, slice ->
                            StorageLegendRow(
                                slice = slice,
                                total = total,
                                color = sliceColor(view, slice.key, index),
                                dimmed = selected != null && selected != slice.key,
                                onClick = {
                                    selected = if (selected == slice.key) null else slice.key
                                    // 按漫画模式下点某一本 → 直接跳到列表页定位
                                    if (view == StorageView.BY_BOOK && selected != null) {
                                        val id = slice.key.removePrefix("b:").toLongOrNull()
                                        onOpenBooks(id)
                                    }
                                }
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
                if (view == StorageView.BY_BOOK && sample.books.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "共 ${sample.books.size} 本，Top$TOP_N 以外的合并成「其他」",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "共 ${sample.books.size} 本漫画，占 ${humanSize(sample.booksTotalBytes)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onOpenBooks(null) }) { Text("按漫画查看 →") }
            }

            if (sample.totalBytes > 0L) {
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
    view: StorageView,
    modifier: Modifier = Modifier
) {
    val ring = 26f
    Canvas(modifier = modifier) {
        if (total <= 0L) return@Canvas
        val diameter = minOf(size.width, size.height) - ring
        val radius = diameter / 2f
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        var startAngle = -90f
        slices.forEachIndexed { index, slice ->
            val sweep = (slice.bytes.toFloat() / total.toFloat() * 360f).coerceAtLeast(0f)
            val dim = selected != null && selected != slice.key
            drawArc(
                color = sliceColor(view, slice.key, index)
                    .copy(alpha = if (dim) 0.25f else 1f),
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
    color: Color,
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
            color = color.copy(alpha = if (dimmed) 0.3f else 1f),
            modifier = Modifier.size(10.dp)
        ) {}
        Spacer(Modifier.width(8.dp))
        Text(
            slice.label,
            fontSize = 12.sp,
            maxLines = 1,
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
