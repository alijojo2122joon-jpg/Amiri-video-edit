# AMIRI CUT

A native, **100 % offline**, device-powered video editor for Android — built for the realme GT3 (Android 16), minimal and professional: *Less features — more control.*

> **Status: Stage 1 of 14** — Project + Media Import + Preview + Timeline.
> Everything listed under *Working now* is real and functional. Everything else is
> marked honestly in the app ("arrives in Stage N") — there are no fake controls.

---

## راهنمای سریع (فارسی)

- این پروژه یک اپ **واقعی Android** با Kotlin + Jetpack Compose + Media3 است؛ وب‌اپ نیست.
- اپ هیچ مجوز اینترنت ندارد (در Manifest حذف شده و در CI هم بررسی می‌شود).
- **ساخت APK بدون کامپیوتر:** پروژه را در یک مخزن GitHub آپلود کن → تب **Actions** → Workflow «Build APK» خودکار اجرا می‌شود → در پایان، از بخش **Artifacts** فایل `AmiriCut-release-apk` را دانلود و نصب کن.
- مرحله ۱ کامل است؛ ابزارهای مراحل بعد در اپ با برچسب «Stage N» مشخص شده‌اند.

---

## Working now (Stage 1)

| Area | What works |
|---|---|
| **Home** | New / Open (restore backup) / Recent projects with cover, resolution, fps, aspect, last-edited. Swipe → rename, swipe ← delete. Long-press → rename, duplicate, backup to file, delete. |
| **New project** | 720p / 1080p / 1440p / 4K · 9:16, 16:9, 1:1, 4:5, 4:3, 21:9, custom · 24/25/30/50/60 fps · black or transparent canvas · create & import media in one step. |
| **Media** | Import video, photo, audio via the Storage Access Framework (persisted read permission, files read in place, never copied or uploaded). Media bin, add at playhead, **Replace Media**, **Relink Missing Media**, remove unused. |
| **Preview** | Hardware-decoded (MediaCodec via Media3 ExoPlayer) playback of the composed timeline: top-most visible visual track, stills, all audio tracks mixed. Frame-exact paused scrubbing. Gaps render as canvas background. |
| **Timeline** | Text, Overlay, V1–V4, A1–A3 tracks + add more. Fixed center playhead, pinch zoom (down to single frames), drag/fling scrub, two-finger navigation, double-tap fit, ruler tap-to-seek, long-press ruler → marker. Real **filmstrip thumbnails** and **real audio waveforms** (MediaCodec-decoded PCM). |
| **Editing** | Select, **long-press & drag to move** (across tracks, with overlap protection), **trim** handles, **split** (selected clip or all tracks), delete, **ripple delete**, duplicate, clip lock, track lock / hide / mute, markers, prev/next edit, frame-by-frame stepping. **Snapping** to playhead, clip edges and markers with haptic tick. |
| **History** | Snapshot Undo/Redo, 100 steps, per project. |
| **Safety** | Autosave (1.5 s after each change + every 10 s), crash detection with **Recover Project** prompt, atomic file writes, project **Backup / Restore** (`.amiricut`). |
| **Guides** | Grid (thirds), center, action safe, title safe, Reels/Shorts/TikTok/YouTube UI zones. |
| **Settings** | Accent colour, Performance mode (Battery Saver → Maximum), snapping, haptics, **Cache Manager** (preview / proxy / render caches: size + clear), verified "no network access" indicator. |
| **Layout** | Portrait and landscape layouts; no activity restart on rotation. |

## Roadmap (honest)

| Stage | Scope | State |
|---|---|---|
| 1 | Project + Media Import + Preview + Timeline | ✅ this build |
| 2 | Cut / Trim polish, Transform (crop, rotate, flip, scale, position, anchor, opacity, blend), speed, reverse, freeze | ⏳ |
| 3 | Keyframe engine (linear, ease, bezier, custom curves) | ⏳ |
| 4 | Text engine + font manager (TTF/OTF, Persian/Arabic RTL) | ⏳ |
| 5 | Masks (rect, ellipse, pen; feather, expansion, invert) | ⏳ |
| 6 | Color (basic, HSL, curves, wheels, .CUBE LUT) + scopes + before/after | ⏳ |
| 7 | GPU effects (glow, light sweep, rays, leaks, film, blur, motion blur, chroma key), effect stack, adjustment layers | ⏳ |
| 8 | Motion tracking | ⏳ |
| 9 | Rotoscoping | ⏳ |
| 10 | Audio tools | ⏳ |
| 11 | Export (Transformer, encoder capability check, render queue, background export) | ⏳ |
| 12–14 | Optimisation, crash testing, UI polish | ⏳ |

---

## How to Build

**Requirements:** JDK 17, Android SDK 35 (installed automatically by Android Studio / the CI runner).

### On a phone only (GitHub Actions)
1. Create a GitHub repository and upload this folder (or push it).
2. Open **Actions → Build APK**. It runs on every push to `main` (or press *Run workflow*).
3. When it finishes, download **AmiriCut-release-apk** from the run's *Artifacts* and install it.

### Android Studio
Open the folder → let Gradle sync → **Run ▶** on the device.

### Command line
```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # timeline / frame-math / history unit tests
```

## How to Run
Install the APK, open **AMIRI CUT**, tap **New Project → Import media**. Grant nothing else — the app needs no runtime permissions because the system file picker grants access per file.

## How to Generate APK
`./gradlew assembleDebug` → debug APK (package `com.amiri.cut.debug`, can be installed side-by-side with release).

## How to Generate Release APK
`./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (minified with R8).

Signing:
- Set `AMIRI_KEYSTORE_PATH`, `AMIRI_KEYSTORE_PASSWORD`, `AMIRI_KEY_ALIAS`, `AMIRI_KEY_PASSWORD` in your environment, **or** in GitHub add the secrets `AMIRI_KEYSTORE_BASE64` (base64 of the `.jks`), `AMIRI_KEYSTORE_PASSWORD`, `AMIRI_KEY_ALIAS`, `AMIRI_KEY_PASSWORD`.
- Without them the release is signed with the debug key — installable for personal use. Keep one key forever if you want updates to install over old versions.

---

## Architecture

Single `:app` Gradle module, strictly layered by package (each layer only depends downward). Splitting into Gradle modules later is mechanical.

```
com.amiri.cut
├── core/            pure Kotlin — no Android imports, unit-tested
│   ├── model/       Project, Track, Clip, MediaAsset, Marker (serializable, immutable)
│   ├── time/        FrameTime: frame-exact µs ↔ frame math, timecode
│   ├── timeline/    TimelineOps: split, trim, move, ripple, duplicate, snap… (pure functions)
│   └── history/     Snapshot undo/redo
├── storage/         ProjectRepository (JSON files, autosave, recovery, backup), AppSettings, CacheManager
├── media/           MediaProbe, ThumbnailCache, WaveformCache, BitmapLoader
├── engine/          PreviewEngine (timeline clock + ExoPlayer slaves)
└── ui/              Jetpack Compose: theme (liquid glass), home, newproject, settings, editor
```

**Data flow:** UI → `EditorController.commit(label, newProject)` → history snapshot → `PreviewEngine.setProject()` → debounced autosave. Every edit is a pure function on an immutable `Project`, so undo is free and the logic is testable without a device.

### Technology choices
- **Kotlin + Jetpack Compose** — modern declarative UI; the timeline is a single custom-drawn `Canvas` (no per-clip views) for 60 fps scrolling.
- **Media3 ExoPlayer** for preview — uses the device's hardware `MediaCodec` decoders and accepts the same Media3 GL `Effect`s as **Media3 Transformer**, which will do export. One effect implementation → Preview ≈ Final Render.
- **JSON project files instead of Room** — the project *is* a document (non-destructive edit list). Atomic JSON files give backup/restore, crash recovery and future format migration for free, without an annotation processor. A database adds nothing at this scale; Room can be added later for a media index if needed.
- **No DI framework, no navigation library** — a tiny service container (`AmiriCutApp`) and a typed screen stack are enough and keep the dependency list short.

## Dependencies (and why)

| Dependency | Why |
|---|---|
| `androidx.compose.*` (BOM 2025.04.01), `material3`, `material-icons-extended` | UI toolkit, dark Material 3 base, icon set (R8 strips unused icons in release). |
| `androidx.activity:activity-compose` | `setContent`, edge-to-edge, Activity Result API for the SAF pickers. |
| `androidx.lifecycle:lifecycle-runtime-compose` | Lifecycle-aware Compose helpers. |
| `androidx.core:core-ktx` | Kotlin extensions for Android APIs. |
| `androidx.media3:media3-exoplayer`, `media3-common` | Hardware-accelerated decode & preview; shared effect pipeline with Transformer (Stage 11). |
| `kotlinx-coroutines-android` | Background work (probing, thumbnails, waveform decode, I/O) off the UI thread. |
| `kotlinx-serialization-json` | Project file format (no reflection, R8-safe). |
| `junit` (test only) | Unit tests for core timeline logic. |

None of these performs network access. Media3's optional network data sources are present in the library but **can't be used**: the app has no `INTERNET` permission (removed via `tools:node="remove"`, verified at runtime in Settings and in CI).

## Effect System (design — implemented from Stage 6/7)
Each clip and adjustment layer will carry an ordered **effect stack** (`List<EffectInstance>` with id, type, enabled flag, parameters, keyframes). The stack is translated into Media3 `GlEffect`s / custom `GlShaderProgram`s (GLSL ES 3.0). The *same* list is applied to the preview ExoPlayer (`setVideoEffects`) and to Transformer's `EditedMediaItem` during export, so preview and render share one implementation. Heavy effects render at Preview Quality (Full/Half/Quarter) in preview and full resolution in export.

## Rendering System
- **Preview (now):** `PreviewEngine` owns a timeline clock. The top visible visual clip drives one ExoPlayer (hardware decoder → `TextureView`); each audio track drives its own player. While video plays, the clock *follows the decoder* (no drift); in gaps/stills it follows the monotonic clock; while the decoder buffers after a cut, the clock holds. Contiguous cuts from one source continue without reseeking. Paused seeks are `EXACT` with latest-wins coalescing for smooth frame-accurate scrubbing.
- **Export (Stage 11):** Media3 Transformer `Composition` (one `EditedMediaItemSequence` per track) encoded with `MediaCodec` in a foreground `WorkManager` job with progress, ETA, cancel and a render queue. Encoder capabilities (`MediaCodecList`) are probed first; unsupported size/fps/codec combinations are replaced by the nearest supported option instead of crashing.

## Timeline System
- Time is integer **microseconds**; every edit is **quantized to frame boundaries** of the project fps (`FrameTime`), so cuts always land on real frames at 24/25/30/50/60 fps.
- `Clip` = asset reference + timeline start + source in/out (non-destructive). Invariants (sorted, non-overlapping, ≥ 1 frame, inside source range, locked = immutable) are enforced in `TimelineOps` and covered by unit tests.
- Tracks are stored in display order; a visual track higher in the list composites above lower ones.
- Rendering: one Compose `Canvas` draws rows, clips, filmstrips, waveforms, ruler (adaptive frame/second ticks), markers, snap guide and playhead. Gestures are a custom state machine (tap / drag / long-press / pinch) so trimming, moving, scrubbing and zooming never conflict.

## Storage
```
files/projects/<id>/project.json     saved project (source of truth)
files/projects/<id>/autosave.json    newest autosave while editing
files/projects/<id>/session.lock     present while open → crash detection
files/projects/<id>/thumb.jpg        Home cover
cache/preview/thumbs/<asset>/…       filmstrip frames (rebuildable)
cache/preview/waveforms/<asset>.wf   peak data, 100 buckets/s (rebuildable)
cache/proxy/, cache/render/          reserved for Stage 11/12
```
Source media are referenced by persisted SAF URIs and are **never modified**. Backups (`.amiricut`) are zip files containing `project.json` + cover; restoring always creates a new project.

## Offline Architecture
- No `INTERNET` / `ACCESS_NETWORK_STATE` permission (merged-manifest removal + CI check + in-app verification).
- No accounts, analytics, crash reporters, ads, remote config or cloud SDKs in the dependency graph.
- `android:allowBackup="false"` — projects are not copied to cloud backup; use the explicit `.amiricut` export instead.
- All decoding, thumbnailing, waveform analysis (and later effects, tracking, roto, export) run on-device on CPU/GPU/MediaCodec.

## Known limitations of Stage 1
- Preview shows the **top-most** visible visual clip; multi-layer compositing (blending V1–V4/Overlay together) arrives with the GPU pipeline in Stage 7.
- Switching between clips of *different* source files has a short decoder warm-up (≈ 100–300 ms). Stage 12 adds a pre-roll second player.
- Performance mode currently affects thumbnail workers/density only; preview quality and proxies follow in Stages 7/12.
