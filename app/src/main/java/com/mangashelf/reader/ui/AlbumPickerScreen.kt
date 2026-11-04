package com.mangashelf.reader.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mangashelf.reader.data.Album
import com.mangashelf.reader.data.AlbumPhoto
import com.mangashelf.reader.data.AlbumRepository
import kotlinx.coroutines.launch

/**
 * 自建相册选择页（v1.6.2）。
 *
 * 和系统 Photo Picker 并存：Photo Picker 零权限、适合「随手选几张」；
 * 这里按 MediaStore 的 bucket 列真实相簿，适合「按相簿整批导入」。
 *
 * 权限被拒时给出可操作的提示，不静默空白。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumPickerScreen(
    onPick: (List<Uri>) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(AlbumRepository.hasPermission(ctx)) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var loadingAlbums by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<Album?>(null) }
    var photos by remember { mutableStateOf<List<AlbumPhoto>>(emptyList()) }
    var page by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var loadingPhotos by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok || AlbumRepository.hasPermission(ctx)
    }

    fun loadAlbums() {
        loadingAlbums = true
        scope.launch {
            albums = AlbumRepository.albums(ctx)
            loadingAlbums = false
        }
    }

    fun loadPhotos(album: Album, reset: Boolean) {
        if (loadingPhotos) return
        loadingPhotos = true
        val nextPage = if (reset) 0 else page + 1
        scope.launch {
            val list = AlbumRepository.photos(ctx, album.bucketId, nextPage)
            photos = if (reset) list else photos + list
            page = nextPage
            loadingPhotos = false
        }
    }

    LaunchedEffect(granted) {
        if (granted) loadAlbums()
    }

    BackHandler(enabled = current != null) {
        current = null
        photos = emptyList()
        page = 0
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = current?.let { "相簿 · ${it.name}" } ?: "本机相册",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (current != null) {
                            current = null
                            photos = emptyList()
                            page = 0
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (current != null && selected.isNotEmpty()) {
                        TextButton(onClick = {
                            val uris = photos.filter { it.id in selected }.map { it.uri }
                            onPick(uris)
                        }) { Text("导入 ${selected.size} 张") }
                    }
                }
            )
        },
        bottomBar = {
            if (current != null) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("已选 ${selected.size} 张", style = MaterialTheme.typography.bodyMedium)
                        Box(Modifier.weight(1f))
                        TextButton(onClick = { selected = emptySet() }) { Text("清空") }
                        TextButton(
                            enabled = selected.isNotEmpty(),
                            onClick = {
                                val uris = photos.filter { it.id in selected }.map { it.uri }
                                onPick(uris)
                            }
                        ) { Text("导入") }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !granted -> PermissionHint(
                    onRequest = { permLauncher.launch(AlbumRepository.readPermission()) }
                )

                current == null -> AlbumList(
                    albums = albums,
                    loading = loadingAlbums,
                    onOpen = { album ->
                        current = album
                        selected = emptySet()
                        photos = emptyList()
                        page = 0
                        loadPhotos(album, reset = true)
                    },
                    onRefresh = { loadAlbums() }
                )

                else -> PhotoGrid(
                    photos = photos,
                    selected = selected,
                    loading = loadingPhotos,
                    onToggle = { id ->
                        selected = if (id in selected) selected - id else selected + id
                    },
                    onLoadMore = { current?.let { loadPhotos(it, reset = false) } }
                )
            }
        }
    }
}

@Composable
private fun PermissionHint(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("需要相册访问权限", style = MaterialTheme.typography.titleMedium)
        Text(
            "用于按手机里的真实相簿读取图片并导入。只读取图片元数据，不上传任何内容。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        Button(onClick = onRequest) { Text("授予权限") }
        OutlinedButton(
            onClick = { onRequest() },
            modifier = Modifier.padding(top = 8.dp)
        ) { Text("已拒绝过？点这里再试一次") }
        Text(
            "若系统不再弹窗，请到「设置 → 应用 → 漫画书架 → 权限」里手动打开。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumList(
    albums: List<Album>,
    loading: Boolean,
    onOpen: (Album) -> Unit,
    onRefresh: () -> Unit
) {
    if (loading && albums.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("正在读取相簿…") }
        return
    }
    if (albums.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("没有读到相簿", style = MaterialTheme.typography.titleMedium)
            Text(
                "可能是权限只授予了部分照片，或设备上还没有图片。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )
            Button(onClick = onRefresh) { Text("重新读取") }
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(albums, key = { it.bucketId }) { album ->
            Column(Modifier.combinedClickable(onClick = { onOpen(album) }, onLongClick = {})) {
                AsyncImage(
                    model = album.coverUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "${album.count} 张",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGrid(
    photos: List<AlbumPhoto>,
    selected: Set<Long>,
    loading: Boolean,
    onToggle: (Long) -> Unit,
    onLoadMore: () -> Unit
) {
    val state = rememberLazyGridState()
    // 用 snapshotFlow 读滚动位置，避免在组合期间读 layoutInfo 触发额外重组
    LaunchedEffect(state, photos.size, loading) {
        snapshotFlow {
            state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collect { last ->
            if (photos.isNotEmpty() && last >= photos.lastIndex - 12 && !loading) onLoadMore()
        }
    }
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(photos, key = { it.id }) { photo ->
            Box {
                AsyncImage(
                    model = photo.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .combinedClickable(onClick = { onToggle(photo.id) }, onLongClick = { onToggle(photo.id) })
                )
                if (photo.id in selected) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "已选",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                    )
                }
            }
        }
        if (loading) {
            item(span = { GridItemSpan(3) }) {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    Text("加载中…", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
