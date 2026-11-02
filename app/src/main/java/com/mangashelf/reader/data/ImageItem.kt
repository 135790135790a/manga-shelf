package com.mangashelf.reader.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 图片导入工作栏里的一张图片。
 *
 * 存放策略：[relPath] 相对 `files/gallery/`，**所有图片平铺在同一层**，
 * 归属靠 [folderId] 而不是目录。
 *
 * 为什么平铺：需求允许「把图片移动到别的文件夹」。若用真实目录，
 * 移动 = 复制 + 删原文件，一千张图的中途被杀就会留下半份；平铺时移动
 * 只是改一个整数列，原子且瞬时，也彻底避免了跨目录重名的处理。
 *
 * [folderId] 为 0 表示根。
 */
@Entity(
    tableName = "image_items",
    indices = [
        Index("folderId"),
        Index(value = ["folderId", "sortIndex"])
    ]
)
data class ImageItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 0 = 根目录 */
    @ColumnInfo(defaultValue = "0")
    val folderId: Long = 0,
    /** 原始文件名，用于导出与「保存到相册」时的命名 */
    val name: String,
    /** 相对 `files/gallery/` 的路径，例如 `a1b2c3d4.jpg`；也用于缩略图 `thumbs/a1b2c3d4.webp` */
    val relPath: String,
    @ColumnInfo(defaultValue = "0")
    val sizeBytes: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val modifiedAt: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val width: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val height: Int = 0,
    /** 手动排序位置，越小越靠前；新建时取当前最大值 + 1 */
    @ColumnInfo(defaultValue = "0")
    val sortIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val ROOT = 0L
    }
}
