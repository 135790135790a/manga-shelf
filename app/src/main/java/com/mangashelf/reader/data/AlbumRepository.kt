package com.mangashelf.reader.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
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

/** 相簿列表的加载结果。带着错误走，UI 才能解释「为什么是空的」。 */
data class AlbumLoad(
    val albums: List<Album> = emptyList(),
    val error: String? = null
)

/** 一页照片。 */
data class PhotoPage(
    val photos: List<AlbumPhoto> = emptyList(),
    val hasMore: Boolean = false,
    val error: String? = null,
    /** 相簿筛选彻底失效、已退回「全部图片」——UI 需要明确提示，不能假装筛选成功 */
    val degraded: Boolean = false
)

/**
 * 读取手机真实相册分类。
 *
 * v1.6.3 重写要点（v1.6.2 在这里踩了坑）：
 *
 * 1. **不再把 `LIMIT/OFFSET` 塞进 `sortOrder`**。v1.6.2 就是这么写的，而这项能力只有部分
 *    MediaProvider 实现（OEM 定制版本经常直接抛 SQLiteException）。一旦抛异常，外层
 *    `runCatching` 把它吞成空表，界面就白屏了——用户看到的就是「相簿点进去什么都没有」。
 *    现在的做法：先试一次带 LIMIT 的查询拿性能，失败就退回不带 LIMIT、由游标手动跳过前 N 条。
 * 2. **列名不再用 `getColumnIndexOrThrow`**。部分 ROM 不返回 `WIDTH/HEIGHT/SIZE`，
 *    严格取列会让整页失败。现在缺列就取默认值，只有 `_ID` 缺失才算真失败。
 * 3. **错误必须能被界面拿到**。所有失败都转成 [PhotoPage.error] / [AlbumLoad.error]，
 *    不再静默返回空表。
 * 4. **相簿筛选失败时降级到「全部图片」**（用户拍板），并打 `degraded` 标记让 UI 提示。
 *
 * 权限：
 * - Android 13+：`READ_MEDIA_IMAGES`
 * - Android 12 及以下：`READ_EXTERNAL_STORAGE`
 * - Android 14+ 部分授权时 Cursor 只返回被授权的那部分（系统行为），由调用方提示。
 */
object AlbumRepository {

    private const val PAGE = 120

    private val ID = MediaStore.Images.Media._ID
    private val NAME = MediaStore.Images.Media.DISPLAY_NAME
    private val SIZE = MediaStore.Images.Media.SIZE
    private val WIDTH = MediaStore.Images.Media.WIDTH
    private val HEIGHT = MediaStore.Images.Media.HEIGHT
    private val MIME = MediaStore.Images.Media.MIME_TYPE
    private val BUCKET_ID = MediaStore.Images.Media.BUCKET_ID
    private val BUCKET_NAME = MediaStore.Images.Media.BUCKET_DISPLAY_NAME
    private val DATE = MediaStore.Images.Media.DATE_MODIFIED
    private val EXTERNAL: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private val ALBUM_PROJECTION = arrayOf(ID, BUCKET_ID, BUCKET_NAME)
    private val PHOTO_PROJECTION = arrayOf(ID, NAME, SIZE, WIDTH, HEIGHT, MIME)

    /** 列出设备上真实存在的相簿，按照片数量降序。 */
    suspend fun albums(context: Context): AlbumLoad = withContext(Dispatchers.IO) {
        val byBucket = LinkedHashMap<String, Album>()
        try {
            context.contentResolver.query(EXTERNAL, ALBUM_PROJECTION, null, null, "$DATE DESC")?.use { c ->
                val idCol = c.getColumnIndex(ID)
                if (idCol < 0) return@use
                val bucketIdCol = c.getColumnIndex(BUCKET_ID)
                val bucketNameCol = c.getColumnIndex(BUCKET_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    // 个别 ROM 的 BUCKET_ID 为空或不存在，退回用相簿名当 key
                    val key = c.str(bucketIdCol).ifBlank { c.str(bucketNameCol) }
                    if (key.isBlank()) continue
                    val name = c.str(bucketNameCol).ifBlank { "未命名相簿" }
                    val prev = byBucket[key]
                    byBucket[key] = if (prev == null) {
                        Album(
                            bucketId = key,
                            name = name,
                            count = 1,
                            coverUri = ContentUris.withAppendedId(EXTERNAL, id)
                        )
                    } else {
                        prev.copy(count = prev.count + 1)
                    }
                }
            } ?: return@withContext AlbumLoad(error = "系统没有返回相册游标，可能缺少相册权限")
        } catch (t: Throwable) {
            return@withContext AlbumLoad(error = "读取相簿失败：${t.readable()}")
        }
        AlbumLoad(albums = byBucket.values.sortedByDescending { it.count })
    }

    /**
     * 读某相簿的一页照片。
     *
     * 三级兜底：`BUCKET_ID` → `BUCKET_DISPLAY_NAME` → 全部图片（打 degraded 标记）。
     */
    suspend fun photos(context: Context, album: Album, page: Int): PhotoPage =
        withContext(Dispatchers.IO) {
            val offset = page * PAGE
            var lastError: String? = null

            val byId = queryPage(context, "$BUCKET_ID = ?", arrayOf(album.bucketId), offset)
            if (byId.error == null) {
                // 第一页拿到数据，或者已经翻到后面（空 = 到底），都算成功
                if (byId.photos.isNotEmpty() || offset > 0) return@withContext byId
            } else {
                lastError = byId.error
            }

            val byName = queryPage(context, "$BUCKET_NAME = ?", arrayOf(album.name), offset)
            if (byName.error == null) {
                if (byName.photos.isNotEmpty() || offset > 0) return@withContext byName
            } else {
                lastError = lastError ?: byName.error
            }

            // 两种筛选都空/失败：降级到全部图片，让用户至少能选图导入
            val all = queryPage(context, null, null, offset)
            when {
                all.error != null -> all.copy(error = lastError ?: all.error)
                else -> all.copy(degraded = true)
            }
        }

    /**
     * 直接读全部图片的一页（相簿筛选整个不可用时的兜底，也给「全部照片」入口复用）。
     */
    suspend fun allPhotos(context: Context, page: Int): PhotoPage = withContext(Dispatchers.IO) {
        queryPage(context, null, null, page * PAGE)
    }

    /**
     * 查询一页。
     *
     * 先试 `sortOrder` 带 `LIMIT/OFFSET`（快），失败再退回不带 LIMIT 的全量读 + 手动跳过。
     */
    private fun queryPage(
        context: Context,
        selection: String?,
        args: Array<String>?,
        offset: Int
    ): PhotoPage {
        val resolver = context.contentResolver

        val limited = read(resolver, selection, args, "$DATE DESC LIMIT $PAGE OFFSET $offset")
        if (limited.ok) {
            // LIMIT 生效时，正好取满一页就认为还有下一页
            return PhotoPage(photos = limited.rows, hasMore = limited.rows.size == PAGE)
        }

        val plain = read(resolver, selection, args, "$DATE DESC")
        if (!plain.ok) {
            return PhotoPage(error = plain.error ?: "查询失败")
        }
        val slice = plain.rows.drop(offset).take(PAGE)
        return PhotoPage(photos = slice, hasMore = plain.rows.size > offset + PAGE)
    }

    private class Outcome(
        val ok: Boolean,
        val rows: List<AlbumPhoto> = emptyList(),
        val error: String? = null
    )

    private fun read(
        resolver: ContentResolver,
        selection: String?,
        args: Array<String>?,
        sort: String
    ): Outcome {
        val out = ArrayList<AlbumPhoto>(PAGE)
        try {
            var cursor: Cursor? = null
            try {
                cursor = resolver.query(EXTERNAL, PHOTO_PROJECTION, selection, args, sort)
                if (cursor == null) return Outcome(false, error = "系统没有返回相册游标")
                val idCol = cursor.getColumnIndex(ID)
                if (idCol < 0) return Outcome(false, error = "相册数据缺少 _ID 列")
                val nameCol = cursor.getColumnIndex(NAME)
                val sizeCol = cursor.getColumnIndex(SIZE)
                val wCol = cursor.getColumnIndex(WIDTH)
                val hCol = cursor.getColumnIndex(HEIGHT)
                val mimeCol = cursor.getColumnIndex(MIME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    out += AlbumPhoto(
                        id = id,
                        uri = ContentUris.withAppendedId(EXTERNAL, id),
                        name = cursor.str(nameCol).ifBlank { "image_$id" },
                        sizeBytes = cursor.num(sizeCol),
                        width = cursor.int(wCol),
                        height = cursor.int(hCol),
                        mime = cursor.str(mimeCol).ifBlank { "image/*" }
                    )
                }
            } finally {
                cursor?.close()
            }
        } catch (t: Throwable) {
            return Outcome(false, error = t.readable())
        }
        return Outcome(true, rows = out)
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

    // ---------------- 游标取值：缺列不抛异常 ----------------

    private fun Cursor.str(col: Int): String =
        if (col < 0) "" else runCatching { getString(col) }.getOrNull().orEmpty()

    private fun Cursor.num(col: Int): Long =
        if (col < 0) 0L else runCatching { getLong(col) }.getOrDefault(0L)

    private fun Cursor.int(col: Int): Int =
        if (col < 0) 0 else runCatching { getInt(col) }.getOrDefault(0)

    private fun Throwable.readable(): String {
        val msg = message?.takeIf { it.isNotBlank() } ?: "未知原因"
        return "${javaClass.simpleName}: $msg"
    }
}
