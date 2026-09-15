package com.mylibrary.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.mylibrary.core.domain.model.ReaderFont

/**
 * MyLibrary's type scale.
 *
 * Two decisions drive this file.
 *
 * **1. Line height depends on the script.** Arabic glyphs carry their own vertical extent —
 * ascenders, descenders, and diacritics both above and below the baseline — while Material 3's
 * default line heights are tuned for Latin. At the same point size, Arabic set to a Latin line
 * height looks cramped and diacritics collide with the line above. So for RTL layouts each style's
 * own default line height is scaled up rather than replaced, which preserves the *relative*
 * proportions of the scale (a display style stays tighter than body text) and leaves Latin output
 * byte-for-byte identical to Material's defaults.
 *
 * **2. The reader's font size is applied by scaling at the call site, not by changing this scale.**
 * The reader lets the user pick between 0.7x and 3.0x; multiplying this one scale keeps body text in
 * the reader, in a bottom sheet and in a dialog all in proportion.
 *
 * The family is the system stack, which Android resolves to Noto Naskh Arabic / Noto Sans Arabic for
 * Arabic and Roboto for Latin. No font files are bundled: shipping one would add megabytes to the
 * APK to replace families the platform already provides and already shapes correctly.
 */
internal fun myLibraryTypography(rtl: Boolean): Typography {
    val base = Typography()
    if (!rtl) return base

    return Typography(
        displayLarge = base.displayLarge.scaledLineHeight(DISPLAY_LINE_HEIGHT_SCALE),
        displayMedium = base.displayMedium.scaledLineHeight(DISPLAY_LINE_HEIGHT_SCALE),
        displaySmall = base.displaySmall.scaledLineHeight(DISPLAY_LINE_HEIGHT_SCALE),

        headlineLarge = base.headlineLarge.scaledLineHeight(HEADLINE_LINE_HEIGHT_SCALE),
        headlineMedium = base.headlineMedium.scaledLineHeight(HEADLINE_LINE_HEIGHT_SCALE),
        headlineSmall = base.headlineSmall.scaledLineHeight(HEADLINE_LINE_HEIGHT_SCALE),

        titleLarge = base.titleLarge.scaledLineHeight(BODY_LINE_HEIGHT_SCALE),
        titleMedium = base.titleMedium.scaledLineHeight(BODY_LINE_HEIGHT_SCALE),
        titleSmall = base.titleSmall.scaledLineHeight(BODY_LINE_HEIGHT_SCALE),

        bodyLarge = base.bodyLarge.scaledLineHeight(BODY_LINE_HEIGHT_SCALE),
        bodyMedium = base.bodyMedium.scaledLineHeight(BODY_LINE_HEIGHT_SCALE),
        bodySmall = base.bodySmall.scaledLineHeight(BODY_LINE_HEIGHT_SCALE),

        labelLarge = base.labelLarge.scaledLineHeight(LABEL_LINE_HEIGHT_SCALE),
        labelMedium = base.labelMedium.scaledLineHeight(LABEL_LINE_HEIGHT_SCALE),
        labelSmall = base.labelSmall.scaledLineHeight(LABEL_LINE_HEIGHT_SCALE),
    )
}

/**
 * Opens up a style's leading for Arabic.
 *
 * Large display text is already generously leaded, so it needs less extra room than running body
 * text; labels are short and all-caps-like, so they need least.
 */
private fun TextStyle.scaledLineHeight(scale: Float): TextStyle =
    copy(lineHeight = (lineHeight.value * scale).sp)

private const val DISPLAY_LINE_HEIGHT_SCALE = 1.10f
private const val HEADLINE_LINE_HEIGHT_SCALE = 1.18f
private const val BODY_LINE_HEIGHT_SCALE = 1.22f
private const val LABEL_LINE_HEIGHT_SCALE = 1.12f

/**
 * Maps the reader's font choice onto a Compose font family.
 *
 * Public because the reader applies it to its own text styles: a reflowable document's body text is
 * sized by the reader, not by the app's type scale, but it must still use the family the user chose
 * in reading settings.
 */
fun readerFontFamily(font: ReaderFont): FontFamily = when (font) {
    ReaderFont.SYSTEM -> FontFamily.Default
    ReaderFont.SERIF -> FontFamily.Serif
    ReaderFont.SANS_SERIF -> FontFamily.SansSerif
    ReaderFont.MONOSPACE -> FontFamily.Monospace
}
