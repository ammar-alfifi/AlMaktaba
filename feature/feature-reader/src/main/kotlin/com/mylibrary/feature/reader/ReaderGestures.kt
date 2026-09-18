package com.mylibrary.feature.reader

/** What a tap on the reading surface asks for, decided by where it landed. */
enum class TapZone {
    /** Back one screen: the previous page, or the previous screenful of a reflowed chapter. */
    PREVIOUS,

    /** Show or hide the toolbar. */
    CENTER,

    /** Forward one screen. */
    NEXT,
}

/**
 * The zone a tap at [x] falls into, on a surface [width] wide.
 *
 * Split into three because a tap is the cheapest gesture a reader has, and spending the whole
 * surface on "toggle the toolbar" wastes it. The edges turn the page and the middle reveals the
 * chrome, which is what every reading app does and therefore what a thumb already expects. The one
 * rule is applied *before* direction is taken into account: the zones are leading / middle /
 * trailing, and a right-to-left book mirrors them, so the physical gesture — tap the side you are
 * moving towards — is the same in Arabic and in English.
 *
 * [reversed] is a second, independent answer to the same physical question — which side of the glass
 * moves forward — so it is combined with the mirroring by *exclusive or* rather than applied after
 * it. That is what makes it worth having: an Arabic comic reads right to left, so the direction
 * mirrors the zones, and a reader whose thumb has learnt "the left side goes forward" can have the
 * book the way the book goes and the taps the way their hand goes. Reversing a mirrored surface
 * therefore gives back the unmirrored one, and there are exactly two tap mappings — but four
 * combinations of *page order and tap mapping*, which is the choice the reader is actually making.
 *
 * Pure, and separated out here so the mirroring can be pinned down by a test rather than by
 * inspecting a screenshot: getting this backwards silently reverses paging in Arabic, which is the
 * app's default language.
 */
fun tapZoneFor(
    x: Float,
    width: Float,
    isRtl: Boolean,
    reversed: Boolean = false,
): TapZone {
    if (width <= 0f) return TapZone.CENTER

    val fraction = (x / width).coerceIn(0f, 1f)
    val zone = when {
        fraction < EDGE_FRACTION -> TapZone.PREVIOUS
        fraction > 1f - EDGE_FRACTION -> TapZone.NEXT
        else -> TapZone.CENTER
    }
    // Exclusive or rather than two mirrors in a row: both settings answer the same question, so
    // asking twice can only give the answer back.
    return if (isRtl != reversed) zone.mirrored() else zone
}

private fun TapZone.mirrored(): TapZone = when (this) {
    TapZone.PREVIOUS -> TapZone.NEXT
    TapZone.NEXT -> TapZone.PREVIOUS
    TapZone.CENTER -> TapZone.CENTER
}

/**
 * The share of the reading surface that turns the page, on each side.
 *
 * Three tenths leaves a middle band of forty per cent for the toolbar gesture: wide enough to hit
 * without aiming, narrow enough that the page-turn zones stay reachable with a thumb at either edge.
 */
private const val EDGE_FRACTION = 0.3f

/**
 * How much of a screenful a side tap moves a scrolling layout, as a fraction of the viewport.
 *
 * Short of a whole screen on purpose: a sliver of the text just read stays in view, which is what
 * makes the jump legible as movement rather than as a new page appearing. Shared by both scroll
 * layouts so a tapped side moves the same distance whether the column is text or pages.
 */
internal const val SCROLL_PAGE_FRACTION = 0.85f
