package com.mangashelf.reader.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 图片导入工作栏的仓库。
 *
 * 刻意不复用 [BookRepository]：两个工作栏的实体、目录、排序、搜索都是两套，
 * 复用只会让"某天一个改动同时影响两边"变成常态。
 */
class GalleryRepository(
    private val dao: ImageDao,
    private val storage: GalleryStorage
) {

    // ------------------------------------------------------------- 文件夹

    fun observeFolders(parentId: Long, sort: GallerySort, asc: Boolean): Flow<List<ImageFolder>> =
        dao.observeFolders(parentId).map { sortFolders(it, sort, asc) }

    fun observeAllFolders(): Flow<List<ImageFolder>> = dao.observeAllFolders()

    suspend fun folder(id: Long): ImageFolder? = dao.folderById(id)

    /** 新建文件夹；同名（同层、忽略大小写）返回 null，由 UI 提示 */
    suspend fun createFolder(parentId: Long, name: String): ImageFolder? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        if (dao.nameTaken(parentId, trimmed) > 0) return null
        val folder = ImageFolder(name = trimmed, parentId = parentId)
        val id = dao.insertFolder(folder)
        return folder.copy(id = id)
    }

    suspend fun renameFolder(id: Long, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val current = dao.folderById(id) ?: return false
        if (dao.nameTaken(current.parentId, trimmed) > 0 && !trimmed.equals(current.name, true)) return false
        dao.renameFolder(id, trimmed)
        return true
    }

    suspend fun saveFolderSort(id: Long, sort: GallerySort, asc: Boolean) {
        dao.updateFolderSort(id, sort.name, asc)
    }

    /**
     * 删除文件夹。
     *
     * [deleteContent] = true：连同子文件夹与里面的图片一起删（文件也删）。
     * false：只删文件夹这一层，其中的图片移回根目录（不丢图）。
     */
    suspend fun deleteFolder(id: Long, deleteContent: Boolean): Int {
        val ids = mutableListOf<Long>()
        collectSubtree(id, ids)
        var removedItems = 0
        for (folderId in ids) {
            val items = dao.itemsIn(folderId)
            if (deleteContent) {
                items.forEach { storage.deleteFile(it.relPath) }
                dao.deleteItems(items.map { it.id })
                removedItems += items.size
            } else {
                // 移回根：交给根目录排序，保持原有先后
                dao.moveToFolder(items.map { it.id }, ImageFolder.ROOT)
            }
        }
        // 后序删除，先子后父
        ids.asReversed().forEach { dao.deleteFolderRow(it) }
        return removedItems
    }

    private suspend fun collectSubtree(id: Long, out: MutableList<Long>) {
        out += id
        dao.foldersIn(id).forEach { collectSubtree(it.id, out) }
    }

    /** 从根到 [id] 的路径，给面包屑用 */
    suspend fun breadcrumb(id: Long): List<ImageFolder> {
        val chain = mutableListOf<ImageFolder>()
        var cursor = id
        var guard = 0
        while (cursor != ImageFolder.ROOT && guard++ < 64) {
            val folder = dao.folderById(cursor) ?: break
            chain.add(0, folder)
            cursor = folder.parentId
        }
        return chain
    }

    // --------------------------------------------------------------- 图片

    fun observeItems(folderId: Long, sort: GallerySort, asc: Boolean): Flow<List<ImageItem>> =
        dao.observeItems(folderId).map { sortItems(it, sort, asc) }

    suspend fun itemsIn(folderId: Long): List<ImageItem> = dao.itemsIn(folderId)

    suspend fun nextSortIndex(folderId: Long): Int = dao.maxSortIndex(folderId) + 1

    suspend fun addItems(items: List<ImageItem>) {
        if (items.isEmpty()) return
        dao.insertItems(items)
    }

    suspend fun moveItems(ids: List<Long>, targetFolderId: Long) {
        if (ids.isEmpty()) return
        dao.moveToFolder(ids, targetFolderId)
    }

    /** 删除图片（数据库行 + 文件 + 缩略图） */
    suspend fun deleteItems(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        val files = ids.mapNotNull { dao.itemById(it) }
        files.forEach { storage.deleteFile(it.relPath) }
        dao.deleteItems(ids)
        return files.size
    }

    suspend fun saveItemOrder(ordered: List<ImageItem>) {
        ordered.forEachIndexed { index, item -> dao.updateSortIndex(item.id, index) }
    }

    suspend fun search(keyword: String): List<ImageItem> {
        val q = keyword.trim()
        if (q.isEmpty()) return emptyList()
        return dao.allItems().filter { it.name.contains(q, ignoreCase = true) }
    }

    suspend fun allItems(): List<ImageItem> = dao.allItems()

    /** 清理孤儿文件（启动时调用） */
    suspend fun cleanupOrphans(): Int {
        val known = dao.allItems().map { it.relPath }.toSet()
        return storage.cleanupOrphans(known)
    }

    // -------------------------------------------------------------- 排序

    private fun sortFolders(list: List<ImageFolder>, sort: GallerySort, asc: Boolean): List<ImageFolder> {
        val base: List<ImageFolder> = when (sort) {
            GallerySort.TIME -> list.sortedBy { it.createdAt }
            GallerySort.NAME -> list.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.CHINA)) { it.name })
            GallerySort.SIZE, GallerySort.IMPORT -> list.sortedBy { it.createdAt }
        }
        return if (asc) base else base.reversed()
    }

    private fun sortItems(list: List<ImageItem>, sort: GallerySort, asc: Boolean): List<ImageItem> {
        val base: List<ImageItem> = when (sort) {
            GallerySort.IMPORT -> list.sortedBy { it.sortIndex }
            GallerySort.TIME -> list.sortedBy { it.modifiedAt }
            GallerySort.SIZE -> list.sortedBy { it.sizeBytes }
            GallerySort.NAME -> list.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.CHINA)) { it.name })
        }
        return if (asc) base else base.reversed()
    }
}
