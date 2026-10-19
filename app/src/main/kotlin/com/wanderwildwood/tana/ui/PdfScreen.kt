package com.wanderwildwood.tana.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.tana.R
import com.wanderwildwood.tana.work.Frame
import com.wanderwildwood.tana.work.PageMarks
import com.wanderwildwood.tana.work.PdfBook
import com.wanderwildwood.tana.work.PdfLocked
import com.wanderwildwood.tana.work.PdfShot
import com.wanderwildwood.tana.work.PdfSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import kotlin.math.abs

/**
 * A PDF, read a page to a screen: each page fitted whole, turned by a swipe or a tap near
 * either edge, the way a list here turns its pages, and with nothing moving in between. Two
 * fingers zoom in on small print and one then moves the page about; turning the page puts it
 * back to fit. The line at the foot says which page this is, and a tap on it goes to another.
 *
 * Each PDF opens at the page it was left at ([PageMarks]). One that cannot be read — locked
 * with a password, or not really a PDF — says so, and [onHandOn] gives it to another app.
 *
 * [source] is null while a server's file is still being fetched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreen(source: PdfSource?, title: String?, onBack: () -> Unit, onHandOn: () -> Unit) {
    val context = LocalContext.current
    val marks = remember { PageMarks(context.applicationContext) }

    // Opened off the main thread, and closed when this page goes, however it goes.
    val opened by produceState<Result<PdfBook>?>(null, source) {
        if (source == null) return@produceState
        var book: PdfBook? = null
        try {
            value = withContext(Dispatchers.IO) {
                runCatching { PdfBook.open(context.applicationContext, source) }.onSuccess { book = it }
            }
            awaitDispose { }
        } finally {
            book?.closeLater()
        }
    }
    val book = opened?.getOrNull()

    // The page on screen, from 0; -1 until the book is open and its last page looked up.
    // Kept through the app being stopped and started again, as the remembered page is.
    var page by rememberSaveable(source) { mutableIntStateOf(-1) }
    LaunchedEffect(book) {
        if (book != null && page !in 0 until book.pages) {
            page = (marks.get(book.key) ?: 0).coerceIn(0, book.pages - 1)
        }
    }
    LaunchedEffect(book, page) {
        if (book != null && page >= 0) marks.put(book.key, page)
    }

    var box by remember { mutableStateOf(IntSize.Zero) }
    // Where the page is, zoomed: null for whole, which is how every page opens.
    var frame by remember(source, page, box) { mutableStateOf<Frame?>(null) }
    // The page as last drawn. Kept on screen until the next one is ready, so a turn is one
    // redraw of the panel rather than a blank and then a page.
    var shot by remember(source) { mutableStateOf<PdfShot?>(null) }
    var drawFailed by remember(source) { mutableStateOf(false) }
    var jumping by remember { mutableStateOf(false) }

    LaunchedEffect(book, page, box, frame) {
        if (book == null || page < 0 || box.width <= 0 || box.height <= 0) return@LaunchedEffect
        // While two fingers are still moving, the last picture is stretched to follow them;
        // the page is drawn sharp again once they have been still for a moment.
        if (frame != null) delay(250)
        val drawn = withContext(Dispatchers.IO) { runCatching { book.draw(page, box.width, box.height, frame) } }
        drawn.onSuccess { shot = it }.onFailure { drawFailed = true }
    }

    fun turn(by: Int) {
        val b = book ?: return
        val next = (page + by).coerceIn(0, b.pages - 1)
        if (next != page) page = next
    }

    BackHandler(onBack = onBack)

    // The name for the bar when the book could not be opened to give it.
    val asked by produceState<String?>(null, source, title) {
        if (title == null && source != null) {
            value = withContext(Dispatchers.IO) { runCatching { PdfBook.nameOf(context.applicationContext, source) }.getOrNull() }
        }
    }
    val name = title ?: book?.name ?: asked.orEmpty()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
        bottomBar = {
            if (book != null && page >= 0 && !drawFailed) {
                Column {
                    HorizontalDividerMMD()
                    val count = NumberFormat.getIntegerInstance()
                    TextMMD(
                        text = stringResource(R.string.pdf_page, count.format(page + 1), count.format(book.pages)),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable { jumping = true }.padding(vertical = 14.dp),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            val failure = opened?.exceptionOrNull()
            when {
                source == null || opened == null -> Words(stringResource(R.string.folder_reading))
                failure != null || drawFailed -> CannotRead(failure is PdfLocked, onHandOn)
                else -> Page(
                    shot = shot,
                    page = page,
                    frame = frame,
                    onSize = { box = it },
                    onTurn = ::turn,
                    onFrame = { frame = it },
                )
            }
        }
    }

    if (jumping && book != null) {
        PageDialog(pages = book.pages, onGo = { page = it }, onDismiss = { jumping = false })
    }
}

/**
 * The page itself, and what a finger does on it. One finger: a swipe left or up is the next
 * page, right or down the one before, once per swipe however long; a tap in the outer third
 * of either side does the same, and a tap in the middle of a zoomed page puts it back whole.
 * Two fingers zoom, after which one moves the page instead of turning it.
 */
@Composable
private fun Page(
    shot: PdfShot?,
    page: Int,
    frame: Frame?,
    onSize: (IntSize) -> Unit,
    onTurn: (Int) -> Unit,
    onFrame: (Frame?) -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val swipe = with(LocalDensity.current) { 40.dp.toPx() }
    // The gesture below is set up once; these keep what it reads current. Without them it
    // would go on turning and zooming the page it first saw.
    val current by rememberUpdatedState(Triple(shot, page, frame))
    val turn by rememberUpdatedState(onTurn)
    val reframe by rememberUpdatedState(onFrame)

    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged(onSize)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var travelled = Offset.Zero
                    var moved = false
                    var turned = false
                    var pinched = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val (s, p, f) = current
                        // Zoom and movement only once this page is drawn, and its size known.
                        val here = s?.takeIf { it.page == p }
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) {
                            pinched = true
                            if (here != null) {
                                val from = f ?: Frame.fit(here.pageWidth, here.pageHeight, w, h)
                                val centre = event.calculateCentroid()
                                val pan = event.calculatePan()
                                val next = from.pinched(
                                    event.calculateZoom(), centre.x, centre.y, pan.x, pan.y,
                                    here.pageWidth, here.pageHeight, w, h,
                                )
                                reframe(if (next.zoom <= 1f) null else next)
                            }
                        } else if (!pinched) {
                            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                            val delta = change.positionChange()
                            travelled += delta
                            if (!moved && travelled.getDistance() > viewConfiguration.touchSlop) moved = true
                            if (moved && f != null && here != null) {
                                reframe(f.moved(delta.x, delta.y, here.pageWidth, here.pageHeight, w, h))
                            } else if (moved && f == null && !turned && travelled.getDistance() > swipe) {
                                turned = true
                                val forward = if (abs(travelled.x) >= abs(travelled.y)) travelled.x < 0 else travelled.y < 0
                                turn(if (forward) 1 else -1)
                            }
                        }
                        event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                    if (!moved && !pinched) {
                        val x = down.position.x
                        val third = size.width / 3f
                        when {
                            x < third -> turn(-1)
                            x > size.width - third -> turn(1)
                            current.third != null -> reframe(null)
                        }
                    }
                }
            },
    ) {
        val s = shot ?: return@Canvas
        val image = s.bitmap.asImageBitmap()
        if (s.page != page) {
            // The page before, until this one is drawn.
            drawImage(image)
            return@Canvas
        }
        // The page as it is now, from a picture drawn at another frame: moved, and stretched
        // by the difference in zoom, until it is drawn again.
        val now = frame ?: Frame.fit(s.pageWidth, s.pageHeight, size.width, size.height)
        val k = now.zoom / s.frame.zoom
        translate(now.left - s.frame.left * k, now.top - s.frame.top * k) {
            scale(k, k, pivot = Offset.Zero) { drawImage(image) }
        }
        // A hairline round the page, so its edge shows against the white around it.
        val scale = now.scale(s.pageWidth, s.pageHeight, size.width, size.height)
        drawRect(
            color = ink,
            topLeft = Offset(now.left, now.top),
            size = Size(s.pageWidth * scale, s.pageHeight * scale),
            style = Stroke(width = 1f),
        )
    }
}

/** Said in place of the page, with a way to have another app try. */
@Composable
private fun CannotRead(locked: Boolean, onHandOn: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        TextMMD(
            text = stringResource(if (locked) R.string.pdf_locked else R.string.pdf_failed),
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
