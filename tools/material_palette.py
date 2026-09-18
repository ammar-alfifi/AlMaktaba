#!/usr/bin/env python3
"""
Generates the app's Material 3 colour schemes from a seed colour, and writes them out as Kotlin.

Run it with `python3 tools/material_palette.py`; it rewrites
`core/core-ui/src/main/kotlin/com/mylibrary/core/ui/theme/Palettes.kt` in place. Nothing about the
generated palettes is decided at runtime — the app ships hex constants — so this only ever runs when
a palette is added or a seed changed.

## What is Material about it, and what is not

A Material 3 scheme is not a set of colours somebody liked. It is a set of *tones*: each role names
a tone (a CIE L* value) of one of six tonal palettes, and the palettes are built from a seed by
taking its hue and fixing a chroma per palette. That structure — the tone numbers, the six palettes,
the chromas, which role reads which tone — is reproduced here **exactly as Material specifies it**,
so every role relationship and every contrast pairing in the output is Material's.

What is *not* identical to Google's Theme Builder is the hue and chroma space. Material builds its
palettes in HCT (CAM16 × L*); this builds them in OKLCh, which is a similar perceptually-uniform
space and is far better conditioned to implement correctly. The consequences are narrow and worth
stating plainly:

  * the neutrals — the ramps the whole interface is actually made of — carry a chroma of six and
    eight, so they are set by their luminance and only tinted by the hue. They land within a few
    units of Google's;
  * the chromatic palettes are **more saturated** than Google's at the extremes of the ramp, because
    OKLab can hold more chroma at a given hue and tone than CAM16 can, and neither space clips: each
    reduces chroma only until the colour is inside sRGB;
  * every tone, in every palette, is the tone Material names — that is fixed by construction and
    checked below.

`verify()` checks those claims against Google's published values for the Material baseline seed and
refuses to write anything if they fail. It is the reason to trust the numbers it emits: an eye
cannot see that a role is the wrong *tone* rather than the wrong colour — it just looks slightly
off, on one screen, in one brightness.
"""

import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "core/core-ui/src/main/kotlin/com/mylibrary/core/ui/theme/Palettes.kt"

# The Material baseline seed. Its palette is published, which is what makes it checkable.
BASELINE_SEED = 0x6750A4

# --- The schemes the app offers -----------------------------------------------------------------
#
# One entry per `ColorSource` the domain model knows about, in the order the picker shows them.
# The seeds were chosen to be far apart in hue, because two seeds that differ only in saturation
# produce almost the same scheme: the chroma of every palette is fixed by the spec. `teal` is the
# app's own colour and is *not* generated — see the note in `Color.kt`.
SEEDS = {
    "PURPLE": 0x6750A4,
    "BLUE": 0x00629E,
    "GREEN": 0x3B6939,
    "AMBER": 0x8B5000,
    "ROSE": 0x984061,
}

# --- The Material 3 specification, as data -------------------------------------------------------

# Chroma per tonal palette, from Material's own "tonal spot" scheme — **in OKLab units, not HCT
# ones**. Material states them as 36, 16, 24, 6, 8 and 84 in CAM16 chroma; an OKLab chroma of 36
# would be far outside sRGB, so each was measured off the colour Material's own baseline palette
# produces for that palette at tone 40 (and, for the neutrals, at tone 80–90, where their chroma is
# the only thing that makes them tinted at all rather than grey). The *ratios* are Material's.
PALETTE_CHROMA = {
    "primary": 0.130,
    "secondary": 0.036,
    "tertiary": 0.060,
    "neutral": 0.0076,
    "neutralVariant": 0.017,
}

# How far the tertiary hue is turned from the seed.
TERTIARY_HUE_SHIFT = 60.0

# The error palette does not come from the seed at all, in Material either. Hue 25 and chroma 84 in
# HCT; here the hue is the one Material's own `error40` sits at in OKLab, so the ramp lands on the
# published colours rather than near them.
ERROR_HUE = 28.7
ERROR_CHROMA = 0.178

# Which tone each role reads, per mode. Straight out of Material's `Scheme.light()` / `Scheme.dark()`.
LIGHT = {
    "primary": ("primary", 40), "onPrimary": ("primary", 100),
    "primaryContainer": ("primary", 90), "onPrimaryContainer": ("primary", 10),
    "inversePrimary": ("primary", 80),
    "secondary": ("secondary", 40), "onSecondary": ("secondary", 100),
    "secondaryContainer": ("secondary", 90), "onSecondaryContainer": ("secondary", 10),
    "tertiary": ("tertiary", 40), "onTertiary": ("tertiary", 100),
    "tertiaryContainer": ("tertiary", 90), "onTertiaryContainer": ("tertiary", 10),
    "error": ("error", 40), "onError": ("error", 100),
    "errorContainer": ("error", 90), "onErrorContainer": ("error", 10),
    "background": ("neutral", 99), "onBackground": ("neutral", 10),
    "surface": ("neutral", 99), "onSurface": ("neutral", 10),
    "surfaceVariant": ("neutralVariant", 90), "onSurfaceVariant": ("neutralVariant", 30),
    "outline": ("neutralVariant", 50), "outlineVariant": ("neutralVariant", 80),
    "scrim": ("neutral", 0),
    "inverseSurface": ("neutral", 20), "inverseOnSurface": ("neutral", 95),
    "surfaceDim": ("neutral", 87), "surfaceBright": ("neutral", 98),
    "surfaceContainerLowest": ("neutral", 100), "surfaceContainerLow": ("neutral", 96),
    "surfaceContainer": ("neutral", 94), "surfaceContainerHigh": ("neutral", 92),
    "surfaceContainerHighest": ("neutral", 90),
    "surfaceTint": ("primary", 40),
}

DARK = {
    "primary": ("primary", 80), "onPrimary": ("primary", 20),
    "primaryContainer": ("primary", 30), "onPrimaryContainer": ("primary", 90),
    "inversePrimary": ("primary", 40),
    "secondary": ("secondary", 80), "onSecondary": ("secondary", 20),
    "secondaryContainer": ("secondary", 30), "onSecondaryContainer": ("secondary", 90),
    "tertiary": ("tertiary", 80), "onTertiary": ("tertiary", 20),
    "tertiaryContainer": ("tertiary", 30), "onTertiaryContainer": ("tertiary", 90),
    "error": ("error", 80), "onError": ("error", 20),
    "errorContainer": ("error", 30), "onErrorContainer": ("error", 90),
    "background": ("neutral", 10), "onBackground": ("neutral", 90),
    "surface": ("neutral", 10), "onSurface": ("neutral", 90),
    "surfaceVariant": ("neutralVariant", 30), "onSurfaceVariant": ("neutralVariant", 80),
    "outline": ("neutralVariant", 60), "outlineVariant": ("neutralVariant", 30),
    "scrim": ("neutral", 0),
    "inverseSurface": ("neutral", 90), "inverseOnSurface": ("neutral", 20),
    "surfaceDim": ("neutral", 6), "surfaceBright": ("neutral", 24),
    "surfaceContainerLowest": ("neutral", 4), "surfaceContainerLow": ("neutral", 10),
    "surfaceContainer": ("neutral", 12), "surfaceContainerHigh": ("neutral", 17),
    "surfaceContainerHighest": ("neutral", 22),
    "surfaceTint": ("primary", 80),
}

# --- Colour --------------------------------------------------------------------------------------


def _srgb_to_linear(channel: float) -> float:
    return channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4


def _linear_to_srgb(channel: float) -> float:
    return channel * 12.92 if channel <= 0.0031308 else 1.055 * (channel ** (1 / 2.4)) - 0.055


def oklab_from_srgb(rgb):
    """sRGB 0..1 to OKLab."""
    r, g, b = (_srgb_to_linear(c) for c in rgb)
    l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
    m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
    s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
    l_, m_, s_ = (c ** (1 / 3) if c >= 0 else -((-c) ** (1 / 3)) for c in (l, m, s))
    return (
        0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
        1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
        0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_,
    )


def srgb_from_oklab(lab):
    """OKLab to sRGB 0..1, unclamped — the caller decides what being outside means."""
    l, a, b = lab
    l_ = l + 0.3963377774 * a + 0.2158037573 * b
    m_ = l - 0.1055613458 * a - 0.0638541728 * b
    s_ = l - 0.0894841775 * a - 1.2914855480 * b
    l_c, m_c, s_c = l_ ** 3, m_ ** 3, s_ ** 3
    linear = (
        4.0767416621 * l_c - 3.3077115913 * m_c + 0.2309699292 * s_c,
        -1.2684380046 * l_c + 2.6097574011 * m_c - 0.3413193965 * s_c,
        -0.0041960863 * l_c - 0.7034186147 * m_c + 1.7076147010 * s_c,
    )
    return tuple(_linear_to_srgb(c) for c in linear)


def oklch_from_srgb(rgb):
    l, a, b = oklab_from_srgb(rgb)
    return l, math.hypot(a, b), math.degrees(math.atan2(b, a)) % 360.0


def _luminance_of_tone(tone: float) -> float:
    """A Material tone is a CIE L*, so this is the standard L* -> relative luminance step."""
    if tone <= 8.0:
        return tone / 903.2962962962963
    return ((tone + 16.0) / 116.0) ** 3


def _relative_luminance(rgb) -> float:
    r, g, b = (_srgb_to_linear(min(1.0, max(0.0, c))) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def _lightness_for_luminance(chroma: float, radians: float, target: float) -> float:
    """
    The OKLab lightness that puts a colour of this chroma and hue at `target` luminance.

    **This is not simply the cube root of the luminance, and assuming it was is a mistake worth
    recording.** That identity holds for greys — for a colour with no chroma, OKLab's L is exactly
    `Y^(1/3)` — but OKLab's L is a nonlinear combination of three cone responses, so a saturated
    colour at a given L has a different luminance from a grey at the same L. Building the palette
    from `Y^(1/3)` therefore produced colours that were consistently too dark: Material's `error40`
    is `#B3261E` at L* 39.7, and this generated `#AC1E18` at L* 38. The check below caught it.

    Luminance rises monotonically with lightness, so a bisection is exact here rather than an
    approximation.
    """
    low, high = 0.0, 1.0
    for _ in range(22):
        mid = (low + high) / 2
        rgb = srgb_from_oklab((mid, chroma * math.cos(radians), chroma * math.sin(radians)))
        if _relative_luminance(rgb) < target:
            low = mid
        else:
            high = mid
    return (low + high) / 2


def tone_to_rgb(hue: float, chroma: float, tone: float):
    """
    The colour at `tone` on the tonal palette of `hue` and `chroma`.

    Tone fixes the *luminance*, exactly as Material defines it, and the hue is kept. Chroma is then
    walked down until the result is inside sRGB, which is what Material does too — a palette that
    asked for more chroma than a hue can hold would otherwise be clipped, and clipping moves the
    hue, which is the one thing a tonal palette may not do.
    """
    target = _luminance_of_tone(tone)
    radians = math.radians(hue)

    low, high = 0.0, chroma
    best = (0.0, 0.0, 0.0)
    for _ in range(22):
        mid = (low + high) / 2
        lightness = _lightness_for_luminance(mid, radians, target)
        rgb = srgb_from_oklab((lightness, mid * math.cos(radians), mid * math.sin(radians)))
        if all(-0.0005 <= c <= 1.0005 for c in rgb):
            best, low = rgb, mid
        else:
            high = mid
    return tuple(round(min(1.0, max(0.0, c)) * 255) for c in best)


def argb(rgb) -> int:
    r, g, b = rgb
    return (0xFF << 24) | (r << 16) | (g << 8) | b


# --- Scheme --------------------------------------------------------------------------------------


def tonal_palettes(seed: int):
    """The six tonal palettes a Material "tonal spot" scheme is built from."""
    _, _, hue = oklch_from_srgb(
        (((seed >> 16) & 0xFF) / 255, ((seed >> 8) & 0xFF) / 255, (seed & 0xFF) / 255)
    )
    hues = {
        "primary": hue,
        "secondary": hue,
        "tertiary": (hue + TERTIARY_HUE_SHIFT) % 360.0,
        "neutral": hue,
        "neutralVariant": hue,
        "error": ERROR_HUE,
    }
    chromas = dict(PALETTE_CHROMA, error=ERROR_CHROMA)
    return {
        name: (hues[name], chromas[name])
        for name in ("primary", "secondary", "tertiary", "neutral", "neutralVariant", "error")
    }


def scheme(seed: int, roles: dict) -> dict:
    palettes = tonal_palettes(seed)
    palette_colour = lambda name, tone: argb(tone_to_rgb(*palettes[name], tone))
    return {role: palette_colour(*palette_colour_args) for role, palette_colour_args in roles.items()}


# --- The check -----------------------------------------------------------------------------------

# Google's published palette for the Material baseline seed, at the tones the roles above use.
BASELINE_NEUTRALS = {
    ("neutral", 10): 0x1C1B1F, ("neutral", 90): 0xE6E1E5, ("neutral", 95): 0xF4EFF4,
    ("neutral", 99): 0xFFFBFE, ("neutral", 100): 0xFFFFFF,
    ("neutralVariant", 30): 0x49454F, ("neutralVariant", 50): 0x79747E,
    ("neutralVariant", 80): 0xCAC4D0, ("neutralVariant", 90): 0xE7E0EC,
}
BASELINE_ERROR = {10: 0x410E0B, 30: 0x8C1D18, 40: 0xB3261E, 80: 0xF2B8B5, 90: 0xF9DEDC}

# The neutral ramps carry a chroma of six and eight, so at that chroma a hue difference between
# OKLab and CAM16 can only move a channel by a few units. Ten is generous for that and still far
# tighter than any real error in the tone -> luminance -> sRGB path, which is what is being checked.
NEUTRAL_TOLERANCE = 10


def _channel_distance(a: int, b: int) -> int:
    return max(abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)) for shift in (16, 8, 0))


def _lstar(hexv: int) -> float:
    rgb = (((hexv >> 16) & 0xFF) / 255, ((hexv >> 8) & 0xFF) / 255, (hexv & 0xFF) / 255)
    y = _relative_luminance(rgb)
    return 116 * (y ** (1 / 3)) - 16 if y > 0.008856 else 903.3 * y


def verify() -> None:
    """
    Checks the generated palette against Material's, on the two things that are actually promised.

    **The neutrals**, which are the ramps the app's whole surface is made of. They carry a chroma of
    six and eight, so at that chroma an OKLab hue and a CAM16 hue can only move a channel by a few
    units: matching Google's to within [NEUTRAL_TOLERANCE] is a real check on the tone → luminance →
    sRGB path, which is the part of this that would be catastrophically wrong if it were wrong.

    **The error ramp's tones**, which are the check on the *chromatic* path. Not its exact colours:
    OKLab holds more chroma than CAM16 at the same hue and tone, so where Material's ramp is
    gamut-limited — at the extremes, tone 10 and tone 80 — this produces a more saturated colour on
    purpose. What must hold is that each tone still *is* its tone, and that tone 40, which both
    spaces can hold, comes out as the colour Material publishes.
    """
    palettes = tonal_palettes(BASELINE_SEED)
    failures = []

    for (name, tone), expected in BASELINE_NEUTRALS.items():
        got = argb(tone_to_rgb(*palettes[name], tone))
        if _channel_distance(got, expected) > NEUTRAL_TOLERANCE:
            failures.append(
                f"  {name} tone {tone}: got #{got & 0xFFFFFF:06X}, Material says #{expected:06X}"
            )

    for tone, expected in BASELINE_ERROR.items():
        got = argb(tone_to_rgb(ERROR_HUE, ERROR_CHROMA, tone))
        if abs(_lstar(got) - tone) > 1.0:
            failures.append(
                f"  error tone {tone}: got #{got & 0xFFFFFF:06X} at L* {_lstar(got):.1f}"
            )
    in_gamut_for_both = argb(tone_to_rgb(ERROR_HUE, ERROR_CHROMA, 40))
    if _channel_distance(in_gamut_for_both, BASELINE_ERROR[40]) > 8:
        failures.append(
            f"  error tone 40: got #{in_gamut_for_both & 0xFFFFFF:06X}, "
            f"Material says #{BASELINE_ERROR[40]:06X}"
        )

    if failures:
        print("The generated palette does not match Material's baseline:", file=sys.stderr)
        print("\n".join(failures), file=sys.stderr)
        print("\nRefusing to write. Fix the colour maths, not the expectations.", file=sys.stderr)
        raise SystemExit(1)

    print(
        f"checked against the Material baseline: neutrals within {NEUTRAL_TOLERANCE}/255, "
        f"every error tone within 1.0 of its L*"
    )


# --- Output --------------------------------------------------------------------------------------


def render_scheme(seed: int, roles: dict, indent: str) -> str:
    colours = scheme(seed, roles)
    lines = []
    for role in roles:
        lines.append(f"{indent}{role} = Color(0xFF{colours[role] & 0xFFFFFF:06X}),")
    return "\n".join(lines)


def main() -> None:
    verify()

    blocks = []
    for name, seed in SEEDS.items():
        blocks.append(
            f"""internal val {name}LightColors = lightColorScheme(
{render_scheme(seed, LIGHT, "    ")}
)

internal val {name}DarkColors = darkColorScheme(
{render_scheme(seed, DARK, "    ")}
)"""
        )
        print(f"  {name.lower()} (seed #{seed:06X})")

    header = '''package com.mylibrary.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.mylibrary.core.domain.model.ColorSource

/**
 * The app's colour schemes, one light and one dark per [ColorSource].
 *
 * **These are generated, and the generator is in the repository.**
 * `tools/material_palette.py` builds each scheme from a seed the way Material 3 specifies: six tonal
 * palettes at fixed chromas around the seed's hue, and every role reading a named tone of one of
 * them. Run it after changing a seed; it rewrites this file and checks itself against Google's
 * published baseline palette before it does.
 *
 * That check is the reason to trust the numbers below. A hand-written palette can only be reviewed
 * by eye, and an eye cannot see that `onSurfaceVariant` is the wrong *tone* rather than the wrong
 * colour — it just looks slightly off, on one screen, in one brightness. Generated from a verified
 * mapping, every pairing in here is the one Material guarantees.
 *
 * [ColorSource.TEAL] is deliberately absent: it is the palette the app shipped with, hand-authored
 * in `Color.kt`, and regenerating it would change the app's own colour for no reason a reader asked
 * for. [ColorSource.WALLPAPER] has no scheme at all — it is the device's, resolved at runtime.
 */

'''

    body = "\n\n".join(blocks)

    resolve = '''

/**
 * The scheme for [source], or `null` where there is none to resolve.
 *
 * `null` for [ColorSource.WALLPAPER], which is the *device's* palette and can only be read at
 * runtime, and for [ColorSource.TEAL], which lives in `Color.kt` with the rest of the hand-authored
 * scheme. Keeping both out of here rather than duplicating them means there is exactly one place a
 * given scheme is written down.
 */
internal fun generatedColorScheme(source: ColorSource, dark: Boolean): ColorScheme? = when (source) {
    ColorSource.WALLPAPER, ColorSource.TEAL -> null
    ColorSource.PURPLE -> if (dark) PURPLEDarkColors else PURPLELightColors
    ColorSource.BLUE -> if (dark) BLUEDarkColors else BLUELightColors
    ColorSource.GREEN -> if (dark) GREENDarkColors else GREENLightColors
    ColorSource.AMBER -> if (dark) AMBERDarkColors else AMBERLightColors
    ColorSource.ROSE -> if (dark) ROSEDarkColors else ROSELightColors
}
'''

    OUT.write_text(header + body + resolve)
    print(f"\nwrote {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
