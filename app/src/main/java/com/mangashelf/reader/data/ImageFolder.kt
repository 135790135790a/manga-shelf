package com.mangashelf.reader.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 图片导入工作栏里的一个文件夹。
 *
 * 与漫画（[Book]）完全隔离：不在同一张表、不共用目录、不共用排序设置。
 * 用「独立表」而不是「Book 加一个 workspace 字段」的理由是 Book 表被八处查询
 * 碰到（书架、搜索、统计、备份、导入历史、清理、排序、埋点），加字段等于每处
 * 都得记得过滤，漏一处就把图片当成漫画显示出来了。
 *
 * [parentId] 为 0 表示根目录。多级嵌套靠它自引用；不做级联外键，
 * 删除文件夹时由 [ImageDao] 手动递归，避免误删整棵树。
 */
@Entity(
    tableName = "image_folders",
    indices = [Index("parentId")]
)
data class ImageFolder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 0 = 根目录 */
    @androidx.room.ColumnInfo(defaultValue = "0")
    val parentId: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** TIME / NAME / SIZE / IMPORT */
    @androidx.room.ColumnInfo(defaultValue = "TIME")
    val sortMode: String = "TIME",
    @androidx.room.ColumnInfo(defaultValue = "0")
    val sortAsc: Boolean = false
) {
    companion object {
        const val ROOT = 0L
    }
}
