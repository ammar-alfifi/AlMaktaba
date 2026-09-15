package com.mylibrary.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mylibrary.core.domain.model.PageFitMode
import com.mylibrary.core.domain.model.ReaderFont
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.SearchHit
import com.mylibrary.core.domain.model.ThemeMode
import com.mylibrary.core.domain.model.TocEntry
import com.mylibrary.core.ui.component.EmptyState

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
 * The reading settings panel.
 *
 * Every control writes straight through to the settings store, so a change is visible on the page
 * behind the sheet as it is made — the sheet is a live preview rather than a form to submit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsPanel(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.reader_settings_theme),
            style = MaterialTheme.typography.titleSmall,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.settings.themeMode == mode,
                    onClick = { onIntent(ReaderIntent.SetThemeMode(mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                ) {
                    Text(themeModeLabel(mode))
                }
            }
        }

        if (!state.isPaged) {
            HorizontalDivider()

            Text(
                text = stringResource(R.string.reader_settings_font),
                style = MaterialTheme.typography.titleSmall,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ReaderFont.entries.forEachIndexed { index, font ->
                    SegmentedButton(
                        selected = state.settings.readerFont == font,
                        onClick = { onIntent(ReaderIntent.SetFont(font)) },
                        shape = SegmentedButtonDefaults.itemShape(index, ReaderFont.entries.size),
                    ) {
                        Text(readerFontLabel(font))
                    }
                }
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
        } else {
            HorizontalDivider()

            Text(
                text = stringResource(R.string.reader_settings_page_fit),
                style = MaterialTheme.typography.titleSmall,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                PageFitMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.settings.pageFitMode == mode,
                        onClick = { onIntent(ReaderIntent.SetPageFit(mode)) },
                        shape = SegmentedButtonDefaults.itemShape(index, PageFitMode.entries.size),
                    ) {
                        Text(pageFitLabel(mode))
                    }
                }
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.reader_settings_keep_awake),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = state.settings.keepScreenOn,
                onCheckedChange = { onIntent(ReaderIntent.SetKeepScreenOn(it)) },
            )
        }
    }
}

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
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
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
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.heightIn(max = 400.dp),
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
