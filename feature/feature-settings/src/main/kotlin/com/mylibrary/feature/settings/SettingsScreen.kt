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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mylibrary.core.domain.model.AppFont
import com.mylibrary.core.domain.model.AppLanguage
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.ColorSource
import com.mylibrary.core.domain.model.PageFitMode
import com.mylibrary.core.domain.model.PageTurnEffect
import com.mylibrary.core.domain.model.ReaderFont
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.model.ReadingDirection
import com.mylibrary.core.domain.model.ReaderLayout
import com.mylibrary.core.domain.model.ThemeMode
import com.mylibrary.core.domain.usecase.UpdateSettingsUseCase
import com.mylibrary.core.ui.component.ChoiceRow
import com.mylibrary.core.ui.component.FeatureScaffold
import com.mylibrary.core.ui.component.SectionHeader
import com.mylibrary.core.ui.format.appLocale
import com.mylibrary.core.ui.mvi.ObserveEffects
import com.mylibrary.core.ui.theme.Spacing
import com.mylibrary.core.ui.theme.appFontFamily
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
fun SettingsRoute(
    onOpenColorSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        onOpenColorSetup = onOpenColorSetup,
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
    onOpenColorSetup: () -> Unit = {},
) {
    // A screen rendered without the route (a preview, or a UI test) still gets a host to render,
    // so the layout is identical in both cases; it simply never receives a message.
    SettingsContent(
        state = state,
        onIntent = onIntent,
        onOpenColorSetup = onOpenColorSetup,
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
 *
 * It carries its own `TopAppBar`, which the other two top-level destinations do not need to think
 * about: the library and the search screen each had one, so settings opened straight into a list of
 * switches with nothing naming the screen. Coming back from the reader to a page whose first line is
 * "Appearance" reads as a lost place rather than as a destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    onOpenColorSetup: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    // Which dialog is open is deliberately *not* part of `SettingsUiState`: it is not a preference,
    // it must not outlive the screen, and keeping it out means the state stays a faithful snapshot
    // of what is stored — a reset confirmation left open across a process death would be a lie.
    var confirmingReset by remember { mutableStateOf(false) }

    FeatureScaffold(
        modifier = modifier,
        topBar = { SettingsTopBar() },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.Large,
                end = Spacing.Large,
                top = Spacing.Large,
                bottom = Spacing.Huge,
            ),
            // Sections are spaced by the list rather than by each section's own trailing padding, so
            // the last one does not leave a gap above the reset button.
            verticalArrangement = Arrangement.spacedBy(Spacing.XLarge),
        ) {
            item { AppearanceSection(state.settings, onIntent, onOpenColorSetup) }
            item { LanguageSection(state.settings, onIntent) }
            item { ReadingSection(state.settings, onIntent) }
            item { AboutSection(state.appVersionName) }
            item { ResetSection(onClick = { confirmingReset = true }) }
        }
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

/** The screen's own top bar, so the destination names itself like the other two do. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(modifier: Modifier = Modifier) {
    TopAppBar(
        title = { Text(text = stringResource(R.string.settings_title)) },
        modifier = modifier,
    )
}

/** Theme mode, the app's colour, and the face the interface is set in. */
@Composable
private fun AppearanceSection(
    settings: ReaderSettings,
    onIntent: (SettingsIntent) -> Unit,
    onOpenColorSetup: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
        ChoiceSettingRow(
            title = stringResource(R.string.settings_theme_mode),
            options = ThemeMode.entries,
            selected = settings.themeMode,
            labelRes = ThemeMode::labelRes,
            onSelect = { onIntent(SettingsIntent.ThemeModeChanged(it)) },
        )
        SectionDivider()
        // A row that opens the setup rather than a control that changes something here. The colour
        // is a palette, not a switch, and choosing one means looking at it: the setup screen draws
        // seven of them at once, in the reader's own language, with a preview of the interface
        // underneath. A dropdown of colour names in a settings list would be asking the reader to
        // pick blind, and then to trust that "Amber" was what they had in mind.
        ListItem(
            headlineContent = { Text(text = stringResource(R.string.settings_appearance_colour)) },
            supportingContent = {
                Text(
                    text = stringResource(R.string.settings_appearance_colour_summary),
                )
            },
            trailingContent = {
                Text(
                    text = stringResource(settings.colorSource.labelRes()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            modifier = Modifier.clickable(onClick = onOpenColorSetup),
        )
        SectionDivider()
        UiFontSettingRow(
            selected = settings.uiFont,
            onSelect = { onIntent(SettingsIntent.UiFontChanged(it)) },
        )
    }
}

/** The app's own language, which overrides the device's — hence SYSTEM being one of the choices. */
@Composable
private fun LanguageSection(settings: ReaderSettings, onIntent: (SettingsIntent) -> Unit) {
    SettingsSection(title = stringResource(R.string.settings_section_language)) {
        ChoiceSettingRow(
            title = stringResource(R.string.settings_language),
            options = AppLanguage.entries,
            selected = settings.language,
            labelRes = AppLanguage::labelRes,
            onSelect = { onIntent(SettingsIntent.LanguageChanged(it)) },
        )
    }
}

/**
 * The reading defaults, in three groups rather than one list.
 *
 * The list used to be flat, which offered the reading font to someone who only reads comics and
 * page fit to someone who only reads novels, with nothing saying which was which. The groups are
 * the reader's own rule — a control belongs where it is honoured — so the same three questions are
 * answered in the same order here as in the reader's settings sheet.
 */
@Composable
private fun ReadingSection(settings: ReaderSettings, onIntent: (SettingsIntent) -> Unit) {
    TextReadingSection(settings, onIntent)
    PageReadingSection(settings, onIntent)
    SharedReadingSection(settings, onIntent)
}

/** Settings that only mean something for reflowable text. */
@Composable
private fun TextReadingSection(settings: ReaderSettings, onIntent: (SettingsIntent) -> Unit) {
    SettingsSection(title = stringResource(R.string.settings_section_reading_text)) {
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
    }
}

/** Settings that only mean something for a document made of page images. */
@Composable
private fun PageReadingSection(settings: ReaderSettings, onIntent: (SettingsIntent) -> Unit) {
    SettingsSection(title = stringResource(R.string.settings_section_reading_pages)) {
        ChoiceSettingRow(
            title = stringResource(R.string.settings_page_fit),
            options = PageFitMode.entries,
            selected = settings.pageFitMode,
            labelRes = PageFitMode::labelRes,
            onSelect = { onIntent(SettingsIntent.PageFitChanged(it)) },
        )
        SectionDivider()
        // Three options, so this renders as a scrolling chip row rather than a segmented one: the
        // English labels ("Page curl", "Slide", "Fade") are the widest set on this screen, and a
        // segmented row would give each of them a third of a phone.
        ChoiceSettingRow(
            title = stringResource(R.string.settings_page_turn_effect),
            options = PageTurnEffect.entries,
            selected = settings.pageTurnEffect,
            labelRes = PageTurnEffect::labelRes,
            onSelect = { onIntent(SettingsIntent.PageTurnEffectChanged(it)) },
        )
        SectionDivider()
        SwitchRow(
            title = stringResource(R.string.settings_bubble_zoom),
            summary = stringResource(R.string.settings_bubble_zoom_summary),
            checked = settings.bubbleZoom,
            onCheckedChange = { onIntent(SettingsIntent.BubbleZoomToggled(it)) },
        )
    }
}

/** Settings every document obeys, whichever format it is in. */
@Composable
private fun SharedReadingSection(settings: ReaderSettings, onIntent: (SettingsIntent) -> Unit) {
    SettingsSection(title = stringResource(R.string.settings_section_reading_all)) {
        ChoiceSettingRow(
            title = stringResource(R.string.settings_layout),
            options = ReaderLayout.entries,
            selected = settings.layout,
            labelRes = ReaderLayout::labelRes,
            onSelect = { onIntent(SettingsIntent.LayoutChanged(it)) },
        )
        SectionDivider()
        ChoiceSettingRow(
            title = stringResource(R.string.settings_reading_direction),
            options = ReadingDirection.entries,
            selected = settings.readingDirection,
            labelRes = ReadingDirection::labelRes,
            onSelect = { onIntent(SettingsIntent.ReadingDirectionChanged(it)) },
        )
        SectionDivider()
        SwitchRow(
            title = stringResource(R.string.settings_tap_to_turn),
            summary = stringResource(R.string.settings_tap_to_turn_summary),
            checked = settings.tapToTurnPages,
            onCheckedChange = { onIntent(SettingsIntent.TapToTurnToggled(it)) },
        )
        // Absent while side taps do nothing at all, matching the reader's own panel.
        if (settings.tapToTurnPages) {
            SectionDivider()
            SwitchRow(
                title = stringResource(R.string.settings_reverse_tap),
                summary = stringResource(R.string.settings_reverse_tap_summary),
                checked = settings.reverseTapZones,
                onCheckedChange = { onIntent(SettingsIntent.ReverseTapZonesToggled(it)) },
            )
        }
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
 * The header comes from `:core:core-ui` so that the three screens which group rows under a title —
 * this one, the search results and the book details page — indent and weight it identically. It sits
 * *outside* the card on purpose: a card is only as tall as its rows, so a header inside one would
 * drag the card's background behind it and turn a section separator into something that looks like a
 * setting the user can press.
 */
@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        SectionHeader(title = title)
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
    HorizontalDivider(modifier = Modifier.padding(horizontal = Spacing.Large))
}

/**
 * A row whose value is one of a fixed, small set of labels.
 *
 * Delegates to `:core:core-ui`'s `ChoiceRow`, which lays two options out as a segmented row and
 * three or more as a horizontally scrolling row of chips. The settings screen used to render every
 * set as a segmented row, which gives each option an equal share of one line and does not scroll —
 * so a label that did not fit was wrapped or clipped. English labels are consistently longer than
 * their Arabic counterparts ("System language" against لغة النظام, "Left to right" against من
 * اليسار) and were the ones being cut. This is the same control as the reader's panel now, rather
 * than a second implementation of it that had drifted in padding and label style.
 */
@Composable
private fun <T> ChoiceSettingRow(
    title: String,
    options: List<T>,
    selected: T,
    @StringRes labelRes: (T) -> Int,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Medium)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.Small))
        ChoiceRow(
            options = options,
            selected = selected,
            onSelect = onSelect,
            label = { option -> Text(text = stringResource(labelRes(option))) },
        )
    }
}

/**
 * The interface's own typeface.
 *
 * Each option is drawn *in the face it names*. A font menu that lists "Amiri" in the system font
 * makes the user choose blind, which is the one thing a font picker must not do.
 */
@Composable
private fun UiFontSettingRow(selected: AppFont, onSelect: (AppFont) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Medium)) {
        Text(
            text = stringResource(R.string.settings_ui_font),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.Small))
        ChoiceRow(
            options = AppFont.entries,
            selected = selected,
            onSelect = onSelect,
            label = { font ->
                Text(
                    text = stringResource(font.labelRes()),
                    style = TextStyle(fontFamily = appFontFamily(font) ?: FontFamily.Default),
                )
            },
        )
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
    val locale = appLocale()
    Column(modifier = modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Medium)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            // SpaceBetween rather than absolute alignment: in RTL the title belongs on the right,
            // and Compose flips the row's main axis for the ambient layout direction.
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                // A weighted, ellipsised title rather than an intrinsic one: "Line spacing" and
                // "Keep screen awake" are long enough in English that the pair used to squeeze the
                // value beside them, and a readout pushed against its label is unreadable at a
                // glance — which is the only way a readout is ever read.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = stringResource(R.string.settings_scale_value, formatScale(value, locale)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = Spacing.Medium),
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
 * Formatted with the *app's* locale, not the device's. `Locale.getDefault()` follows the system, so
 * an Arabic device running the app in English produced Arabic-Indic digits — "١٫٥×" — inside an
 * otherwise English screen, which the comment here used to claim was the desired behaviour. The
 * locale is passed in from the composition, where the in-app language has already been applied.
 */
private fun formatScale(scale: Float, locale: Locale): String =
    String.format(locale, "%.1f", scale)

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
private fun ReaderFont.labelRes(): Int = when (this) {
    ReaderFont.SYSTEM -> R.string.settings_font_system
    ReaderFont.SERIF -> R.string.settings_font_serif
    ReaderFont.SANS_SERIF -> R.string.settings_font_sans_serif
    ReaderFont.MONOSPACE -> R.string.settings_font_monospace

    // The bundled Arabic faces, named once in `:core:core-ui` and shared with the reader's panel:
    // the same three names are offered in both places, and a second copy of them here is a second
    // thing to keep in step for no benefit.
    ReaderFont.AMIRI -> CoreUiR.string.ui_font_amiri
    ReaderFont.PLEX_ARABIC -> CoreUiR.string.ui_font_plex_arabic
    ReaderFont.REEM_KUFI -> CoreUiR.string.ui_font_reem_kufi
}

@StringRes
private fun ColorSource.labelRes(): Int = when (this) {
    ColorSource.WALLPAPER -> R.string.settings_color_wallpaper
    ColorSource.TEAL -> R.string.settings_color_teal
    ColorSource.PURPLE -> R.string.settings_color_purple
    ColorSource.BLUE -> R.string.settings_color_blue
    ColorSource.GREEN -> R.string.settings_color_green
    ColorSource.AMBER -> R.string.settings_color_amber
    ColorSource.ROSE -> R.string.settings_color_rose
}

@StringRes
private fun AppFont.labelRes(): Int = when (this) {
    AppFont.SYSTEM -> CoreUiR.string.ui_font_system
    AppFont.AMIRI -> CoreUiR.string.ui_font_amiri
    AppFont.PLEX_ARABIC -> CoreUiR.string.ui_font_plex_arabic
    AppFont.REEM_KUFI -> CoreUiR.string.ui_font_reem_kufi
}

@StringRes
private fun PageTurnEffect.labelRes(): Int = when (this) {
    PageTurnEffect.CURL -> R.string.settings_page_turn_curl
    PageTurnEffect.SLIDE -> R.string.settings_page_turn_slide
    PageTurnEffect.FADE -> R.string.settings_page_turn_fade
}

@StringRes
private fun PageFitMode.labelRes(): Int = when (this) {
    PageFitMode.WIDTH -> R.string.settings_fit_width
    PageFitMode.PAGE -> R.string.settings_fit_page
    PageFitMode.ACTUAL_SIZE -> R.string.settings_fit_actual
}

@StringRes
private fun ReaderLayout.labelRes(): Int = when (this) {
    ReaderLayout.SCROLL -> R.string.settings_layout_scroll
    ReaderLayout.PAGED -> R.string.settings_layout_paged
}

@StringRes
private fun ReadingDirection.labelRes(): Int = when (this) {
    ReadingDirection.SYSTEM -> R.string.settings_direction_system
    ReadingDirection.LEFT_TO_RIGHT -> R.string.settings_direction_ltr
    ReadingDirection.RIGHT_TO_LEFT -> R.string.settings_direction_rtl
}
