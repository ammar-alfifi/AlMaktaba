package com.mylibrary.feature.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import com.mylibrary.core.ui.component.ChoiceRow
import com.mylibrary.core.ui.component.FeatureScaffold
import com.mylibrary.core.ui.theme.MyLibraryTheme
import com.mylibrary.core.ui.theme.Spacing
import com.mylibrary.core.ui.theme.colorSchemeForSource

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
                            ChoiceRow(
                                options = ThemeMode.entries,
                                selected = settings.themeMode,
                                onSelect = { onIntent(SettingsIntent.ThemeModeChanged(it)) },
                                label = { mode -> Text(stringResource(mode.setupLabelRes())) },
                            )
                        }

                        Section(title = stringResource(R.string.settings_color_colour)) {
                            Swatches(
                                selected = settings.colorSource,
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
 * One colour of the app.
 *
 * A filled circle rather than a labelled chip, because the name of a colour is not the colour: no
 * label distinguishes the blue from the purple as quickly as the two of them side by side do. The
 * name is kept for anyone using a screen reader, which is the one place a swatch cannot speak for
 * itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(selected: ColorSource, onSelect: (ColorSource) -> Unit) {
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
                val scheme = colorSchemeForSource(source, dark = false)
                val name = stringResource(source.labelRes())

                Box(
                    modifier = Modifier
                        .size(SWATCH_SIZE)
                        .clip(CircleShape)
                        .background(swatchBrush(source))
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
                    if (chosen) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            // Drawn in the scheme's own `onPrimary`, so the tick stays legible on a
                            // light palette as well as a dark one without a second rule.
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
@Composable
private fun swatchBrush(source: ColorSource): Brush {
    val fallback = colorSchemeForSource(source, dark = false).primary
    if (source != ColorSource.WALLPAPER) return SolidColor(fallback)

    val context = LocalContext.current
    val colour = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { dynamicLightColorScheme(context).primary }.getOrDefault(fallback)
        } else {
            fallback
        }
    }
    return SolidColor(colour)
}

/** Android 12 introduced the wallpaper palette; below it the choice does not exist to be made. */
private val supportsWallpaperColors: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

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
