# MyLibrary — مكتبتي

A multi-format Android reader for digital books and comics: **PDF, EPUB, TXT, CBZ and CBR**,
Arabic-first, built entirely with Jetpack Compose and Material 3.

<p dir="rtl">

**مكتبتي** تطبيق أندرويد لقراءة الكتب الرقمية والقصص المصورة بصيغ PDF و EPUB و TXT و CBZ و CBR.
اللغة العربية هي اللغة الافتراضية للتطبيق، مع دعم كامل للإنجليزية، وواجهة مبنية بالكامل بـ
Jetpack Compose و Material 3، ومعمارية نظيفة متعددة الوحدات تعتمد على MVI و Hilt.

</p>

---

## Table of contents

1. [What it does](#1-what-it-does)
2. [Requirements compliance](#2-requirements-compliance)
3. [Building and running](#3-building-and-running)
4. [Architecture](#4-architecture)
5. [Key design decisions](#5-key-design-decisions)
6. [Testing](#6-testing)
7. [Engineering findings worth knowing](#7-engineering-findings-worth-knowing)
8. [Known limitations](#8-known-limitations)

---

## 1. What it does

| Capability | Details |
|---|---|
| **Formats** | PDF (pdfium), EPUB 2 & 3, plain text (with Arabic charset detection), CBZ (zip), CBR (RAR) |
| **Languages** | Arabic by default, English as a complete second locale, switchable in-app without a restart |
| **Direction** | Full RTL for Arabic, LTR for English — and a document's own direction is honoured *independently* of the UI, so an English TXT reads left-to-right inside the Arabic interface |
| **Library** | Import through the Storage Access Framework, grid/list layouts, five sort orders, favourite and format filters, automatic cover extraction |
| **Reader** | One toolbar across all five formats, adapting to what the open file can do; paged and reflowable modes; **EPUB and TXT can be split into pages** as well as scrolled; tap zones that turn the page (mirrored for Arabic) or scroll a screenful, with a haptic tick on every turn and a switch to turn them off; pinch-zoom, double-tap and a clamped pan; three page-fit modes; per-document search, outlines, bookmarks; font/theme/line-height controls that apply live, and one button that puts them all back |
| **Storage** | No storage permission at all — only scoped `content://` access to files the user picked |

## 2. Requirements compliance

Every hard constraint from the specification, and where it is satisfied:

| # | Requirement | Where |
|---|---|---|
| 1 | Arabic is the default UI language | `values/strings.xml` in every module holds Arabic; `values-en/` holds English. Verified in the built APK: the *unqualified* `string/lib_empty_title` resolves to `لا توجد كتب بعد` |
| 1 | English as a complete second language | `values-en/strings.xml` in every module, key-for-key |
| 1 | In-app language switching | `SettingsScreen` → `AppLanguage` → `app/.../ui/Locale.kt`; applies language *and* layout direction immediately, no restart |
| 1 | Full RTL / LTR | `android:supportsRtl="true"`, direction derived from the effective locale, and a per-document direction override in the reader |
| 1 | English content inside an Arabic UI | The reader sets direction from the *document's* language, not the UI's (`ReflowableDocument` → `ProvideLayoutDirection`) |
| 2 | Material 3 only | `androidx.compose.material3` throughout; no Material 2 component anywhere. The XML theme is a bare `android:Theme.Material.*` used only for the window background before the first Compose frame |
| 2 | Dynamic colour + fallback | `MyLibraryTheme` uses dynamic colour on API 31+ and a hand-built tonal palette (`theme/Color.kt`) otherwise |
| 2 | M3 components | `Scaffold`, `TopAppBar`, `NavigationBar`, `NavigationRail`, `FloatingActionButton`, `ExtendedFloatingActionButton`, `Card`, `Button`, `OutlinedButton`, `TextButton`, `IconButton`, `OutlinedTextField`, `Switch`, `Slider`, `Snackbar`, `ModalBottomSheet`, `AlertDialog`, `SearchBar`, `SegmentedButton`, `FilterChip`, `AssistChip`, `ListItem`, `Badge`-style overlays, `LinearProgressIndicator`, `CircularProgressIndicator`, `HorizontalDivider`, `VerticalDivider`, `DropdownMenu` |
| 2 | Edge-to-edge & predictive back | `enableEdgeToEdge()`, `android:enableOnBackInvokedCallback="true"`, and explicit `BackHandler` ordering in the reader |
| 2 | Adaptive layouts | `WindowSizeClass` (AndroidX) drives `NavigationBar` ↔ `NavigationRail`; the library grid uses `GridCells.Adaptive` |
| 3 | Compose-first | No `ViewPager2`, no `RecyclerView`, no `Fragment`, no `findViewById`, no XML layout |
| 3 | Compose Pager | `HorizontalPager` in `PagedReader.kt` |
| 3 | Lazy lists | `LazyColumn`, `LazyRow`, `LazyVerticalGrid` |
| 4 | Multi-module clean architecture | 13 modules; see [§4](#4-architecture) |
| 4 | UI never depends on decoders | Enforced by the module graph: `:feature:*` cannot see `:format:*` |
| 5 | MVI + UDF | `core/ui/mvi/MviViewModel.kt`; one immutable state, intents in, effects out |
| 5 | `StateFlow`/`SharedFlow` only | No `LiveData` anywhere in the project |
| 6 | Hilt everywhere | `@HiltAndroidApp`, `@HiltViewModel`, constructor injection; engines bound via `@IntoSet` multibinding |
| 7 | No whole-document loading | Engines render on demand; the reader uses a **byte-bounded** page cache |
| 7 | Byte-limited cache | `PageCache` sizes its budget in bytes, not page count |
| 7 | Proactive memory handling | `inSampleSize` downsampling in every image path; `OutOfMemoryError` mapped to `AppError.OutOfMemory` |
| 8 | Scoped storage | Zero storage permissions; SAF only; `FileProvider`-free because nothing is shared by path |
| 8 | `cacheDir` for temporaries | Covers in `cacheDir/covers`, CBR extraction in `cacheDir/comic-archives` (deleted on close) |

## 3. Building and running

### Download

A signed, installable build is attached to the latest release:

**→ [MyLibrary-v1.2.0.apk](https://github.com/ammar-alfifi/MyLibrary/releases/download/v1.2.0/MyLibrary-v1.2.0.apk)** (~33 MB)

Android 8.0 (API 26) and above. Signed with APK Signature Scheme v2 + v3. The app requests **no
storage permission** — books are added through the system file picker, which grants access to the
files you choose and nothing else.

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
| AGP | 9.2.1 — note this uses **built-in Kotlin**; `org.jetbrains.kotlin.android` must not be applied |
| Kotlin | 2.3.21 |
| Gradle | 9.5.1 |
| Compose | BOM 2026.08.00 equivalents (Material 3 1.4.0, Compose UI 1.12.0) |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |

## 4. Architecture

```text
MyLibrary/
├── app/                      entry point, navigation host, in-app locale, Hilt root
├── core/
│   ├── core-common/          AppResult, AppError, dispatchers, text utilities   (pure JVM)
│   ├── core-domain/          models, engine contracts, repositories, use cases  (pure JVM)
│   ├── core-data/            Room, DataStore, repository impls, engine wiring
│   └── core-ui/              Material 3 theme, shared components, MVI base
├── feature/
│   ├── feature-library/      library, import, book details
│   ├── feature-reader/       paged + reflowable readers, panels, page cache
│   ├── feature-settings/     appearance, language, library and reading settings
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

1. **`:core:core-domain` is a plain JVM module.** It has no Android dependency at all, so no
   decoder or framework type can leak into a use case, and the entire domain tests in milliseconds
   with plain JUnit — no Robolectric, no device.
2. **No `:feature:*` module depends on any `:format:*` module.** A ViewModel physically cannot
   reference `PdfEngine`, because the module is not on its classpath.

The seam that makes this possible is `DocumentSource`. Decoders read bytes through it; `core-data`
implements it over a `content://` URI using `ContentResolver`. So `:format:format-epub` has never
heard of `Uri`, and `:feature:feature-reader` has never heard of pdfium.

Adding a format is one module plus one `@Provides @IntoSet` method in `EngineModule` — there is no
`when (format)` anywhere in the app, and no registry to keep in sync.

## 5. Key design decisions

A few choices where the obvious approach was rejected, and why.

**The domain is pure JVM, and pays one copy for it.** `PageImage` carries an `IntArray` of ARGB
pixels rather than a platform `Bitmap`, because `Bitmap` would force Android into `core-domain` and
with it Robolectric for every domain test. The cost is one `getPixels`/`createBitmap` copy per page
render, at the size the screen actually needs. That is the right trade: it is O(page), it happens
off the main thread, and it buys a domain layer that tests in milliseconds.

**The page cache is bounded in bytes, not pages.** "Keep the last 10 pages" is 20 MB on one book and
800 MB on another, and the second one is an out-of-memory crash while scrolling. `PageCache` budgets
by bytes so a heavy book keeps fewer pages and a light one keeps more.

**Reflowable position is (chapter, character offset), not a pixel scroll.** A pixel offset is
meaningless the moment the user changes the font size, which is a control the reader puts one tap
away. Anchoring to the text means re-flowing never loses the reader's place.

**The reader has one toolbar, and it asks the document what it can do.** PDF, EPUB, TXT, CBZ and CBR
do not offer the same things — a comic has no text layer to search and no outline to navigate, a
plain-text file has no outline either — so the toolbar's spine is fixed (back, title and position,
bookmark, settings, overflow) and the *overflow's contents* are derived from the opened document's
`EngineCapabilities` by a pure function, `readerMenuActions`. An action that cannot work is absent
rather than present and inert. Two rejected alternatives, both of which looked reasonable: rendering
every action for every format (the toolbar then offers a comic a search that can only apologise), and
branching on `isPaged` instead of on capabilities (which conflates "has pages" with "has text" — a
CBZ has pages and no text, a TXT the reverse).

**A page in a text file is a decision, not a fact.** An EPUB chapter has no pages: how many it
becomes depends on the screen, the font size and the leading, all of which the reader can change
while reading. So the paginator measures the chapter with the same `TextMeasurer` that will draw it,
cuts text blocks at line boundaries — a page never ends mid-line, and the continuation re-wraps
identically — and moves what cannot be divided whole. That split is why `BlockMeasure` is an
interface: *which characters go on which page* is arithmetic, `paginate` is a pure function over it,
and both are tested without a device. Only *how tall is this text* needs a text shaper.

Two consequences are deliberate. A page is a character offset, not a page number, so a font-size
change re-paginates and lands the reader on the text they were already reading rather than on page
one. And an image or a table is given a page to itself rather than an estimated height, because
neither is knowable without decoding or laying it out, and a wrong guess puts a caption on top of a
picture. The cost is white space around a small illustration; the alternative is a rendering fault
that looks like a bug in the app.

**The fit mode is expressed in the render box, not in the view.** `PageFitMode` was stored, offered
in two settings screens and never read by the renderer. Wiring it up in the view layer would not have
worked: pdfium maps a page onto whatever rectangle it is handed instead of letterboxing, so the
*shape* of the box a page is rendered into decides how it comes out. Page fit asks for the viewport
(contained, letterboxed), width fit for the viewport's width and as much height as the page's own
proportions require, and actual size for the page's own dimensions — and the two whose size comes
from the file are then capped, because a PDF page tree may declare any MediaBox it likes. What the
file controls gets bounded; what the screen controls does not.

**Progress for a paged document is exact; for a reflowable one it is per-chapter.** Page *n* of *N*
is honest. A chapter index is not — chapters range from one page to a hundred — so reflowable
progress counts the chapters *behind* the reader, and the reader's own progress bar reads the same
`ReadingProgressUseCase` arithmetic the library card and bookmarks list do, because a reader showing
40% for a book the shelf says is 12% through is worse than either number alone. Refining it further
with a character offset within the chapter is modelled — `fromChapter` takes a fraction — but nothing
tracks a scroll fraction to pass it yet, so both screens are chapter-accurate and agree.

**Room generates Java here, deliberately.** See [§7](#7-engineering-findings-worth-knowing).

## 6. Testing

**393 unit tests, 0 failures, across 11 modules.** `./gradlew test` runs them all.

| Module | Tests | Covers |
|---|---:|---|
| `format-epub` | 84 | container/OPF parsing, nav + NCX, sanitiser, path resolution, traversal refusal, embedded fonts, links |
| `feature-reader` | 103 | HTML → block parsing, chapter text offsets and link anchors, which toolbar actions a document supports, tap-zone mirroring, page-fit geometry, **page-breaking arithmetic** (line boundaries, spacing, atomic blocks, degenerate pages), progress agreement with the library |
| `format-text` | 47 | Windows-1256/UTF-16/BOM decoding, chapter splitting, escaping, search offsets |
| `core-domain` | 32 | format resolution, progress arithmetic, library join, import rules |
| `core-common` | 30 | natural sort key, file-name parsing, byte formatting, result combinators |
| `format-archive` | 29 | natural page ordering, junk-entry filtering, container sniffing, sample-size maths |
| `feature-search` | 20 | snippet offsets, result grouping, query history |
| `core-data` | 17 | **real SQLite**: every sort order, `LIKE … ESCAPE`, cascade deletes, upserts |
| `format-pdf` | 15 | aspect fitting, outline nesting, malformed bookmark trees |
| `app` | 8 | **cold start**: real Hilt graph + `MainActivity` lifecycle, and the language override |
| `feature-settings` | 8 | intent → settings mapping, and that the reader's own reset touches only reading settings |

Three properties of the suite are worth pointing out:

- **The startup tests build the real dependency graph.** `app`'s smoke tests launch `MainActivity`
  through its actual `onCreate` under Robolectric, constructing every Hilt binding, the Room
  database, the DataStore and all four decoders. They are what caught the launch crash described in
  [§7](#7-engineering-findings-worth-knowing) — a failure that compiled cleanly, passed all 241
  other tests, and killed the app on every device.
- **The decoder tests build real files.** `format-archive` writes an actual CBZ with `ZipOutputStream`
  (pages stored out of order, plus `__MACOSX/` and `.DS_Store` cruft); `format-epub` builds an
  in-memory EPUB with a container, OPF, NCX, nav document and chapters. Nothing is mocked.
- **The data-layer tests execute the actual generated SQL.** The DAO tests run under Robolectric
  against an in-memory Room database, which is what verifies the five hand-written `ORDER BY`
  clauses, the `LIKE … ESCAPE '\'` search, the `IFNULL(author, …)` fallback and the
  `ON DELETE CASCADE` — none of which the compiler can check, because they are strings.
- **The page-ordering test was mutation-checked.** Replacing `naturalSortKey` with a naive string
  sort produced exactly 5 failures, then reverted. A test that cannot fail is not a test.

Not covered, and stated plainly: `renderPage`'s decode path needs a device (`BitmapFactory` has no
JVM implementation), and there is no CBR success-path fixture because no RAR encoder was available
to build one — the RAR *open* path is exercised through a mislabelled-extension test.

### On running the app

`app/build/outputs/apk/debug/app-debug.apk` builds, installs and packages correctly — verified
against the artifact itself (Arabic default label, English override, launchable activity, all four
ABIs with the pdfium native libraries, `Stored`/uncompressed and `extractNativeLibs=false`).

**Cold start is covered by automated tests.** `app`'s smoke tests build the real Hilt graph and
launch `MainActivity` through its true lifecycle under Robolectric, which is what caught the launch
crash in [§7](#7-engineering-findings-worth-knowing).

What is **not** covered is everything past startup: rendering, gestures, the reader, the file
picker. No AVD completes its boot in the environment this was built in — the emulator process dies
at RenderThread initialisation under both hardware acceleration and software emulation, across five
attempts and two system images — so those paths are inferred from the code and the unit tests, not
observed. They are the most likely place for the next bug to be.

## 7. Engineering findings worth knowing

Five things were discovered while building this that are not obvious and would cost the next person
real time. The first is the one that mattered most.

### The bug that shipped

**Overriding `LocalContext` breaks Hilt, and it crashes on launch.** The in-app language switch
originally localised the UI by providing a configuration-scoped context to the whole Compose tree:

```kotlin
CompositionLocalProvider(LocalContext provides localizedContext, …)   // ← wrong
```

`createConfigurationContext()` returns a plain `ContextImpl`, not the hosting Activity. Every
`hiltViewModel()` reads `LocalContext` to build its `HiltViewModelFactory`, which requires an
Activity context and throws otherwise:

```
java.lang.IllegalStateException: Expected an activity context for creating a HiltViewModelFactory
    but instead found: android.app.ContextImpl
    at androidx.hilt.lifecycle.viewmodel.compose.HiltViewModelKt
    at com.mylibrary.feature.library.LibraryScreenKt.LibraryRoute
```

The result was an app that compiled cleanly, passed 241 tests, and **died on every launch** the
moment the library screen created its first ViewModel.

The fix is to localise through the locals that actually carry localisation. `stringResource`
resolves against `LocalResources` — verified by disassembling `StringResources_androidKt`, which
references that local and not `LocalContext` — so providing `LocalResources`, `LocalConfiguration`
and `LocalLayoutDirection` is both sufficient and correct, and leaves the Activity context in place
for everything that needs it.

The lesson generalises: **a `CompositionLocalProvider` is a global override, and `LocalContext` is
not a styling detail — it is the bridge to the Android framework.** Replace it and you break every
library that reaches through it.

### The design flaw it exposed

The same stack trace revealed a second problem worth fixing on its own merits: constructing
`DocumentRepositoryImpl` eagerly built **all four decoders**, and `PdfEngine`'s constructor loads
pdfium's native library. Because the repository is built as soon as the library screen creates its
first ViewModel, several megabytes of shared object were being loaded **on the main thread during
the first frame** — even for a user whose library contains only text files — and any failure there
took down the entire app rather than one format.

Two changes: the engine set is now injected as `Dagger`'s `Lazy`, so it is materialised when a book
is actually decoded rather than at startup; and `PdfEngine` loads its native library lazily and
tolerates failure, reporting `AppError.DecoderUnavailable` for PDFs while leaving every other
format usable.

### The rest

**AGP 9 compiles Kotlin itself.** Applying `org.jetbrains.kotlin.android` is now an *error*, not a
deprecation. Kotlin's `jvmTarget` follows `android.compileOptions.targetCompatibility`, so the
convention plugins set the Java level and leave Kotlin alone. KGP and KSP versions are aligned by
declaring them in the root `plugins {}` block, which puts them on the build classpath where AGP's
bundled versions resolve against them.

**Room's Kotlin code generation is locale-sensitive.** On a machine whose locale is Arabic, Room
emits the schema version as an Arabic-Indic digit — `RoomOpenDelegate(١, …)` — where Kotlin requires
`1`, and the module fails to compile with a syntax error in generated code nobody wrote. KSP runs the
processor in a forked worker JVM that takes its locale from the environment, so `-Duser.language` on
the Gradle daemon does not reach it. The fix is `room.generateKotlin=false`: the Java writer appends
numbers with `StringBuilder`, which is locale-independent. The build *locale* is also pinned to US
English in `gradle.properties` for the same class of hazard.

**juniversalchardet cannot detect Arabic in Windows-1256 — it reports MacCyrillic.** It does not
merely miss the encoding; it confidently returns the wrong one, and a whole Arabic book decodes to
mojibake. `:format:format-text` therefore runs a byte-distribution fingerprint for Windows-1256
*before* consulting the detector, but only overrules a single-byte-character-set verdict — a
multi-byte detection is never second-guessed.

**An EPUB's `encryption.xml` is not evidence of DRM.** IDPF and Adobe *font obfuscation* ships in a
large share of DRM-free commercial books. Treating the mere presence of that file as DRM would
refuse to open books the user legitimately owns, so `:format:format-epub` only reports
`AppError.Protected` for a non-font encryption algorithm — and fails closed on anything it cannot
parse.

## 8. Known limitations

Stated rather than hidden:

- **Release signing is opt-in, and the keystore is not in this repository.** `:app` reads
  `keystore.properties` from the repository root if it exists and signs the release build with it;
  if it does not exist, `assembleRelease` still succeeds and produces an unsigned APK. Both that
  file and the keystore are gitignored, which is why a fresh clone builds with no secret material —
  but it also means **the key used for the published APK exists only on the machine that built it**.
  Anyone shipping an update must keep that keystore: Android refuses to install an update signed
  with a different key.
- **Search inside books is capped at the 20 most recently added books** and is off by default, since
  it opens every book it scans. The cap is reported to the user rather than silently narrowing the
  result.
- **Recent search queries are session-scoped.** Persisting them needs a DataStore key; the ViewModel
  does not currently inject one.
- **Highlights are modelled and stored** (`Bookmark` carries `colorArgb`) but the reader exposes
  bookmarking only, not text selection.
- **The reader's gestures are the part of this app least covered by tests.** The geometry, the
  action rules and the page-breaking arithmetic behind them are pure functions with unit tests, but
  the gestures themselves — tap zones, pinch-zoom, the clamped pan — have only been exercised by
  reading the code and building the APK, because the environment this was developed in has no device
  or emulator to run them on. The same goes for pagination end to end: what it *decides* is tested,
  what it *looks like* is not. Treat the first run on real hardware as the real test.
- **Paged text stops at the end of a chapter.** Swiping turns pages within the chapter and no
  further, because the view paginates one chapter at a time — a pager spanning several would have to
  re-index its pages every time a window shifted, and a jump nobody can test is worse than a
  boundary. The edge tap zones do move on to the next chapter, and the last page says so and offers
  a button.
- **An image or a table gets a page to itself in paged mode**, because neither height is knowable
  before decoding or laying it out. A small illustration therefore leaves some white space around
  it. Measuring the bounds without decoding is possible — `BitmapFactory` will report them from a
  header — and is the way to fix this properly; it is not done here.
- **`pageSnapping` is still a stored-but-unhonoured setting.** It is offered in Settings and
  persisted, and the reader does not read it: Compose's `HorizontalPager` always snaps, so
  "continuous scrolling instead of snapping to one page" would mean a second rendering path for
  *fixed-page* documents. It is called out here rather than quietly left to look implemented, which
  is exactly the state `pageFitMode` was in until it was wired up.
- **R8/minification is disabled** for the release build, which is why the APK is ~33 MB. Turning it
  on needs keep rules for pdfium's JNI entry points and the Room/Hilt generated code; the proguard
  files are already wired up for it.
- **`getRelativeTimeSpanString` follows the system locale**, not the in-app language, so relative
  dates can disagree with the rest of the UI when the two differ. The fix is to pass the
  locale-scoped context, which is a one-line change once the API level allows it.
- **CBR has no automated success-path test** — see [§6](#6-testing).
- **The app has not been run on a device** in the environment it was built in; see §6.

---

## Licence and attribution

Book decoding is performed by third-party libraries, each under its own licence: pdfium (BSD-3),
junrar (UnRAR licence), jsoup (MIT) and juniversalchardet (MPL 1.1/GPL/LGPL tri-licence). EPUB
parsing is implemented in this project directly on `java.util.zip` and jsoup — there is no Readium
dependency.
