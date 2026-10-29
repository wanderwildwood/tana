package com.wanderwildwood.tana.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.tana.R
import com.wanderwildwood.tana.work.Markdown
import com.wanderwildwood.tana.work.MdKind
import com.wanderwildwood.tana.work.MdRow
import com.wanderwildwood.tana.work.MdStyle
import com.wanderwildwood.tana.work.NotText
import com.wanderwildwood.tana.work.PdfBook
import com.wanderwildwood.tana.work.PdfSource
import com.wanderwildwood.tana.work.Prefs
import com.wanderwildwood.tana.work.TextBook
import com.wanderwildwood.tana.work.TextOpener
import com.wanderwildwood.tana.work.TooBig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A text file, read a screen at a time: its lines in a list that turns by whole rows, like
 * every list here, with the rows on the page and the page out of all at the foot. Plain text
 * is shown as it is written; Markdown ([markdown]) as plain reading, with headings, bullets,
 * quotes and code set apart. The two buttons in the bar make the text smaller or larger,
 * from 14sp up, and the choice is kept.
 *
 * The file is indexed once, off the main thread, and only the lines on screen are read, so a
 * file of many megabytes opens as one of a few lines does. A file that is not text, or is too
 * large, says so and [onHandOn] gives it to another app.
 *
 * [source] is null while a server's file is still being fetched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextScreen(source: PdfSource?, title: String?, markdown: Boolean, onBack: () -> Unit, onHandOn: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context.applicationContext) }
    var size by remember { mutableIntStateOf(prefs.textSize) }

    val opened by produceState<Result<TextBook>?>(null, source) {
        if (source == null) return@produceState
        var book: TextBook? = null
        try {
            value = withContext(Dispatchers.IO) {
                runCatching { TextOpener.open(context.applicationContext, source, markdown) }.onSuccess { book = it }
            }
            awaitDispose { }
        } finally {
            book?.let { b -> Thread { runCatching { b.close() } }.start() }
        }
    }
    val book = opened?.getOrNull()
    val asked by produceState<String?>(null, source, title) {
        if (title == null && source != null) {
            value = withContext(Dispatchers.IO) { runCatching { PdfBook.nameOf(context.applicationContext, source) }.getOrNull() }
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = title ?: asked.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
                actions = {
                    SizeButton(small = true, description = stringResource(R.string.text_smaller)) {
                        if (size > 0) { size--; prefs.textSize = size }
                    }
                    SizeButton(small = false, description = stringResource(R.string.text_larger)) {
                        if (size < Prefs.TEXT_SIZES.lastIndex) { size++; prefs.textSize = size }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val failure = opened?.exceptionOrNull()
            when {
                source == null || opened == null -> Words(stringResource(R.string.folder_reading))
                failure != null -> CannotRead(failure, onHandOn)
                book != null && book.lineCount == 0 -> Words(stringResource(R.string.text_empty))
                book != null -> Lines(book, markdown, Prefs.TEXT_SIZES[size.coerceIn(0, Prefs.TEXT_SIZES.lastIndex)])
            }
        }
    }
}

@Composable
private fun Lines(book: TextBook, markdown: Boolean, sp: Int) {
    val state = rememberLazyListState()
    val ink = MaterialTheme.colorScheme.onSurface
    WithPageFoot(state, book.lineCount) {
        PagedColumn(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            state = state,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp),
        ) {
            items(count = book.lineCount, key = { it }) { i ->
                val raw = remember(book, i) { book.line(i) }
                if (markdown) {
                    MarkdownLine(Markdown.row(raw, book.isFence(i), book.isCode(i)), sp, ink)
                } else {
                    TextMMD(
                        text = raw.replace("\t", "    ").ifEmpty { " " },
                        fontSize = sp.sp,
                        lineHeight = (sp * 1.35f).sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownLine(row: MdRow, sp: Int, ink: androidx.compose.ui.graphics.Color) {
    val scale = if (row.kind == MdKind.HEADING) when (row.level) { 1 -> 1.45f; 2 -> 1.3f; 3 -> 1.15f; else -> 1f } else 1f
    val size = (sp * scale).sp
    when (row.kind) {
        MdKind.FENCE -> Spacer(Modifier.height(4.dp))
        MdKind.BLANK -> Spacer(Modifier.height((sp * 0.7f).dp))
        MdKind.RULE -> Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(1.dp).background(ink))
        MdKind.CODE -> TextMMD(
            text = row.text.ifEmpty { " " },
            fontSize = (sp * 0.9f).sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 1.dp, bottom = 1.dp),
        )
        else -> {
            val indent = when (row.kind) {
                MdKind.ITEM -> 16 + 18 * row.level
                                else -> 16
            }
            val text = styled(row.text, row)
            val body = if (row.kind == MdKind.QUOTE) "│ ".repeat(row.level.coerceAtLeast(1)) else ""
            TextMMD(
                text = if (body.isEmpty()) text else buildAnnotatedString { append(body); append(text) },
                fontSize = size,
                lineHeight = (size.value * 1.35f).sp,
                fontWeight = if (row.kind == MdKind.HEADING) FontWeight.Bold else null,
                fontStyle = if (row.kind == MdKind.QUOTE) FontStyle.Italic else null,
                modifier = Modifier.fillMaxWidth().padding(
                    start = indent.dp, end = 16.dp,
                    top = if (row.kind == MdKind.HEADING) 10.dp else 2.dp,
                    bottom = if (row.kind == MdKind.HEADING) 4.dp else 2.dp,
                ),
            )
        }
    }
}

private fun styled(text: String, row: MdRow): AnnotatedString = buildAnnotatedString {
    append(text)
    row.spans.forEach { span ->
        val style = when (span.style) {
            MdStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            MdStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            MdStyle.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
        }
        addStyle(style, span.start.coerceIn(0, text.length), span.end.coerceIn(0, text.length))
    }
}

@Composable
private fun SizeButton(small: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).semantics { contentDescription = description }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TextMMD(
            text = "A",
            fontSize = if (small) 14.sp else 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(0.dp),
        )
    }
}

@Composable
private fun CannotRead(failure: Throwable, onHandOn: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        TextMMD(
            text = stringResource(
                when (failure) {
                    is NotText -> R.string.text_binary
                    is TooBig -> R.string.text_big
                    else -> R.string.text_failed
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButtonMMD(onClick = onHandOn, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            TextMMD(text = stringResource(R.string.pdf_hand_on), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Words(text: String) {
    TextMMD(text = text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(16.dp))
}
