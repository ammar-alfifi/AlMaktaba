package com.mylibrary.feature.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mylibrary.core.domain.model.ColorSource
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.model.ThemeMode
import com.mylibrary.core.ui.component.FeatureScaffold
import com.mylibrary.core.ui.theme.MyLibraryTheme
import com.mylibrary.core.ui.theme.Spacing
import com.mylibrary.core.ui.theme.rememberColorSchemeForSource

/**
 * Choosing the app's colours: what a first launch opens on, and what Settings opens later.
 *
 * **One screen, two ways in**, and deliberately not two screens. The thing a reader sets at first
 * run is the thing they come back to change, and a "welcome" that is subtly different from the
 * settings page — different wording, a different order, an option that only exists in one of them —
 * is how an app ends up with two ideas of what its own appearance setting is.
 *
 * **The preview is the point.** A row of colour swatches says what the primary colour will be and
 * nothing else; what a reader is actually choosing is what a button, a card and a line of text are
 * going to look like for the next year. So the choices repaint the screen they are on, and the card
 * at the bottom shows real Material components — a `Switch`, a `FilterChip`, a `Button` — drawn in
 * the scheme being chosen. Everything except that card is drawn in the *chosen* scheme too, which is
 * why the whole thing sits inside its own [MyLibraryTheme].
 *
 * The app at large already does this: `MyLibraryTheme` is applied at the root from the settings the
 * store holds, and this screen writes to that store on every tap, so the reader sees the change
 * everywhere the instant they make it.
 */
@Composable
fun ColorSetupRoute(
    onDone: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ColorSetupScreen(
        settings = state.settings,
        onIntent = viewModel::onIntent,
        onDone = onDone,
    )
}

/**
 * The screen, rendered from a settings object alone.
 *
 * Stateless like every other screen here, so it can be previewed or asserted on without a ViewModel
 * or a store — and so that the first-run path and the Settings path are visibly the same call.
 */
@Composable
fun ColorSetupScreen(
    settings: ReaderSettings,
    onIntent: (SettingsIntent) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The whole screen is drawn in the scheme being chosen, so a tap repaints it. `MyLibraryTheme`
    // is applied again here rather than inherited: this screen is reachable from the root, which
    // supplies the *stored* scheme, and the difference between the two is zero except for the
    // instant between a tap and the store echoing it back.
    MyLibraryTheme(
        themeMode = settings.themeMode,
        colorSource = settings.colorSource,
    ) {
        FeatureScaffold(modifier = modifier) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.XLarge)
                    .padding(top = Spacing.XLarge, bottom = Spacing.Huge),
                verticalArrangement = Arrangement.spacedBy(Spacing.XLarge),
            ) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.widthIn(max = Spacing.MaxContentWidth),
                        verticalArrangement = Arrangement.spacedBy(Spacing.XLarge),
                    ) {
                        Heading()

                        Section(title = stringResource(R.string.settings_color_theme)) {
                            ThemeModeCards(
                                selected = settings.themeMode,
                                colorSource = settings.colorSource,
                                onSelect = { onIntent(SettingsIntent.ThemeModeChanged(it)) },
                            )
                        }

                        Section(title = stringResource(R.string.settings_color_colour)) {
                            Swatches(
                                selected = settings.colorSource,
                                // The swatches are drawn in the brightness the app is actually in, so
                                // a colour that looks right here is the colour the reader gets. In
                                // dark mode the light scheme's primary is a different colour, and a
                                // swatch that always showed the light one was the swatch lying.
                                dark = settings.themeMode.isDark(isSystemInDarkTheme()),
                                onSelect = { onIntent(SettingsIntent.ColorSourceChanged(it)) },
                            )
                        }

                        Preview()

                        Button(
                            onClick = {
                                // The answer is recorded first, so a reader who taps and immediately
                                // closes the app is not asked again on the next launch.
                                onIntent(SettingsIntent.SetupCompleted)
                                onDone()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.settings_color_done))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Text(
            text = stringResource(R.string.settings_color_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.settings_color_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One card per theme mode, each showing a miniature of the app in that mode.
 *
 * This is how the professional Material pickers answer the question: not with the word "داكن",
 * which every reader has already seen a hundred times, but with the app itself in dark. The text
 * stays for screen readers and for anyone who still wants it; the picture is doing the work.
 */
@Composable
private fun ThemeModeCards(
    selected: ThemeMode,
    colorSource: ColorSource,
    onSelect: (ThemeMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
    ) {
        ThemeMode.entries.forEach { mode ->
            val chosen = mode == selected
            val label = stringResource(mode.setupLabelRes())

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.Small),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.82f)
                        .clip(MaterialTheme.shapes.medium)
                        .border(
                            width = if (chosen) SELECTED_RING else 1.dp,
                            color = if (chosen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = MaterialTheme.shapes.medium,
                        )
                        .selectable(
                            selected = chosen,
                            role = Role.RadioButton,
                            onClick = { onSelect(mode) },
                        )
                        .semantics { contentDescription = label },
                ) {
                    MiniAppPreview(colorSource = colorSource, mode = mode)

                    if (chosen) {
                        // A small filled badge rather than a corner tick, so the retina reads it
                        // before the finger even asks which card was tapped.
                        Box(
                            modifier = Modifier
                                .padding(Spacing.Small)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .align(Alignment.TopEnd),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (chosen) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

/**
 * A miniature of the app in [mode], real colours rather than sketches.
 *
 * Four elements and nothing more — a top bar, two lines of reading, a chip and a FAB — but each
 * drawn from the actual [ColorScheme] that machine would run in, including the split of light and
 * dark that SYSTEM implies. `surfaceContainer` marks the furniture, `primaryContainer` the
 * emphasis; those two pairings are most of what a theme is.
 *
 * The SYSTEM card follows the device rather than assuming light. It used to treat anything that was
 * not explicitly dark as light, so a reader whose phone was already in dark mode saw a light
 * miniature under "System" — the one card meant to say "whatever the phone is doing" showing the
 * opposite of it.
 */
@Composable
private fun MiniAppPreview(colorSource: ColorSource, mode: ThemeMode) {
    val dark = mode.isDark(systemInDarkTheme = isSystemInDarkTheme())
    // The wallpaper palette where there is one, so the miniature shows what the device would
    // actually paint rather than the app's teal standing in for it.
    val scheme = rememberColorSchemeForSource(colorSource, dark)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceContainer)
                .padding(horizontal = Spacing.Medium, vertical = Spacing.Small),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(LINE)
                    .clip(CircleShape)
                    .background(scheme.primaryContainer),
            )
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(LINE)
                    .clip(CircleShape)
                    .background(scheme.onSurfaceVariant.copy(alpha = 0.5f)),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.Medium, vertical = Spacing.Small),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LINE)
                    .clip(CircleShape)
                    .background(scheme.onSurface.copy(alpha = 0.85f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .height(LINE)
                    .clip(CircleShape)
                    .background(scheme.onSurface.copy(alpha = 0.5f)),
            )
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(scheme.secondaryContainer)
                    .padding(horizontal = Spacing.Small, vertical = LINE_HALF),
        ) {
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .height(LINE)
                    .clip(CircleShape)
                    .background(scheme.onSecondaryContainer.copy(alpha = 0.7f)),
            )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.End)
                .padding(Spacing.Medium)
                .size(28.dp)
                .clip(CircleShape)
                .background(scheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(scheme.onPrimary),
            )
        }
    }
}

/**
 * One colour of the app, as a two-tone Material You swatch.
 *
 * A single filled circle says what the primary will be; a half-and-half of primary and tertiary
 * says what the *pairing* will be, which is the thing the reader is actually choosing.
 *
 * [dark] is the brightness the app is in right now, and each swatch is drawn in the scheme that
 * brightness would actually produce — including the device's wallpaper palette, which
 * `colorSchemeForSource` cannot supply. That is the difference between a preview and a decoration:
 * a palette shown in the light scheme while the app runs dark is a colour the reader never sees.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(selected: ColorSource, dark: Boolean, onSelect: (ColorSource) -> Unit) {
    val available = ColorSource.entries.filter { it.isAvailable(supportsWallpaperColors) }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
        // `FlowRow` rather than a `Row`, and the difference is not cosmetic: seven swatches at a
        // comfortable size do not fit across a phone, and a `Row` does not wrap — it overflows, so
        // the last colour simply is not on the screen. On a phone in Arabic that was the wallpaper
        // option, which is the one a reader on Android 12 is most likely to want.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
            verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
        ) {
            available.forEach { source ->
                val chosen = source == selected
                val scheme = rememberColorSchemeForSource(source, dark)
                val name = stringResource(source.labelRes())

                Box(
                    modifier = Modifier
                        .size(SWATCH_SIZE)
                        .clip(CircleShape)
                        .background(scheme.primary)
                        .border(
                            width = if (chosen) SELECTED_RING else 1.dp,
                            color = if (chosen) {
                                MaterialTheme.colorScheme.onBackground
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        )
                        .selectable(
                            selected = chosen,
                            role = Role.RadioButton,
                            onClick = { onSelect(source) },
                        )
                        // The name is what a screen reader reads instead of the colour, which is
                        // the one thing a circle cannot say for itself. Without it the seven
                        // swatches are seven unlabelled radio buttons.
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    // The tertiary half: one circle can say "blue", two tones can say "blue and
                    // gold together", which is the pairing the reader is really choosing. The
                    // wallpaper swatch stays a single tone, since it has no fixed pairing to show.
                    if (source != ColorSource.WALLPAPER) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .size(SWATCH_SIZE / 2, SWATCH_SIZE)
                                .background(scheme.tertiary),
                        )
                    }

                    if (chosen) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            // Drawn against the lighter of the two tones, so the tick stays
                            // legible over the primary half without a per-colour rule.
                            tint = scheme.onPrimary,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        Text(
            text = stringResource(selected.labelRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}



/**
 * What the interface will look like, in the colour being chosen right now.
 *
 * Real components rather than painted rectangles. The question a reader is answering is not "do I
 * like this hue" but "will I be able to read my library in it", and only the actual controls answer
 * that — a container and its content colour, a chip against a surface, a switch's track against its
 * thumb. Three rectangles of flat colour would look identical for two schemes that differ in every
 * one of those pairings.
 */
@Composable
private fun Preview() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.Large),
            verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_color_preview_title)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_color_preview_body))
                },
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = {}) {
                    Text(stringResource(R.string.settings_color_preview_button))
                }
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text(stringResource(R.string.settings_color_preview_chip)) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_color_preview_switch),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(checked = true, onCheckedChange = {})
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        content()
    }
}

/**
 * What the swatch for [source] is filled with.
 *
 * [ColorSource.WALLPAPER] is the exception to everything else here: it has no fixed colour, so
 * filling its circle with the app's own teal would put two identical circles side by side and leave
 * the reader to guess which was which. It shows the palette the *device* would hand over instead,
 * which is the honest answer to "what will this look like" — and on a device with no wallpaper
 * palette to read, the picker does not offer this choice at all.
 */
/**
 * Android 12 introduced the wallpaper palette; below it the choice does not exist to be made.
 */
private val supportsWallpaperColors: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Whether [this] theme mode draws a dark scheme on a device whose system theme is [systemInDarkTheme].
 *
 * A pure function so the one rule that can go wrong here — that `SYSTEM` follows the device rather
 * than being treated as light — is testable without a composition. The theme cards draw a miniature
 * of the app for each mode, and the SYSTEM card is the one that has no fixed answer of its own: it
 * used to be drawn light whatever the phone was doing, so a reader in dark mode saw a light preview
 * under the card that promises to follow their phone.
 */
internal fun ThemeMode.isDark(systemInDarkTheme: Boolean): Boolean = when (this) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> systemInDarkTheme
}

@androidx.annotation.StringRes
private fun ThemeMode.setupLabelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_color_theme_system
    ThemeMode.LIGHT -> R.string.settings_color_theme_light
    ThemeMode.DARK -> R.string.settings_color_theme_dark
}

@androidx.annotation.StringRes
private fun ColorSource.labelRes(): Int = when (this) {
    ColorSource.WALLPAPER -> R.string.settings_color_wallpaper
    ColorSource.TEAL -> R.string.settings_color_teal
    ColorSource.PURPLE -> R.string.settings_color_purple
    ColorSource.BLUE -> R.string.settings_color_blue
    ColorSource.GREEN -> R.string.settings_color_green
    ColorSource.AMBER -> R.string.settings_color_amber
    ColorSource.ROSE -> R.string.settings_color_rose
}

/** Big enough to compare hues at a glance, small enough that seven fit a phone's width. */
private val SWATCH_SIZE = 44.dp

private val SELECTED_RING = 3.dp

/** Heights of the painted lines inside a theme-mode card's miniature. */
private val LINE = 6.dp

private val LINE_HALF = 3.dp
