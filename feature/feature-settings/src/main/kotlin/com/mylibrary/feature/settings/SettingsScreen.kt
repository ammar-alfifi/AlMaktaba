package com.mylibrary.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mylibrary.core.domain.model.AppLanguage
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.ColorSource
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.ui.component.ChoiceRow
import com.mylibrary.core.ui.component.FeatureScaffold
import com.mylibrary.core.ui.component.SectionHeader
import com.mylibrary.core.ui.mvi.ObserveEffects
import com.mylibrary.core.ui.theme.Spacing
import kotlinx.coroutines.launch
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
 *
 * **This screen is the interface's, and only the interface's.** It holds the theme, the colour, the
 * language and the version — the things that are true of MyLibrary itself, whatever is open. Every
 * preference that decides what a *book* looks like while it is being read lives in the reader's own
 * settings panel, which is where the reader is when they want it, and where a change is visible on
 * the page behind the sheet as it is made. The two lists used to overlap — this screen offered the
 * reading font, its size, the leading, the page fit and the page-turn effect — which meant the same
 * decision had two homes and neither could claim to be the one that mattered.
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
            item { AppearanceSection(state.settings, onOpenColorSetup) }
            item { LanguageSection(state.settings, onIntent) }
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

/**
 * The app's appearance, as one row that opens the appearance settings.
 *
 * **The theme is no longer offered twice.** This section used to hold a theme picker *and* a row
 * opening the colour setup, and that screen holds a theme picker of its own — so the same decision
 * had two homes on two screens, one of them a navigation trip away from the other. The theme and the
 * colour are one question about how the app looks, and they are answered together where the answer
 * can be seen: the setup screen repaints the whole interface as each is chosen. This row is the way
 * in, and it says which colour is current.
 */
@Composable
private fun AppearanceSection(
    settings: ReaderSettings,
    onOpenColorSetup: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
        // A row that opens the setup rather than controls that change something here. The colour
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
 * The formats the app opens, taken from the domain so the list and the decoders cannot drift.
 *
 * Formatted here rather than in the ViewModel because it is presentation — the same data would be a
 * bulleted list on a tablet — and the separator is punctuation, not prose, so it needs no
 * translation of its own.
 */
private val SUPPORTED_FORMATS: String =
    BookFormat.entries.joinToString(separator = " · ") { format -> format.displayName }

@StringRes
private fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.ARABIC -> R.string.settings_language_arabic
    AppLanguage.ENGLISH -> R.string.settings_language_english
    AppLanguage.SYSTEM -> R.string.settings_language_system
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
