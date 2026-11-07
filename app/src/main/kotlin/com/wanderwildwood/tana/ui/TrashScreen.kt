package com.wanderwildwood.tana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.tana.R
import com.wanderwildwood.tana.work.TRASH_DAYS
import com.wanderwildwood.tana.work.Trashed
import java.text.DateFormat
import java.util.Date

/**
 * The trash: what was deleted from the phone in the last [TRASH_DAYS] days, newest first.
 * Restore is one press; Delete forever and Empty trash ask first, in the row's own words, and
 * stay armed until the next other touch, Back or leaving. Anything older than a week has
 * already gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(view: TrashView, vm: BrowserViewModel) {
    val items = view.items
    val listState = rememberLazyListState()
    val (emptying, pressEmpty) = rememberArmed(items?.size) { vm.emptyTrash() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.trash_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), vm::closeTrash) },
            )
        },
        bottomBar = {
            if (!items.isNullOrEmpty()) {
                Column {
                    HorizontalDividerMMD()
                    FootButton(
                        stringResource(if (emptying) R.string.trash_empty_armed else R.string.trash_empty),
                        Modifier.fillMaxWidth().padding(10.dp),
                        pressEmpty,
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surface)) {
            TextMMD(
                text = view.note ?: pluralStringResource(R.plurals.trash_rule, TRASH_DAYS, TRASH_DAYS),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            DottedRule()
            when {
                items == null -> TextMMD(
                    text = stringResource(R.string.folder_reading),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(16.dp),
                )
                items.isEmpty() -> TextMMD(
                    text = stringResource(R.string.trash_none),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(16.dp),
                )
                else -> WithPageFoot(listState, items.size) {
                    PagedColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), state = listState, showCutRow = true) {
                        items(items, key = { it.root.path + "/" + it.slot }) { item -> TrashRow(item, vm) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrashRow(item: Trashed, vm: BrowserViewModel) {
    val (armed, press) = rememberArmed(item.root.path + item.slot) { vm.deleteForever(item) }
    val day = remember(item.at) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.at)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        TextMMD(text = item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextMMD(
            text = stringResource(R.string.trash_deleted, day, item.folder.substringAfterLast('/')),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!armed) {
                TextMMD(
                    text = stringResource(R.string.trash_restore),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable { vm.restore(item) }
                        .padding(end = 24.dp, top = 12.dp, bottom = 12.dp),
                )
            }
            TextMMD(
                text = stringResource(if (armed) R.string.trash_forever_armed else R.string.trash_forever),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (armed) FontWeight.Bold else null,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(onClick = press)
                    .padding(end = 12.dp, top = 12.dp, bottom = 12.dp),
            )
        }
    }
    DottedRule()
}
