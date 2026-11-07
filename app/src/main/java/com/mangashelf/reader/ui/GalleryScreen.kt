package com.mangashelf.reader.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mangashelf.reader.data.AnimationKind
import com.mangashelf.reader.data.GalleryLayout
import com.mangashelf.reader.data.GallerySort
import com.mangashelf.reader.data.ImageFolder
import com.mangashelf.reader.data.ImageItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片格子的手势日志标签。验收「拖动松手不弹菜单」时用
 * `adb logcat -s TileGesture` 抓完整事件序列。
 */
private const val GESTURE_TAG = "TileGesture"

/**
 * 图片工作栏：独立于书架的文件夹 + 图片浏览。
 *
 * 支持多级文件夹、三档视图、四种排序、长按菜单（重命名/删除/移动）、多选批量。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: GalleryViewModel = viewModel()
) {
    val ctx = LocalContext.current
    val folders by vm.folders.collectAsStateWithLifecycle()
    val images by vm.items.collectAsStateWithLifecycle()
    val crumbs by vm.crumbs.collectAsStateWithLifecycle()
    val layout by vm.layout.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val asc by vm.asc.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var showSortMenu by remember { mutableStateOf(false) }
    var showViewMenu by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showImportSheet by remember { mutableStateOf(false) }
    var albumOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ImageFolder?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<ImageFolder?>(null) }
    var menuFolder by remember { mutableStateOf<ImageFolder?>(null) }
    var menuItem by remember { mutableStateOf<ImageItem?>(null) }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var moveTargetIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var confirmDeleteItems by remember { mutableStateOf(false) }
    var previewIndex by remember { mutableStateOf<Int?>(null) }
    var pendingImport by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingSize by remember { mutableStateOf(0L) }

    // 拖拽归档（网格视图）：长按图片开始拖，拖到某个文件夹上松手即移动
    var dragItem by remember { mutableStateOf<ImageItem?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var hoverFolder by remember { mutableStateOf<ImageFolder?>(null) }
    val folderRects = remember { mutableStateMapOf<Long, Rect>() }
    val itemRects = remember { mutableStateMapOf<Long, Rect>() }

    // 选完先算一下总大小，再让用户确认，避免一不小心把几百张大图倒进来
    LaunchedEffect(pendingImport) {
        if (pendingImport.isEmpty()) {
            pendingSize = 0L
            return@LaunchedEffect
        }
        pendingSize = withContext(Dispatchers.IO) {
            pendingImport.sumOf { uri ->
                runCatching {
                    ctx.contentResolver.query(
                        uri, arrayOf(OpenableColumns.SIZE), null, null, null
                    )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L } ?: 0L
                }.getOrDefault(0L)
            }
        }
    }

    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }

    // Photo Picker：零权限；低版本系统由 Activity 1.9 自动降级到文件选择器
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) pendingImport = uris
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) pendingImport = uris
    }

    // 系统返回键：在子文件夹里先回上级，到根才退出工作栏
    BackHandler(enabled = crumbs.size > 1) {
        if (selectMode) {
            selectMode = false
            selected = emptySet()
        } else {
            vm.goUp()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = crumbs.lastOrNull()?.name ?: "图片",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (crumbs.size > 1) {
                            Text(
                                text = crumbs.joinToString(" / ") { it.name },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    val inSubFolder = crumbs.size > 1
                    IconButton(onClick = {
                        when {
                            selectMode -> {
                                selectMode = false
                                selected = emptySet()
                            }
                            // 在子文件夹里：返回上一级（v1.6.2 修：原来这里直接退出了整个工作栏）
                            inSubFolder -> vm.goUp()
                            else -> onBack()
                        }
                    }) {
                        Icon(
                            imageVector = if (selectMode) Icons.Filled.Clear else Icons.Filled.ArrowBack,
                            contentDescription = when {
                                selectMode -> "退出多选"
                                inSubFolder -> "返回上一级"
                                else -> "返回"
                            }
                        )
                    }
                },
                actions = {
                    WorkspaceSwitch(
                        current = com.mangashelf.reader.data.GalleryPrefs.WORKSPACE_GALLERY,
                        onComic = onBack,
                        onGallery = { },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    IconButton(onClick = { showImportSheet = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "导入或新建")
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Filled.List, contentDescription = "排序")
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            GallerySort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(option.label + if (sort == option) (if (asc) " ↑" else " ↓") else "")
                                    },
                                    onClick = {
                                        showSortMenu = false
                                        vm.setSort(option)
                                    }
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { showViewMenu = true }) {
                            Icon(Icons.Filled.Menu, contentDescription = "视图")
                        }
                        DropdownMenu(expanded = showViewMenu, onDismissRequest = { showViewMenu = false }) {
                            GalleryLayout.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label + if (layout == option) " ✓" else "") },
                                    onClick = {
                                        showViewMenu = false
                                        vm.setLayout(option)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (selectMode) "退出多选" else "多选") },
                                onClick = {
                                    showViewMenu = false
                                    selectMode = !selectMode
                                    selected = emptySet()
                                }
                            )
                        }
                    }
                    // 「新建文件夹」从视图菜单里提出来，放在顶栏一次可见的位置
                    TextButton(onClick = { showNewFolder = true }) {
                        Text("+ 文件夹", style = MaterialTheme.typography.labelMedium)
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (images.isEmpty() && folders.isEmpty()) {
                EmptyGallery(onImport = { showImportSheet = true })
            } else {
                val cell = when (layout) {
                    GalleryLayout.LARGE -> 96.dp
                    GalleryLayout.SMALL -> 68.dp
                    GalleryLayout.LIST -> 0.dp
                }
                if (layout == GalleryLayout.LIST) {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        folders.forEach { folder ->
                            item(key = "f${folder.id}") {
                                FolderRow(
                                    name = folder.name,
                                    onClick = { vm.enter(folder) },
                                    onLongClick = { menuFolder = folder }
                                )
                            }
                        }
                        images.forEach { image ->
                            item(key = "i${image.id}") {
                                ImageRow(
                                    file = vm.fileOf(image),
                                    item = image,
                                    frames = vm.ugoiraFramesOf(image),
                                    selected = selected.contains(image.id),
                                    onClick = {
                                        if (selectMode) selected = selected.toggle(image.id)
                                        else previewIndex = images.indexOf(image)
                                    },
                                    onLongClick = {
                                        if (selectMode) selected = selected.toggle(image.id) else menuItem = image
                                    }
                                )
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(cell),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        folders.forEach { folder ->
                            item(key = "f${folder.id}") {
                                FolderTile(
                                    name = folder.name,
                                    highlighted = hoverFolder?.id == folder.id,
                                    onClick = { vm.enter(folder) },
                                    onLongClick = { menuFolder = folder },
                                    modifier = Modifier.onGloballyPositioned {
                                        folderRects[folder.id] = it.boundsInRoot()
                                    }
                                )
                            }
                        }
                        images.forEach { image ->
                            item(key = "i${image.id}") {
                                ImageTile(
                                    file = vm.fileOf(image),
                                    item = image,
                                    frames = vm.ugoiraFramesOf(image),
                                    selected = selected.contains(image.id),
                                    dragging = dragItem?.id == image.id,
                                    onClick = {
                                        if (selectMode) selected = selected.toggle(image.id)
                                        else previewIndex = images.indexOf(image)
                                    },
                                    modifier = Modifier.onGloballyPositioned {
                                        itemRects[image.id] = it.boundsInRoot()
                                    },
                                    onDragStart = { localOffset ->
                                        dragItem = image
                                        dragPos = (itemRects[image.id]?.topLeft ?: Offset.Zero) + localOffset
                                        hoverFolder = folderAt(dragPos, folderRects, folders)
                                    },
                                    onDrag = { delta ->
                                        dragPos += delta
                                        hoverFolder = folderAt(dragPos, folderRects, folders)
                                    },
                                    onDragEnd = { moved ->
                                        val target = if (moved) hoverFolder else null
                                        android.util.Log.i(
                                            GESTURE_TAG,
                                            "DROP item=${image.id} moved=$moved target=${target?.id ?: "none"} " +
                                                "action=${if (target != null) "MOVE" else if (!moved) "MENU" else "CANCEL_DROP"}"
                                        )
                                        when {
                                            // 拖到文件夹上松手：只移动，不弹菜单、不开预览
                                            target != null -> {
                                                vm.moveItems(listOf(image.id), target.id)
                                                Toast.makeText(ctx, "已移动到「${target.name}」", Toast.LENGTH_SHORT).show()
                                            }
                                            // 长按没动就松手：进多选或弹菜单
                                            !moved -> {
                                                if (selectMode) selected = selected.toggle(image.id)
                                                else menuItem = image
                                            }
                                            // 长按拖到空白处：什么都不做
                                            else -> Unit
                                        }
                                        dragItem = null
                                        hoverFolder = null
                                    },
                                    // 组件销毁 / 手势被接管：只清视觉拖拽态，不弹菜单、不改选中
                                    onCancel = {
                                        dragItem = null
                                        hoverFolder = null
                                    }
                                )
                            }
                        }
                    }
                }
            }

            busy?.let { text ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        // 导入进行中才给「取消」，其它忙碌（删除/移动）不给误触
                        if (text.contains("导入中")) {
                            TextButton(onClick = { vm.cancelImport() }) { Text("取消") }
                        }
                    }
                }
            }

            if (selectMode) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 6.dp
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("已选 ${selected.size} 张", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = {
                            val all = images.map { it.id }.toSet()
                            selected = if (selected.size == all.size) emptySet() else all
                        }) { Text("全选") }
                        TextButton(
                            onClick = { moveTargetIds = selected.toList() },
                            enabled = selected.isNotEmpty()
                        ) { Text("移动") }
                        TextButton(
                            onClick = {
                                val picked = images.filter { selected.contains(it.id) }
                                vm.saveManyToGallery(picked) { ok, total ->
                                    Toast.makeText(
                                        ctx,
                                        "已存 $ok / $total 张到相册",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            enabled = selected.isNotEmpty()
                        ) { Text("相册") }
                        TextButton(
                            onClick = { confirmDeleteItems = true },
                            enabled = selected.isNotEmpty()
                        ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    if (showImportSheet) {
        AlertDialog(
            onDismissRequest = { showImportSheet = false },
            title = { Text("导入图片") },
            text = {
                Text(
                    "「快速从相册选」用系统相册，不申请任何权限；" +
                        "「按相簿浏览」会读取手机里的真实相簿（微信/QQ/相机等），需要相册权限。" +
                        "导入后会在本 App 私有目录留一份副本。"
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        showImportSheet = false
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("快速从相册选") }
                    TextButton(onClick = {
                        showImportSheet = false
                        albumOpen = true
                    }) { Text("按相簿浏览") }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showImportSheet = false }) { Text("取消") }
                    TextButton(onClick = {
                        showImportSheet = false
                        filePicker.launch(arrayOf("image/*"))
                    }) { Text("从文件选") }
                }
            }
        )
    }

    if (albumOpen) {
        AlbumPickerScreen(
            onPick = { uris ->
                albumOpen = false
                if (uris.isNotEmpty()) pendingImport = uris
            },
            onBack = { albumOpen = false }
        )
    }

    if (showNewFolder) {
        NameDialog(
            title = "新建文件夹",
            initial = "",
            onConfirm = { vm.createFolder(it); showNewFolder = false },
            onDismiss = { showNewFolder = false }
        )
    }
    renameTarget?.let { folder ->
        NameDialog(
            title = "重命名文件夹",
            initial = folder.name,
            onConfirm = { vm.renameFolder(folder, it); renameTarget = null },
            onDismiss = { renameTarget = null }
        )
    }

    menuFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { menuFolder = null },
            title = { Text(folder.name) },
            text = { Text("重命名只改名字；删除可以选择连图片一起删。") },
            confirmButton = {
                Row {
                    TextButton(onClick = { renameTarget = folder; menuFolder = null }) { Text("重命名") }
                    TextButton(onClick = { deleteFolderTarget = folder; menuFolder = null }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { menuFolder = null }) { Text("取消") } }
        )
    }

    deleteFolderTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleteFolderTarget = null },
            title = { Text("删除「${folder.name}」？") },
            text = { Text("可以连里面的图片一起删，也可以只删文件夹（图片移回上一级）。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteFolder(folder, true); deleteFolderTarget = null }) {
                    Text("连图片一起删", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { deleteFolderTarget = null }) { Text("取消") }
                    TextButton(onClick = { vm.deleteFolder(folder, false); deleteFolderTarget = null }) {
                        Text("只删文件夹")
                    }
                }
            }
        )
    }

    menuItem?.let { item ->
        // v1.6.2：菜单只放动作，不再显示文件名与尺寸。
        // 详情统一放在预览页底部的信息条里，避免"长按=详情"和"长按=拖拽"抢同一个手势。
        AlertDialog(
            onDismissRequest = { menuItem = null },
            title = { Text("图片操作") },
            text = { Text("这一张该怎么处理？") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        vm.saveToGallery(item) { ok, msg ->
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        }
                        menuItem = null
                    }) { Text("存到相册") }
                    TextButton(onClick = { moveTargetIds = listOf(item.id); menuItem = null }) { Text("移动") }
                    TextButton(onClick = {
                        vm.deleteItems(listOf(item.id))
                        menuItem = null
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton(onClick = { menuItem = null }) { Text("取消") } }
        )
    }

    if (confirmDeleteItems) {
        AlertDialog(
            onDismissRequest = { confirmDeleteItems = false },
            title = { Text("删除 ${selected.size} 张图片？") },
            text = { Text("图片文件和记录会一起删掉，不能恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteItems(selected.toList())
                    confirmDeleteItems = false
                    selectMode = false
                    selected = emptySet()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteItems = false }) { Text("取消") } }
        )
    }

    if (moveTargetIds.isNotEmpty()) {
        MoveTargetDialog(
            crumbs = crumbs,
            onPick = { target ->
                vm.moveItems(moveTargetIds, target)
                moveTargetIds = emptyList()
                selectMode = false
                selected = emptySet()
            },
            onDismiss = { moveTargetIds = emptyList() }
        )
    }

    previewIndex?.let { index ->
        ImagePreviewScreen(
            items = images,
            initialIndex = index,
            fileOf = { vm.fileOf(it) },
            ugoiraFramesOf = { vm.ugoiraFramesOf(it) },
            onClose = { previewIndex = null },
            folderNames = folders.associate { it.id to it.name }
        )
    }

    if (pendingImport.isNotEmpty()) {
        val sizeText = if (pendingSize > 0) "约 ${gallerySize(pendingSize)}" else "大小未知"
        AlertDialog(
            onDismissRequest = { pendingImport = emptyList() },
            title = { Text("导入 ${pendingImport.size} 张图片？") },
            text = {
                Text("合计 $sizeText。会复制一份到 App 私有目录，原图不动；导入过程中可以取消。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val picked = pendingImport
                    pendingImport = emptyList()
                    vm.importImages(picked)
                }) { Text("开始导入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = emptyList() }) { Text("取消") }
            }
        )
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> =
    if (contains(id)) this - id else this + id

@Composable
private fun EmptyGallery(onImport: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("图片工作栏还是空的", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "这里和书架互不影响，可以建文件夹分门别类放图片。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        TextButton(onClick = onImport) { Text("导入图片") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(
    name: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (highlighted) Modifier.border(
                    width = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(10.dp)
                ) else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("📁", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageTile(
    file: File,
    item: ImageItem,
    frames: List<com.mangashelf.reader.data.UgoiraFrame>,
    selected: Boolean,
    dragging: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDragStart: (Offset) -> Unit = {},
    onDrag: (Offset) -> Unit = {},
    onDragEnd: (Boolean) -> Unit = {},
    // 手势被取消（组件被移出组合、外层抢走事件、系统返回手势、切后台）。
    // 语义上「取消」≠「没移动」：取消时必须只清拖拽态，绝不能弹菜单。
    onCancel: () -> Unit = {}
) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .then(if (dragging) Modifier.alpha(0.45f) else Modifier)
            // 长按在这里是空操作：真正处理长按的是下面的 detectDragGesturesAfterLongPress。
            // combinedClickable 只负责「短按=预览」并抑制长按后误触发 onClick。
            .combinedClickable(onClick = onClick, onLongClick = {})
            .pointerInput(item.id) {
                var total = Offset.Zero
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        total = Offset.Zero
                        android.util.Log.i(GESTURE_TAG, "LONG_PRESS item=${item.id} state=LONG_PRESS_PENDING")
                        onDragStart(offset)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        total += amount
                        onDrag(amount)
                    },
                    onDragEnd = {
                        val dist = total.getDistance()
                        val moved = dist > viewConfiguration.touchSlop
                        android.util.Log.i(
                            GESTURE_TAG,
                            "UP item=${item.id} dist=${dist.toInt()}px slop=${viewConfiguration.touchSlop.toInt()}px moved=$moved state=DROPPING"
                        )
                        onDragEnd(moved)
                    },
                    // 这里绝对不能复用 onDragEnd(false)：拖动成功后视图重组会销毁本组件，
                    // 从而触发 cancel；若把它当成「没移动」，就会在移动成功后弹出图片操作菜单。
                    onDragCancel = {
                        android.util.Log.i(GESTURE_TAG, "CANCEL item=${item.id} state=IDLE (no menu)")
                        onCancel()
                    }
                )
            }
    ) {
        AnimatedImage(
            file = file,
            item = item,
            playing = false,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            ugoiraFrames = frames
        )
        if (item.isAnimated) {
            AnimatedBadge(
                kind = AnimationKind.fromName(item.kind),
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
            )
        }
        if (selected) {
            Text(
                "✓",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(name: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📁", Modifier.width(32.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageRow(
    file: File,
    item: ImageItem,
    frames: List<com.mangashelf.reader.data.UgoiraFrame>,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surface
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))) {
            AnimatedImage(
                file = file,
                item = item,
                playing = false,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                ugoiraFrames = frames
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            item.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (item.isAnimated) {
            Text(
                AnimationKind.fromName(item.kind).label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                singleLine = true,
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun MoveTargetDialog(
    crumbs: List<ImageFolder>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到") },
        text = {
            Column {
                TextButton(onClick = { onPick(ImageFolder.ROOT) }) { Text("根目录") }
                crumbs.filter { it.id != ImageFolder.ROOT }.forEach { folder ->
                    TextButton(onClick = { onPick(folder.id) }) { Text(folder.name) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun gallerySize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1) return "$bytes B"
    val mb = kb / 1024.0
    return if (mb < 1) String.format(java.util.Locale.US, "%.0f KB", kb)
    else String.format(java.util.Locale.US, "%.1f MB", mb)
}

/** 拖拽位置落在哪个文件夹上；没有则 null。 */
private fun folderAt(pos: Offset, rects: Map<Long, Rect>, folders: List<ImageFolder>): ImageFolder? =
    folders.firstOrNull { rects[it.id]?.contains(pos) == true }
