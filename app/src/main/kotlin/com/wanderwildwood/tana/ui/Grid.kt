package com.wanderwildwood.tana.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.checkbox.CheckboxMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.store.Loc
import com.wanderwildwood.tana.work.Thumbs

/** Three across: on the Kompakt each is about as wide as a thumb, and a page holds twelve. */
internal const val GRID_COLUMNS = 3

/**
 * One row of the grid view: up to [GRID_COLUMNS] squares, the last row's spare places left
 * empty. A row of the paged list, so the grid turns its pages on whole rows as every list does.
 */
@Composable
internal fun GridRow(
    row: List<Entry>,
    selecting: Boolean,
    selection: Set<Loc>,
    onPress: (Entry) -> Unit,
    onLongPress: (Entry) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        row.forEach { entry ->
            Tile(entry, selecting, entry.loc in selection, { onPress(entry) }, { onLongPress(entry) }, Modifier.weight(1f))
        }
        repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * One file or folder as a square. A photograph or video on the phone is its own small picture,
 * with no name: the picture says more than IMG_2041.jpg does. Anything else is its icon over
 * its name. Pressed and held as a row of the list is; a chosen one has a heavy frame and a
 * ticked box in its corner.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    entry: Entry,
    selecting: Boolean,
    selected: Boolean,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val pictured = Thumbs.can(entry)
    // The picture, and whether it is still being made: one that cannot be drawn (a kind the
    // phone does not read, a broken file) falls back to its icon and name.
    val thumb by produceState(
        if (pictured) Thumbs.ready(entry) to Thumbs.cannot(entry) else null to true,
        entry.loc, entry.modified, entry.size,
    ) {
        if (value.first == null && !value.second) value = Thumbs.load(context, entry) to true
    }
    val (image, settled) = thumb
    Box(
        modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .semantics { contentDescription = entry.name }
            .combinedClickable(onClick = onPress, onLongClick = onLongPress)
            .then(if (selected) Modifier.border(4.dp, ink) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            image != null -> {
                Image(
                    bitmap = remember(image) { image.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().keepColors(),
                )
                if (Thumbs.isVideo(entry)) PlayMark(Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
            // Still being made: an empty frame, which the picture then fills. No name flashes by.
            !settled -> Box(Modifier.fillMaxSize().border(1.dp, ink))
            else -> Column(
                Modifier.fillMaxSize().border(1.dp, ink).padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = if (entry.isFolder) Icons.Folder else Icons.File,
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(4.dp))
                TextMMD(
                    text = entry.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else null,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selecting) {
            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(28.dp).background(paper).border(1.dp, ink),
                contentAlignment = Alignment.Center,
            ) {
                CheckboxMMD(checked = selected, onCheckedChange = { onPress() })
            }
        }
    }
}

/** A video among the photographs: a dark triangle on a light disc, in the corner. */
@Composable
private fun PlayMark(modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    Canvas(modifier.size(24.dp)) {
        val r = size.minDimension / 2
        drawCircle(paper, r)
        drawCircle(ink, r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        val c = Offset(size.width / 2, size.height / 2)
        drawPath(
            Path().apply {
                moveTo(c.x - r * 0.3f, c.y - r * 0.45f)
                lineTo(c.x + r * 0.5f, c.y)
                lineTo(c.x - r * 0.3f, c.y + r * 0.45f)
                close()
            },
            ink,
        )
    }
}
