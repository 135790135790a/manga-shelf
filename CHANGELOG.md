## v1.6.5 — 2026-11-09

> 只动图片导入工作栏的壁纸链路（预览 / 编辑页、WallpaperService、静态壁纸设置、动图解码与渲染）；漫画书架、阅读器、漫画导入导出、页面管理零改动（`git diff v1.6.4` 已验证为空）。versionCode 28。

### 变更

- **壁纸主路径统一为内置 `WallpaperService`**：静态图不再默认走 `WallpaperManager.setBitmap`。原因：系统静态壁纸的视差由桌面启动器决定，应用无法控制；只有动态壁纸能在 `onOffsetsChanged` 里决定「随桌面平移」还是「固定」。
- **新增 `WallpaperTransform`**：缩放（1×–5×）、归一化位移（±1）、水平/垂直翻转、0/90/180/270 旋转、三种填充（COVER/FIT/ORIGINAL），序列化成 JSON 存进 `wallpaper_prefs`，编辑页与壁纸服务共用同一套换算（`placeFrame`）。
- **新增壁纸编辑页 `ui/WallpaperEditorScreen.kt`**：画布恒等于屏幕比例，双指缩放、单指拖动、翻转、旋转、填充切换、视差开关、复位；动图在编辑页实时播放，确认后设置。
- **视差**：开启时内容额外放大 `1 + 2×PARALLAX_RATIO`（12%），桌面滑动时按 `xOffset` 平移；关闭时固定。启动器 3 秒内不回调 offsets 则自动判定为「不支持位移」，退化为固定画面（不抖动、不报错）。
- **省电模式**：设置 → 壁纸 → 「省电模式（系统静态壁纸）」。开启后静态图走 `setBitmap`（把变换烘焙进一张屏幕尺寸的位图），无视差但最省电；动图仍走服务。
- **动图清晰度**：壁纸解码尺寸改为「屏幕宽 ×(1+2×12%)」，替代原来的原图 1:1 解码，兼顾内存与清晰度。

### 设置页

- 新增「壁纸」分组：省电模式开关、当前壁纸信息（类型 / 视差 / 缩放）。

### 已知限制

- SVGA 动画仍不能设为壁纸（`SVGADrawable` 在 2.6.1 无手动推进接口，壁纸服务里没有 View），会给出明确提示。
- 系统静态壁纸（省电模式）下视差由桌面启动器决定，应用无法控制。

### 构建

- `compileDebugKotlin` BUILD SUCCESSFUL；`assembleRelease` 通过；单测 4/4；`verify-release.ps1` 全部通过。
- APK `10,000,174` 字节（9.54 MB），SHA-256 `DBBD1D4EA725898DC66E569094EC8EEEE9F74D7D0EDD6AF4AEE1E287646C92DD`。
- 真机未验证（`adb devices` 为空），详见 `测试报告-v1.6.5.md`。

## v1.6.4 — 2026-11-07

> 只动图片导入工作栏（手势 / 壁纸 / 数据持久化）；漫画书架、阅读器、漫画导入导出、页面管理零改动（`git diff v1.6.3` 已验证为空）。

### 修复

- **长按拖动后仍弹出「图片操作」菜单**（v1.6.2 / v1.6.3 两次都没修掉的真因）：`onDragCancel = { onDragEnd(false) }` 把「手势被取消」当成了「没有移动」。移动成功后列表重组会销毁被拖拽的格子，从而触发 cancel，于是又走一次「没移动 → 弹菜单」，出现「已移动 1 张图片」Toast 与菜单同屏的现象。现在 `onDragCancel` 独立成 `onCancel()`，只清视觉态，不弹菜单、不改选中。
- 手势加了 `Log.i("TileGesture", ...)` 打点，`adb logcat -s TileGesture` 可核对事件序列（LONG_PRESS / UP dist/slop/moved / DROP action / CANCEL）。

### 新增

- **设为壁纸**：预览页底部信息条。静态图走 `WallpaperManager.setBitmap`（桌面 + 锁屏）；GIF / 动图 WebP / APNG / Ugoira / Lottie 走新增的 `service/GalleryWallpaperService.kt`（`WallpaperService`，25fps 自绘）。Ugoira 只缓存当前帧与预取帧，避免几百帧一起解进内存。SVGA 的动画只能由 `SVGAImageView` 驱动，壁纸里做不到，会明确提示。
- **数据状态面板**：设置 → 数据与备份 → 数据状态，展示图片工作栏张数与占用、数据库大小、自动快照份数，可手动立即快照。

### 变更

- `BackupManager.export/restore` 增加可选 `gallery` 参数：整库备份现在同时打包 `gallery/` 下的图片与 `gallery/ugoira/<uuid>/` 的帧目录；不传时行为与旧版完全一致，老备份仍可恢复。
- `BookRepository` 增加 `imageDao` / `galleryStorage` 两个可空属性，由 `MangaShelfApp` 注入。

### 权限

- 新增 `SET_WALLPAPER`（安装即授予，非运行时权限）；`GalleryWallpaperService` 由系统以 `BIND_WALLPAPER` 绑定。

### 数据库

- 无变化（仍 version 10）。

### 产物

- `漫画书架-v1.6.4.apk` · 9.52 MB（9,986,870 字节）
- versionCode 27 / versionName 1.6.4
- SHA-256 `A2E72138015F57EF1360C376441A4E2F42FB306C9F0D25EE165ACD0489FE771C`

## v1.6.3 — 2026-11-05

> 只动图片工作栏与相册读取；漫画书架 / 阅读器 / 漫画导入导出 / 页面管理零改动（`git diff` 已验证）。

### 修复

- **相簿预览空白 + 无法导入**：`AlbumRepository` 重写。原实现把 `LIMIT $PAGE OFFSET ...` 拼进 `sortOrder`，OEM 定制 MediaProvider 会抛 `SQLiteException`，又被 `runCatching{}.getOrDefault(emptyList())` 吞掉 → 界面拿到空表且没有任何错误提示；同时用 `getColumnIndexOrThrow` 取 WIDTH/HEIGHT/SIZE/MIME_TYPE，部分 ROM 不返回这些列导致整页失败。现在分页参数失败自动退回全量读 + 手动切片，取列全部容错，`BUCKET_ID` → `BUCKET_DISPLAY_NAME` → 全部图片三级兜底，并把错误原样带出到界面。
- **相簿页没有空态**：加载中 / 空 / 出错 / 降级四种状态都有对应界面，出错可重试。
- **动图预览不播放**：预览态用的是自建带 Gif 解码器的 `ImageLoader`，但没禁用磁盘缓存，Coil 直接用同一个 `File` key 命中列表页写入的静态首帧缓存。现在播放态 `diskCachePolicy(DISABLED)` 并加独立 `memoryCacheKey`。

### 新增

- `service/GalleryImportService.kt`：图片导入前台服务（通知进度、完成通知、点击回到 App），与漫画侧服务分离。
- `data/GalleryDiagnostics.kt` + `ui/GalleryDiagnosticsScreen.kt`：常驻诊断面板（设置 → 诊断信息），逐张输出文件头、重新嗅探结果、数据库元数据与帧目录，一键复制。

### 数据库

- 无变化（仍 version 10）。

### 产物

- APK 9957662 字节（9.50 MB），SHA-256 `3CB24D3205C0955955984E6007722DDFAF9E11F34AB3E51BAD28FE529252E37F`

### 追加修复 — 2026-11-05（versionCode 26）

### 修复

- **WebP 动图被判成静态图**：`AnimationSniffer.webpAnimFrames` 一直在 `ANIM` 块里读帧数，但 WebP 的 `ANIM` 块结构是「背景色 4 字节 + 循环次数 2 字节」——**没有帧数字段**，而循环次数通常写 0（无限循环），于是读出来永远是 0，所有 WebP 动图都落进 `STATIC` 分支，预览页自然不动。现在改为按标准统计 `ANMF` 块个数（每帧一个），并用 `seek` 而不是 `skipBytes` 跳块，避免靠近文件末尾时少跳。
- **存量数据不会自动变好**：`GalleryViewModel.repairKindsOnce()` 在首次进入图片工作栏时扫一遍 `kind='STATIC'` 的记录，重新嗅探，凡是动图就回写 `kind / frames / durationMs` 并刷新，只执行一次（prefs 打标记），完成后提示「已修正 N 张动图的类型」。

### 数据库

- 无结构变化（仍 version 10），只回写已有行的 `kind/frames/durationMs`。

### 产物

- APK 9,960,206 字节（9.50 MB），SHA-256 `1AF8E84D62E98B2E0EF0A6C9731168F4DB825C98E1EDC0828BF31F8CCA06A465`

## v1.6.2 — 2026-11-04

> 只动图片工作栏，漫画书架 / 阅读器 / 导入 / 导出 / 页面管理零改动。

### 修复

- **长按移动误弹详情**：`ImageTile` 原来是 `combinedClickable(onLongClick)` 与 `detectDragGesturesAfterLongPress` 抢同一个 500ms 判定，菜单先弹出、拖拽被 Dialog 吞掉后又经 `onDragEnd(false)` 再弹一次。现在长按只由拖拽检测器处理，`combinedClickable` 的 `onLongClick` 置空；详情从菜单里移除，改到预览页底部信息条。拖动阈值改用 `viewConfiguration.touchSlop`。
- **文件夹没有返回上一级**：`navigationIcon` 原来无论哪层都退出整个工作栏。现在三分支（多选 / 子目录回上级 / 根目录退出），并补 `BackHandler`。
- **相册只显示相机/截图/下载**：原来走系统 Photo Picker，分类由系统决定。新增 `data/AlbumRepository.kt` + `ui/AlbumPickerScreen.kt`，按 `MediaStore.BUCKET_ID` 列真实相簿（封面 + 张数 + 分页），权限按需申请。
- **Ugoira 变 WebP 且不动**：存储层本来就没转码，问题是列表直接展示帧文件、播放延时用「总时长÷帧数」平均、单帧时不播。现在解包写 `frames.json` 记每帧延时，按真实延时播放，>500 帧降采样 30fps，类型保持 Ugoira。

### 新增

- `data/AlbumRepository.kt`、`ui/AlbumPickerScreen.kt`
- `UgoiraParser.saveManifest` / `loadManifest`（`frames.json`）
- 预览页底部信息条：格式 / 尺寸 / 大小 / 帧数 / 时长 / 导入时间 / 所属文件夹

### 权限

- 新增 `READ_MEDIA_IMAGES`（Android 13+）与 `READ_EXTERNAL_STORAGE`（`maxSdkVersion=32`），**只在进入「按相簿浏览」时申请**；`verify-release.ps1` 白名单同步。

### 数据库

- 无变化（仍 version 10，无新列）；`frames.json` 是新增文件，老数据自动走兜底逻辑，不需要迁移。

### 产物

- APK 9,939,150 字节（9.48 MB），SHA-256 `A4554E09DB3C8699E6F721EC52D4CBACD53C427675ED7D8A8D604F1A73543DAD`

## v1.6.1 — 2026-11-03

### 新增

- **图片预览页**：`ui/ImagePreviewScreen.kt`。点缩略图进全屏，`HorizontalPager` 左右翻页，双指缩放 1×–4×，双击 1×↔2.5×，缩放态禁翻页；顶部返回 + 页码 + 动图类型标签，底部文件名 / 尺寸 / 大小 / 时长；返回保持原位置。
- **长按菜单**：存到相册（`Pictures/MangaShelf`）、重命名、移动到、删除；多选栏新增「相册」，批量写入并汇报成功数。
- **批量导入**：相册走 `PickMultipleVisualMedia(50)`，文件走 `OpenMultipleDocuments`，两个入口都先弹确认框（张数 + `OpenableColumns.SIZE` 预估大小），导入过程可取消，已完成的保留。
- **拖拽归档**：网格视图长按图片开始拖动，目标文件夹高亮描边，松手移动；拖到空白处松手则弹出长按菜单。
- **新建文件夹入口**：从「视图」下拉菜单提到顶栏 `+ 文件夹`，解决「找不到新建文件夹」。
- **六类动图**：GIF / 动图 WebP / APNG / Ugoira / Lottie / SVGA。`data/AnimationSniffer.kt` 按**文件内容**判型（不只看扩展名），`ui/AnimatedImage.kt` 统一渲染，列表只出首帧并带播放角标，预览才播放，离开页面在 `DisposableEffect` 释放。

### 数据

- `image_items` 新增 4 列：`mimeType` / `kind` / `frames` / `durationMs`，`MIGRATION_9_10` 四条 `ALTER TABLE`（均带 `DEFAULT`，与 `@ColumnInfo(defaultValue)` 对齐）。
- 数据库版本 9 → **10**。
- Ugoira 解包目录：`files/gallery/ugoira/<uuid>/`。

### 依赖

- `io.coil-kt:coil-gif:2.6.0`（GIF / APNG / 动图 WebP）
- `com.airbnb.android:lottie-compose:6.4.0`
- `com.github.svga:SVGAPlayer-Android:2.6.1`（JitPack，`settings.gradle.kts` 新增 `maven("https://jitpack.io")`）

### 变更

- `versionName` → 1.6.1，`versionCode` → 23。
- 导出 ZIP 含动图时保留原始文件；导出 PDF / 长图时取首帧。

### 已验证 / 未验证

- 已验证：`assembleRelease` 通过、单测 4/4、`verify-release.ps1` 七项通过、schema 10 与迁移 SQL 逐字一致。
- 未验证：真机（`adb devices` 为空）、500+ 张压测、Lottie/SVGA 实际渲染效果。

## v1.6.0 — 2026-11-02

### 新增

- **漫画导出（四种格式）**：`export/Exporter.kt` + `ui/ExportDialogs.kt`。入口三处（长按菜单 / 多选批量栏 / 页面管理页菜单）。
  - 原始 PDF：直接复制 `comics/<书>/source.pdf`，没有源文件时置灰
  - 重排 PDF：`PdfDocument` 按当前页序把可见页合成，`drawColor(WHITE)` 打底避免透明变黑
  - ZIP：`ZipOutputStream` 逐页写入，可选带 `thumbs/`
  - 长图：先只读尺寸算分片，`MAX_SLICE_HEIGHT = 8000`、`MAX_WIDTH = 1440`，逐页解码绘制后立即 `recycle()`
  - 每次弹系统目录选择器（`OpenDocumentTree`），进度可取消，批量逐本继续、失败不中断
- **图片工作栏**：`data/ImageFolder.kt`、`ImageItem.kt`、`ImageDao.kt`、`GalleryStorage.kt`、`GalleryPrefs.kt`、`GalleryRepository.kt` + `ui/GalleryViewModel.kt`、`GalleryScreen.kt`。与漫画书架完全隔离：独立表、独立目录 `files/gallery/`、独立偏好 `gallery_prefs`。
  - 支持多级文件夹（`parentId` 递归，64 层护栏），移动只改整数列、不复制文件
  - 四种排序 + 三档视图 + 多选批量
- **权限面板**：`ui/PermissionSection.kt`。设置 → 权限，显示通知 / 相册读取状态，可按需申请。冷启动零权限申请。
- **存储统计**：新增「图片工作栏」切片。

### 数据库

- `version 8 → 9`，`MIGRATION_8_9` 建 `image_folders` / `image_items` 两表与三条索引。

### 其他

- 顶栏工作栏切换做成搜索左侧的分段控件（`ui/WorkspaceSwitch.kt`），漫画页与图片页共用
- 图片导入完成后写一条 `import_batches` 记录（`sourceType = "GALLERY"`），「导入历史」里可与漫画导入区分
- `ShelfScreen` 顶栏新增「图片」入口；`MainActivity` 增加图片工作栏状态（`rememberSaveable`）
- `PageManageScreen` 排序菜单新增「导出这本书」

### 校验

- `assembleRelease` 通过，`testDebugUnitTest` 4/4
- `verify-release.ps1` 七项通过
- APK 8,803,564 B（8.40 MB），SHA-256 `B01AB3F4C9BE080C58DEEF8FCC8B32A9AA5EFEFECE677E235E352A1C7720A081`

## v1.5.9 — 2026-11-01

### 修复

- **分批导入不可用**：旧实现把 `vnd.android.document/directory` 传给 `OpenMultipleDocuments`（即 `ACTION_OPEN_DOCUMENT`），这个 Intent 在 Android 上只能选文件、选不了目录；能选目录的 `ACTION_OPEN_DOCUMENT_TREE` 又不支持多选。结果就是系统选择器里什么都选不中，回调拿不到任意 URI，界面上也没有"开始导入"这一步，看起来就是"点了没反应"。
  - 现在：选父目录 → 弹窗列出子文件夹（`FolderImporter.listChildFolders`）→ 多选 → 「开始导入（N 批）」→ 走 v1.5.6 已有的批次队列（`ShelfViewModel.importFolders`），批次进度、页级进度、暂停/继续、跳过、单批重试全部沿用。
  - 新增 `FolderImporter.inspect()`：只查元数据不解码，返回书册数、页数、总字节，最多扫 4000 个条目、每 64 条 yield。
  - 新增 `ui/BatchImportDialog.kt`：已选列表 + 移除/清空/继续添加 + 汇总行。

### 新增

- **鲸鱼娘启动图**：`res/drawable-{mdpi,hdpi,xhdpi,xxhdpi}/splash_whale.jpg` 与 `splash_whale_night.jpg`。原图 1216×1952，按 360dp 宽等比生成五档里的四档，`layer-list` 居中不拉伸。浅色底 `#F6F4EF`、深色底 `#16161B`；深色版用连通域洪水填充把四周白底换成 `#16161B`、角色压暗到 0.78。JPEG q88 控制体积，全套 1.13 MB。
- **设置页「存储空间」**：`data/StorageStats.kt` + `ui/StorageSection.kt`。按字节统计 source / pages / thumbs / database / backup / other，外加 `StatFs` 的可用与总量；IO 线程扫描、每 256 个文件 yield；点图例高亮扇区；带刷新与「上次统计」时间；空态与加载态齐备。
- **存储占比细化到单本**：`StorageStats` 增加 `BookStat`（名称/页数/导入时间/原文件/页面/缩略图/合计）与 5 分钟结果缓存；`ui/StorageSection.kt` 增加「分类占比 / 按漫画占比」切换（Top8 + 其他）；新增 `ui/StorageBooksScreen.kt` 全屏列表（搜索、四种排序、单本详情、清理缩略图）与 `data/StorageCleaner.kt`；`BookDao.allBooks()` 供一次取全。
  - 修口径：旧代码 `else pages += child.length()` 把 `comics/<书>/` 下的 `source.pdf` 也算进漫画页面，现在单列「原文件」，无副本时用 `Book.sourceSizeBytes` 兜底并标「估算」。
- **开场界面可调**：`data/SplashPrefs.kt`（0/1/2/3/5 秒，默认 2 秒）+ `ui/SplashOverlay.kt`（Compose 层，同图同位置接 `windowBackground`，淡出后可跳过）；`MainActivity` 冷启动挂载、`MangaShelfApp.splashShown` 保证一进程只播一次；设置页「启动与开场」分组含时长选择与「重新查看开场」预览。

### 其他

- `ShelfViewModel.importFolders` 增加空列表短路。
- 设置页「版本」行同步到 versionCode 20。

### 校验

- `verify-release.ps1` 七项全过
- APK：8,667,988 字节（8.27 MB）
- SHA-256：`84D76ECB1BEDEB48C4AB8A033D446E94660488194FBC9FA8C13E46F83099DE36`

## v1.5.8 — 2026-10-31

### 新增
- 设置页：主题 / 护眼 / 备份 / 布局 / 导入排序 / 存储 / 关于集中管理
- 独立搜索页：整页搜索 + 300ms 防抖 + 搜索历史（20 条，可删可清空）
- 主页护眼：书架/搜索/设置也套暖色叠加，按页面分别记忆
- 文件页批量：删除（二次确认，删源文件）、存相册（`Pictures/漫画书架/`）
- 文件页顶栏「选择」按钮，不必再靠长按进多选

### 修复
- 阅读器与主页护眼状态分离，互不覆盖

### 校验
- `assembleRelease testDebugUnitTest` 通过，单测 4/4；`verify-release.ps1` 七项全过
- APK 7,859,808 字节 / SHA-256 `644D275331D88E2DB0F8F54CCE6DA9467AC70629D0E9CF4225EEFF58DDCBF880`

## v1.5.7 — 2026-10-30

### 新增
- 文件页：根目录授权 + 树形浏览 + 排序（名称/时间/大小/导入顺序）+ 多选批量导入
- 导入排序与滚动位置持久化（按目录分别保存）
- 搜索历史（20 条，可复用/删除/清空）
- 启动图（跟随白天/夜晚，冷启动不再闪黑）
- 阅读页护眼模式（四档循环，叠加层不影响手势）
- 数据库每日自动备份（保留 3 份）+ 手动备份/恢复
- 文件页多选显示预估大小；搜索防抖 300ms
- 护眼改为菜单：三档色温 + 三种策略（手动/定时/跟随系统）
- 启动关键路径加超时（修库 20s / 清残留 30s / 清任务 15s / 备份 60s）
- 修 CI：`settings.gradle.kts` 在 CI 上改用官方仓库（阿里云镜像在 runner 上超时导致 KSP 解析失败）
- 修 lint：音量键翻页改覆写 `onKeyDown`，去掉 `super.dispatchKeyEvent` 的 RestrictedApi 错误

### 修复
- `EyeCareState.version` 改用 getter/setter，修复 Compose 不重组

### 校验
- `assembleRelease testDebugUnitTest` 通过；`verify-release.ps1` 七项全过
- APK 7679484 B，SHA-256 `DF55BB879D85580C01519542527DE9A3B6413B4A5992F41423F67FACD6F1F03E`

# 更新日志

本文件记录每个版本「用户能感觉到」的变化。安装包与校验值见 [版本记录.md](版本记录.md)。

## v1.5.6 — 2026-10-29

### 新增

- **多文件夹分批导入**：文件选择器里可以一次多选几个文件夹，每个文件夹排成一批；
  批次队列支持上移/下移调顺序、跳过某一批、单独重试失败的那批。
- **三级进度**：批次（第几批/共几批）→ 书册（第几本/共几本）→ 页（第几页/共几页），
  长任务不再只有一个转圈。
- **导入暂停 / 继续**：书册边界安全点暂停，通知栏也能点；暂停期间可以自由浏览书架。
- **导入历史页**：独立整页，环形图按「来源 / 格式 / 结果」三个维度统计，
  带图例数量与百分比、状态筛选、每页 20 条、一键导出 CSV。
- **书架多选**：顶栏「选择」进入，卡片描边打勾，底部批量栏可存封面 / 打标签 / 删除。

### 修复

- 冷启动崩溃：`ShelfViewModel` 初始化顺序问题（成员在 `init` 里被引用但还没赋值）。
- 批量导入全失败：`FolderImporter` 用了父目录的 SAF 授权去读子目录，子树授权失效。

### 稳定性

- 导入状态机加守卫，非法流转（例如把已完成的批次改回「导入中」）直接抛异常而不是静默写脏。
- 批量操作加门闩（CAS），连点「删除」不会并发跑两遍。
- 导入任务表持久化，进程被系统回收后重开会把未完成批次浮到书架顶部并可续传。

### 工程

- CI 扩成两条线：平时只跑单测 + debug 包，打 `v*` tag 时跑 lint + 单测 + release 并上传 Release。
- 签名支持用 Secrets 覆盖，没配就回退仓库内 keystore。

### 已知限制

- 国产 ROM 的后台管理仍可能把前台服务掐掉；已尽量保命，但无法保证「绝不中断」。
- 暂停只在书册边界生效，当前这本进行中时点暂停会等它导完。
- 真机验证仍缺失（本机 `adb devices` 为空），详见测试报告。

## v1.5.5 — 2026-10-28

- 稳定性加固：分页索引越界保护、排序/回收站索引保护、启动任务异常兜底、坏图跳过。
- 书架封面支持「普通画质 / 省空间」两档，扫书时不再连续解码大图。
- 书籍信息加作者与标签，搜索框可搜标签。
- 阅读模式三选一：左右翻页 / 上下滚动 / 双页并排。
- 后台导入进度细化到页级。

## v1.5.4 — 2026-10-28

- 继续阅读：书架顶部「最近在读」卡片。
- 双页并排模式（大屏看跨页图）。
- 页面管理：拖拽排序、置顶/置底、页级缩略图、CBZ 导入。

## v1.5.3 — 2026-10-27

- 页面管理页（删除/恢复页面、回收站）。
- 导入时保留原 PDF 副本，可重新解析。
- 页序支持上移/下移与简化拖拽。
- 书架长按菜单顶部内嵌书籍信息。

## v1.5.2 — 2026-10-26

- 导入任务持久化与中断恢复（进程被杀后可续）。
- 导入排序：文件夹/图片两套默认规则，导入前可临时覆盖。
- 整本书保存到相册（MediaStore）。
- 备份与恢复（zip 含图片）。

## v1.5.1 — 2026-10-26

- 书架搜索（书名/作者/标签）。
- 闪屏跟随主题、超大目录导入节流。

## v1.5.0 — 2026-10-26

- 数据层升级到 schema v2（页序间接层）。
- 批量导入队列、书架排序（时间/书名/页数）。
- 白天 / 夜晚 / 跟随系统三档主题。
- 防闪退：OOM 降采样、坏图占位、数据库损坏自愈、阅读越界保护。
