# MangaShelf (漫画书架)

[中文](README.md) · **English**

> Turn **PDF / images / CBZ archives** into a manga reader you flip with your finger — native Android, fully local, no network, no ads.

[**↓ Download latest APK**](https://github.com/135790135790a/manga-shelf/releases/latest)　·　[Features](#1-features)　·　[Build](#4-build)　·　[Known limitations](#7-known-limitations)

| | |
| :-- | :-- |
| **Latest version** | v1.6.3 (versionCode 26, hotfix on 2026-11-05) |
| **Package** | 9.50 MB · installs over the previous version, data preserved |
| **Requirements** | Android 7.0+ (minSdk 24), targetSdk 34 |
| **Stack** | Kotlin · Jetpack Compose (Material 3) · Room · Coil · PdfRenderer |
| **Permissions** | 2 foreground-service + 1 notification + 2 media-read (requested only when needed, never on cold start); no network |
| **Storage** | Everything lives in the app's private dir; uninstalling wipes it. To move devices use Settings → Backup |

**How it works**: import a PDF → every page is rendered to an image → it shows up on the shelf → tap to flip left/right.
You can also select a batch of images (`001.jpg`, `002.jpg`, …) and they are sorted by file name into one book; or pick a folder and the whole folder becomes a book; CBZ / ZIP import directly.

---

## 1. Features

### Hotfix: WebP animation detection (v1.6.3 · 2026-11-05)

| Problem | Fix |
| :-- | :-- |
| Animated WebP showed as a "static image" and never played in the viewer | `AnimationSniffer.webpAnimFrames` was reading the *loop count* from the WebP `ANIM` chunk and treating it as the frame count. The loop count is usually 0 (= loop forever), so every animated WebP was classified as static. It now counts `ANMF` chunks, which is what actually encodes frames |
| Already-imported records did not repair themselves | A one-shot repair runs the first time you open the image workspace: records with `kind='STATIC'` are re-sniffed, animations get `kind/frames/durationMs` written back and the list refreshes |

### Albums / animation / background import fixes (v1.6.3)

| Capability | Description |
| :-- | :-- |
| Albums that actually work | Fixes "album opens to a blank page and the import button stays grey". Root cause: the query appended `LIMIT/OFFSET` to `sortOrder` (some OEM MediaProviders throw, and the exception was swallowed into an empty list) and used `getColumnIndexOrThrow` for width/height/size. Now it tries the paged query first, falls back to a full read plus manual slicing, and degrades three levels: `BUCKET_ID` → `BUCKET_DISPLAY_NAME` → all images |
| Empty / error states | Album list and photo grid each have loading, empty and error states. Errors show the reason and a retry; a degraded load says so explicitly |
| Animation really plays in the viewer | Fixes "viewer does not animate". The player `ImageLoader` had the GIF decoder but did not disable the disk cache, so it hit the first-frame bitmap the list had cached. Playback now uses `diskCachePolicy(DISABLED)` plus a separate `memoryCacheKey` |
| Background import for the image workspace | A dedicated `GalleryImportService` (foreground service + progress notification + completion notification). Switching apps or locking the screen no longer interrupts it. It is completely separate from the manga-side `ImportForegroundService` — zero changes on the manga path |
| Diagnostics panel | Settings → Diagnostics (a permanent entry). Lists name / relPath / file magic / re-sniffed type / DB kind / frames / duration / dimensions / thumbnail / frame directory per item, with copy-all, for tracking down "imported type is wrong" issues |

### Image viewer / animations / drag-to-folder (v1.6.1)

| Capability | Description |
| --- | --- |
| Image viewer | Tap a thumbnail for full screen: swipe pages, pinch zoom, double-tap 1×/2.5×, page counter on top, size and duration at the bottom |
| Long-press menu | Save to gallery / rename / move to / delete (multi-select batch save to gallery too) |
| Batch import | Two paths (gallery and files); a confirmation dialog shows count and estimated size, and the import can be cancelled |
| Drag to folder | In grid view, long-press an image and drag it onto a folder — the target is outlined, releasing moves it |
| New folder | A visible "+ Folder" button in the top bar (it used to be buried in the view menu) |
| Six animation types | GIF / animated WebP / APNG / Ugoira / Lottie / SVGA. Lists show the first frame with a badge; playback happens in the viewer only |

### Image workspace specifics (v1.6.2)

| Capability | Description |
| :-- | :-- |
| Gesture separation | Tap only previews; long-press-and-release opens the action menu; long-press-and-drag only moves. The three are mutually exclusive, so dragging never pops an info dialog |
| Back one level | In a subfolder, both the top-bar arrow and the system back gesture go up one level; only the root exits the workspace. Scroll position and sort order are preserved |
| Real albums | "Import images → Browse by album" lists the device's actual albums (WeChat / QQ / Camera / user albums) via `MediaStore.BUCKET_ID`, with covers and counts, paged. Coexists with the zero-permission system photo picker |
| Ugoira playback | Unpacking writes `frames.json` with per-frame delays; the viewer loops at those delays. Above 500 frames it downsamples to 30 fps. The type stays Ugoira and is never rewritten to WebP |
| Image details | Moved from the long-press menu to the bottom of the viewer: format · dimensions · size · frames · duration · import time · folder |

### Manga export / image workspace / permission panel (v1.6.0)

| Feature | Description |
| --- | --- |
| Manga export (4 formats) | Long-press a book → Export, multi-select → batch bar Export, or the page-manage menu. Original PDF (only if the source is retained) / rebuilt PDF (current page order minus deleted pages) / ZIP (optionally with thumbnails) / long image (auto-sliced above 8000 px, named `_1ofN`). A system directory picker opens each time; progress is cancellable; per-book results are listed and can be opened |
| Workspace switch | A segmented control "Manga / Images" left of the search icon. The active side is highlighted and each side keeps its own position, sort and layout |
| Image workspace | Entered from the top bar; data is **fully isolated** from the manga shelf (dedicated tables `image_folders` / `image_items` and a dedicated directory `files/gallery/`). Supports importing images, multi-level folders, rename/move/delete, four sort orders, three layouts (large icons / small icons / list), long-press save-to-gallery and delete, multi-select batch actions |
| Permission panel | Settings → Permissions shows notification and media-read status, with request buttons and a shortcut to system settings. **Nothing is requested on cold start**: picking images uses the system picker and saving to the gallery uses MediaStore |
| Storage breakdown | Storage gained a dedicated "Image workspace" slice, counted separately from manga |

### Batch import fix / whale-girl splash / storage donut (v1.5.9)

| Feature | Description |
| --- | --- |
| Batch import (fixed) | "Batch import" finally works: pick a parent folder → a dialog lists its subfolders → multi-select → "Start import (N batches)". The list shows folder name, book count, page count and estimated size, with remove / clear / add more. The button is disabled while empty, and re-adding the same folder warns |
| Whale-girl splash | Cold start shows the splash art at four density buckets, scaled without distortion; light background `#F6F4EF`, dark background `#16161B`, and the dark variant is dimmed so it is easy on the eyes at night |
| Storage donut in Settings | Settings → Storage: a donut plus legend, counting source files / manga pages / thumbnails / database / backups / other by bytes. Switch between "by category" and "by manga" (Top 8 + others); tap a legend entry or slice to locate a book. The per-manga list supports search, sorting by total / source / cache / thumbnails, per-book details and thumbnail-cache cleanup; refresh and last-scan time are shown |
| Opening screen | Settings → Startup: duration 0 / 1 / 2 / 3 / 5 s (default 2 s) with a "Skip" button at bottom right; Settings can replay the opening without restarting. On cold start the system `windowBackground` and the Compose layer show the same image at the same position, so nothing jumps. Returning from background, rotating and Activity recreation do not replay it |

### Settings page / standalone search / home eye-care / file-page batch actions (v1.5.8)

| Feature | Description |
| --- | --- |
| Settings page | Entered from the top bar: theme, eye-care, backup/restore, shelf layout, import sorting, storage cleanup, about — all on one page |
| Standalone search page | Entered from the magnifier: a full-page field with clear and search buttons, results after a 300 ms pause, and history (20 entries, removable/clearable) when the query is empty |
| Home eye-care | The shelf, search and settings pages also get a warm overlay (drawn only, so taps and swipes still work). Home and reader remember their own toggle |
| File-page batch delete | Multi-select then Delete, with a confirmation, removes the source files (irreversible) |
| File-page save to gallery | Multi-select then Gallery writes into the system album `Pictures/漫画书架/`, no extra permission on Android 10+ |
| File-page "Select" | An explicit top-bar button; the old long-press-to-select still works |

### Batch import and multi-select (v1.5.7)

| Capability | Description |
| --- | --- |
| Multi-folder batch import | `ACTION_OPEN_DOCUMENT` cannot pick directories and `ACTION_OPEN_DOCUMENT_TREE` does not support multi-select, so selection happens in-app: add parent folders one by one → confirm in a list → start |
| Batch queue | The "Batches" button opens a list where you can reorder, skip a batch, or retry a failed one |
| Three-level progress | Batch (n of m) → book (n of m) → page (n of m) |
| Pause / resume | Only at **book boundaries** (the current book finishes first); the notification action works too |
| Interruption recovery | If the process is killed, unfinished batches resurface on top of the shelf after relaunch, with retry and skip |
| Import history | A separate full page: donut charts by source / format / result, with filters, paging and CSV export |
| Shelf multi-select | Enter from the top bar; cards get check marks; batch save cover / tag / delete at the bottom |

> Why pause does not work mid-page: interrupting while a page renders leaves a half-written image, and resuming would have to decide which page was partially written.
> Pausing at book boundaries makes recovery simply "continue with the next book", so no partial output is ever produced.

### Shelf

| Capability | Description |
| --- | --- |
| Three layouts | Grid / list / staggered, switched from the top bar and remembered |
| Search | Matches title / author / tags |
| Sorting | Recently read / title / page count, ascending or descending, persisted |
| Continue reading | A card at the top jumps to the last read page and stays out of the way while searching |
| Covers | Two thumbnail tiers (400 px / 1000 px) with a 250 ms debounce during fast scrolling |
| Theme | Light / dark (dark grey, not pure black) / follow system, with the splash matching on cold start |
| Long-press menu | Rename, author/tags, reading direction and mode, page order, page management, export, save to gallery, delete |
| Recycle bin | Deleted books go to a bin and can be restored within 30 days |
| Backup | Settings → "Back up shelf" writes a zip (images included); "Restore from backup" brings it back |

### Import

| Capability | Description |
| --- | --- |
| Folder as a book | `OpenDocumentTree` recursive read, natural sort on relative paths (`2.png` before `10.png`, `ch2/` before `ch10/`) |
| Single PDF | System file picker (SAF), no storage permission, pages rendered at 1440 px |
| Multiple images | Sorted by file name, EXIF rotation fixed, transparent PNG composited onto white |
| CBZ / ZIP | Imported directly as an archive, entries sorted naturally |
| Ignore junk | `说明.txt`, `Thumbs.db`, `.DS_Store` are skipped; a broken image is skipped and the import continues |
| Rollback on failure | A failed import leaves no garbage directories |
| Space pre-check | Free space is estimated before importing; if it is not enough you are told the shortfall |
| Background-safe | Foreground service + keep screen on, so switching to WeChat or locking does not interrupt |
| Resume after kill | Tasks are persisted; after a process kill the app offers "N books were not finished" with one-tap retry |
| Import sorting | Natural / file name / modified time / size / import order, with separate defaults for folders and images |
| Page-level progress | Banner and notification show "rendering page X / Y", throttled to 5 pages or 300 ms |
| Read-ahead | Neighbouring pages are prefetched while reading, so flipping does not flash white |

### Reader

| Capability | Description |
| --- | --- |
| Three modes | Left/right paging / vertical scrolling (webtoon) / two-page spread, saved per book |
| Direction | Right-to-left (Japanese) / left-to-right (Chinese, Western), per book |
| Page turning | Tap the side zones, horizontal swipe, volume keys (can be disabled), bottom slider, thumbnail picker |
| Gestures | Pinch zoom, pan while zoomed, double-tap zoom |
| Edge paging | When zoomed, you must drag past the image edge to turn the page; otherwise it springs back, so you never flip by accident |
| Sensitivity | Low (hard to misfire) / medium (default) / high |
| Long-press auto-flip | Off by default; when on, long-press no longer saves the page — use "Save page" in the toolbar |
| Eye-care | Warm / soft / custom, with manual / scheduled 19–7 / follow-system-dark policies |
| Progress memory | Every book remembers the page you reached and reopens there |

---

## 2. Project layout

```
漫画书翻译/                         ← project root, open this level in Android Studio
├─ settings.gradle.kts              ← repositories (Aliyun mirrors locally, official on CI)
├─ build.gradle.kts                 ← AGP 8.5.2 / Kotlin 1.9.24 / KSP
├─ gradle.properties
├─ gradle/wrapper/gradle-wrapper.properties
├─ keystore/mangashelf.jks          ← signing key (shared by debug and release)
├─ app/
│  ├─ build.gradle.kts              ← all dependencies
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ res/                       ← themes, splash, icon
│     └─ java/com/mangashelf/reader/
│        ├─ MangaShelfApp.kt        ← Application, owns the repositories
│        ├─ MainActivity.kt         ← minimal navigation between shelf / reader / file page / image workspace
│        ├─ data/                   ← Room entities, DAOs, migrations, storage rules, backup, stats
│        ├─ importer/               ← PDF / image / folder / CBZ import
│        ├─ export/                 ← manga export (PDF / ZIP / long image)
│        ├─ service/                ← import foreground services (one for manga, one for images)
│        └─ ui/                     ← every screen: shelf, reader, settings, search, file page, image workspace
├─ web/                             ← web version (Python + PyMuPDF)
├─ README.md  ·  README.en.md       ← Chinese / English
└─ DESIGN.md                        ← design notes (flows, data model, technical decisions)
```

---

## 3. Requirements

| Item | Requirement |
| --- | --- |
| Android Studio | Koala (2024.1.1) or newer |
| JDK | **17** (required by AGP 8.5; the bundled JBR 17 works) |
| Gradle | 8.7 (pinned in `gradle-wrapper.properties`) |
| compileSdk / targetSdk | 34 |
| minSdk | 24 (Android 7.0) |
| Device | A real device or an API 24+ emulator |

## 4. Build

The project **ships its own `gradlew`** (Gradle 8.7 wrapper) and `settings.gradle.kts` already puts the Aliyun mirrors first, so it builds out of the box.

**Option A: command line (fastest)**

```powershell
cd "C:\Users\86139\Desktop\deepseek\漫画书翻译"
.\gradlew.bat assembleRelease     # release build, ~9.5 MB (R8 is disabled)
```

The signing key lives in the repo (`keystore/mangashelf.jks`) and is used by both debug and release, so any machine produces an upgrade-compatible APK. See the "Signing" section of [版本记录.md](版本记录.md).

**Option B: Android Studio**

1. `File > Open` and select the `漫画书翻译` directory
2. Wait for Sync (the first run downloads a few hundred MB of dependencies)
3. `Run ▶` to install, or `Build > Build Bundle(s)/APK(s) > Build APK(s)`

APK output:

```
app\build\outputs\apk\release\app-release.apk     # assembleRelease
app\build\outputs\apk\debug\app-debug.apk         # assembleDebug
```

With `adb` available:

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

> The first build needs network access for dependencies (a few hundred MB).
> If the official repositories are slow, the Aliyun mirrors at the top of `settings.gradle.kts` are used instead (skipped automatically on CI).

---

## 5. Usage

1. Open the app → empty shelf → tap **Import manga**
2. Choose **Folder (whole folder = one book)** / **Batch import (every book inside a parent folder)** / **Staged import (pick several folders, one batch each)** / **PDF file** / **Images (multi-select)** / **CBZ / ZIP archive**
3. Watch the "importing 12 / 86 pages" progress; the book appears on the shelf with its first page as the cover
4. Tap the cover to read:
   - swipe left/right to flip
   - tap the middle → title, back button and page counter on top; page slider and direction switch at the bottom
   - tap the left or right zone → previous / next page
   - pinch or double-tap → zoom in; double-tap again to reset
   - long-press → save the current page to the gallery
5. Long-press a shelf card → rename / change direction / page order / page management / export / delete

Japanese manga default to **right-to-left** (page 2 sits left of page 1). For Chinese or Western comics, tap
"switch to left-to-right" at the bottom of the reader, or change it in the shelf long-press menu; the choice is remembered.

---

## 6. Where data lives

- Page images: `/data/data/com.mangashelf.reader/files/comics/<12-char id>/page_001.jpg`
- Image workspace: `/data/data/com.mangashelf.reader/files/gallery/`
- Book metadata: the Room database `mangashelf.db`
- Automatic backups: `/data/data/com.mangashelf.reader/files/backup/`
- Everything is in the app's private directory and disappears on uninstall. **No storage permission is requested** and MediaStore is only used when saving to the gallery.

To export/back up: Settings → Data & backup → "Back up shelf" writes a zip (images included); "Restore from backup" brings it back on a new device.

---

## 7. Known limitations

- **Each PDF page is rendered to a 1440 px wide JPEG** (quality 88). Dense A4 scans look slightly soft at maximum zoom; raise `PdfImporter.TARGET_WIDTH` to 2048 for more detail (at twice the storage and memory cost).
- Password-protected PDFs are not supported (the import fails with a message).
- **Text inside PDFs is not extracted**, so there is no search or text selection (that belongs to the OCR + translation roadmap).
- CBR (RAR) is not supported; CBZ / ZIP are.
- **No device regression testing yet**: `adb devices` was empty throughout development, so every release was verified by compilation and static checks only. `测试报告-*.md` marks item by item what still needs confirmation on a real device.
- Aggressive OEM battery management can still kill the import process; this release relies on a foreground service plus per-item persistence, so finished items are never lost.

---

## 8. Roadmap

1. **Translation** (the reason this folder is called "漫画书翻译"): long-press/box-select a panel → region capture → OCR → translate → overlay in place. See section 7 of `DESIGN.md`.
2. Cloud sync and accounts (currently local backup plus system auto-backup; there is no server).
3. Tablet two-page spreads and better large-screen layouts.
4. In-manga text search (requires an OCR index).

---

## 9. Web version (`web/`)

A desktop implementation of the same product, originally built to validate the interaction:

| | Android `app/` | Web `web/` |
| --- | --- | --- |
| How to run | Build an APK with Android Studio | Double-click `web\start.bat`, open the browser |
| PDF rendering | `PdfRenderer` (on device) | PyMuPDF (local Python) |
| UI | Kotlin + Jetpack Compose | Plain HTML / CSS / JS |
| Data | Room + `files/comics` | `web/data/library.json` + page JPEGs |
| Paging | HorizontalPager | two-layer transform animation + wheel/keyboard/zone taps |
| Folder import | **Supported** (`OpenDocumentTree` recursion + natural sort) | **Supported** (natural sort, subfolders as chapters) |

Both share the same product structure — shelf → (import) → reader — the same palette and the same right-to-left logic.
See [web/README.md](web/README.md).

---

## 10. Using it on a phone

### Route A: install the APK (prebuilt, recommended)

**`漫画书架-v1.6.3.apk` (~9.50 MB) sits in this directory**; just install it:

1. Allow installing from unknown sources in Android settings (the exact path differs by vendor)
2. Copy the APK to the phone (USB, WeChat file transfer, QQ, cloud drive — anything works)
3. Tap it and open "漫画书架"

**Fully offline**: no PC, no network; PDFs and images are read through the system file picker.

| Item | Value |
| --- | --- |
| Package | `com.mangashelf.reader` |
| Version | 1.6.3 (versionCode 26) |
| Requirements | Android 7.0+ (minSdk 24), targetSdk 34 |
| Signing | Self-signed key (`keystore/mangashelf.jks`), so later builds upgrade in place |
| Size | 9,960,206 bytes (9.50 MB) |
| Icon | Penguin girl (generated from `企鹅娘-无水印.png`, fixed since v1.2) |

> **Versioning rule**: every release produces a **new file**; old APKs are never overwritten or deleted (the file name carries the version).
> Historical builds, per-version changes and SHA-256 values live in [版本记录.md](版本记录.md).

### Build it yourself

See [section 4](#4-build).

### Automatic builds with GitHub Actions (optional)

The repo ships two pipelines (`.github/workflows/android.yml`):

- **push / PR**: lint + unit tests + a debug build;
- **tag (`v*`)**: lint + unit tests + `assembleRelease`, and the APK is uploaded to the matching Release.

Signing defaults to the in-repo `keystore/mangashelf.jks`. To use repository secrets instead, add these four under
`Settings → Secrets and variables → Actions` and CI will prefer them automatically:

| Secret | Meaning |
| --- | --- |
| `KEYSTORE_BASE64` | output of `base64 -w0 keystore/mangashelf.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

If they are absent the pipeline does not fail — it logs a warning and falls back to the in-repo keystore.

### Route B: no app, use the phone browser (LAN)

See "Using it on a phone" in [web/README.md](web/README.md). Three steps:

1. Double-click `web\允许手机访问.bat` once to allow the port through the firewall
2. Double-click `打开漫画书架.lnk` and note the `手机上： http://192.168.x.x:8971/` line
3. Open that address in the phone browser on the same Wi-Fi → menu → "Add to home screen"

Phone gestures are supported: swipe to flip, tap the sides, **pinch to zoom**, pan while zoomed, double-tap to zoom.

Limitation: **the PC must stay on**, the phone and PC must share a Wi-Fi network, and the PDF is rendered on the PC.

### Comparison

| | APK | Web (LAN) |
| --- | --- | --- |
| Works offline | **Yes, fully local** | No, the PC must be running |
| PDF rendering | `PdfRenderer` on the phone | PyMuPDF on the PC |
| Import | System file picker (**folders** / PDF / images / CBZ) | Folder upload / local path / file |
| Folder as a book | **Supported** (SAF tree, recursive) | Supported (natural sort + subfolders) |
| Progress storage | Room database | `library.json` on the server |
| Phone gestures | Swipe + pinch zoom | Swipe + pinch zoom |
