# AlMaktaba — المكتبة

An Android reader for digital books and comics in **PDF, EPUB, TXT, CBZ and CBR**.
Arabic is the app's default language with full English support, and the entire interface is built
with **Jetpack Compose** and **Material 3**, on a multi-module clean architecture using MVI and Hilt.

**العربية:** [README.md](README.md)

---

## Table of contents

1. [Overview](#overview)
2. [Features](#features)
3. [Supported formats](#supported-formats)
4. [Building and running](#building-and-running)
5. [Architecture](#architecture)
6. [Testing](#testing)
7. [Licence](#licence)

---

## Overview

AlMaktaba is an Arabic-first digital reader: it imports books through the Storage Access Framework
(SAF) while requesting **no permissions at all** — no storage and no network — and reads them in a
single reader that adapts to what the open file can do.

| Item | Value |
|---|---|
| **Version** | `1.0.0` |
| **Minimum Android** | 8.0 (API 26) |
| **Permissions** | None whatsoever — no storage, no network |
| **Languages** | Arabic (default) and English, switchable in-app without a restart |

## Features

- **Library**: import single files or a whole device folder through SAF; grid and list layouts; five
  sort orders; favourites; format and folder filters; automatic cover extraction; moving a book
  between folders; a selection mode that acts on several books at once; and a *continue reading*
  button that follows the open folder.
- **Reader**: one toolbar that adapts to each document's capabilities; every format has both a paged
  and a continuous-scroll presentation; tap zones that turn the page (mirrored for Arabic and
  independently reversible); pinch-zoom and a clamped pan; double-tap a speech bubble in a comic to
  zoom into it; page-turn effects (page curl, slide, fade); three page-fit modes; in-document
  search; outlines; bookmarks; read-aloud and quote sharing; full control of font, size, line
  spacing, margins and paragraph spacing; three bundled Arabic typefaces; three page-paper colours;
  and an automatic hand-off into the next volume when reading a folder as a series.
- **Appearance**: six Material 3 colour schemes plus the wallpaper palette on Android 12+, with a
  first-run picker.
- **Opening from elsewhere**: the app registers as a viewer for every format it reads, so a file
  opened from a file manager is added to the library and opened automatically.

## Supported formats

| Format | Engine |
|---|---|
| PDF | pdfium |
| EPUB 2 and EPUB 3 | in-project parser built on `java.util.zip` and jsoup |
| TXT | charset detection with Windows-1256 support for Arabic |
| CBZ | ZIP |
| CBR | RAR (junrar) |

## Building and running

### Download

A signed, installable build is attached to the latest release:

**→ [AlMaktaba-v1.0.0.apk](https://github.com/ammar-alfifi/AlMaktaba/releases/download/v1.0.0/AlMaktaba-v1.0.0.apk)**

Signed with APK Signature Scheme v2 + v3. The app requests no permissions at all; books are added
through the system file picker, which grants access to the files you choose and nothing else.

### Requirements

- JDK 17+ (JDK 21 verified)
- Android SDK with **platform 37** (`io.legere:pdfiumandroid` publishes a minimum `compileSdk` of 37)
- Gradle 9.5+ (the wrapper pins 9.5.1)

```bash
# Debug build
./gradlew :app:assembleDebug

# Every unit test in every module
./gradlew test

# Install on a connected device or emulator
./gradlew :app:installDebug
```

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

### Toolchain

| | |
|---|---|
| AGP | 9.2.1 (built-in Kotlin — `org.jetbrains.kotlin.android` must not be applied) |
| Kotlin | 2.3.21 |
| Gradle | 9.5.1 |
| Compose | BOM 2026.08.00 equivalents (Material 3 1.4.0, Compose UI 1.12.0) |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |

### Release signing

Release signing is **opt-in**: `:app` reads `keystore.properties` from the repository root if it
exists and signs the release build with it; when it is absent the release build still succeeds and
produces an unsigned APK. Neither that file nor the keystore is ever committed.

## Architecture

```text
AlMaktaba/
├── app/                      entry point, navigation host, in-app locale, Hilt root
├── core/
│   ├── core-common/          AppResult, AppError, dispatchers, text utilities   (pure JVM)
│   ├── core-domain/          models, engine contracts, repositories, use cases  (pure JVM)
│   ├── core-data/            Room, DataStore, repository impls, engine wiring
│   └── core-ui/              Material 3 theme, shared components, MVI base
├── feature/
│   ├── feature-library/      library, import, book details
│   ├── feature-reader/       paged + reflowable readers, panels, page cache
│   ├── feature-settings/     appearance and language
│   └── feature-search/       library search and search inside books
├── format/
│   ├── format-pdf/           pdfium engine
│   ├── format-epub/          EPUB 2/3 container, OPF, NCX/nav, sanitiser
│   ├── format-text/          charset detection, chapter index, markup
│   └── format-archive/       CBZ (zip) and CBR (RAR)
└── build-logic/              convention plugins shared by every module
```

### The dependency rule, and how it is enforced

```text
feature/*  ──▶  core-domain  ◀──  core-data  ──▶  format/*
    │                ▲                                 ▲
    └──▶  core-ui ───┘                                 │
                                                       │
             core-data is the ONLY module that ────────┘
             names a decoder
```

Two rules do the real work, and both are enforced by the compiler rather than by convention:

1. **`:core:core-domain` is a plain JVM module.** It has no Android dependency at all, so no decoder
   or framework type can leak into a use case, and the entire domain tests in milliseconds with
   plain JUnit.
2. **No `:feature:*` module depends on any `:format:*` module.** A ViewModel physically cannot
   reference `PdfEngine`, because the module is not on its classpath.

The seam that makes this possible is `DocumentSource`. Decoders read bytes through it and `core-data`
implements it over a `content://` URI using `ContentResolver`. Adding a format is one module plus one
`@Provides @IntoSet` method in `EngineModule` — there is no `when (format)` anywhere in the app.

## Testing

**632 unit tests, 0 failures, across 13 modules.** `./gradlew test` runs them all.

| Module | Tests | Covers |
|---|---:|---|
| `feature-reader` | 255 | pagination arithmetic, the bubble detector, page-turn effects, the byte-bounded page cache, the reading order across a folder's volumes |
| `format-epub` | 89 | container/OPF parsing, nav + NCX, the sanitiser, declared covers |
| `format-text` | 52 | Windows-1256 / UTF-16 decoding, chapter splitting, search offsets |
| `core-data` | 48 | real SQLite: every sort order, search, the 1 → 3 migration, bookmarks and notes |
| `core-domain` | 66 | format resolution, progress arithmetic, import rules, folder sequences |
| `core-common` | 30 | natural sort key, file-name parsing, byte formatting |
| `format-archive` | 29 | natural page ordering, junk-entry filtering, container sniffing |
| `feature-search` | 20 | snippet offsets, result grouping, query history |
| `format-pdf` | 15 | aspect fitting, outline nesting |
| `feature-settings` | 12 | intent → settings mapping, the line between the two resets |
| `app` | 8 | cold start: real Hilt graph + `MainActivity` lifecycle, and the language override |
| `core-ui` | 5 | the segmented row's corner rule |
| `feature-library` | 3 | the set of folders marked unavailable |

Properties of the suite worth pointing out:

- **The startup tests build the real dependency graph.** `app`'s tests launch `MainActivity` through
  its actual lifecycle under Robolectric, constructing every Hilt binding, the Room database and all
  the decoders — which is what once caught a launch crash that compiled cleanly.
- **The decoder tests build real files.** `format-archive` writes an actual CBZ with
  `ZipOutputStream`; `format-epub` builds an in-memory EPUB with a container, OPF, NCX and chapters.
  Nothing is mocked.
- **The data-layer tests execute the actual generated SQL** under Robolectric against an in-memory
  Room database.

## Licence and attribution

Book decoding is performed by third-party libraries, each under its own licence: pdfium (BSD-3),
junrar (UnRAR licence), jsoup (MIT) and juniversalchardet (MPL 1.1/GPL/LGPL tri-licence). EPUB
parsing is implemented in this project directly on `java.util.zip` and jsoup — there is no Readium
dependency.

The three bundled typefaces — **Amiri**, **IBM Plex Sans Arabic** and **Reem Kufi** — are under the
SIL Open Font License 1.1, and their licence texts ship in [`licenses/`](licenses/).
