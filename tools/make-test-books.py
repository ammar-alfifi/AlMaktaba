#!/usr/bin/env python3
"""
Generates the MyLibrary test corpus into `test-books/`.

These are not random sample files. Each one is built to exercise a specific behaviour that the
reader gained in v2, so that "it renders" can be checked against "it renders *this*":

  novel-ar.pdf            Arabic, several pages          PDF rendering, text layer, search, paging
  features.epub           everything below              the whole 2.0.1–2.0.3 surface at once
    ├─ a table with colspan, header row                  tables were flattened into a run-on paragraph
    ├─ a three-level nested list                         depth was structurally always zero
    ├─ a footnote (epub:type="noteref" → #fn1)           footnote semantics used to be stripped
    ├─ a cross-chapter link and an external link         links did nothing at all
    ├─ a figure with a <figcaption>                      the caption used to detach from its image
    ├─ an embedded Naskh font + body{font-family}        @font-face was never parsed
    ├─ a <br> inside a poem                              <br> collapsed to whitespace
    └─ named chapters in the nav                         chapter titles were never rendered
  arabic-utf8.txt         chapters split by form feed    UTF-8 detection + chapter splitting
  arabic-windows1256.txt  the same text, cp1256          the charset fingerprint (the detector says
                                                          MacCyrillic for Arabic cp1256)
  comic.cbz               page1.png … page12.png         natural page ordering: page10 must sort
                                                          AFTER page2, not before it

CBR is deliberately absent: creating a RAR archive needs a RAR encoder, and none is available here
(7z can only extract them). Test CBR with a real comic from the internet.

Usage:
    python3 tools/make-test-books.py            # writes test-books/
    python3 tools/make-test-books.py --push     # …and adb-pushes them to the running emulator
"""

from __future__ import annotations

import io
import os
import shutil
import struct
import subprocess
import sys
import zipfile
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "test-books"
FONT = Path("/usr/share/fonts/paktype-naskh-basic-fonts/PakTypeNaskhBasic.ttf")

ARABIC_TITLE = "مكتبة الاختبار"


# --------------------------------------------------------------------------------------- helpers

def png_solid(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    """A minimal PNG. Hand-rolled so the corpus does not depend on Pillow being installed."""
    def chunk(tag: bytes, payload: bytes) -> bytes:
        return (
            struct.pack(">I", len(payload))
            + tag
            + payload
            + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    rows = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(rows, 9))
        + chunk(b"IEND", b"")
    )


def png_labelled(width: int, height: int, rgb: tuple[int, int, int], label: int) -> bytes:
    """
    A solid page with a big block-pixel numeral drawn on it, so a page is identifiable on screen.

    Uses Pillow when it is available (it is here) and falls back to a plain solid otherwise — the
    corpus must still be generated on a machine without it.
    """
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        return png_solid(width, height, rgb)

    image = Image.new("RGB", (width, height), rgb)
    draw = ImageDraw.Draw(image)
    text = str(label)
    # A rectangle per digit, in a contrasting colour — crude, but it needs no font file and is
    # unmistakable at a glance when paging through the comic.
    digit_w, digit_h, gap = 90, 220, 24
    total = len(text) * digit_w + (len(text) - 1) * gap
    x = (width - total) // 2
    y = (height - digit_h) // 2
    segments = {
        "0": "abcedf", "1": "bc", "2": "abged", "3": "abgcd", "4": "fgbc",
        "5": "afgcd", "6": "afgecd", "7": "abc", "8": "abcdefg", "9": "abfgcd",
    }
    thickness = 22
    for ch in text:
        for seg in segments.get(ch, ""):
            box = {
                "a": (x, y, x + digit_w, y + thickness),
                "b": (x + digit_w - thickness, y, x + digit_w, y + digit_h // 2),
                "c": (x + digit_w - thickness, y + digit_h // 2, x + digit_w, y + digit_h),
                "d": (x, y + digit_h - thickness, x + digit_w, y + digit_h),
                "e": (x, y + digit_h // 2, x + thickness, y + digit_h),
                "f": (x, y, x + thickness, y + digit_h // 2),
                "g": (x, y + digit_h // 2 - thickness // 2, x + digit_w, y + digit_h // 2 + thickness // 2),
            }[seg]
            draw.rectangle(box, fill=(255, 255, 255))
        x += digit_w + gap

    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    print(f"  {path.relative_to(ROOT)}  ({len(data) / 1024:.1f} KB)")


# ------------------------------------------------------------------------------------------ PDF

ARABIC_PDF_HTML = """<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8"><title>%s</title>
<style>
  body { font-family: "Noto Naskh Arabic", serif; font-size: 16pt; line-height: 1.9; margin: 2cm; }
  h1 { font-size: 24pt; } h2 { font-size: 19pt; page-break-before: always; }
  p { text-align: justify; }
</style></head><body>
<h1>%s</h1>
<p>هذا ملف اختبار بصيغة PDF، بالعربية. الغرض منه التحقق من عرض الصفحات، وتكبيرها وتصغيرها،
والبحث في طبقة النص، والتنقل بين الصفحات.</p>
<h2>الفصل الأول: في الحكاية</h2>
<p>كان يا ما كان، في قديم الزمان، سلطانٌ له ثلاثة أبناء. وكان يحبّهم حبّاً شديداً، غير أنه
لم يكن يستطيع أن يختار أيّهم يخلفه على العرش. فجمعهم يوماً وقال لهم: «اذهبوا في الأرض،
ومن يأتني بأعجب ما يرى، فهو أولى الناس بملكي».</p>
<p>فسار الأبناء الثلاثة في ثلاث طرق مختلفة، وكل واحد منهم يظنّ أن ما سيجده هو الأعجب.
ولم يكونوا يعلمون أن العجب لا يكون في الشيء، بل في العين التي تراه.</p>
<p>الرحلة الطويلة هذه تحتاج إلى نصٍّ يكفي لملء أكثر من صفحة واحدة، حتى يتسنّى اختبار تقليب
الصفحات وشريط التقدم. لذلك سنطيل الكلام قليلاً دون أن نضيف معنى.</p>
<p>وفيه أيضاً جملة قصيرة. وجملة أطول منها قليلاً. وجملة ثالثة طويلة تمتدّ على السطر وتتجاوزه
إلى السطر التالي، ليقف القارئ على كيف يتعامل التطبيق مع الفقرات المتتابعة.</p>
<h2>الفصل الثاني: في العبرة</h2>
<p>لمّا رجع الأبناء، جاء الأول بمرآة تُرى فيها البلدان البعيدة. وجاء الثاني بسجّادة تُطوى
فيُنقل صاحبها حيث يشاء. وجاء الثالث بطبيب يداوي من المرض.</p>
<p>فقال السلطان: «كلّ ما جئتم به عجيب، غير أن أعجب الأشياء أن يرى المرء ما بين يديه
ولا يحتاج إلى مرآة، وأن يمشي في الأرض ولا يحتاج إلى سجّادة، وأن يعرف قدر صحّته قبل أن
يمرض».</p>
<p>وتنتهي الحكاية هنا. وهذا نصٌّ إضافي للتأكد من أن الصفحة الأخيرة تُعرض عرضاً صحيحاً، وأن
إشارة التقدم تصل إلى نهايتها.</p>
</body></html>
"""


def make_pdf() -> None:
    if not shutil.which("libreoffice"):
        print("  novel-ar.pdf  SKIPPED (libreoffice not installed)")
        return

    staging = OUT / ".pdf-staging"
    staging.mkdir(parents=True, exist_ok=True)
    source = staging / "novel-ar.html"
    source.write_text(ARABIC_PDF_HTML % (ARABIC_TITLE, ARABIC_TITLE), encoding="utf-8")

    subprocess.run(
        ["libreoffice", "--headless", "--convert-to", "pdf", "--outdir", str(staging), str(source)],
        check=True, capture_output=True, timeout=300,
    )
    produced = staging / "novel-ar.pdf"
    if produced.exists():
        shutil.copy(produced, OUT / "novel-ar.pdf")
        print(f"  novel-ar.pdf  ({produced.stat().st_size / 1024:.1f} KB)  renders via LibreOffice")
    shutil.rmtree(staging, ignore_errors=True)


# ----------------------------------------------------------------------------------------- EPUB

CHAPTER_ONE = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="ar" dir="rtl">
<head><title>الفصل الأول</title><link rel="stylesheet" type="text/css" href="styles.css"/></head>
<body>
  <h1 id="c1">الفصل الأول: الجداول والقوائم</h1>

  <p>يبدأ الفصل بفقرة عادية، ليكون هناك ما يقارَن به ما بعده. ثم يأتي جدول حقيقي بعمودين
  وصفّ رأس، وهو ما كان يُعرض قبل اليوم كفقرة واحدة طويلة.</p>

  <table>
    <thead>
      <tr><th>الصيغة</th><th>الملاحظة</th></tr>
    </thead>
    <tbody>
      <tr><td>PDF</td><td>صفحات ثابتة وطبقة نصية</td></tr>
      <tr><td colspan="2">EPUB — وهذا الامتداد يمتدّ على العمودين</td></tr>
      <tr><td>CBZ</td><td>صور مرتّبة ترتيباً طبيعياً</td></tr>
    </tbody>
  </table>

  <p>ثم قائمة متداخلة من ثلاثة مستويات. العمق كان مستحيلاً أن يكون غير صفر في النسخة السابقة،
  وكانت القائمة الداخلية تُبتلع داخل نص العنصر الأب فيُعرض الابن مرتين.</p>

  <ul>
    <li>المستوى الأول</li>
    <li>عنصر له أبناء
      <ol>
        <li>المستوى الثاني</li>
        <li>وعنصر له أبناء أيضاً
          <ul>
            <li>المستوى الثالث — أ</li>
            <li>المستوى الثالث — ب</li>
          </ul>
        </li>
      </ol>
    </li>
    <li>عودة إلى المستوى الأول</li>
  </ul>

  <p>وهذه فقرة فيها حاشية<sup><a href="#fn1" id="ref1" epub:type="noteref">١</a></sup> يمكن
  الضغط عليها للانتقال إلى نصّها في آخر الفصل.</p>

  <p>وهذا رابط إلى <a href="chapter2.xhtml#c2">الفصل الثاني</a>، وهو انتقال بين فصلين.
  وهذا رابط خارجي إلى <a href="https://example.com">موقع خارجي</a>.</p>

  <figure>
    <img src="images/figure.png" alt="مستطيل اختباري"/>
    <figcaption>الشكل ١: صورة اختبارية — وهذه العبارة يجب أن تبقى ملتصقة بالصورة لا منفصلة عنها.</figcaption>
  </figure>

  <p>وهذا بيت من الشعر، وفيه فاصل سطر داخل الفقرة نفسها:<br/>
  قفا نبكِ من ذكرى حبيبٍ ومنزلِ<br/>
  بسقط اللوى بين الدخول فحوملِ</p>

  <hr/>

  <h2 id="fn1">الحواشي</h2>
  <p>١ — هذا نصّ الحاشية. إن وصلت إلى هنا بالضغط على الرقم في الأعلى، فيجب أن يظهر زر الرجوع
  في الشريط العلوي ليعيدك إلى موضعك.</p>
</body></html>
"""

CHAPTER_TWO = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" lang="ar" dir="rtl">
<head><title>الفصل الثاني</title><link rel="stylesheet" type="text/css" href="styles.css"/></head>
<body>
  <h1 id="c2">الفصل الثاني: الخطوط</h1>
  <p>هذا الفصل مكتوب بالخط نفسه، لكن الغرض منه التحقق من أن الخط المضمّن داخل الملف يُستخدم
  فعلاً. النصّ العربي هنا يجب أن يظهر بخط نسخ، لا بخط النظام الافتراضي.</p>
  <p>وللمقارنة: إن اخترتَ من إعدادات القراءة خطاً غير «الافتراضي»، فيجب أن يتغيّر الخط هنا
  فوراً — لأن اختيارك يتقدّم على خط الكتاب.</p>
  <p>بسم الله الرحمن الرحيم. وصلى الله على سيدنا محمد وعلى آله وصحبه أجمعين.</p>
  <blockquote>هذا اقتباس. يُعرض على خلفية مختلفة لا بخط مائل، لأن الميل ليس عُرفاً في الطباعة
  العربية.</blockquote>
  <p>ونصّ ختامي ليكون للفصل نهاية معقولة.</p>
</body></html>
"""

CHAPTER_THREE = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" lang="ar" dir="rtl">
<head><title>الفصل الثالث</title><link rel="stylesheet" type="text/css" href="styles.css"/></head>
<body>
  <h1>الفصل الثالث: البحث</h1>
  <p>ضع كلمةً مميّزة في هذا الفصل وابحث عنها، مثل «زرافة». يجب أن يعيدك البحث إلى هذا الموضع
  لا إلى بداية الفصل.</p>
  <p>وزرافة ثانية في فقرة لاحقة، ليكون للبحث أكثر من نتيجة واحدة في الفصل نفسه.</p>
  <p>نهاية الاختبار.</p>
</body></html>
"""

STYLES = """@charset "utf-8";

@font-face {
  font-family: "Naskh Test";
  font-weight: normal;
  font-style: normal;
  src: url("fonts/test-naskh.ttf") format("truetype");
}

body {
  font-family: "Naskh Test", serif;
  line-height: 1.9;
  margin: 1em;
}
"""

NAV = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="ar" dir="rtl">
<head><title>المحتويات</title></head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>جدول المحتويات</h1>
    <ol>
      <li><a href="chapter1.xhtml">الفصل الأول: الجداول والقوائم</a></li>
      <li><a href="chapter2.xhtml">الفصل الثاني: الخطوط</a>
        <ol>
          <li><a href="chapter2.xhtml#c2">مقدمة الفصل</a></li>
        </ol>
      </li>
      <li><a href="chapter3.xhtml">الفصل الثالث: البحث</a></li>
    </ol>
  </nav>
</body></html>
"""

CONTAINER = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

OPF = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="ar">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:mylibrary-test-features</dc:identifier>
    <dc:title>كتاب اختبار المزايا</dc:title>
    <dc:creator>مكتبتي</dc:creator>
    <dc:language>ar</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="css" href="styles.css" media-type="text/css"/>
    <item id="font" href="fonts/test-naskh.ttf" media-type="font/ttf"/>
    <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
    <item id="c3" href="chapter3.xhtml" media-type="application/xhtml+xml"/>
    <item id="fig" href="images/figure.png" media-type="image/png"/>
    <item id="cover" href="images/figure.png" media-type="image/png" properties="cover-image"/>
  </manifest>
  <spine>
    <itemref idref="c1"/>
    <itemref idref="c2"/>
    <itemref idref="c3"/>
  </spine>
</package>
"""


def make_epub() -> None:
    figure = png_solid(600, 400, (74, 99, 140))
    font_bytes = FONT.read_bytes() if FONT.exists() else b""
    if not font_bytes:
        print("  features.epub  WARNING: no Naskh TTF found; embedding skipped")

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as z:
        # `mimetype` must be first and uncompressed for the file to be a valid OCF container.
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", CONTAINER)
        z.writestr("OEBPS/content.opf", OPF)
        z.writestr("OEBPS/nav.xhtml", NAV)
        z.writestr("OEBPS/styles.css", STYLES)
        z.writestr("OEBPS/chapter1.xhtml", CHAPTER_ONE)
        z.writestr("OEBPS/chapter2.xhtml", CHAPTER_TWO)
        z.writestr("OEBPS/chapter3.xhtml", CHAPTER_THREE)
        z.writestr("OEBPS/images/figure.png", figure)
        if font_bytes:
            z.writestr("OEBPS/fonts/test-naskh.ttf", font_bytes)

    write(OUT / "features.epub", buffer.getvalue())


# ------------------------------------------------------------------------------------------ TXT

TXT_BODY = """{title}

الفصل الأول

كان يا ما كان، في قديم الزمان، سلطانٌ له ثلاثة أبناء.
وهذا سطر ثانٍ، وسطر ثالث.

الفصل الثاني

لمّا رجع الأبناء، جاء الأول بمرآة، وجاء الثاني بسجّادة، وجاء الثالث بطبيب.

الفصل الثالث

وتنتهي الحكاية هنا.
"""


def make_txt() -> None:
    text = TXT_BODY.format(title=ARABIC_TITLE)
    # Form feeds are what `:format:format-text` splits chapters on.
    chaptered = text.replace("\n\nالفصل", "\n\n\fالفصل")

    write(OUT / "arabic-utf8.txt", chaptered.encode("utf-8"))
    write(OUT / "arabic-windows1256.txt", chaptered.encode("cp1256"))


# ------------------------------------------------------------------------------------------ CBZ

def make_cbz() -> None:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as z:
        # Twelve pages: page10 must sort AFTER page2. A naive string sort puts it before, which is
        # exactly the bug the natural-order key exists to prevent.
        for page in range(1, 13):
            shade = 40 + (page * 15) % 200
            z.writestr(
                f"page{page}.png",
                png_labelled(700, 1000, (shade, 70, 140), page),
            )
        # Real comic archives carry cruft; the engine must ignore all of it.
        z.writestr("__MACOSX/._page1.png", b"junk")
        z.writestr(".DS_Store", b"junk")
        z.writestr("ComicInfo.xml", "<ComicInfo><Title>اختبار</Title></ComicInfo>")

    write(OUT / "comic.cbz", buffer.getvalue())


# ----------------------------------------------------------------------------------------- main

README = """# test-books — ملفات الاختبار

These files are generated by `tools/make-test-books.py`. Do not edit them by hand; edit the
generator and re-run it.

Each one exists to exercise something specific, so a failure points at a feature rather than at
"the reader":

| File | Tests |
|---|---|
| `novel-ar.pdf` | PDF rendering, zoom, the text layer, in-document search, page navigation |
| `features.epub` | tables, nested lists, footnote links, cross-chapter links, external links, embedded Naskh font, figure captions, `<br>`, chapter titles, images |
| `arabic-utf8.txt` | UTF-8 detection and form-feed chapter splitting |
| `arabic-windows1256.txt` | the Windows-1256 fingerprint — the detector reports MacCyrillic for this file, so it is the one that proves the fingerprint runs first |
| `comic.cbz` | natural page ordering (`page10` after `page2`) and junk-entry filtering (`__MACOSX/`, `.DS_Store`) |

**CBR has no fixture.** Creating a RAR archive requires a RAR encoder, and this machine has none
(7z can extract RAR but not create it). Test CBR with a real comic archive.

## Loading them into the app

```bash
adb push test-books/. /sdcard/Download/
```

Then in the app: **إضافة كتب** → open the drawer → **التنزيلات** → long-press to select several.
"""


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    print(f"Generating test books into {OUT.relative_to(ROOT)}/")
    make_pdf()
    make_epub()
    make_txt()
    make_cbz()
    write(OUT / "README.md", README.encode("utf-8"))

    if "--push" in sys.argv:
        print("\nPushing to the emulator…")
        subprocess.run(["adb", "push", f"{OUT}/.", "/sdcard/Download/"], check=True)
        print("Done — in the app: إضافة كتب → التنزيلات")


if __name__ == "__main__":
    main()
