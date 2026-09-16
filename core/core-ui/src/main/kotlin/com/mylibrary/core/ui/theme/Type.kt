package com.mylibrary.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.mylibrary.core.domain.model.AppFont
import com.mylibrary.core.domain.model.ReaderFont
import com.mylibrary.core.ui.R

/**
 * MyLibrary's type scale.
 *
 * Three decisions drive this file.
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
 * **3. The interface can be set in a bundled Arabic face.** [AppFont] applies one family across every
 * style at once, which is the only way an app's typography stays coherent — swapping the family on
 * `bodyLarge` alone would leave headings in a different hand from the text under them. `SYSTEM`
 * leaves the platform stack (Noto Naskh Arabic / Noto Sans Arabic for Arabic, Roboto for Latin),
 * which is what the app shipped with and remains the default.
 */
internal fun myLibraryTypography(
    rtl: Boolean,
    uiFont: AppFont = AppFont.SYSTEM,
): Typography {
    val base = Typography()
    val family = appFontFamily(uiFont)

    val scaled = if (!rtl) base else Typography(
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

    return family?.let { scaled.withFamily(it) } ?: scaled
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
 * Sets every style in the scale in one family.
 *
 * All fifteen styles are listed rather than derived, because `Typography` has no `map` and the
 * alternative — copying the fields a caller happens to remember — is how a type scale ends up with
 * headings in one face and body text in another.
 */
private fun Typography.withFamily(family: FontFamily): Typography = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),

    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),

    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),

    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),

    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

/**
 * Maps a bundled Arabic face onto a Compose font family, or `null` for the system stack.
 *
 * `null` rather than `FontFamily.Default` so the "no preference" case can be recognised by callers
 * that need to leave the family alone entirely — notably the typography above, which must not
 * overwrite the platform stack with an explicit default.
 *
 * A face that the platform cannot instantiate (a variable font on a very old release, a corrupt
 * resource) resolves to the default typeface inside Compose rather than throwing, which is the
 * behaviour wanted here: a typeface must never be able to stop a screen from drawing.
 */
fun appFontFamily(font: AppFont): FontFamily? = when (font) {
    AppFont.SYSTEM -> null
    AppFont.AMIRI -> FontFamily(Font(R.font.amiri_regular))
    AppFont.PLEX_ARABIC -> FontFamily(Font(R.font.ibm_plex_arabic_regular))
    AppFont.REEM_KUFI -> FontFamily(Font(R.font.reem_kufi_regular))
}

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

    ReaderFont.AMIRI -> FontFamily(Font(R.font.amiri_regular))
    ReaderFont.PLEX_ARABIC -> FontFamily(Font(R.font.ibm_plex_arabic_regular))
    ReaderFont.REEM_KUFI -> FontFamily(Font(R.font.reem_kufi_regular))
}
