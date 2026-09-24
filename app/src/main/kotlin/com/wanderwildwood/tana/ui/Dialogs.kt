package com.wanderwildwood.tana.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.radio_button.RadioButtonMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.wanderwildwood.tana.R
import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.work.Clash
import com.wanderwildwood.tana.work.Names
import com.wanderwildwood.tana.work.Sort
import com.wanderwildwood.tana.work.SortBy

@Composable
private fun Title(text: String) {
    TextMMD(text = text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
}

@Composable
private fun WideButton(label: String, onClick: () -> Unit) {
    OutlinedButtonMMD(onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        TextMMD(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

/** A row in a dialog that is a press, with a rule beneath. */
@Composable
private fun DialogRow(label: String, note: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        TextMMD(text = label, style = MaterialTheme.typography.bodyMedium)
        if (note != null) TextMMD(text = note, style = MaterialTheme.typography.labelSmall)
    }
    HorizontalDividerMMD(thickness = 0.5.dp)
}

/**
 * Typing a name: a new folder, or a new name for something. The name is chosen up to its
 * extension, so typing replaces "holiday" and leaves ".jpg" alone.
 */
@Composable
fun NameDialog(title: String, confirm: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val stem = remember(initial) {
        val ext = Names.extension(initial)
        if (ext.isEmpty()) initial.length else initial.length - ext.length - 1
    }
    var value by remember(initial) { mutableStateOf(TextFieldValue(initial, TextRange(0, stem))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val ok = Names.valid(value.text) != null
    val commit = {
        if (ok) {
            onDone(value.text)
            onDismiss()
        }
    }
    EInkDialog(onDismiss = onDismiss) {
        Title(title)
        Spacer(Modifier.height(14.dp))
        TextFieldMMD(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            // Done on the keyboard finishes it: a keyboard up over a 480-tall panel covers
            // the dialog's own buttons.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedButtonMMD(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = stringResource(R.string.cancel), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButtonMMD(onClick = commit, enabled = ok, modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = confirm, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** How a folder is laid out, and whether hidden files show. A press changes it at once. */
@Composable
fun SortDialog(sort: Sort, showHidden: Boolean, onSort: (SortBy) -> Unit, onHidden: () -> Unit, onDismiss: () -> Unit) {
    EInkDialog(onDismiss = onDismiss) {
        Title(stringResource(R.string.sort_title))
        Spacer(Modifier.height(8.dp))
        listOf(
            Triple(SortBy.NAME, R.string.sort_name, R.string.sort_name_forward to R.string.sort_name_reversed),
            Triple(SortBy.DATE, R.string.sort_date, R.string.sort_date_forward to R.string.sort_date_reversed),
            Triple(SortBy.SIZE, R.string.sort_size, R.string.sort_size_forward to R.string.sort_size_reversed),
            Triple(SortBy.TYPE, R.string.sort_type, R.string.sort_type_forward to R.string.sort_type_reversed),
        ).forEach { (by, label, directions) ->
            val chosen = sort.by == by
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onSort(by) }.padding(vertical = 8.dp),
            ) {
                RadioButtonMMD(selected = chosen, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    TextMMD(text = stringResource(label), style = MaterialTheme.typography.bodyMedium)
                    // Only the order in use says which way round it is; a press on it again
                    // turns it, and that is the one thing the reader needs to see.
                    if (chosen) {
                        TextMMD(
                            text = stringResource(if (sort.reversed) directions.second else directions.first),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        HorizontalDividerMMD(Modifier.padding(vertical = 8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onHidden).padding(vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                TextMMD(text = stringResource(R.string.sort_hidden), style = MaterialTheme.typography.bodyMedium)
                TextMMD(text = stringResource(R.string.sort_hidden_note), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(12.dp))
            SwitchMMD(checked = showHidden, onCheckedChange = null)
        }
        Spacer(Modifier.height(14.dp))
        WideButton(stringResource(R.string.close), onDismiss)
    }
}

/** The less common things to do with what is chosen. Only what applies to the choice shows. */
@Composable
fun MoreDialog(
    chosen: List<Entry>,
    readOnly: Boolean,
    onShare: () -> Unit,
    onCompress: () -> Unit,
    onExtract: () -> Unit,
    onOpenWith: () -> Unit,
    onRename: () -> Unit,
    onInfo: () -> Unit,
    onPin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val one = chosen.singleOrNull()
    EInkDialog(onDismiss = onDismiss) {
        if (chosen.any { !it.isFolder }) DialogRow(stringResource(R.string.action_share), onClick = onShare)
        if (one != null && !one.isFolder) DialogRow(stringResource(R.string.action_open_with), onClick = onOpenWith)
        if (one != null && !readOnly && !one.isFolder && Names.extension(one.name) == "zip") {
            DialogRow(stringResource(R.string.action_extract), onClick = onExtract)
        }
        if (!readOnly) DialogRow(stringResource(R.string.action_compress), onClick = onCompress)
        if (one != null && !readOnly) DialogRow(stringResource(R.string.action_rename), onClick = onRename)
        if (one != null) DialogRow(stringResource(R.string.action_info), onClick = onInfo)
        if (one != null && one.isFolder && !readOnly) DialogRow(stringResource(R.string.action_pin), onClick = onPin)
        Spacer(Modifier.height(14.dp))
        WideButton(stringResource(R.string.close), onDismiss)
    }
}

/**
 * What a thing is. The checksum is on request: it means reading the whole file, and a film
 * on a server is not something to read end to end just because the dialog was opened. It is
 * shown in fours so it can be read against a checksum posted somewhere else.
 */
@Composable
fun InfoDialog(info: Info, onHash: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val e = info.entry
    EInkDialog(onDismiss = onDismiss) {
        Title(e.name)
        Spacer(Modifier.height(10.dp))
        Fact(stringResource(R.string.info_where), info.where)
        if (e.isFolder) {
            val files = info.files
            val bytes = info.bytes
            Fact(
                stringResource(R.string.info_contains),
                if (files == null || bytes == null) stringResource(R.string.info_counting)
                else pluralStringResource(R.plurals.info_size_in_files, files, size(context, bytes), files),
            )
        } else {
            Fact(stringResource(R.string.info_size), size(context, e.size) + "  (" + "%,d".format(e.size) + " B)")
            Fact(stringResource(R.string.info_type), Names.mime(e.name))
        }
        date(context, e.modified)?.let { Fact(stringResource(R.string.info_modified), it) }
        if (!e.isFolder) {
            val hex = info.sha256
            when {
                hex != null -> {
                    TextMMD(text = stringResource(R.string.info_sha256), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    TextMMD(
                        text = hex.chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                info.hashing -> Fact(stringResource(R.string.info_sha256), stringResource(R.string.info_sha256_working))
                else -> DialogRow(stringResource(R.string.info_sha256), stringResource(R.string.info_sha256_work_out), onHash)
            }
        }
        Spacer(Modifier.height(14.dp))
        WideButton(stringResource(R.string.close), onDismiss)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        TextMMD(text = label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        TextMMD(text = value, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Pasting found names already taken. Keeping both is first because it loses nothing;
 * replacing is last and asks again in its own face, because it is the one that deletes.
 */
@Composable
fun ClashDialog(clashing: Clashing, onAnswer: (Clash?) -> Unit) {
    val (armed, pressReplace) = rememberArmed(clashing) { onAnswer(Clash.REPLACE) }
    val n = clashing.names.size
    EInkDialog(onDismiss = { onAnswer(null) }) {
        Title(pluralStringResource(R.plurals.clash_title, n, n, clashing.names.first()))
        if (n > 1) {
            Spacer(Modifier.height(6.dp))
            TextMMD(
                text = clashing.names.take(4).joinToString("\n") + if (n > 4) "\n…" else "",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        DialogRow(stringResource(R.string.clash_keep_both)) { onAnswer(Clash.KEEP_BOTH) }
        DialogRow(pluralStringResource(R.plurals.clash_skip, n)) { onAnswer(Clash.SKIP) }
        DialogRow(pluralStringResource(if (armed) R.plurals.clash_replace_armed else R.plurals.clash_replace, n), onClick = pressReplace)
        Spacer(Modifier.height(14.dp))
        WideButton(stringResource(R.string.cancel)) { onAnswer(null) }
    }
}

/**
 * Something that went wrong, in front of the reader until they have read it. A dialog, which
 * this app otherwise avoids, because a copy that stopped half way is not something to leave
 * to a line at the foot of the screen that goes away.
 */
@Composable
fun TroubleDialog(words: String, onRead: () -> Unit) {
    EInkDialog(onDismiss = onRead) {
        TextMMD(text = words, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(18.dp))
        WideButton(stringResource(R.string.trouble_close), onRead)
    }
}
