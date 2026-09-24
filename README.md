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
| **Library** | Import through the Storage Access Framework — single files **or a whole device folder**, which keeps a series together as one shelf and can be re-scanned for new volumes later; grid/list layouts, five sort orders, favourite, format and folder filters, automatic cover extraction, moving a book between folders; a **continue-reading button above the shelf that follows the folder chip** — with a folder open it offers *that series'* book, not the most recent book in the library, and it says which book it would open |
| **Opening from elsewhere** | The app registers as a viewer for every format it reads, so opening a file from a file manager — or sharing one into it — adds the book to the library and opens it in the reader. A search hit opens the reader *at the hit*, not at the last position |
| **Launcher shortcut** | A long-press on the app icon offers **Search**, which opens the search screen directly |
| **Reader** | One toolbar across all five formats, adapting to what the open file can do; **every format has both a pages layout and a continuous-scroll one**, chosen by one setting that both families obey; tap zones that turn the page (mirrored for Arabic, and independently reversible) or scroll a screenful, with a haptic tick on every turn and a switch to turn them off; **which side the first page is on** is a setting of its own; pinch-zoom, double-tap and a clamped pan; **double-tap a speech bubble or panel in a comic to zoom into it** — the balloon is found by reading the page's pixels, and a break in its outline is sealed rather than allowed to hand back the panel; page turns animated by a page-curl, a slide or a fade — **the curl lifts a corner on a diagonal fold and rolls the sheet over in every format, reflowed text included**; three page-fit modes; per-document search, outlines, bookmarks, and read-aloud and quote sharing for any file with a text layer; **font, size, line spacing, margins, paragraph spacing and a first-line indent, applied live to a reflowed book** — three bundled Arabic typefaces and the platform's own, plus a page paper that can be the app's own background, a warm sepia or true black — and one button that puts a reader's settings back **without touching the app's theme or its language**; and, in a folder of several, a reader that **carries on into the next volume by itself** — the previous and next books of the series are drawn above and below the open one in both layouts, a blank page at each seam names what was finished and what comes next, and the order is the folder's own, naturally sorted so *Vol 2* precedes *Vol 10* |
| **Appearance** | Six Material 3 colour schemes — five generated from seeds by `tools/material_palette.py`, one the app's own hand-authored teal — plus the wallpaper palette on Android 12+; a first launch asks which, and the same screen reopens from Settings with the whole interface repainting live; light/dark/system |
| **Storage** | No permissions at all — not storage, not network. Only scoped `content://` access to files and folders the user picked |

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
| 2 | Dynamic colour + fallback | `MyLibraryTheme` uses the wallpaper palette on API 31+ when asked, and one of six Material 3 schemes otherwise — five of them generated from a seed by `tools/material_palette.py`, which checks itself against Google's published baseline palette before writing |
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
| 8 | Folder access through SAF | A folder is added with `OpenDocumentTree`, its grant persisted and released when the folder is removed — still no storage permission |

## 3. Building and running

### Download

A signed, installable build is attached to the latest release:

**→ [MyLibrary-v1.8.7.apk](https://github.com/ammar-alfifi/MyLibrary/releases/download/v1.8.7/MyLibrary-v1.8.7.apk)** (~21 MB)

Android 8.0 (API 26) and above. Signed with APK Signature Scheme v2 + v3. The app requests **no
permissions at all** — not storage, not network. Books are added through the system file picker,
which grants access to the files you choose and nothing else.

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
│   ├── feature-settings/     appearance and language — the interface's own settings alone
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

**The page curl lifts a corner, and the fold runs diagonally.** Every other turn effect is a
transform of the page — a scale, a fade, a rotation about its binding edge — and an earlier curl was
one too: a 70° `rotationY` with a gradient rectangle standing in for the shadow. It reads as a card
turning, because that is what it is. A plane cannot bend, and a page lifted at its corner bends along
a line running from one edge to another *across* that corner, not along a line parallel to the spine.
What sells it is that the sheet past the fold wraps until it is standing edge-on and then turns its
back to the reader; a page rolled up like a poster has neither the diagonal nor the back, and reads as
neither. A comic or a PDF page is a bitmap, so it can be cut into bands parallel to the fold, and each
band is one `drawImage` under an affine transform clipped to the strip of screen it occupies. The
whole wrap is one-dimensional — a point's journey depends on nothing but its distance from the fold —
which is why the bands are exact rather than an approximation of some mesh: the spacing of a comic's
own hatching across the bend tracks `R·sin(d/R)` to within a pixel of where the arithmetic says it
should be. Bands past a quarter turn come out mirrored on their own, because the cosine that
foreshortens them has gone negative by then, and that mirror image *is* the sheet's back. The radius
is a share of the page's shorter side rather than a length in pixels, so the sheet keeps its shape on
a tablet.

Two dozen `drawImage` calls and one gradient, with nothing per-pixel on the main thread — and a page
nobody is turning still takes the single draw call it always took. What the effect cannot do is bend
*live text*: EPUB and TXT draw their pages as composables rather than bitmaps, so bending one would
mean rasterising the text on every frame of the drag. Text pages keep the rotation, and the reader
decides between the two in one place.

**A setting appears where it is honoured, not where its format happens to be.** The in-reader
settings sheet used to branch on whether the *file* was made of page images, and that is a different
question from what the reader is doing with it. An EPUB laid out as pages turns its pages and reads
the page-turn setting to animate them — and was never offered the control, because it is not a PDF.
The two questions are now asked separately: `isPageImages` is about the file (are there pixels to
render and text to search), and `hasPages` is about the reader (`layout == PAGED`). A control's
visibility follows from those, in one pure function that four tests pin rather than four branches in
a composable — which is how the second bug in this area was caught: page fit and speech-bubble zoom
look like a pair and are not, because a page in a scrolling column has no frame to be fitted into and
still has bubbles too small to read.

**Pages or scrolling is one setting, not two.** It used to be two — a reflow mode for text and a
"snap to pages" switch for page images — which gave one decision two homes, and the one belonging to
page images was wired to nothing at all: declared, persisted, surfaced in Settings, covered by tests,
and read by no reader. It also meant a PDF could not be scrolled at all. There is now one `layout`
setting, `PAGED` by default, and both families obey it: four presentations, one for each pairing of
what the file is made of and how the reader asked to see it. The image scroll is a `LazyColumn` over
the same render pipeline the pager uses, so it is a second way of *arranging* pages rather than a
second renderer, and it reports position through the same intent — which is why progress, bookmarks
and restore work there without knowing it exists.

**Progress for a paged document is exact; for a reflowable one it is per-chapter.** Page *n* of *N*
is honest. A chapter index is not — chapters range from one page to a hundred — so reflowable
progress counts the chapters *behind* the reader, and the reader's own progress bar reads the same
`ReadingProgressUseCase` arithmetic the library card and bookmarks list do, because a reader showing
40% for a book the shelf says is 12% through is worse than either number alone. Refining it further
with a character offset within the chapter is modelled — `fromChapter` takes a fraction — but nothing
tracks a scroll fraction to pass it yet, so both screens are chapter-accurate and agree.

**Arabic typefaces are bundled, and the reader can wear one.** The app shipped with the platform's
own families — Noto Naskh Arabic and Noto Sans Arabic, resolved by the system stack — on the argument
that the platform already shapes Arabic correctly and a bundled face would cost megabytes for nothing.
That argument holds for *correctness* and fails for *choice*: a reader has no way to change the voice
of a book, and the same page looks different on two devices. Three faces under the SIL Open Font
License are therefore in `:core:core-ui`'s `res/font` — Amiri (a Naskh revival), IBM Plex Sans Arabic
and Reem Kufi — offered as reading fonts, drawn in the picker in their own face so the choice is made
by looking rather than by reading a name. ~790 KB, and the licence texts ship in `licenses/`.

They are reading fonts only. An interface could wear one too, and the app offered exactly that from
1.3.0 to 1.6.0 — a fourth picker on the colour screen, feeding the app's whole type scale. It was
removed, and the reason is worth keeping: a body face chosen for a column of prose is the wrong
instrument for a row in a list. Amiri wants a leading that leaves a settings list looking untidy, and
Reem Kufi's geometric Kufic turns a button label into an ornament, so two of the three were never a
sensible answer and the picker was really offering one decision disguised as three. The scale the app
draws with is now `myLibraryTypography`'s, unconditionally, and the type is one less thing that can
be wrong in a way nobody can see from a screenshot.

**The page's paper is not the app's theme.** The reader offers the app's own background, a warm
sepia and true black as three papers, and deliberately as a setting of its own rather than as more
entries in the theme picker. They answer different questions: the theme is the *app's* chrome — the
toolbar, the panels, every other screen — while the paper is the surface a book is drawn on, and a
reader who wants a dark app with a warm page (or the reverse) is asking for two answers, not one
contradiction. The paper is applied as a scoped colour-scheme override around the reader's content
and nothing else, so the toolbar above the page stays the theme's; and it is a reading setting, so
the reader's own reset puts it back while the settings screen's reset leaves it alone.

**The settings screen is the interface's; the reader's panel is the book's.** One `ReaderSettings`
object observed by every screen is what makes a setting change a single `copy()` and a single write,
and it is worth keeping — but one object is not one *screen*. Settings used to offer the reading
controls a second time, in the one place none of them can be seen working: a font size, a leading, a
layout, chosen three taps away from the page they apply to with a library list in between. Every one
of them is now offered only in the reader's panel, which opens over the page and shows the result as
the finger moves, and the settings screen holds what a reader cannot see from inside a book — the
theme, the colour and the language. The line is held by the intent interfaces rather than by
discipline: there is one sealed interface per surface, so changing the leading is a `ReaderIntent`
and no composable on the settings screen can name it.

The two resets follow the same line, and are disjoint. The screen's puts back the theme, the colour
and the language; the reader's puts back everything that decides how a book is read. Between them
every field has exactly one home, which is what stops either from undoing the other's work: someone
who has spent an evening tuning their text cannot lose it to a button on a screen that does not show
a single one of those controls, and someone who has made a book unreadable — a font size they cannot
see past, margins that leave no column — can put *that* back without the app's language changing
under them. The settings screen's reset also keeps the answer to the first-run colour setup, because
being sent through a welcome they have already had reads as the reset having broken something.

**The folder is the unit of a series, and a folder is not a copy.** Adding a folder through the
Storage Access Framework records the *tree* URI, takes a persistable read grant, walks the tree and
files what it finds under it. Nothing is copied and nothing is moved: the association is an id on each
book, which is why a re-scan can pick up new volumes, why removing a folder keeps its books, and why
a folder whose permission has been revoked is shown as unavailable rather than silently disappearing
with the reader's series inside it.

**What "continue" means is decided by the folder chip, not by the shelf.** A device folder is a
series, so the book to carry on with is the series' own — the most recently read book in the whole
library is a different question from the one the chip asked, and answering it anyway is how a reader
who has opened a folder ends up in a book from somewhere else. The button is therefore scoped by the
same selection that scopes the list, and it names the folder it is reading from. It is also why a book
that has never been *opened* is never offered: "continue" on a book nobody has started is a lie, and
the shelf is where an unread book is found — which leaves the button absent rather than wrong when
everything is finished or nothing has been started.

**A folder is a series, so the reader reads *through* it rather than a file at a time.** Reaching the
last page of volume two and having to go back to the shelf for volume three is where the thread of a
series is usually dropped, so the reader no longer stops at the end of a file: it draws the volume
*before* the open one above it and the volume *after* it below, in all four presentations and in both
directions, and the seam between two books is a blank page naming what was finished and what comes
next. That is Mihon's long strip, whose chapter-transition card appears only when the next chapter
could *not* be loaded — and what the reader used to do here (end the book with a card and a button) is
the weaker half of it, because a card has to be dismissed before the reader can go on and can only
ever point forwards. Six decisions are worth naming.

The order is `naturalSortKey` of the title, the same natural order the folder was scanned in, so
`Vol 2` precedes `Vol 10` — not the library's display sort, because "recently read" is a property of
the reader's history and not of the series, and a next volume that moved around as books were opened
would be worse than none.

The next book is opened *before* the reader reaches it and closed again once they are well past the
seam. A document opened on the frame the reader arrives at it is a stall in the middle of the one
gesture this exists to make continuous, and a reader working through ten volumes must not leave ten
decoders resident — so a neighbour is opened within three units of the seam and kept within eight. The
two distances are deliberately different: with one, a reader sitting on the seam and turning one page
back and forth would open and close the same file on every turn.

The handover is a change of *document*, not a second reader. The state's book moves, the position in
the book being left is written before the state stops describing it, and the pages already on screen
stay where they are — because every entry of the reading order is keyed by its book as well as its
page. Within one file that is a nicety; across two it is the difference between carrying on and
landing on page 3 of the volume just finished, which has a page 3 of its own.

The seam is a *page* rather than a decoration over the last one: a turn reaches it from either
direction, and while the reader is standing on it nothing is reported as a position, so the progress
bar goes on naming the book they have just finished. A seam is also placed for a neighbour that could
not be opened, which is what keeps the end of a volume from being a wall — and there the seam offers
to open that file as a book of its own, which is what the reader did before any of this existed. Three
things cannot be continued into: a volume waiting for a password, a file that will not open, and a
volume drawn by the other family of reader — a column of chapters cannot become a column of pages
halfway down, so a folder that mixes an EPUB with a CBZ stops at the seam, as it always did.

And there is no setting for it. Carrying on into the next volume of the series is what reading a
series is, so the reader does it the way it keeps the screen awake while a book is open: as part of
being a reader, rather than as a preference to find.

**A shelf shows every format's own cover, and says so honestly when there is none.** An EPUB's cover
is the artwork its package document declares — read from the manifest property EPUB 3 uses, from the
`<meta name="cover">` indirection EPUB 2 uses, or from the one image a book only names as a cover —
and is used verbatim, because rendering page one of an EPUB is not even meaningful. A PDF, CBZ or CBR
has no declared cover and *is* its pages, so page one rendered at cover size is exactly what its cover
is in practice. A plain text file has neither, and gets the deterministic coloured stand-in the shelf
uses for anything coverless rather than a blank frame — as does any book whose cover file has gone,
since covers live in `cacheDir` and Android is entitled to evict it.

A cover also carries a small format chip — `PDF`, `EPUB`, `CBZ` — because *which of these is the PDF*
is a question a mixed shelf asks constantly. It is drawn on the shelf's own covers and not on the
continue-reading row's, where the cover is a 36dp thumbnail: at that size the chip is wider than the
artwork under it and hides the one thing the reader recognises the book by, and the row already names
the book in words a line above it.

**Room generates Java here, deliberately.** See [§7](#7-engineering-findings-worth-knowing).

## 6. Testing

**629 unit tests, 0 failures, across 11 modules.** `./gradlew test` runs them all.

| Module | Tests | Covers |
|---|---:|---|
| `format-epub` | 89 | container/OPF parsing, nav + NCX, sanitiser, path resolution, traversal refusal, embedded fonts, links, **the declared cover** (the EPUB 3 manifest property, the EPUB 2 metadata indirection, a cover named only as one, and the two ways a book has none) |
| `feature-reader` | 255 | **the margins and paragraph spacing the two readers share** (that the settings reach both, and that a spacing of zero means none), HTML → block parsing, chapter text offsets and link anchors, which toolbar actions a document supports, tap-zone mirroring and its reversal, page-fit geometry, **page-breaking arithmetic** (line boundaries, spacing, atomic blocks, degenerate pages), progress agreement with the library, **the speech-bubble detector** (enclosed regions, specks, slivers, resolution independence, and — against whole drawn comic pages rather than hand-written pixel arrays — that a broken outline does not hand back the panel, and that a tap on the lettering finds the balloon), **the tap-to-page geometry**, the page-turn effects, **the zoom handover from the column to the opened page**, **the cache's byte accounting**, **the size a zoom is measured against**, **the curl** (that the fold runs diagonally rather than along an edge, that it sweeps the whole page over a turn, that every band is foreshortened and placed on the chord its angle subtends, that it never wraps past half a turn, and that it rolls far enough for the sheet to show its back), and **which settings a reader is offered** (the four document-and-layout combinations, and that page fit and bubble zoom do not gate on the same thing), **the reading order across a folder's volumes** (`readingOrder` placing the previous volume above, the next below and a seam at each join, even for a neighbour that could not be opened; that every entry's key names its book, so two volumes' page 3 do not wear each other's state; that a position past a book's end rounds to its own last page rather than into the next volume; and the lookup of a title by book id for a seam that names a volume whose document is not open) |
| `format-text` | 52 | Windows-1256/UTF-16/BOM decoding, chapter splitting, escaping, search offsets |
| `core-domain` | 66 | format resolution, progress arithmetic, library join, import rules, **folder import and re-scan** (adoption, missing files, revoked grants, deleting with or without contents), **what "continue reading" offers** (the open folder rather than the whole library, a book nobody has opened, a folder with nothing unfinished left), **the sequence around a volume** (natural order in both directions, the two ends of a folder, a book with no folder, a folder whose row is gone), and **the range of every slider** (both ends clamped, the middle untouched, and a floor of zero that is genuinely reachable) |
| `core-common` | 30 | natural sort key, file-name parsing, byte formatting, result combinators |
| `format-archive` | 29 | natural page ordering, junk-entry filtering, container sniffing, sample-size maths |
| `feature-search` | 20 | snippet offsets, result grouping, query history |
| `core-data` | 48 | **real SQLite**: every sort order, `LIKE … ESCAPE`, cascade deletes, upserts, folders, and **the version 1 → 3 migration against a real version 1 database** (including that the new search index cascades away with its book). Also **bookmarks' notes and highlight colours** surviving a write, a read and a clear. Also **the settings store's history**: that an upgrade is not sent through the first-run screen, that a reader who had turned dynamic colour *off* is not repainted with their wallpaper, that an unknown colour name — or page paper — degrades rather than throws, and that **the persisted search history** comes back in order and is not cut in half by a query containing the characters a naive delimiter would use. And **the full-text index**: literal `%`/`_` matching, per-book limits, and that a snippet's highlight offsets point at the match inside the collapsed whitespace it is shown in |
| `format-pdf` | 15 | aspect fitting, outline nesting, malformed bookmark trees |
| `app` | 8 | **cold start**: real Hilt graph + `MainActivity` lifecycle, and the language override |
| `feature-settings` | 12 | intent → settings mapping on both paths, and **the line between the two resets** — that the screen's puts back the theme, the colour and the language and touches nothing else, compared as a whole object so a field added later cannot slip through, and that the reader's is the exact complement |

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

**And since 1.3.1 the app runs on an emulator.** `tools/start-emulator.sh` boots an API 35 AVD with
the one GPU backend that works on this host (`-gpu angle`; every other backend segfaults the
emulator process, and the headless build dies regardless of backend). That made the parts this
document had always listed as unverified — the reader's gestures, the bubble detector, the folder
import, the upgrade path — testable, and the first thing it found was that swiping did not turn
pages. See [§7](#7-engineering-findings-worth-knowing).

**Three behaviours are shell-verified on it rather than only unit-tested**, because all three are
things a reader does with a finger and a folder of volumes:

| What was checked | How it was checked |
|---|---|
| The continue-reading button follows the folder chip | A folder of three comics added through the picker; with `Series` selected the button reads *متابعة القراءة من «Series»* and offers that folder's book, and switching to a folder whose books have never been opened removes it entirely |
| A folder of volumes imports and its chip appears | `mylib-series` (`Vol 01`, `Vol 02`, `Vol 10`) added through the picker on the API 35 emulator; the folder row and its three books landed in the library with the folder id, which is what the reader's sequence is built from |
| Reading on across a volume's seam, both ways | `mylib-series` opened in the paged reader: the last page of `Vol 02` tapped into the seam, on into `Vol 10` page 1 and back out of it — the toolbar naming the book, the progress bar restarting, and the numeral drawn on the page (`101`, `24`) agreeing with both, at every step |

`adb` drives it — `input tap`, `uiautomator dump` — which is why the checks above are statements about
what the screen said rather than about what the code intended.

**The crossing was walked on the emulator, and it found a bug.** The end-of-book *panel* those checks
described is gone — the seam replaced it, in every presentation and in both directions — and what is
pinned by tests is the arithmetic a screen cannot report as wrong: the order built over the folder's
sequence (`ReadingSequenceTest`), the keys that keep a handover from wearing one book's page with
another's (`PageWindowTest`, `ReadingSequenceTest`), and the lookup of a position past a book's end.
The checks then run against `test-books/series` — each page carries a numeral only its own volume
produces (`11, 12, 13` then `21, …` then `101, 102`), which is what a screenshot is read against —
were: the last page of `Vol 02` turned into the seam naming it and `Vol 10`; the next turn landing on
`Vol 10` page 1 (numeral `101`), with the toolbar naming `Vol 10` and the progress bar restarting at
`١ من ٢`; the same crossing backwards, landing on `Vol 02`'s *last* page (numeral `24`) with the bar
restarting at `٤ من ٤`; and a slider jump inside the open book. Every step was read twice — the
numbers the toolbar and the bar report, and the numeral drawn on the page — so that a bar agreeing
with itself could not pass for a bar agreeing with the book.

The first walk found the bug §7 describes: after one crossing the progress bar stopped moving while
the pages went on turning, which is the freeze reported when moving between two files, in either
direction. The reflowed presentations' seam (`test-books/series-text`: a column of chapters, and a
pager of pages within them) has not been walked yet, nor has a folder that mixes an EPUB with a CBZ.

## 7. Engineering findings worth knowing

The things below were discovered while building this. None of them is obvious, and each would cost
the next person real time. The first is the one that mattered most.

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

### The progress bar that froze after a handoff

**A collector that outlives its composition must not read a plain `val`.** Crossing a seam — reading
on from one volume of a folder into the next — is not a change of screen: the reader stays composed,
the pager keeps turning, and only the state underneath it says a different book is open. The pager's
report flow, which turns the entry under the reader into an intent, is started once and never
restarted (deliberately: restarting it would re-announce the page already on screen, and after a
handoff that re-announcement *is* the handoff). Its order was read through `rememberUpdatedState`,
with a comment saying exactly why — and the open book's id beside it was read as a plain value, so
the flow kept the id of the book the reader had *arrived* in.

The consequence was one comparison inverting for the rest of the session:

```kotlin
is ReadingEntry.Page ->
    if (entry.bookId == openBookId) {          // ← openBookId is the book the collector started on
        ReaderIntent.PageChanged(entry.pageIndex)
    } else {
        ReaderIntent.EnteredBook(entry.bookId, ReadingLocator.Paged(entry.pageIndex))
    }
```

After the first crossing, every page of the open book compared unequal to a book the reader had left,
so every page turn was reported as a crossing into the book already open — and `enterBook` answers
that with nothing, because it is already the open one. The pages went on turning and the progress bar
stopped moving, in both directions, until the reader left the book. The fix is the one the other three
presentations already had: `val currentBookId by rememberUpdatedState(openBookId)`.

What makes this worth writing down is that nothing else could have caught it. The order arithmetic is
pure and tested (`ReadingSequenceTest`, `PageWindowTest`), the intent mapping is a `when` a compiler
checks for exhaustiveness, and the whole bug is one captured value in a lambda that is correct on the
frame it is created. It was found by walking the crossing on the emulator and comparing two things
that should agree: the position the bar reported and the numeral drawn on the page. They disagreed —
the bar said `Vol 01`, page 1 of 3, while the page on screen was `Vol 02`'s — and a temporary log in
the flow showed the intent being mapped to `EnteredBook` for the book that was already open. The same
lesson as the gesture detector above, one layer down: **this class of bug lives in composition
lifetime, not in logic**, and only a running app answers it.

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

**A gesture detector on pager content costs you the pager's own drag — and on a device it cost the
page turn.** The reader's pages were turned by tapping the edges and *not* by swiping, for two
releases, because `detectTransformGestures` on a page consumes every drag it sees and a
`HorizontalPager` can only turn a page from a drag that reaches it. Every unit test passed: the
geometry is pure and tested, the tap-zone arithmetic is pure and tested, and whether a Compose
gesture detector eats a scroll is neither. The emulator settled it in one swipe. The page's detector
now claims a drag only when it is a pinch or when the page is already zoomed, and leaves a
one-finger drag at 1× to the pager. **The lesson is about what tests can cover**: this class of bug
is invisible to pure-function tests and to a compiler, and only a running app answers it.

**The reader owns the zoom, not the page.** Related, and found the same afternoon: with the zoom
state and the gesture handlers living inside each page's composable, a double-tap in the middle of
the screen was delivered to a *neighbouring* page's node — a real, fully-composed page, but one that
was not on screen — so the zoom applied to a page nobody could see. Diagnosing it took logging the
composition identity of the node that received the touch: it reported a different page from the one
being drawn. The fix was structural rather than a patch: `PagedReaderContent` keeps one zoom for the
page on screen, hangs one set of handlers on the viewport, and each page is a renderer that reports
what it drew. Turning the page resets the zoom, which is also what a reader expects.

**A foreign key on `books.folderId` would have deleted the library's reading positions.** The
obvious schema for "a book belongs to a folder" is a foreign key with `ON DELETE SET NULL`. SQLite
cannot add a constraint with `ALTER TABLE`, so a foreign key in a migration means the twelve-step
rebuild — create, copy, drop, rename — and `books` is the *parent* of `reading_positions` and
`bookmarks`, both declared `ON DELETE CASCADE`. Dropping it performs an implicit
`DELETE FROM books`, and with foreign keys enforced (which Room enables, and which the existing
cascade test proves) every reading position and every bookmark in the user's library would go with
it, on the upgrade, for every user. `PRAGMA foreign_keys = OFF` does not help: it is a no-op inside a
transaction, and Room runs migrations inside one. So `books.folderId` carries no constraint, and
`FolderRepositoryImpl.deleteFolder` clears the association and deletes the row in a single
transaction instead — the same place the "match on URI and update rather than replace" rule already
lived. The migration is tested against a real version 1 database, because no other test in the
project would notice a migration that is merely *wrong*: it would pass every build and then throw on
the first launch after an upgrade, on the only copy of the library that exists.

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
- **Search inside books builds a persisted index** the first time it is used: every book is opened
  *once* and its text stored, after which a query is a scan of text already on disk and the whole
  library is searched — there is no cap. It is still off by default because that first search has to
  open every book to index it. A book whose text changes (a re-import) keeps its old index until the
  next pass; there is no invalidation-on-change yet.
- **Bookmarks can carry a note and a highlight colour**, edited from the bookmarks panel; a highlight
  is a *coloured bookmark*, not a range of selected text. Free-text selection over reflowed text —
  selecting a run and highlighting that run — is not implemented: the model stores a position, not a
  range, and adding one is a schema change of its own.
- **The bubble detector is a heuristic, not image analysis.** It floods the light region under the
  double-tap and accepts it only if it is enclosed, small enough to be worth framing, and dense
  enough to be a shape. Every way that can fail returns "no region" and falls back to a plain zoom
  about the tapped point — a leak through a broken outline reaches the page border, which is checked,
  so a wrong-but-plausible rectangle is not reachable. What it cannot do is understand artwork: a
  bubble drawn *inside* a panel of a similar tone is one region, and a panel that bleeds off the page
  is refused rather than framed. Related: a bubble zoomed far enough is limited by `MAX_RENDER_SCALE`
  (3×), so the sharpest render is 3× the viewport however far the zoom goes.
- **The reader's gestures are the part of this app least covered by tests.** The geometry, the
  action rules and the page-breaking arithmetic behind them are pure functions with unit tests, but
  the gestures themselves — tap zones, pinch-zoom, the clamped pan — have no automated coverage:
  they have been exercised by hand on the emulator (see [§6](#6-testing)) and by reading the code.
  The same goes for pagination end to end: what it *decides* is tested, what it *looks like* is not.
  Treat the first run on real hardware as the real test.
- **A page turn crosses chapters and volumes, but only within one family of reader.** The pager holds
  the open book's window of chapters *and* the neighbouring volume's, so the last page of a chapter
  is followed by the first of the next and then by a seam page — no button anywhere, in either
  direction. What it cannot do is change what it is halfway down: a reflowed book is a column of
  chapters and a comic is a column of pages, so a folder that mixes the two stops at the seam with an
  offer to open the next file, which is what it did before any of this existed.
- **The reading order holds at most three books open at once.** The open volume plus the one on
  either side, and the neighbours only while the reader is near a seam — within three units to open
  one and eight to close it. That is the memory trade the feature is built on, bounded further by the
  byte-budgeted page cache, which drops a closed volume's pages with it (`PageCache.evict`).
- **Continuous reading has no setting.** A reader who wants to stop at the end of a volume has to
  leave the folder — which is a real limitation, stated as one: the seam cannot be turned off because
  the reader reads a series as a series.
- **Paged text re-paginates a neighbour's window before the reader reaches it.** Crossing a seam in
  the paginated layout means laying out the neighbouring volume's opening chapters so that the turn
  across lands on a page that already exists; that work happens near the seam, and it is the reason a
  seam in a reflowed book can be reached a moment before it is ready to be crossed.
- **An image or a table gets a page to itself in paged mode**, because neither height is knowable
  before decoding or laying it out. A small illustration therefore leaves some white space around
  it. Measuring the bounds without decoding is possible — `BitmapFactory` will report them from a
  header — and is the way to fix this properly; it is not done here.
- **A folder re-scan reports files it can no longer see, and never deletes them.** A book whose
  file has been moved, or whose storage is not mounted, is indistinguishable from one that was
  genuinely removed — so the count is reported and the book stays. The scan also stops at 2000 files
  and 8 levels deep, and says so when it does.
- **The next volume is the folder's, in natural title order.** `Vol 2` precedes `Vol 10`, which is
  what a series needs and what a file named `chapter-10` also gets — but a folder whose files carry
  no ordering in their names (a pile of unrelated books imported together) will read them in an order
  that is alphabetical-by-natural-sort rather than the order they were added. A folder of one book, an
  unfiled book and a folder whose row has gone simply have nothing on either side of them.
- **Folders are a grouping, not a copy.** Books are never moved or duplicated; a folder is a
  remembered SAF tree plus an id on each book. That is why removing a folder keeps its books, and why
  a folder whose permission has been revoked shows as "unavailable" until the user points at it again.
- **R8 is enabled for release** — minification *and* resource shrinking — which brings the APK from
  ~33 MB down to ~21 MB. The app-specific keep rules live in `app/proguard-rules.pro`; the
  libraries' own consumer rules cover pdfium's JNI surface, Room's generated code and Hilt. Shrinking
  is a packaging-time change, so the paths it can affect are the device-only ones (native PDF
  rendering, opening the Room database): they build clean, but still want a run on real hardware, as
  every device-only path in this project does.
- **Relative dates used to follow the system locale** rather than the in-app language. Fixed:
  `DisplayFormatters` resolves them through a locale-scoped context, so they agree with the rest
  of the UI.
- **A book opened from a file manager may fail to reopen later.** The VIEW/SEND road imports the
  URI like the picker does and tries to take a persistable read grant, but many providers hand
  VIEW intents only a transient one. The book opens this session; a later open can fail with a
  file-access error, and the honest fixes are copying the file in or re-adding it from the picker.
- **CBR has no automated success-path test** — see [§6](#6-testing).
- **There is no physical device in this environment** — everything that needs a screen has been
  checked on the emulator only; see [§6](#6-testing).

---

## Licence and attribution

Book decoding is performed by third-party libraries, each under its own licence: pdfium (BSD-3),
junrar (UnRAR licence), jsoup (MIT) and juniversalchardet (MPL 1.1/GPL/LGPL tri-licence). EPUB
parsing is implemented in this project directly on `java.util.zip` and jsoup — there is no Readium
dependency.

The three bundled typefaces — Amiri, IBM Plex Sans Arabic and Reem Kufi — are under the SIL Open Font
License 1.1, and their licence texts are in `licenses/`. The launcher icon is drawn in this repository
as vector paths — a flat white shelf of books, three upright and one leaning, on a flat field of the
app's own primary, with no watermark, no gradient and no raster asset at any density.
