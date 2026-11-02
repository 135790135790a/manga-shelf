package com.mangashelf.reader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 图片导入工作栏的数据访问。
 *
 * 刻意与 [BookDao] / [PageDao] 分开：任何一处 JOIN 或共用查询都会让两个工作栏
 * 的数据串在一起，而"串数据"正是这次需求里明令禁止的。
 */
@Dao
interface ImageDao {

    // ---------------------------------------------------------------- 文件夹

    @Query("SELECT * FROM image_folders WHERE parentId = :parentId ORDER BY name COLLATE NOCASE ASC")
    fun observeFolders(parentId: Long): Flow<List<ImageFolder>>

    @Query("SELECT * FROM image_folders ORDER BY name COLLATE NOCASE ASC")
    fun observeAllFolders(): Flow<List<ImageFolder>>

    @Query("SELECT * FROM image_folders WHERE id = :id")
    fun folderById(id: Long): ImageFolder?

    @Query("SELECT * FROM image_folders WHERE parentId = :parentId")
    suspend fun foldersIn(parentId: Long): List<ImageFolder>

    @Query("SELECT COUNT(*) FROM image_folders WHERE parentId = :parentId")
    suspend fun childFolderCount(parentId: Long): Int

    @Query("SELECT COUNT(*) FROM image_folders WHERE parentId = :parentId AND name = :name COLLATE NOCASE")
    suspend fun nameTaken(parentId: Long, name: String): Int

    @Insert
    suspend fun insertFolder(folder: ImageFolder): Long

    @Query("UPDATE image_folders SET name = :name WHERE id = :id")
    suspend fun renameFolder(id: Long, name: String)

    @Query("UPDATE image_folders SET sortMode = :mode, sortAsc = :asc WHERE id = :id")
    suspend fun updateFolderSort(id: Long, mode: String, asc: Boolean)

    @Query("DELETE FROM image_folders WHERE id = :id")
    suspend fun deleteFolderRow(id: Long)

    // ---------------------------------------------------------------- 图片

    @Query("SELECT * FROM image_items WHERE folderId = :folderId ORDER BY sortIndex ASC, id ASC")
    fun observeItems(folderId: Long): Flow<List<ImageItem>>

    @Query("SELECT * FROM image_items ORDER BY createdAt ASC")
    fun observeAllItems(): Flow<List<ImageItem>>

    @Query("SELECT * FROM image_items WHERE folderId = :folderId ORDER BY sortIndex ASC, id ASC")
    suspend fun itemsIn(folderId: Long): List<ImageItem>

    @Query("SELECT * FROM image_items ORDER BY createdAt ASC")
    suspend fun allItems(): List<ImageItem>

    @Query("SELECT * FROM image_items WHERE id = :id")
    suspend fun itemById(id: Long): ImageItem?

    @Query("SELECT COUNT(*) FROM image_items WHERE folderId = :folderId")
    suspend fun itemCount(folderId: Long): Int

    @Query("SELECT COALESCE(MAX(sortIndex), 0) FROM image_items WHERE folderId = :folderId")
    suspend fun maxSortIndex(folderId: Long): Int

    @Insert
    suspend fun insertItems(items: List<ImageItem>): List<Long>

    @Query("UPDATE image_items SET sortIndex = :index WHERE id = :id")
    suspend fun updateSortIndex(id: Long, index: Int)

    /** 移动图片到另一个文件夹；[folderId] 传 0 即移回根 */
    @Query("UPDATE image_items SET folderId = :folderId WHERE id IN (:ids)")
    suspend fun moveToFolder(ids: List<Long>, folderId: Long)

    @Query("DELETE FROM image_items WHERE id IN (:ids)")
    suspend fun deleteItems(ids: List<Long>)

    @Query("DELETE FROM image_items WHERE folderId = :folderId")
    suspend fun deleteItemsInFolder(folderId: Long)
}
