package com.mangashelf.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangashelf.reader.data.AppDatabase
import com.mangashelf.reader.data.StorageCleaner
import com.mangashelf.reader.data.StorageStats
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.5.9：设置页「按漫画查看」全屏列表。
 *
 * 为什么不在设置页里直接铺列表：设置页是 LazyColumn，嵌套可滚动列表会直接崩
 * （`LazyColumn` 不能无限高度嵌在另一个 `LazyColumn` 里）。单独一页还能给
 * 搜索框、排序切换、单本详情留出足够的空间。
 */
enum class BookSort(val label: String) {
    TOTAL("合计"),
    SOURCE("原文件"),
    CACHE("缓存"),
    THUMB("缩略图")
}

private val bookTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StorageBooksScreen(
    initial: StorageStats.Sample,
    focusBookId: Long?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var sample by remember { mutableStateOf(initial) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(BookSort.TOTAL) }
    var desc by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<StorageStats.BookStat?>(null) }
    var confirmClean by remember { mutableStateOf<StorageStats.BookStat?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    fun reload() {
        if (loading) return
        loading = true
        scope.launch {
            sample = runCatching {
                val books = AppDatabase.get(context).bookDao().allBooks()
                StorageStats.collect(context, books, force = true)
            }.getOrElse { sample }
            loading = false
        }
    }

    val filtered = remember(sample, query, sort, desc) {
        val q = query.trim()
        val base = if (q.isEmpty()) sample.books
        else sample.books.filter { it.title.contains(q, ignoreCase = true) }
        val sorted = when (sort) {
            BookSort.TOTAL -> base.sortedBy { it.totalBytes }
            BookSort.SOURCE -> base.sortedBy { it.sourceBytes }
            BookSort.CACHE -> base.sortedBy { it.cacheBytes }
            BookSort.THUMB -> base.sortedBy { it.thumbBytes }
        }
        if (desc) sorted.reversed() else sorted
    }

    // 从饼图图例点进来时定位到那一本
    LaunchedEffect(focusBookId, filtered.size) {
        if (focusBookId != null) {
            val idx = filtered.indexOfFirst { it.bookId == focusBookId }
            if (idx >= 0) listState.animateScrollToItem(idx)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("按漫画查看") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                actions = {
                    TextButton(onClick = { reload() }, enabled = !loading) { Text("刷新") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索漫画名", fontSize = 13.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BookSort.entries.forEach { s ->
                    FilterChip(
                        selected = sort == s,
                        onClick = {
                            if (sort == s) desc = !desc else {
                                sort = s
                                desc = true
                            }
                        },
                        label = {
                            Text(
                                if (sort == s) "${s.label} ${if (desc) "↓" else "↑"}" else s.label,
                                fontSize = 12.sp
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "共 ${filtered.size} 本 · 合计 ${humanSize(filtered.sumOf { it.totalBytes })}" +
                    if (loading) " · 统计中…" else "",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(6.dp))

            if (filtered.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (query.isBlank()) "还没有导入任何漫画" else "没有匹配「$query」的漫画",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.bookId }) { book ->
                        BookStatRow(book = book, onClick = { detail = book })
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }

    // 单本详情
    detail?.let { book ->
        val totalAll = sample.booksTotalBytes.coerceAtLeast(1L)
        val share = book.totalBytes * 100f / totalAll
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(book.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailLine("总页数", "${book.pageCount} 页")
                    DetailLine("导入时间", bookTimeFormat.format(Date(book.createdAt)))
                    HorizontalDivider()
                    DetailLine(
                        "原文件",
                        humanSize(book.sourceBytes) + if (book.sourceEstimated) "（按导入记录估算）" else ""
                    )
                    DetailLine("漫画页面", humanSize(book.pageBytes))
                    DetailLine("缩略图", humanSize(book.thumbBytes))
                    DetailLine("合计", humanSize(book.totalBytes), bold = true)
                    DetailLine("占书架比例", String.format(Locale.US, "%.1f%%", share))
                    if (book.partial) {
                        Text(
                            "这本书的目录读不全，上面的数字可能偏小。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmClean = book },
                    enabled = book.thumbBytes > 0L
                ) { Text("清理缩略图") }
            },
            dismissButton = { TextButton(onClick = { detail = null }) { Text("关闭") } }
        )
    }

    // 清理确认
    confirmClean?.let { book ->
        AlertDialog(
            onDismissRequest = { confirmClean = null },
            title = { Text("清理缩略图？") },
            text = {
                Text(
                    "会删掉这本书的 ${humanSize(book.thumbBytes)} 缩略图缓存，页面图片和原文件不动。" +
                        "下次打开页面管理或阅读时会按需重新生成，不用担心变空白。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = book
                    confirmClean = null
                    detail = null
                    scope.launch {
                        val r = runCatching {
                            val folder = AppDatabase.get(context).bookDao().get(target.bookId)?.folder
                                ?: return@runCatching StorageCleaner.Result(0L, false)
                            StorageCleaner.clearThumbs(context, folder)
                        }.getOrElse { StorageCleaner.Result(0L, false) }
                        toast = if (r.ok) "已清理 ${humanSize(r.deletedBytes)}" else "清理失败，稍后再试"
                        reload()
                    }
                }) { Text("清理") }
            },
            dismissButton = { TextButton(onClick = { confirmClean = null }) { Text("取消") } }
        )
    }

    toast?.let { msg ->
        AlertDialog(
            onDismissRequest = { toast = null },
            confirmButton = { TextButton(onClick = { toast = null }) { Text("好") } },
            text = { Text(msg, fontSize = 13.sp) }
        )
    }
}

@Composable
private fun BookStatRow(book: StorageStats.BookStat, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${book.pageCount} 页 · 原文件 ${humanSize(book.sourceBytes)}" +
                    " · 页面 ${humanSize(book.pageBytes)} · 缩略图 ${humanSize(book.thumbBytes)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(10.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        ) {
            Text(
                humanSize(book.totalBytes),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
            color = if (bold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}
