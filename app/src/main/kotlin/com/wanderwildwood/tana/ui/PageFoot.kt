package com.wanderwildwood.tana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.tana.R

/** One row the list has laid out: its place in the list, where it starts and how tall it is. */
internal data class LaidRow(val index: Int, val offset: Int, val size: Int)

/** Where a page stands: rows [first] to [last] (counted from 1) of [total], page [page] of [pages]. */
internal data class PagePosition(val first: Int, val last: Int, val total: Int, val page: Int, val pages: Int)

/**
 * Where the list is, or null when every row fits on one page. [rows] are the rows laid out on
 * screen, [rowCount] all the rows the list holds and [viewport] the height they are paged in.
 *
 * A page is the whole rows on screen: [rows] that start inside the viewport and end inside it.
 * Turning forward from the top lands each page on the row after the last, so with rows of one
 * height page N starts at row (N - 1) x per-page. Per page is the whole rows now showing; on the
 * last page, which may be short, it is what the viewport holds at the average height of the rows
 * laid out. The pages in all are the rows divided by that, rounded up, and the last page is always
 * "N of N".
 */
internal fun pagePosition(rows: List<LaidRow>, rowCount: Int, viewport: Int): PagePosition? {
    if (rowCount <= 0 || viewport <= 0 || rows.isEmpty()) return null
    val whole = rows.filter { it.offset >= 0 && it.offset + it.size <= viewport }
    // A row taller than the screen is read a screen at a time: it is a page of one row.
    val shown = whole.ifEmpty { listOf(rows.first()) }
    val first = shown.first().index
    val last = shown.last().index
    if (first <= 0 && last >= rowCount - 1) return null
    val onLast = last >= rowCount - 1
    val average = rows.sumOf { it.size }.toDouble() / rows.size
    val fits = if (average > 0) (viewport / average).toInt() else 0
    val perPage = (if (onLast) maxOf(shown.size, fits) else shown.size).coerceAtLeast(1)
    val pages = (rowCount + perPage - 1) / perPage
    val page = if (onLast) pages else ((first + perPage / 2) / perPage + 1).coerceIn(1, pages)
    return PagePosition(first + 1, last + 1, rowCount, page, pages)
}

/** [pagePosition] of a lazy list as it is laid out now; its blank room after the last row is not a row. */
private fun LazyListState.position(rowCount: Int): PagePosition? {
    val info = layoutInfo
    val rows = info.visibleItemsInfo
        .filter { it.index < rowCount }
        .map { LaidRow(it.index, it.offset, it.size) }
    val viewport = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
    return pagePosition(rows, rowCount, viewport)
}

/**
 * A list and, under it when it runs to more than one page, a quiet line saying where it is: the
 * rows on this page, and the page out of all. It is read from the list as it is laid out, and
 * changes only when a page is turned.
 */
@Composable
internal fun WithPageFoot(listState: LazyListState, rowCount: Int, list: @Composable () -> Unit) {
    val position by remember(listState, rowCount) { derivedStateOf { listState.position(rowCount) } }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { list() }
        position?.let { PageFoot(it) }
    }
}

@Composable
private fun PageFoot(at: PagePosition) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.onSurface))
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextMMD(
                text = stringResource(R.string.page_range, at.first, at.last, at.total),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f),
            )
            TextMMD(
                text = stringResource(R.string.page_of, at.page, at.pages),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
