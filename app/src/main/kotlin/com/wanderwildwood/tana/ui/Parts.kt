package com.wanderwildwood.tana.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.checkbox.CheckboxMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.tana.R
import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.store.Loc
import com.wanderwildwood.tana.work.Job
import com.wanderwildwood.tana.work.Mode
import com.wanderwildwood.tana.work.Transfers
import com.wanderwildwood.tana.work.Work
import kotlinx.coroutines.delay

@Composable
internal fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * A row that asks before it acts: the first press arms it and changes what it says, a second
 * press does it, and it disarms itself after four seconds so nothing is left live for whoever
 * picks the phone up next. Returns whether it is armed, and the press to hand the row.
 */
@Composable
internal fun rememberArmed(key: Any?, onConfirmed: () -> Unit): Pair<Boolean, () -> Unit> {
    var armed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4000)
            armed = false
        }
    }
    return armed to {
        if (armed) {
            armed = false
            onConfirmed()
        } else {
            armed = true
        }
    }
}

/**
 * One file or folder. A press opens it, a long press starts choosing; while choosing, a
 * press adds or takes it away. The box stands in for the icon while choosing, so the row
 * does not shift sideways under the reader's thumb.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EntryRow(
    entry: Entry,
    selecting: Boolean,
    selected: Boolean,
    showFolder: String?,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPress, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selecting) {
                CheckboxMMD(checked = selected, onCheckedChange = null)
            } else {
                Icon(
                    imageVector = if (entry.isFolder) Icons.Folder else Icons.File,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            TextMMD(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val parts = listOfNotNull(
                showFolder,
                if (entry.isFolder) null else size(context, entry.size),
                date(context, entry.modified),
            )
            if (parts.isNotEmpty()) {
                TextMMD(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDividerMMD(thickness = 0.5.dp)
}

/**
 * The date in the phone's own form, with the year only when it is not this one. A store that
 * keeps no date reports zero, and "1 Jan 1970" is worse than saying nothing.
 */
internal fun date(context: android.content.Context, millis: Long): String? {
    if (millis <= 0) return null
    val thisYear = DateUtils.formatDateTime(context, System.currentTimeMillis(), DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_NO_MONTH_DAY)
    val itsYear = DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_NO_MONTH_DAY)
    var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    flags = flags or if (thisYear == itsYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
    return DateUtils.formatDateTime(context, millis, flags)
}

@Composable
internal fun Heading(text: String) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
    )
    HorizontalDividerMMD()
}

/** A row on the start page: a name, and a line under it only when that line says something. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlaceRow(title: String, note: String?, onPress: () -> Unit, onLongPress: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPress, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        TextMMD(text = title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note != null) TextMMD(text = note, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDividerMMD(thickness = 0.5.dp)
}

/**
 * Where this folder is, each step a press back to it: "SD card / Music / Albums". On a
 * phone with no breadcrumbs and a Back that only goes one way, this is how you get from
 * deep in a tree to half way up it in one press.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PathLine(root: Loc, rootLabel: String, here: Loc, onJump: (Loc) -> Unit) {
    val below = here.path.removePrefix(root.path).trim('/').split('/').filter { it.isNotEmpty() }
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        var loc = root
        val steps = mutableListOf(root to rootLabel)
        below.forEach { name ->
            loc = loc.child(name)
            steps += loc to name
        }
        steps.forEachIndexed { i, (target, label) ->
            val last = i == steps.lastIndex
            TextMMD(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (last) FontWeight.Bold else null,
                modifier = if (last) Modifier.padding(vertical = 4.dp) else Modifier.clickable { onJump(target) }.padding(vertical = 4.dp),
            )
            if (!last) TextMMD(text = "  /  ", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
    HorizontalDividerMMD()
}

/** Four words along the foot while things are chosen. Delete asks in its own face. */
@Composable
internal fun SelectionBar(count: Int, onCopy: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit, onMore: () -> Unit) {
    val (armed, pressDelete) = rememberArmed(count, onDelete)
    HorizontalDividerMMD()
    if (armed) {
        FootButton(stringResource(R.string.action_delete_armed), Modifier.fillMaxWidth().padding(10.dp), pressDelete)
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)) {
        FootButton(stringResource(R.string.action_copy), Modifier.weight(1f), onCopy)
        Spacer(Modifier.width(6.dp))
        FootButton(stringResource(R.string.action_move), Modifier.weight(1f), onMove)
        Spacer(Modifier.width(6.dp))
        FootButton(stringResource(R.string.action_delete), Modifier.weight(1f), pressDelete)
        Spacer(Modifier.width(6.dp))
        FootButton(stringResource(R.string.action_more), Modifier.weight(1f), onMore)
    }
}

@Composable
internal fun FootButton(label: String, modifier: Modifier, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButtonMMD(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** What was picked up, and where it can be put down. */
@Composable
internal fun CarryStrip(carry: Carry, canPaste: Boolean, onPaste: () -> Unit, onCancel: () -> Unit) {
    HorizontalDividerMMD()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val n = carry.entries.size
        TextMMD(
            text = pluralStringResource(if (carry.mode == Mode.MOVE) R.plurals.carry_move else R.plurals.carry_copy, n, n) +
                if (canPaste) "" else " " + stringResource(R.string.carry_open_a_folder),
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            FootButton(stringResource(R.string.cancel), Modifier.weight(1f), onCancel)
            Spacer(Modifier.width(10.dp))
            FootButton(stringResource(R.string.carry_paste), Modifier.weight(1f), onPaste, enabled = canPaste)
        }
    }
}

/**
 * The copy, move or delete under way, or how the last one ended. Words and a percentage,
 * never a bar that crawls: every step of a moving bar is a repaint on this panel.
 */
@Composable
internal fun WorkStrip(work: Work) {
    val text: String = when (work) {
        is Work.Running -> {
            val p = work.progress
            if (p == null) {
                stringResource(R.string.work_starting)
            } else {
                val done = minOf(p.filesDone + 1, p.filesTotal)
                val head = when (val job = work.job) {
                    is Job.Paste -> stringResource(if (job.mode == Mode.MOVE) R.string.work_moving else R.string.work_copying, done, p.filesTotal)
                    is Job.Delete -> stringResource(R.string.work_deleting, p.filesDone, p.filesTotal)
                    is Job.Fetch -> stringResource(R.string.work_fetching)
                }
                val percent = if (p.bytesTotal > 0) " · ${p.bytesDone * 100 / p.bytesTotal}%" else ""
                head + percent
            }
        }
        is Work.Finished -> when (val job = work.job) {
            is Job.Paste -> {
                val moved = work.outcome.done
                val first = pluralStringResource(if (job.mode == Mode.MOVE) R.plurals.work_moved else R.plurals.work_copied, moved, moved)
                val skipped = work.outcome.skipped
                if (skipped > 0) first + " " + pluralStringResource(R.plurals.work_skipped, skipped, skipped) else first
            }
            is Job.Delete -> pluralStringResource(R.plurals.work_deleted, work.outcome.done, work.outcome.done)
            is Job.Fetch -> return
        }
        is Work.Stopped -> stringResource(R.string.work_stopped)
        else -> return
    }

    // A result stays long enough to read and then goes; a failure is a dialog instead.
    if (work is Work.Finished || work is Work.Stopped) {
        LaunchedEffect(work) {
            delay(5000)
            Transfers.seen()
        }
    }

    HorizontalDividerMMD()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        TextMMD(text = text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        if (work is Work.Running) {
            Spacer(Modifier.width(10.dp))
            FootButton(stringResource(R.string.work_stop), Modifier.width(96.dp), Transfers::stop)
        }
    }
}

/** A short thing to say, along the foot, gone after a few seconds. */
@Composable
internal fun NoticeStrip(text: String, onSeen: () -> Unit) {
    LaunchedEffect(text) {
        delay(5000)
        onSeen()
    }
    HorizontalDividerMMD()
    TextMMD(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSeen).padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
