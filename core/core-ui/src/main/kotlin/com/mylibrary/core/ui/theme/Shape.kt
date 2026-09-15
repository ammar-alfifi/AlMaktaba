package com.mylibrary.core.ui.theme

import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Material 3's shape scale, tuned slightly rounder than the defaults.
 *
 * Book covers are rectangles with sharp corners, so a softer container around them reads as
 * "shelf" rather than "spreadsheet". The extra-large radius is used for book cards and for modal
 * sheets, which is where the difference is actually visible.
 */
internal val MyLibraryShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)
