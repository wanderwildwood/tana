package com.wanderwildwood.tana.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import androidx.compose.foundation.lazy.rememberLazyListState
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.tana.R
import java.text.NumberFormat

/**
 * A spreadsheet, looked through: a line of words to find at the top, and the rows holding
 * them beneath. A table as wide as a sheet does not fit on a phone, so each row is a few lines
 * of what is in it, and tapping one shows it whole, every heading beside its value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetScreen(view: SheetView, vm: BrowserViewModel) {
    // Which row of the sheet is open, by its place in the sheet; -1 for none.
    var open by rememberSaveable(view.entry.loc, view.at) { mutableStateOf(-1) }
    val sheet = view.sheet
    val row = sheet?.body?.getOrNull(open)

    val androidView = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Back puts the keyboard away first, then closes the row, then the sheet. See KeyboardFirst.
    BackHandler {
        val up = ViewCompat.getRootWindowInsets(androidView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        when {
            up -> {
                keyboard?.hide()
                focusManager.clearFocus()
            }
            row != null -> open = -1
            else -> vm.closeSheet()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = view.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    BarButton(Icons.Back, stringResource(R.string.cd_back)) { if (row != null) open = -1 else vm.closeSheet() }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when {
                view.failure != null -> Line(stringResource(R.string.sheet_failed))
                sheet == null -> Line(stringResource(R.string.folder_reading))
                row != null -> WholeRow(sheet.headings, row)
                else -> Rows(view, vm, onOpen = { open = it })
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Rows(view: SheetView, vm: BrowserViewModel, onOpen: (Int) -> Unit) {
    val sheets = view.sheets.orEmpty()
    val sheet = view.sheet ?: return
    val body = sheet.body
    val focusManager = LocalFocusManager.current
    val count = NumberFormat.getIntegerInstance()

    // The sheets as a line of their names that wraps, the one shown in bold, as the kinds
    // are on the search page. Only when there is more than one.
    if (sheets.size > 1) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
            sheets.forEachIndexed { i, s ->
                TextMMD(
                    text = s.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (i == view.at) FontWeight.Bold else null,
                    textDecoration = if (i == view.at) TextDecoration.Underline else null,
                    modifier = Modifier.clickable { vm.showSheet(i) }.padding(horizontal = 6.dp, vertical = 8.dp),
                )
            }
        }
    }
    // Typed into here and passed on, so the cursor keeps up with the typing on a slow panel.
    var words by rememberSaveable(view.entry.loc) { mutableStateOf(view.words) }
    TextFieldMMD(
        value = words,
        onValueChange = {
            words = it
            vm.find(it)
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        singleLine = true,
        placeholder = { TextMMD(text = stringResource(R.string.sheet_find), style = MaterialTheme.typography.bodyMedium) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
    val found = view.found
    val shown = found ?: body.indices.toList()
    Line(
        listOfNotNull(
            if (view.finding) {
                stringResource(R.string.sheet_finding)
            } else if (found == null) {
                pluralStringResource(R.plurals.sheet_rows, body.size, count.format(body.size))
            } else {
                pluralStringResource(R.plurals.sheet_found, body.size, count.format(found.size), count.format(body.size))
            },
            if (sheet.cut) stringResource(R.string.sheet_cut, count.format(body.size)) else null,
        ).joinToString(" "),
    )
    HorizontalDividerMMD()
    val rowsState = rememberLazyListState()
    Box(Modifier.weight(1f)) {
        when {
            body.isEmpty() -> Line(stringResource(R.string.sheet_empty))
            !view.finding && found?.isEmpty() == true -> Line(stringResource(R.string.search_none))
            else -> ListJumpBox(rowsState, ListJump.Pages) {
                PagedColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), state = rowsState) {
                    items(shown, key = { it }) { i -> RowLines(body[i]) { onOpen(i) } }
                }
            }
        }
    }
}

/** One row in the list: its first cell, then the rest after it, as much as fits in a few lines. */
@Composable
private fun RowLines(cells: List<String>, onPress: () -> Unit) {
    val filled = cells.map { it.trim() }.filter { it.isNotEmpty() }
    Column(Modifier.fillMaxWidth().clickable(onClick = onPress).padding(horizontal = 16.dp, vertical = 10.dp)) {
        TextMMD(
            text = filled.firstOrNull().orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (filled.size > 1) {
            TextMMD(
                text = filled.drop(1).joinToString(" · ") { it.replace('\n', ' ') },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDividerMMD(thickness = 0.5.dp)
}

/** A row opened: each heading over its value, the empty ones left out. */
@Composable
private fun WholeRow(headings: List<String>, cells: List<String>) {
    val pairs = cells.mapIndexedNotNull { i, value ->
        if (value.isBlank()) return@mapIndexedNotNull null
        val heading = headings.getOrNull(i)?.trim().orEmpty()
        (heading.ifEmpty { stringResource(R.string.sheet_column, letters(i)) }) to value
    }
    val cellsState = rememberLazyListState()
    ListJumpBox(cellsState, ListJump.Pages) {
        PagedColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), state = cellsState) {
            items(pairs.size) { i ->
                val (heading, value) = pairs[i]
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    TextMMD(text = heading, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    TextMMD(text = value, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDividerMMD(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun Line(text: String) {
    TextMMD(text = text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/** 0 → A, 25 → Z, 26 → AA: a column as the spreadsheet itself names it. */
internal fun letters(column: Int): String {
    var n = column + 1
    val out = StringBuilder()
    while (n > 0) {
        val r = (n - 1) % 26
        out.insert(0, 'A' + r)
        n = (n - 1) / 26
    }
    return out.toString()
}
