package com.mangashelf.reader.ui

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mangashelf.reader.MangaShelfApp
import com.mangashelf.reader.data.AnimationKind
import com.mangashelf.reader.data.AnimationSniffer
import com.mangashelf.reader.data.AppDatabase
import com.mangashelf.reader.data.GalleryExporter
import com.mangashelf.reader.data.GalleryLayout
import com.mangashelf.reader.data.GalleryPrefs
import com.mangashelf.reader.data.GalleryRepository
import com.mangashelf.reader.data.GallerySort
import com.mangashelf.reader.data.GalleryStorage
import com.mangashelf.reader.data.ImageFolder
import com.mangashelf.reader.data.ImageItem
import com.mangashelf.reader.data.ImportBatch
import com.mangashelf.reader.data.UgoiraParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 图片工作栏。与书架完全隔离：
 * 表是 image_folders / image_items，文件在 files/gallery/，偏好是 gallery_prefs。
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MangaShelfApp
    private val repo: GalleryRepository = app.galleryRepository
    private val storage: GalleryStorage = app.galleryStorage

    private val _folders = MutableStateFlow<List<ImageFolder>>(emptyList())
    val folders: StateFlow<List<ImageFolder>> = _folders.asStateFlow()

    private val _items = MutableStateFlow<List<ImageItem>>(emptyList())
    val items: StateFlow<List<ImageItem>> = _items.asStateFlow()

    private val _crumbs = MutableStateFlow<List<ImageFolder>>(emptyList())
    val crumbs: StateFlow<List<ImageFolder>> = _crumbs.asStateFlow()

    private val _currentId = MutableStateFlow(ImageFolder.ROOT)
    val currentId: StateFlow<Long> = _currentId.asStateFlow()

    private val _layout = MutableStateFlow(GalleryLayout.LARGE)
    val layout: StateFlow<GalleryLayout> = _layout.asStateFlow()

    private val _sort = MutableStateFlow(GallerySort.TIME)
    val sort: StateFlow<GallerySort> = _sort.asStateFlow()

    private val _asc = MutableStateFlow(false)
    val asc: StateFlow<Boolean> = _asc.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    /** 导入串行化：单独导入与批量导入共用同一把锁，避免并发送 index 冲突 */
    private val importMutex = Mutex()
    /** 当前导入任务，用于「取消导入」 */
    private var importJob: Job? = null
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // 恢复上次停留的文件夹与视图偏好；文件夹已被删时回退到根
        val ctx = getApplication<Application>()
        _layout.value = GalleryPrefs.loadLayout(ctx)
        _sort.value = GalleryPrefs.loadSort(ctx)
        _asc.value = GalleryPrefs.loadSortAsc(ctx)
        val saved = GalleryPrefs.loadCurrentFolder(ctx)
        viewModelScope.launch {
            val exists = withContext(Dispatchers.IO) { repo.folder(saved) }
            _currentId.value = if (exists != null) saved else ImageFolder.ROOT
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val id = _currentId.value
            val sortMode = _sort.value
            val ascFlag = _asc.value
            val pair = withContext(Dispatchers.IO) {
                val list = repo.observeFolders(id, sortMode, ascFlag).first()
                val imgs = repo.observeItems(id, sortMode, ascFlag).first()
                list to imgs
            }
            val chain = withContext(Dispatchers.IO) { repo.breadcrumb(id) }
            _folders.value = pair.first
            _items.value = pair.second
            _crumbs.value = chain
            GalleryPrefs.saveCurrentFolder(getApplication(), id)
        }
    }

    fun enter(folder: ImageFolder) {
        _currentId.value = folder.id
        refresh()
    }

    fun goUp() {
        val chain = _crumbs.value
        _currentId.value = if (chain.size >= 2) chain[chain.size - 2].id else ImageFolder.ROOT
        refresh()
    }

    fun goRoot() {
        _currentId.value = ImageFolder.ROOT
        refresh()
    }

    fun enterCrumb(index: Int) {
        val chain = _crumbs.value
        _currentId.value = chain.getOrNull(index)?.id ?: ImageFolder.ROOT
        refresh()
    }

    fun setLayout(choice: GalleryLayout) {
        _layout.value = choice
        GalleryPrefs.saveLayout(getApplication(), choice)
    }

    fun setSort(choice: GallerySort) {
        // 再点同一项 = 翻转升降序
        if (choice == _sort.value) {
            _asc.value = !_asc.value
        } else {
            _sort.value = choice
            _asc.value = choice == GallerySort.NAME
        }
        GalleryPrefs.saveSort(getApplication(), _sort.value, _asc.value)
        refresh()
    }

    fun notify(text: String) {
        _message.value = text
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** 新建文件夹；同名（忽略大小写）返回失败提示 */
    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            notify("名字不能为空")
            return
        }
        if (trimmed.length > 40) {
            notify("名字太长（最多 40 字）")
            return
        }
        viewModelScope.launch {
            val made = withContext(Dispatchers.IO) { repo.createFolder(_currentId.value, trimmed) }
            notify(if (made == null) "这个文件夹里已经有同名的了" else "已新建「${made.name}」")
            if (made != null) refresh()
        }
    }

    fun renameFolder(folder: ImageFolder, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            notify("名字不能为空")
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { repo.renameFolder(folder.id, trimmed) }
            notify(if (ok) "已重命名为「$trimmed」" else "这个文件夹里已经有同名的了")
            if (ok) refresh()
        }
    }

    fun deleteFolder(folder: ImageFolder, deleteContent: Boolean) {
        viewModelScope.launch {
            _busy.value = "正在删除…"
            val removed = withContext(Dispatchers.IO) { repo.deleteFolder(folder.id, deleteContent) }
            _busy.value = null
            notify(if (deleteContent) "已删除文件夹和 $removed 张图片" else "已删除文件夹，$removed 张图片已移回上级")
            refresh()
        }
    }

    fun moveItems(ids: List<Long>, folderId: Long) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.moveItems(ids, folderId) }
            notify("已移动 ${ids.size} 张图片")
            refresh()
        }
    }

    fun deleteItems(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _busy.value = "正在删除…"
            withContext(Dispatchers.IO) { repo.deleteItems(ids) }
            _busy.value = null
            notify("已删除 ${ids.size} 张图片")
            refresh()
        }
    }

    fun saveItemOrder(orderedIds: List<Long>) {
        val byId = _items.value.associateBy { it.id }
        val ordered = orderedIds.mapNotNull { byId[it] }
        if (ordered.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.saveItemOrder(ordered) }
            refresh()
        }
    }

    /** 导入图片：逐张复制进私有目录，再写库。单张失败跳过，不中断整批。
     *  用 Mutex 串行化，避免用户连点「导入」时两个批次同时写同一个文件夹的 sortIndex。
     *  支持中途取消：已导入的图片会保留，未开始的跳过。 */
    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val folderId = _currentId.value
        var ok = 0
        var fail = 0
        importJob = viewModelScope.launch {
            try {
                importMutex.withLock {
                    _busy.value = "正在导入 0 / ${uris.size}"
                    val done = withContext(Dispatchers.IO) {
                        val copied = ArrayList<ImageItem>(uris.size)
                        var nextIndex = repo.nextSortIndex(folderId)
                        for (index in uris.indices) {
                            ensureActive()
                            val item = runCatching { copyOne(uris[index], folderId, nextIndex) }.getOrNull()
                            if (item != null) {
                                copied += item
                                nextIndex++
                                ok++
                            } else {
                                fail++
                            }
                            _busy.value = "导入中 ${index + 1} / ${uris.size}（成功 $ok · 失败 $fail）"
                        }
                        if (copied.isNotEmpty()) repo.addItems(copied)
                        copied
                    }
                    _busy.value = null
                    if (done.isNotEmpty()) {
                        // 记一条导入历史，来源标 GALLERY，这样「导入历史」里能和漫画导入区分开
                        runCatching {
                            val now = System.currentTimeMillis()
                            AppDatabase.get(app).importBatchDao().insert(
                                ImportBatch(
                                    batchId = "gallery-$now",
                                    createdAt = now,
                                    finishedAt = now,
                                    sourceType = "GALLERY",
                                    status = ImportBatch.DONE,
                                    totalBooks = 1,
                                    doneBooks = 1,
                                    totalPages = done.size,
                                    donePages = done.size,
                                    sizeBytes = done.sumOf { it.sizeBytes }
                                )
                            )
                        }
                    }
                    notify(
                        when {
                            done.isEmpty() && fail == 0 -> "没有导入任何图片"
                            done.isEmpty() -> "导入失败：$fail 张都没读出来（格式不支持或读取被拒）"
                            fail == 0 -> "已导入 ${done.size} 张图片"
                            else -> "导入完成：成功 ${done.size} 张，失败 $fail 张"
                        }
                    )
                    refresh()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _busy.value = null
                notify("已取消导入（已完成的 $ok 张会保留）")
                refresh()
            } finally {
                _busy.value = null
                importJob = null
            }
        }
    }

    /** 取消正在进行的导入。已经复制完并入库的图片不会回滚。 */
    fun cancelImport() {
        importJob?.cancel()
        importJob = null
    }

    private suspend fun copyOne(uri: Uri, folderId: Long, sortIndex: Int): ImageItem? {
        val ctx = getApplication<Application>()
        val resolver = ctx.contentResolver
        var name = "image.jpg"
        var size = 0L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
                    if (sizeIdx >= 0) size = c.getLong(sizeIdx)
                }
            }
        }
        val rel = storage.newRelPath(name)
        val target = storage.fileOf(rel)
        target.parentFile?.mkdirs()
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        } ?: return null
        if (!target.isFile || target.length() == 0L) {
            runCatching { target.delete() }
            return null
        }

        // 先嗅探真实格式：动图（GIF/WebP/APNG/Ugoira/Lottie/SVGA）不一定是「能被 BitmapFactory 解码的图片」
        val sniffed = AnimationSniffer.sniff(target, name)

        // Ugoira 是 zip，需要把帧解出来；解不出来就当不支持的动图，删文件并返回 null
        var frames = sniffed.frames
        var durationMs = sniffed.durationMs
        if (sniffed.kind == AnimationKind.UGOIRA) {
            val uuid = rel.substringBeforeLast('.')
            val dir = storage.ugoiraDir(uuid)
            val unpacked = UgoiraParser.unpack(target, dir)
            if (unpacked.isEmpty()) {
                runCatching { target.delete() }
                runCatching { dir.deleteRecursively() }
                return null
            }
            frames = unpacked.size
            durationMs = UgoiraParser.totalDuration(unpacked)
        }

        // 只读尺寸，不解码整张图，避免大图 OOM。Lottie/SVGA/Ugoira 不是位图，尺寸留 0。
        var w = 0
        var h = 0
        if (sniffed.kind == AnimationKind.STATIC || sniffed.kind == AnimationKind.GIF ||
            sniffed.kind == AnimationKind.WEBP_ANIM || sniffed.kind == AnimationKind.APNG
        ) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(target.absolutePath, opts) }
            w = opts.outWidth.coerceAtLeast(0)
            h = opts.outHeight.coerceAtLeast(0)
            if (w <= 0 || h <= 0) {
                // 不是能被解码的图片（比如选了 pdf / txt），删掉刚复制的文件
                runCatching { target.delete() }
                return null
            }
        } else {
            w = sniffed.width
            h = sniffed.height
        }

        return ImageItem(
            folderId = folderId,
            name = name,
            relPath = rel,
            sizeBytes = if (size > 0) size else target.length(),
            modifiedAt = System.currentTimeMillis(),
            width = w,
            height = h,
            sortIndex = sortIndex,
            createdAt = System.currentTimeMillis(),
            mimeType = sniffed.mime,
            kind = sniffed.kind.name,
            frames = frames,
            durationMs = durationMs
        )
    }

    fun fileOf(item: ImageItem): File = storage.fileOf(item.relPath)

    /** Ugoira 的帧文件（列表/预览共用）。非 Ugoira 返回空列表。 */
    fun ugoiraFramesOf(item: ImageItem): List<File> {
        if (item.kind != AnimationKind.UGOIRA.name) return emptyList()
        val uuid = item.relPath.substringBeforeLast('.')
        return runCatching {
            storage.ugoiraDir(uuid).listFiles()?.filter { it.isFile }?.sortedBy { it.name }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** 保存单张到系统相册（动图原样复制），结果通过回调给 UI。 */
    fun saveToGallery(item: ImageItem, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                GalleryExporter.saveOne(getApplication(), fileOf(item), item.name)
            }.getOrDefault(false)
            onResult(
                ok,
                if (ok) "已保存到相册（Pictures/MangaShelf）" else "保存失败，可能是空间不足或相册写入被拒"
            )
        }
    }

    /** 批量保存到相册，返回成功数。 */
    fun saveManyToGallery(items: List<ImageItem>, onResult: (Int, Int) -> Unit) {
        viewModelScope.launch {
            var ok = 0
            for (item in items) {
                if (runCatching { GalleryExporter.saveOne(getApplication(), fileOf(item), item.name) }
                        .getOrDefault(false)
                ) ok++
            }
            onResult(ok, items.size)
        }
    }
}
