package com.mylibrary.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mylibrary.core.domain.model.AppLanguage
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.PageFitMode
import com.mylibrary.core.domain.model.ReaderFont
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.model.ReadingDirection
import com.mylibrary.core.domain.model.ReflowMode
import com.mylibrary.core.domain.model.ThemeMode
import com.mylibrary.core.domain.model.ViewMode
import com.mylibrary.core.domain.usecase.UpdateSettingsUseCase
import com.mylibrary.core.ui.mvi.ObserveEffects
import kotlinx.coroutines.launch
import java.util.Locale
import com.mylibrary.core.ui.R as CoreUiR

/**
 * The settings destination.
 *
 * Wires the ViewModel to [SettingsScreen] and turns the one-shot effects into a snackbar. The
 * snackbar host is owned here rather than taken from the app's scaffold because the app hosts this
 * screen as a navigation destination and has no way to hand a `SnackbarHostState` down to it; the
 * host is laid over the bottom of the content, so the message still appears above the app's
 * navigation bar.
 */
@Composable
fun SettingsRoute(modifier: Modifier = Modifier) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // `showSnackbar` suspends until the message is dismissed, and the effect callback does not
    // suspend, so the message is launched into the composition's own scope — which also means the
    // snackbar disappears with the screen instead of outliving it.
    val scope = rememberCoroutineScope()

    // The effect carries a resource id, and the string is resolved here rather than inside the
    // ViewModel so that changing the in-app language re-words the message without a new effect.
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is SettingsEffect.ShowMessage -> scope.launch {
                snackbarHostState.showSnackbar(context.getString(effect.messageResId))
            }
        }
    }

    SettingsContent(
        state = state,
        onIntent = viewModel::onIntent,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/**
 * The settings screen, rendered from [state] alone.
 *
 * Stateless on purpose: every value it draws comes from [state] and every interaction leaves as an
 * [SettingsIntent], so the whole screen can be previewed or asserted on from a hand-written state
 * without a ViewModel, a store or a device.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A screen rendered without the route (a preview, or a UI test) still gets a host to render,
    // so the layout is identical in both cases; it simply never receives a message.
    SettingsContent(
        state = state,
        onIntent = onIntent,
        snackbarHostState = remember { SnackbarHostState() },
        modifier = modifier,
    )
}

/**
 * The screen's body, shared by the route and by [SettingsScreen].
 *
 * The snackbar host is a parameter rather than something this function remembers, so that the route
 * can push effects into the host the screen actually renders — a host created here would be a
 * second, invisible one.
 */
@Composable
private fun SettingsContent(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    // Which dialog is open is deliberately *not* part of `SettingsUiState`: it is not a preference,
    // it must not outlive the screen, and keeping it out means the state stays a faithful snapshot
    // of what is stored — a reset confirmation left open across a process death would be a lie.
    var confirmingReset by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
            // Sections are spaced by the list rather than by each section's own trailing padding, so
            // the last one does not leave a gap above the reset button.
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item { AppearanceSection(state.settings, onIntent) }
            item { LanguageSection(state.settings, onIntent) }
            item { LibrarySection(state.settings, onIntent) }
            item { ReadingSection(state.settings, onIntent) }
            item { AboutSection(state.appVersionName) }
            item { ResetSection(onClick = { confirmingReset = true }) }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (confirmingReset) {
        ResetConfirmationDialog(
            onConfirm = {
                // The dialog closes first: leaving it up while the store is written would show a
                // confirm button that looks like it did nothing.
                confirmingReset = false
                onIntent(SettingsIntent.ResetToDefaults)
            },
            onDismiss = { confirmingReset = false },
        )
    }
}

/** Theme mode and dynamic colour. */
@Composable
private fun AppearanceSection(settings: ReaderSettings, onIntent: (SettingsIntent) -> Unit) {
    SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
        SegmentedSettingRow(
            title = stringResource(R.string.settings_theme_mode),
            options = ThemeMode.entries,
            selected = settings.themeMode,
            labelRes = ThemeMode::labelRes,
            onSelect = { onIntent(SettingsIntent.ThemeModeChanged(it)) },
        )
        SectionDivider()
        SwitchRow(
            title = stringResource(R.string.settings_dynamic_color),
            summary = stringResource(R.string.settings_dynamic_color_summary),
            checked = settings.dynamicColor,
            onCheckedChange = { onIntent(SettingsIntent.DynamicColorToggled(it)) },
        )
    }
}

/** The app's own language, which overrides the device's — hence SYSTEM being one of the choices. */
@Composable
private fun LanguageSection(settings: ReaderSettings, onIntent: (SettingsIntent) -> Unit) {
    SettingsSection(title = stringResource(R.string.settings_section_language)) {
        SegmentedSettingRow(
            title = stringResource(R.string.settings_language),
            options = AppLanguage.entries,
            selected = settings.language,
            labelRes = AppLanguage::labelRes,
            onSelect = { onIntent(SettingsIntent.LanguageChanged(it)) },
        )
    }
}

/** How the book list is laid out and ordered. */
@Composable
private fun LibrarySection(settings: ReaderSettings, onIntent: (SettingsIntent) -> Unit) {
    SettingsSection(title = stringResource(R.string.settings_section_library)) {
        SegmentedSettingRow(
            title = stringResource(R.string.settings_view_mode),
            options = ViewMode.entries,
            selected = settings.viewMode,
            labelRes = ViewMode::labelRes,
            onSelect = { onIntent(SettingsIntent.ViewModeChanged(it)) },
        )
        SectionDivider()
        // A dropdown rather than a segmented row: five sort orders do not fit a phone's width side
        // by side, and squeezing them into a scrollable row would hide options behind a gesture.
        DropdownSettingRow(
            title = stringResource(R.string.settings_sort),
            options = LibrarySort.entries,
            selected = settings.librarySort,
            labelRes = LibrarySort::labelRes,
            onSelect = { onIntent(SettingsIntent.SortChanged(it)) },
        )
    }
}

/** The defaults every document opens with. */
@Composable
private fun ReadingSection(settings: ReaderSettings, onIntent: (SettingsIntent) -> Unit) {
    SettingsSection(title = stringResource(R.string.settings_section_reading)) {
        DropdownSettingRow(
            title = stringResource(R.string.settings_reader_font),
            options = ReaderFont.entries,
            selected = settings.readerFont,
            labelRes = ReaderFont::labelRes,
            onSelect = { onIntent(SettingsIntent.ReaderFontChanged(it)) },
        )
        SectionDivider()
        SliderSettingRow(
            title = stringResource(R.string.settings_font_scale),
            value = settings.fontScale,
            valueRange = UpdateSettingsUseCase.MIN_FONT_SCALE..UpdateSettingsUseCase.MAX_FONT_SCALE,
            onValueChange = { onIntent(SettingsIntent.FontScaleChanged(it)) },
        )
        SectionDivider()
        SliderSettingRow(
            title = stringResource(R.string.settings_line_height),
            value = settings.lineHeightScale,
            valueRange = UpdateSettingsUseCase.MIN_LINE_HEIGHT..UpdateSettingsUseCase.MAX_LINE_HEIGHT,
            onValueChange = { onIntent(SettingsIntent.LineHeightChanged(it)) },
        )
        SectionDivider()
        SegmentedSettingRow(
            title = stringResource(R.string.settings_reflow_mode),
            options = ReflowMode.entries,
            selected = settings.reflowMode,
            labelRes = ReflowMode::labelRes,
            onSelect = { onIntent(SettingsIntent.ReflowModeChanged(it)) },
        )
        SectionDivider()
        SwitchRow(
            title = stringResource(R.string.settings_tap_to_turn),
            summary = stringResource(R.string.settings_tap_to_turn_summary),
            checked = settings.tapToTurnPages,
            onCheckedChange = { onIntent(SettingsIntent.TapToTurnToggled(it)) },
        )
        SectionDivider()
        SegmentedSettingRow(
            title = stringResource(R.string.settings_page_fit),
            options = PageFitMode.entries,
            selected = settings.pageFitMode,
            labelRes = PageFitMode::labelRes,
            onSelect = { onIntent(SettingsIntent.PageFitChanged(it)) },
        )
        SectionDivider()
        SegmentedSettingRow(
            title = stringResource(R.string.settings_reading_direction),
            options = ReadingDirection.entries,
            selected = settings.readingDirection,
            labelRes = ReadingDirection::labelRes,
            onSelect = { onIntent(SettingsIntent.ReadingDirectionChanged(it)) },
        )
        SectionDivider()
        SwitchRow(
            title = stringResource(R.string.settings_keep_screen_on),
            summary = stringResource(R.string.settings_keep_screen_on_summary),
            checked = settings.keepScreenOn,
            onCheckedChange = { onIntent(SettingsIntent.KeepScreenOnToggled(it)) },
        )
        SectionDivider()
        SwitchRow(
            title = stringResource(R.string.settings_show_progress),
            summary = stringResource(R.string.settings_show_progress_summary),
            checked = settings.showProgressIndicator,
            onCheckedChange = { onIntent(SettingsIntent.ShowProgressToggled(it)) },
        )
        SectionDivider()
        SwitchRow(
            title = stringResource(R.string.settings_page_snapping),
            summary = stringResource(R.string.settings_page_snapping_summary),
            checked = settings.pageSnapping,
            onCheckedChange = { onIntent(SettingsIntent.PageSnappingToggled(it)) },
        )
    }
}

/** Version, supported formats, and where the app's files actually live. */
@Composable
private fun AboutSection(appVersionName: String) {
    SettingsSection(title = stringResource(R.string.settings_section_about)) {
        ListItem(
            headlineContent = { Text(text = stringResource(R.string.settings_about_version_title)) },
            supportingContent = {
                Text(
                    text = appVersionName.ifBlank {
                        stringResource(R.string.settings_about_version_unknown)
                    },
                )
            },
        )
        SectionDivider()
        ListItem(
            headlineContent = { Text(text = stringResource(R.string.settings_about_formats_title)) },
            // Built from the domain's format list rather than typed out here, so this row cannot
            // promise a format the app does not actually open.
            supportingContent = { Text(text = SUPPORTED_FORMATS) },
        )
        SectionDivider()
        ListItem(
            headlineContent = { Text(text = stringResource(R.string.settings_about_storage_title)) },
            supportingContent = { Text(text = stringResource(R.string.settings_about_storage_body)) },
        )
    }
}

/** The one destructive action on the screen, so it sits apart from every section and asks twice. */
@Composable
private fun ResetSection(onClick: () -> Unit) {
    Column {
        OutlinedButton(
            onClick = onClick,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.settings_reset))
        }
        Text(
            text = stringResource(R.string.settings_reset_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
    }
}

@Composable
private fun ResetConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_reset_dialog_title)) },
        text = { Text(text = stringResource(R.string.settings_reset_dialog_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.settings_reset_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(CoreUiR.string.ui_cancel))
            }
        },
    )
}

/**
 * A titled group of rows.
 *
 * The header is a plain `Text` rather than a `ListItem` because a card is only as tall as its rows
 * — putting the header inside would make the card's background run behind it and turn a section
 * separator into something that looks like a setting.
 */
@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            // Indented to sit above the *text* of the rows below, which the card itself insets.
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

/**
 * The divider between two rows of the same card.
 *
 * Inset to match the rows' text so it reads as "these are separate settings" rather than as the
 * card having been sliced in two.
 */
@Composable
private fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

/**
 * A row whose value is one of a fixed, small set of short labels.
 *
 * Labels are short by contract — the segmented row gives every option an equal share of one line
 * and does not scroll, so a long label would be clipped rather than wrapped.
 */
@Composable
private fun <T> SegmentedSettingRow(
    title: String,
    options: List<T>,
    selected: T,
    @StringRes labelRes: (T) -> Int,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = { Text(text = stringResource(labelRes(option))) },
                )
            }
        }
    }
}

/** A row whose value is picked from a menu — for sets too large to sit side by side. */
@Composable
private fun <T> DropdownSettingRow(
    title: String,
    options: List<T>,
    selected: T,
    @StringRes labelRes: (T) -> Int,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        ListItem(
            headlineContent = { Text(text = title) },
            supportingContent = { Text(text = stringResource(labelRes(selected))) },
            trailingContent = {
                IconButton(onClick = { expanded = true }) {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = stringResource(R.string.settings_show_options),
                    )
                }
            },
            // The whole row opens the menu, not just the arrow: a full-width row that only responds
            // on a 48dp target is the kind of thing users read as "the tap did not register".
            modifier = Modifier.clickable { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(labelRes(option))) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/**
 * A row whose value is a continuous multiplier.
 *
 * The current value is shown next to the title because a slider's thumb is not a readout: without
 * it, "slightly larger" and "much larger" look the same once the row scrolls away from the default.
 */
@Composable
private fun SliderSettingRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            // SpaceBetween rather than absolute alignment: in RTL the title belongs on the right,
            // and Compose flips the row's main axis for the ambient layout direction.
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_scale_value, formatScale(value)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A row with an on/off value; the whole row toggles, not just the switch. */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    ListItem(
        headlineContent = { Text(text = title) },
        supportingContent = { summary?.let { Text(text = it) } },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = modifier.clickable { onCheckedChange(!checked) },
    )
}

/**
 * The formats the app opens, taken from the domain so the list and the decoders cannot drift.
 *
 * Formatted here rather than in the ViewModel because it is presentation — the same data would be a
 * bulleted list on a tablet — and the separator is punctuation, not prose, so it needs no
 * translation of its own.
 */
private val SUPPORTED_FORMATS: String =
    BookFormat.entries.joinToString(separator = " · ") { format -> format.displayName }

/**
 * `1.0`-style multiplier text.
 *
 * Formatted with the default locale so the decimal separator and the digit shapes follow the
 * language the user picked, including Arabic-Indic digits.
 */
private fun formatScale(scale: Float): String = String.format(Locale.getDefault(), "%.1f", scale)

@StringRes
private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

@StringRes
private fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.ARABIC -> R.string.settings_language_arabic
    AppLanguage.ENGLISH -> R.string.settings_language_english
    AppLanguage.SYSTEM -> R.string.settings_language_system
}

@StringRes
private fun ViewMode.labelRes(): Int = when (this) {
    ViewMode.GRID -> R.string.settings_view_grid
    ViewMode.LIST -> R.string.settings_view_list
}

@StringRes
private fun LibrarySort.labelRes(): Int = when (this) {
    LibrarySort.RECENTLY_READ -> R.string.settings_sort_recently_read
    LibrarySort.RECENTLY_ADDED -> R.string.settings_sort_recently_added
    LibrarySort.TITLE_ASC -> R.string.settings_sort_title_asc
    LibrarySort.TITLE_DESC -> R.string.settings_sort_title_desc
    LibrarySort.AUTHOR -> R.string.settings_sort_author
}

@StringRes
private fun ReaderFont.labelRes(): Int = when (this) {
    ReaderFont.SYSTEM -> R.string.settings_font_system
    ReaderFont.SERIF -> R.string.settings_font_serif
    ReaderFont.SANS_SERIF -> R.string.settings_font_sans_serif
    ReaderFont.MONOSPACE -> R.string.settings_font_monospace
}

@StringRes
private fun PageFitMode.labelRes(): Int = when (this) {
    PageFitMode.WIDTH -> R.string.settings_fit_width
    PageFitMode.PAGE -> R.string.settings_fit_page
    PageFitMode.ACTUAL_SIZE -> R.string.settings_fit_actual
}

@StringRes
private fun ReflowMode.labelRes(): Int = when (this) {
    ReflowMode.SCROLL -> R.string.settings_reflow_scroll
    ReflowMode.PAGED -> R.string.settings_reflow_paged
}

@StringRes
private fun ReadingDirection.labelRes(): Int = when (this) {
    ReadingDirection.SYSTEM -> R.string.settings_direction_system
    ReadingDirection.LEFT_TO_RIGHT -> R.string.settings_direction_ltr
    ReadingDirection.RIGHT_TO_LEFT -> R.string.settings_direction_rtl
}
