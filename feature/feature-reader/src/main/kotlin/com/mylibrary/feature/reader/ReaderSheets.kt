package com.mylibrary.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mylibrary.core.domain.model.PageFitMode
import com.mylibrary.core.domain.model.PageTurnEffect
import com.mylibrary.core.domain.model.ReaderFont
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.ReaderLayout
import com.mylibrary.core.domain.model.SearchHit
import com.mylibrary.core.domain.model.ThemeMode
import com.mylibrary.core.domain.model.TocEntry
import com.mylibrary.core.ui.component.ChoiceRow
import com.mylibrary.core.ui.component.EmptyState
import com.mylibrary.core.ui.theme.Spacing
import com.mylibrary.core.ui.theme.readerFontFamily

/**
 * The four panels that slide over the page, plus the password prompt.
 *
 * All of them are `ModalBottomSheet`s driven from [ReaderUiState.openPanel], so only one can be open
 * at a time and the reader's pager never has to reason about stacking. Each panel emits intents
 * rather than mutating anything itself, which keeps every state change in one place.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderPanelSheet(
    panel: ReaderPanel,
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { onIntent(ReaderIntent.ClosePanel) },
        sheetState = sheetState,
    ) {
        when (panel) {
            ReaderPanel.TABLE_OF_CONTENTS -> TableOfContentsPanel(
                outline = state.outline,
                onEntryClick = { onIntent(ReaderIntent.JumpTo(it.locator)) },
            )

            ReaderPanel.BOOKMARKS -> BookmarksPanel(
                bookmarks = state.bookmarks,
                onBookmarkClick = { onIntent(ReaderIntent.JumpTo(it.locator)) },
                onDelete = { onIntent(ReaderIntent.DeleteBookmark(it)) },
            )

            ReaderPanel.SETTINGS -> ReaderSettingsPanel(
                state = state,
                onIntent = onIntent,
            )

            ReaderPanel.SEARCH -> SearchPanel(
                state = state,
                onIntent = onIntent,
            )
        }
    }
}

/**
 * The document's table of contents, indented by nesting level.
 *
 * A document with no outline says so rather than showing an empty sheet — an empty panel reads as a
 * bug, while "this file has no table of contents" reads as a fact about the file.
 */
@Composable
private fun TableOfContentsPanel(
    outline: List<TocEntry>,
    onEntryClick: (TocEntry) -> Unit,
) {
    if (outline.isEmpty()) {
        EmptyState(
            icon = androidx.compose.material.icons.Icons.Filled.Bookmark,
            title = stringResource(R.string.reader_no_toc_title),
            message = stringResource(R.string.reader_no_toc_message),
            modifier = Modifier.heightIn(min = 240.dp),
        )
        return
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(items = outline.flatten(), key = { entry -> "${entry.level}:${entry.title}:${entry.locator}" }) { entry ->
            ListItem(
                headlineContent = {
                    Text(
                        text = entry.title,
                        style = if (entry.level == 0) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // Indentation uses `start` so it mirrors correctly in a right-to-left document.
                    .padding(start = (entry.level * 16).dp)
                    .clickable { onEntryClick(entry) },
            )
        }
    }
}

/** Flattens a nested outline into display order, carrying each entry's depth. */
private fun List<TocEntry>.flatten(): List<TocEntry> = flatMap { entry ->
    listOf(entry) + entry.children.let { children ->
        children.map { child -> child.copy(level = entry.level + 1) }.flatten()
    }
}

@Composable
private fun BookmarksPanel(
    bookmarks: List<com.mylibrary.core.domain.model.Bookmark>,
    onBookmarkClick: (com.mylibrary.core.domain.model.Bookmark) -> Unit,
    onDelete: (Long) -> Unit,
) {
    if (bookmarks.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Bookmark,
            title = stringResource(R.string.reader_no_bookmarks_title),
            message = stringResource(R.string.reader_no_bookmarks_message),
            modifier = Modifier.heightIn(min = 240.dp),
        )
        return
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(items = bookmarks, key = { it.id }) { bookmark ->
            ListItem(
                headlineContent = {
                    Text(bookmark.label ?: stringResource(R.string.reader_bookmark))
                },
                supportingContent = {
                    val text = bookmark.note ?: bookmark.excerpt
                    if (!text.isNullOrBlank()) {
                        Text(text, maxLines = 2)
                    }
                },
                trailingContent = {
                    IconButton(onClick = { onDelete(bookmark.id) }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(com.mylibrary.core.ui.R.string.ui_delete),
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBookmarkClick(bookmark) },
            )
        }
    }
}

/**
 * Which blocks of reading settings the current presentation actually honours.
 *
 * The sheet used to branch on `state.isPaged` — whether the *file* is made of page images — and that
 * is the wrong question. What decides whether a control means anything is what the reader is doing
 * with the document: a paged EPUB turns its pages and has a turn effect to choose, and a PDF laid
 * out as a continuous scroll has no pages to turn and no page fit to apply. The four combinations
 * are pinned by a test rather than by reading four branches.
 */
internal data class ReaderSettingsScope(
    /** Reflowed text: the reading font, its size, and the leading. */
    val text: Boolean,
    /** Discrete pages in front of the reader: the page-turn effect. */
    val pages: Boolean,
    /**
     * How a page is scaled into the frame it is shown in.
     *
     * Paged page images only. A page in a scrolling column is simply as wide as the screen — there
     * is no second frame for it to be fitted into — so the setting has nothing to say there.
     */
    val pageFit: Boolean,
    /**
     * Speech-bubble zoom.
     *
     * Any page-image document, in *either* layout: a bubble is unreadable at page width whether the
     * page is turned or scrolled past, and both layouts answer a double-tap on one by opening it at
     * full size. Gating this with [pageFit] would hide a control the scroll layout honours.
     */
    val panels: Boolean,
)

/** The scope for [state]'s current document and layout. */
internal fun readerSettingsScope(state: ReaderUiState): ReaderSettingsScope = ReaderSettingsScope(
    text = !state.isPageImages,
    pages = state.hasPages,
    pageFit = state.isPageImages && state.hasPages,
    panels = state.isPageImages,
)

/**
 * The reading settings panel.
 *
 * Every control writes straight through to the settings store, so a change is visible on the page
 * behind the sheet as it is made — the sheet is a live preview rather than a form to submit.
 *
 * The groups are ordered by how much of the document they apply to: the layout every document
 * obeys, then the controls only reflowed text has, then the ones only page images have, and last
 * the two that are true whatever is open. The same three questions, in the same order, are asked in
 * the app's own settings screen, so neither surface can teach the reader a different mental model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsPanel(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
) {
    val scope = readerSettingsScope(state)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.XLarge)
            .padding(bottom = Spacing.Huge),
        verticalArrangement = Arrangement.spacedBy(Spacing.Large),
    ) {
        SettingGroup(title = stringResource(R.string.reader_settings_theme)) {
            ChoiceRow(
                options = ThemeMode.entries,
                selected = state.settings.themeMode,
                onSelect = { onIntent(ReaderIntent.SetThemeMode(it)) },
                label = { mode -> Text(themeModeLabel(mode)) },
            )
        }

        HorizontalDivider()

        // For every document, because every document has a layout — a PDF can be scrolled and an
        // EPUB can be paged, and this is the one control that says which.
        SettingGroup(title = stringResource(R.string.reader_settings_layout)) {
            ChoiceRow(
                options = ReaderLayout.entries,
                selected = state.settings.layout,
                onSelect = { onIntent(ReaderIntent.SetLayout(it)) },
                label = { layout -> Text(layoutLabel(layout)) },
            )
        }

        if (scope.text) {
            HorizontalDivider()

            SettingGroup(
                title = stringResource(R.string.reader_settings_font),
                // The font choice is the one control here whose options cannot explain themselves in
                // words alone — the whole question is what the letters look like.
                summary = stringResource(R.string.reader_settings_font_summary),
            ) {
                ChoiceRow(
                    options = ReaderFont.entries,
                    selected = state.settings.readerFont,
                    onSelect = { onIntent(ReaderIntent.SetFont(it)) },
                    label = { font ->
                        // Drawn in the face it names. A font menu in the system font makes the
                        // reader pick blind, which defeats the point of offering a choice.
                        Text(
                            text = readerFontLabel(font),
                            style = TextStyle(fontFamily = readerFontFamily(font)),
                        )
                    },
                )
            }

            LabelledSlider(
                label = stringResource(R.string.reader_settings_font_size),
                value = state.settings.fontScale,
                valueRange = FONT_SCALE_RANGE,
                onValueChange = { onIntent(ReaderIntent.SetFontScale(it)) },
                valueLabel = "${(state.settings.fontScale * 100).toInt()}%",
            )

            LabelledSlider(
                label = stringResource(R.string.reader_settings_line_height),
                value = state.settings.lineHeightScale,
                valueRange = LINE_HEIGHT_RANGE,
                onValueChange = { onIntent(ReaderIntent.SetLineHeight(it)) },
                valueLabel = String.format(java.util.Locale.ROOT, "%.2f", state.settings.lineHeightScale),
            )
        }

        if (scope.pages) {
            HorizontalDivider()

            SettingGroup(
                title = stringResource(R.string.reader_settings_page_turn),
                summary = stringResource(R.string.reader_settings_page_turn_summary),
            ) {
                ChoiceRow(
                    options = PageTurnEffect.entries,
                    selected = state.settings.pageTurnEffect,
                    onSelect = { onIntent(ReaderIntent.SetPageTurnEffect(it)) },
                    label = { effect -> Text(pageTurnEffectLabel(effect)) },
                )
            }
        }

        if (scope.pageFit || scope.panels) {
            HorizontalDivider()

            if (scope.pageFit) {
                SettingGroup(title = stringResource(R.string.reader_settings_page_fit)) {
                    ChoiceRow(
                        options = PageFitMode.entries,
                        selected = state.settings.pageFitMode,
                        onSelect = { onIntent(ReaderIntent.SetPageFit(it)) },
                        label = { mode -> Text(pageFitLabel(mode)) },
                    )
                }

                HorizontalDivider()
            }

            SettingGroup(
                title = stringResource(R.string.reader_settings_bubble_zoom),
                summary = stringResource(R.string.reader_settings_bubble_zoom_summary),
            ) {
                Switch(
                    checked = state.settings.bubbleZoom,
                    onCheckedChange = { onIntent(ReaderIntent.SetBubbleZoom(it)) },
                )
            }
        }

        HorizontalDivider()

        SwitchSetting(
            title = stringResource(R.string.reader_settings_keep_awake),
            checked = state.settings.keepScreenOn,
            onCheckedChange = { onIntent(ReaderIntent.SetKeepScreenOn(it)) },
        )

        SwitchSetting(
            title = stringResource(R.string.reader_settings_tap_to_turn),
            checked = state.settings.tapToTurnPages,
            onCheckedChange = { onIntent(ReaderIntent.SetTapToTurnPages(it)) },
        )

        HorizontalDivider()

        ResetReaderSettingsButton(onClick = { onIntent(ReaderIntent.RequestResetSettings) })
    }
}

/**
 * A labelled block of controls inside the settings sheet.
 *
 * One place that decides how a heading sits above its control, so the sheet's rows line up with each
 * other instead of each one nudging itself into position. The summary is optional and sits between
 * the two: a short line saying what the control does to the page, for the settings whose name is not
 * enough — "Zoom into speech bubbles" is clear, *when* it applies is not.
 */
@Composable
private fun SettingGroup(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}

/** A titled on/off control, laid out like every other row in the sheet. */
@Composable
private fun SwitchSetting(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            // Weighted so a long English label wraps within its own half rather than pushing the
            // switch off the row.
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Puts the reading settings back.
 *
 * Behind a confirmation, and the dialog says what will and will not change rather than asking "are
 * you sure?" — the reader has just made a book unreadable and is about to lose whatever else they
 * had set, so the useful thing to tell them is that their theme and their library are untouched.
 */
@Composable
private fun ResetReaderSettingsButton(onClick: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { confirming = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.reader_settings_reset))
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.reader_settings_reset)) },
            text = { Text(stringResource(R.string.reader_settings_reset_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onClick()
                    },
                ) {
                    Text(stringResource(com.mylibrary.core.ui.R.string.ui_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(com.mylibrary.core.ui.R.string.ui_cancel))
                }
            },
        )
    }
}

@Composable
private fun layoutLabel(layout: ReaderLayout): String = stringResource(
    when (layout) {
        ReaderLayout.SCROLL -> R.string.reader_layout_scroll
        ReaderLayout.PAGED -> R.string.reader_layout_paged
    },
)

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    valueLabel: String,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
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

/** Search inside the current document, with the matched run emphasised in each result. */
@Composable
private fun SearchPanel(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.XLarge)) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { onIntent(ReaderIntent.SearchQueryChanged(it)) },
            label = { Text(stringResource(R.string.reader_search_hint)) },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { onIntent(ReaderIntent.SubmitSearch) }) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.reader_search_action),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        when {
            state.isSearching -> ListItem(
                headlineContent = { Text(stringResource(R.string.reader_searching)) },
            )

            state.searchResults.isNotEmpty() -> LazyColumn(
                contentPadding = PaddingValues(vertical = Spacing.Small),
                // The sheet sits at the bottom of the screen and the field is the first thing in it,
                // so the keyboard would otherwise cover every result.
                modifier = Modifier.heightIn(max = 400.dp).imePadding(),
            ) {
                items(items = state.searchResults, key = { hit -> "${hit.locator}:${hit.matchStart}" }) { hit ->
                    SearchResultRow(hit = hit, onClick = { onIntent(ReaderIntent.JumpTo(hit.locator)) })
                }
            }

            state.searchQuery.isNotBlank() -> ListItem(
                headlineContent = { Text(stringResource(R.string.reader_no_results)) },
            )

            else -> ListItem(
                supportingContent = { Text(stringResource(R.string.reader_search_hint_body)) },
                headlineContent = { Text(stringResource(R.string.reader_search_title)) },
            )
        }
    }
}

@Composable
private fun SearchResultRow(hit: SearchHit, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(hit.label.orEmpty()) },
        supportingContent = {
            val highlight = MaterialTheme.colorScheme.primary
            Text(
                text = buildAnnotatedString {
                    val start = hit.matchStart.coerceIn(0, hit.snippet.length)
                    val end = hit.matchEnd.coerceIn(start, hit.snippet.length)
                    append(hit.snippet.substring(0, start))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = highlight)) {
                        append(hit.snippet.substring(start, end))
                    }
                    append(hit.snippet.substring(end))
                },
                maxLines = 3,
            )
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

/**
 * The password prompt for an encrypted document.
 *
 * The password is held only for as long as this dialog is composed and is handed straight to the
 * decoder; it is never written to the database, to preferences or to a saved-state bundle.
 */
@Composable
fun PasswordDialog(
    wasWrong: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (wasWrong) {
                        stringResource(R.string.reader_password_wrong)
                    } else {
                        stringResource(R.string.reader_password_message)
                    },
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = if (showPassword) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    },
                    label = { Text(stringResource(R.string.reader_password_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                FilterChip(
                    selected = showPassword,
                    onClick = { showPassword = !showPassword },
                    label = { Text(stringResource(R.string.reader_password_show)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(password) },
                enabled = password.isNotBlank(),
            ) {
                Text(stringResource(R.string.reader_password_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.mylibrary.core.ui.R.string.ui_cancel))
            }
        },
    )
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.reader_theme_system
        ThemeMode.LIGHT -> R.string.reader_theme_light
        ThemeMode.DARK -> R.string.reader_theme_dark
    },
)

@Composable
private fun readerFontLabel(font: ReaderFont): String = stringResource(
    when (font) {
        ReaderFont.SYSTEM -> R.string.reader_font_system
        ReaderFont.SERIF -> R.string.reader_font_serif
        ReaderFont.SANS_SERIF -> R.string.reader_font_sans
        ReaderFont.MONOSPACE -> R.string.reader_font_mono

        // The bundled Arabic faces are named once, in `:core:core-ui`, and shared with the app's own
        // interface-font setting: the same three names are offered in both places.
        ReaderFont.AMIRI -> com.mylibrary.core.ui.R.string.ui_font_amiri
        ReaderFont.PLEX_ARABIC -> com.mylibrary.core.ui.R.string.ui_font_plex_arabic
        ReaderFont.REEM_KUFI -> com.mylibrary.core.ui.R.string.ui_font_reem_kufi
    },
)

@Composable
private fun pageTurnEffectLabel(effect: PageTurnEffect): String = stringResource(
    when (effect) {
        PageTurnEffect.CURL -> R.string.reader_page_turn_curl
        PageTurnEffect.SLIDE -> R.string.reader_page_turn_slide
        PageTurnEffect.FADE -> R.string.reader_page_turn_fade
    },
)

@Composable
private fun pageFitLabel(mode: PageFitMode): String = stringResource(
    when (mode) {
        PageFitMode.WIDTH -> R.string.reader_fit_width
        PageFitMode.PAGE -> R.string.reader_fit_page
        PageFitMode.ACTUAL_SIZE -> R.string.reader_fit_actual
    },
)

private val FONT_SCALE_RANGE = 0.7f..3.0f
private val LINE_HEIGHT_RANGE = 0.8f..2.5f
