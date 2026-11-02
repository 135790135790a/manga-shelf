package com.mangashelf.reader.data

import android.content.Context

/** 图片工作栏的三种视图 */
enum class GalleryLayout(val label: String) {
    LARGE("大图标"),
    SMALL("小图标"),
    LIST("列表");

    companion object {
        fun fromName(name: String?): GalleryLayout =
            entries.firstOrNull { it.name == name } ?: LARGE
    }
}

/** 图片工作栏与大文件夹列表的排序方式（与漫画书架的 ShelfSort 是两套） */
enum class GallerySort(val label: String) {
    TIME("时间"),
    NAME("名称"),
    SIZE("大小"),
    IMPORT("导入顺序")
}

/**
 * 图片导入工作栏的偏好。
 *
 * 与书架偏好（`shelf_prefs` / `import_sort` / `dir_state`）**完全分开的文件**：
 * 两个工作栏的视图、排序、上次位置、搜索历史都不共享，改一边不会影响另一边。
 */
object GalleryPrefs {

    private const val FILE = "gallery_prefs"

    private const val KEY_LAYOUT = "layout"
    private const val KEY_CURRENT_FOLDER = "current_folder"
    private const val KEY_WORKSPACE = "workspace"
    private const val KEY_SORT = "sort"
    private const val KEY_SORT_ASC = "sort_asc"
    private const val KEY_HISTORY = "search_history"

    /** 顶层用哪一边：COMIC / GALLERY，重启后保持上次所在工作栏 */
    const val WORKSPACE_COMIC = "COMIC"
    const val WORKSPACE_GALLERY = "GALLERY"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun loadWorkspace(context: Context): String =
        runCatching { prefs(context).getString(KEY_WORKSPACE, WORKSPACE_COMIC) }
            .getOrNull() ?: WORKSPACE_COMIC

    fun saveWorkspace(context: Context, workspace: String) {
        runCatching { prefs(context).edit().putString(KEY_WORKSPACE, workspace).apply() }
    }

    fun loadLayout(context: Context): GalleryLayout = GalleryLayout.fromName(
        runCatching { prefs(context).getString(KEY_LAYOUT, null) }.getOrNull()
    )

    fun saveLayout(context: Context, layout: GalleryLayout) {
        runCatching { prefs(context).edit().putString(KEY_LAYOUT, layout.name).apply() }
    }

    fun loadCurrentFolder(context: Context): Long =
        runCatching { prefs(context).getLong(KEY_CURRENT_FOLDER, 0L) }.getOrDefault(0L)

    fun saveCurrentFolder(context: Context, folderId: Long) {
        runCatching { prefs(context).edit().putLong(KEY_CURRENT_FOLDER, folderId).apply() }
    }

    fun loadSort(context: Context): GallerySort {
        val name = runCatching { prefs(context).getString(KEY_SORT, null) }.getOrNull()
        return GallerySort.entries.firstOrNull { it.name == name } ?: GallerySort.IMPORT
    }

    fun loadSortAsc(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_SORT_ASC, true) }.getOrDefault(true)

    fun saveSort(context: Context, sort: GallerySort, asc: Boolean) {
        runCatching {
            prefs(context).edit()
                .putString(KEY_SORT, sort.name)
                .putBoolean(KEY_SORT_ASC, asc)
                .apply()
        }
    }

    // 搜索历史（与书架那套的 search_prefs 分开）

    const val MAX_HISTORY = 20

    fun loadHistory(context: Context): List<String> {
        val raw = runCatching { prefs(context).getString(KEY_HISTORY, null) }.getOrNull() ?: return emptyList()
        return raw.split('\u0001').filter { it.isNotBlank() }
    }

    fun addHistory(context: Context, keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        val list = loadHistory(context).filterNot { it.equals(k, ignoreCase = true) }
        val next = (listOf(k) + list).take(MAX_HISTORY)
        runCatching {
            prefs(context).edit().putString(KEY_HISTORY, next.joinToString("\u0001")).apply()
        }
    }

    fun clearHistory(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_HISTORY).apply() }
    }
}
