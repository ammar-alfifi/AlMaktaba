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
 * Pure, and separated out here so the mirroring can be pinned down by a test rather than by
 * inspecting a screenshot: getting this backwards silently reverses paging in Arabic, which is the
 * app's default language.
 */
fun tapZoneFor(x: Float, width: Float, isRtl: Boolean): TapZone {
    if (width <= 0f) return TapZone.CENTER

    val fraction = (x / width).coerceIn(0f, 1f)
    val zone = when {
        fraction < EDGE_FRACTION -> TapZone.PREVIOUS
        fraction > 1f - EDGE_FRACTION -> TapZone.NEXT
        else -> TapZone.CENTER
    }
    return if (isRtl) zone.mirrored() else zone
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
