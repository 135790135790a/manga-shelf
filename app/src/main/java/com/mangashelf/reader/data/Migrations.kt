package com.mangashelf.reader.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v8 → v9：图片导入工作栏（v1.6.0）。
 *
 * 只建两张新表，不动老数据。用独立表而不是给 `books` 加 workspace 字段，
 * 是为了让两个工作栏在 SQL 层面就互不可见——书架那套查询根本读不到这里。
 *
 * 带 DEFAULT 的列与实体上的 `@ColumnInfo(defaultValue=...)` 逐字对应，
 * 避免迁移后的表结构与 Room 期望的结构对不上（迁移测试会逐字比对）。
 */
/**
 * v9 → v10（v1.6.1）：图片工作栏支持动图。
 *
 * 四列都带 DEFAULT，与 [ImageItem] 上的 `@ColumnInfo(defaultValue = ...)` 对齐，
 * 这样 ALTER 之后的表结构与 Room 期望的建表语句逐字一致，打开数据库时不会触发
 * 「Migration didn't properly handle」。
 *
 * 老数据的 kind 落在 STATIC，表现与升级前完全一样。
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `image_items` ADD COLUMN `mimeType` TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE `image_items` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'STATIC'")
        database.execSQL("ALTER TABLE `image_items` ADD COLUMN `frames` INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE `image_items` ADD COLUMN `durationMs` INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `image_folders` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `parentId` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL,
                `sortMode` TEXT NOT NULL DEFAULT 'TIME',
                `sortAsc` INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_image_folders_parentId` ON `image_folders` (`parentId`)"
        )

        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `image_items` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `folderId` INTEGER NOT NULL DEFAULT 0,
                `name` TEXT NOT NULL,
                `relPath` TEXT NOT NULL,
                `sizeBytes` INTEGER NOT NULL DEFAULT 0,
                `modifiedAt` INTEGER NOT NULL DEFAULT 0,
                `width` INTEGER NOT NULL DEFAULT 0,
                `height` INTEGER NOT NULL DEFAULT 0,
                `sortIndex` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_image_items_folderId` ON `image_items` (`folderId`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_image_items_folderId_sortIndex` ON `image_items` (`folderId`, `sortIndex`)"
        )
    }
}

/**
 * v7 → v8：新增 `import_batches`（导入批次表，v1.5.6）。
 *
 * 只建新表，不动老表。老版本导入过的记录还留在 `import_tasks` 里、没有对应批次，
 * 导入历史页会把它们当作「无批次记录」，不影响使用。
 *
 * 新表所有 NOT NULL 列都不带 DEFAULT：实体上也没写 `@ColumnInfo(defaultValue=...)`，
 * 两边逐字一致，避免又踩 ALTER/DEFAULT 子句对不上的坑。
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `import_batches` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `batchId` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `finishedAt` INTEGER NOT NULL,
                `sourceType` TEXT NOT NULL,
                `rootTreeUri` TEXT,
                `status` TEXT NOT NULL,
                `totalBooks` INTEGER NOT NULL,
                `doneBooks` INTEGER NOT NULL,
                `failedBooks` INTEGER NOT NULL,
                `skippedBooks` INTEGER NOT NULL,
                `totalPages` INTEGER NOT NULL,
                `donePages` INTEGER NOT NULL,
                `sizeBytes` INTEGER NOT NULL,
                `elapsedMs` INTEGER NOT NULL,
                `failReason` TEXT
            )
            """.trimIndent()
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_import_batches_batchId` ON `import_batches` (`batchId`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_import_batches_createdAt` ON `import_batches` (`createdAt`)")
    }
}

/**
 * v6 → v7：作者 / 标签 / 阅读模式，以及导入任务的页级进度。
 *
 * 两张表都走 ALTER：books 被 pages 外键引用（重建要处理外键），import_tasks 本来就只有几行。
 * ALTER 加 NOT NULL 必须带 DEFAULT，所以实体上都标了 `@ColumnInfo(defaultValue = ...)`，
 * 两边才对得上。
 *
 * 老数据：`author`/`tags` 为空串（搜索时不命中，不影响原有书名搜索）；
 * `readMode` 默认 HORIZONTAL，与升级前的单页/双页行为一致（双页仍读旧的 `twoPage` 字段）。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE `books` ADD COLUMN `author` TEXT NOT NULL DEFAULT ''"
        )
        database.execSQL(
            "ALTER TABLE `books` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''"
        )
        database.execSQL(
            "ALTER TABLE `books` ADD COLUMN `readMode` TEXT NOT NULL DEFAULT 'HORIZONTAL'"
        )
        database.execSQL(
            "ALTER TABLE `import_tasks` ADD COLUMN `totalPages` INTEGER NOT NULL DEFAULT 0"
        )
        database.execSQL(
            "ALTER TABLE `import_tasks` ADD COLUMN `renderedPages` INTEGER NOT NULL DEFAULT 0"
        )
        database.execSQL(
            "ALTER TABLE `import_tasks` ADD COLUMN `currentPage` INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/**
 * v5 → v6：加「最后阅读时间」`books.lastReadAt` 和「双页模式」`books.twoPage`。
 *
 * 两条都走 ALTER（books 表小、又被 pages 的外键引用，重建代价更大）。
 * ALTER 加 NOT NULL 必须带 DEFAULT，所以实体上也标了 `@ColumnInfo(defaultValue = "0")`，
 * 这样 Room 从 [Book] 生成的结构里也有默认值，两边才对得上。
 *
 * 老数据：`lastReadAt = 0` 表示没读过（排序时回退到导入时间）；`twoPage = 0` 表示单页。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE `books` ADD COLUMN `lastReadAt` INTEGER NOT NULL DEFAULT 0"
        )
        database.execSQL(
            "ALTER TABLE `books` ADD COLUMN `twoPage` INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/**
 * v4 → v5：页面级管理（PDF 原始页码 / 缩略图 / 尺寸 / 软删除）+ 书架长按信息（来源大小 / 封面页）。
 *
 * 两张表分两种策略：
 * - `books` 只有几十行，且被 `pages` 外键引用，**重建表要处理外键**，所以走 ALTER；
 *   ALTER 加 NOT NULL 必须带 DEFAULT，因此实体上标了 `@ColumnInfo(defaultValue = "0")`，
 *   让 Room 期望的结构里也带默认值，两边才对得上。
 * - `pages` 行数等于总页数（可能上万），但重建一次也就几十毫秒，而且能彻底避免
 *   "ALTER 留下的 DEFAULT 子句与 Room 期望不一致"导致启动时 `IllegalStateException`。
 *
 * 老数据回填：`originPage = idx + 1`（升级前的页序就是导入顺序，等价于 PDF 原始页码；
 * 文件夹来源的书 originPage 不影响任何行为）。`deleted` 全置 0，全部视为未删除。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE `books` ADD COLUMN `sourceSizeBytes` INTEGER NOT NULL DEFAULT 0"
        )
        database.execSQL(
            "ALTER TABLE `books` ADD COLUMN `coverPageIdx` INTEGER NOT NULL DEFAULT 0"
        )

        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pages_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bookId` INTEGER NOT NULL,
                `idx` INTEGER NOT NULL,
                `relPath` TEXT NOT NULL,
                `originName` TEXT NOT NULL,
                `sizeBytes` INTEGER NOT NULL,
                `modifiedAt` INTEGER NOT NULL,
                `manualPos` INTEGER,
                `originPage` INTEGER NOT NULL,
                `thumbRelPath` TEXT,
                `width` INTEGER NOT NULL,
                `height` INTEGER NOT NULL,
                `deleted` INTEGER NOT NULL,
                FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO `pages_new`
                (`id`, `bookId`, `idx`, `relPath`, `originName`, `sizeBytes`, `modifiedAt`, `manualPos`,
                 `originPage`, `thumbRelPath`, `width`, `height`, `deleted`)
            SELECT `id`, `bookId`, `idx`, `relPath`, `originName`, `sizeBytes`, `modifiedAt`, `manualPos`,
                   `idx` + 1, NULL, 0, 0, 0
            FROM `pages`
            """.trimIndent()
        )
        database.execSQL("DROP TABLE `pages`")
        database.execSQL("ALTER TABLE `pages_new` RENAME TO `pages`")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_pages_bookId` ON `pages` (`bookId`)")
    }
}

/**
 * v3 → v4：新增 import_tasks 表，让导入队列能活过进程被杀。
 *
 * 纯新增表，不碰任何既有数据；表结构必须与 Room 从 [ImportTask] 生成的 SQL 逐字符一致
 * （列顺序、NOT NULL、AUTOINCREMENT、索引名 index_<table>_<column>）。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `import_tasks` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `batchId` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `sourceKey` TEXT,
                `sourceTreeUri` TEXT,
                `sourceType` TEXT NOT NULL,
                `sortIndex` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `error` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_import_tasks_batchId` ON `import_tasks` (`batchId`)"
        )
    }
}

/**
 * v2 → v3：给 pages 加 originName（原始文件名），供「按名称排序」。
 *
 * 同样用重建表：`ALTER TABLE ... ADD COLUMN originName TEXT NOT NULL DEFAULT ''`
 * 会在 sqlite_master 里留下 DEFAULT 子句，和 Room 期望的结构对不上。
 * pages 的行数等于总页数（几千行），重建一次是毫秒级，不值得冒 schema 不一致的风险。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pages_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bookId` INTEGER NOT NULL,
                `idx` INTEGER NOT NULL,
                `relPath` TEXT NOT NULL,
                `originName` TEXT NOT NULL,
                `sizeBytes` INTEGER NOT NULL,
                `modifiedAt` INTEGER NOT NULL,
                `manualPos` INTEGER,
                FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO `pages_new`
                (`id`, `bookId`, `idx`, `relPath`, `originName`, `sizeBytes`, `modifiedAt`, `manualPos`)
            SELECT `id`, `bookId`, `idx`, `relPath`, '', `sizeBytes`, `modifiedAt`, `manualPos`
            FROM `pages`
            """.trimIndent()
        )
        database.execSQL("DROP TABLE `pages`")
        database.execSQL("ALTER TABLE `pages_new` RENAME TO `pages`")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_pages_bookId` ON `pages` (`bookId`)")
    }
}

/**
 * v1 → v2：为"批量导入 + 每本书独立排序"打地基。
 *
 * 原则：**绝不丢数据、绝不重排老页序**。
 * - 老书 sortMode/sourceType 置为 'FIXED'，表示"顺序固定、不参与重排"，
 *   页序与升级前完全一致。
 * - 新建 pages 表，按每本 pageCount 回填 idx，relPath 与 ComicStorage 命名一致。
 *
 * 为什么 books 用"重建表"而不是 `ALTER TABLE ADD COLUMN ... DEFAULT`：
 * ALTER 加的列会在 sqlite_master 里留下 `DEFAULT 'FIXED'`，而 Room 从实体生成的
 * 期望 schema 里没有 DEFAULT 子句，两者对不上。Room 正常升级路径不校验 schema，
 * 但只要 identity hash 出问题、或用工具检查，就会报 "Migration didn't properly
 * handle" 而打不开库。重建表能让迁移后的结构**与 Room 期望逐字符一致**，
 * 代价只是多几条 SQL（整段在 Room 的事务里执行，中途失败会整体回滚）。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 1) 按 Room 期望的结构重建 books（新列不带 DEFAULT，值在 INSERT SELECT 里给定）
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `books_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `folder` TEXT NOT NULL,
                `pageCount` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `lastPage` INTEGER NOT NULL,
                `rtl` INTEGER NOT NULL,
                `sortMode` TEXT NOT NULL,
                `sortAsc` INTEGER NOT NULL,
                `sourceKey` TEXT,
                `sourceTreeUri` TEXT,
                `sourceType` TEXT NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO `books_new`
                (`id`, `title`, `folder`, `pageCount`, `createdAt`, `lastPage`, `rtl`,
                 `sortMode`, `sortAsc`, `sourceKey`, `sourceTreeUri`, `sourceType`)
            SELECT `id`, `title`, `folder`, `pageCount`, `createdAt`, `lastPage`, `rtl`,
                   'FIXED', 1, NULL, NULL, 'FIXED'
            FROM `books`
            """.trimIndent()
        )
        database.execSQL("DROP TABLE `books`")
        database.execSQL("ALTER TABLE `books_new` RENAME TO `books`")

        // 2) 页面顺序表
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pages` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bookId` INTEGER NOT NULL,
                `idx` INTEGER NOT NULL,
                `relPath` TEXT NOT NULL,
                `sizeBytes` INTEGER NOT NULL,
                `modifiedAt` INTEGER NOT NULL,
                `manualPos` INTEGER,
                FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_pages_bookId` ON `pages` (`bookId`)")

        // 3) 老书回填：每本插入 pageCount 行，relPath 与 ComicStorage.pageFile 的命名一致
        database.execSQL(
            """
            INSERT INTO `pages` (`bookId`, `idx`, `relPath`, `sizeBytes`, `modifiedAt`)
            WITH RECURSIVE `seq`(`bookId`, `pos`, `total`) AS (
                SELECT `id`, 0, `pageCount` FROM `books` WHERE `pageCount` > 0
                UNION ALL
                SELECT `bookId`, `pos` + 1, `total` FROM `seq` WHERE `pos` + 1 < `total`
            )
            SELECT `bookId`, `pos`, printf('page_%03d.jpg', `pos` + 1), 0, 0 FROM `seq`
            """.trimIndent()
        )
    }
}
