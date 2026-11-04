package com.mangashelf.reader.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 系统相册里的一个「相簿」（MediaStore 的 bucket）。 */
data class Album(
    val bucketId: String,
    val name: String,
    val count: Int,
    /** 该相簿最新一张的 content Uri，用作封面 */
    val coverUri: Uri?
)

/** 相簿里的一张照片。 */
data class AlbumPhoto(
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mime: String
)

/**
 * 读取手机真实相册分类。
 *
 * v1.6.2 之前相册导入走的是系统 Photo Picker（`PickMultipleVisualMedia`），分类由系统决定，
 * 只出现「相机 / 屏幕截图 / 下载内容」。这里改用 MediaStore 的 `BUCKET_ID` 分组，
 * 能列出微信、QQ、各相机 App 以及用户自建相簿。
 *
 * 权限：
 * - Android 13+：`READ_MEDIA_IMAGES`
 * - Android 12 及以下：`READ_EXTERNAL_STORAGE`
 * - Android 14+ 用户只授权了部分照片时，Cursor 只会返回被授权的那部分（系统行为），
 *   调用方需要提示「仅显示已授权照片」。
 *
 * 所有查询都包了 runCatching，取不到就返回空表，不抛异常。
 */
object AlbumRepository {

    private const val PAGE = 120

    /** 列出设备上真实存在的相簿，按照片数量降序。 */
    suspend fun albums(context: Context): List<Album> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )
            val sort = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            val byBucket = LinkedHashMap<String, Album>()
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, sort)
                ?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                    val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        val bucketId = c.getString(bucketIdCol) ?: continue
                        val bucketName = c.getString(bucketNameCol)?.takeIf { it.isNotBlank() } ?: "未命名相簿"
                        val prev = byBucket[bucketId]
                        if (prev == null) {
                            // 倒序遍历，第一张就是最新的，直接当封面
                            byBucket[bucketId] = Album(
                                bucketId = bucketId,
                                name = bucketName,
                                count = 1,
                                coverUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                            )
                        } else {
                            byBucket[bucketId] = prev.copy(count = prev.count + 1)
                        }
                    }
                }
            byBucket.values.sortedByDescending { it.count }
        }.getOrDefault(emptyList())
    }

    /**
     * 分页读一个相簿里的照片。
     * [offset] 用 page * PAGE 传，返回空表表示到底了。
     */
    suspend fun photos(context: Context, bucketId: String, page: Int): List<AlbumPhoto> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.MIME_TYPE
            )
            // API 26 起 MediaStore 支持 SQL 片段里的 LIMIT/OFFSET
            val sort = "${MediaStore.Images.Media.DATE_MODIFIED} DESC LIMIT $PAGE OFFSET ${page * PAGE}"
            val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
            val args = arrayOf(bucketId)
            val out = ArrayList<AlbumPhoto>(PAGE)
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args, sort)
                ?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val wCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                    val hCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                    val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        out += AlbumPhoto(
                            id = id,
                            uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                            name = c.getString(nameCol) ?: "image_$id",
                            sizeBytes = c.getLong(sizeCol),
                            width = c.getInt(wCol),
                            height = c.getInt(hCol),
                            mime = c.getString(mimeCol) ?: "image/*"
                        )
                    }
                }
            out
        }.getOrDefault(emptyList())
    }

    /** 当前系统版本需要的读相册权限名。 */
    fun readPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /** 是否已经拿到读相册权限（部分授权时也算拿到，只是结果变少）。 */
    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(readPermission()) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
