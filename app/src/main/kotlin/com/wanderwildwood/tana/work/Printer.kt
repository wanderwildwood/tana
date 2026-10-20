package com.wanderwildwood.tana.work

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Printing a PDF or a picture, through Android's own print screen: it finds the printers on
 * the network, asks which one and how many, and turns the pages into what the printer takes.
 * This app only hands over the pages.
 *
 * Android calls the adapters below on the main thread and lets the answer come later, from any
 * thread, so reading and writing go on a thread of their own. Each call is answered exactly
 * once: finished, failed or cancelled.
 */
object Printer {

    private val PICTURES = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    /** As fine as a picture is worth sending: past this the printer throws the detail away. */
    const val DPI = 300

    fun canPrint(name: String) = Pdfs.canRead(name) || Names.extension(name) in PICTURES

    /** False when the phone has nothing to print with; the screen says so. */
    fun print(activity: Activity, file: File): Boolean {
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PRINTING)) return false
        val printing = activity.getSystemService(PrintManager::class.java) ?: return false
        val adapter = if (Pdfs.canRead(file.name)) PdfPages(file) else PicturePage(activity, file)
        return runCatching { printing.print(file.name, adapter, null) }.isSuccess
    }

    /**
     * Where a picture goes on the page. [turned] when it is the other way round to the paper
     * (a wide photo on upright paper), so it is turned a quarter to fill more of it; [scale] is
     * points of paper to a pixel of the picture, as big as fits whole.
     */
    data class Placing(val scale: Float, val turned: Boolean)

    fun place(pictureWidth: Int, pictureHeight: Int, areaWidth: Float, areaHeight: Float): Placing {
        val turned = pictureWidth != pictureHeight && areaWidth != areaHeight &&
            (pictureWidth > pictureHeight) != (areaWidth > areaHeight)
        val across = if (turned) pictureHeight else pictureWidth
        val down = if (turned) pictureWidth else pictureHeight
        return Placing(min(areaWidth / across, areaHeight / down), turned)
    }

    /**
     * The size to read a picture at so it has no more pixels than the paper shows at [DPI]: a
     * photo from a good camera is many times that, and would only fill memory. Null when the
     * picture is no bigger than that already.
     */
    fun readingSize(pictureWidth: Int, pictureHeight: Int, areaWidth: Float, areaHeight: Float): Pair<Int, Int>? {
        val placing = place(pictureWidth, pictureHeight, areaWidth, areaHeight)
        // Points are 72 to the inch; the picture's pixels at that scale, to dots at DPI.
        val shrink = placing.scale / 72f * DPI
        if (shrink >= 1f) return null
        return max(1, (pictureWidth * shrink).roundToInt()) to max(1, (pictureHeight * shrink).roundToInt())
    }

    /**
     * A PDF goes as it is. Which pages, and fitting them to the paper, is the print screen's
     * own work once it has the whole file; it is told how many pages there are, so it can offer
     * a choice of them before the file is sent.
     */
    private class PdfPages(private val file: File) : PrintDocumentAdapter() {
        @Volatile private var pages: Int? = null

        override fun onLayout(
            old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal,
            callback: LayoutResultCallback, extras: Bundle?,
        ) {
            if (cancel.isCanceled) return callback.onLayoutCancelled()
            val known = pages
            // The file is the same whatever paper is chosen; only the first layout is news.
            if (known != null) return callback.onLayoutFinished(info(known), false)
            thread(name = "print-pdf-pages") {
                val count = count()
                pages = count
                if (cancel.isCanceled) callback.onLayoutCancelled()
                else callback.onLayoutFinished(info(count), true)
            }
        }

        private fun info(count: Int) = PrintDocumentInfo.Builder(file.name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(count)
            .build()

        /** Unknown when Android's reader cannot open it, as with a locked PDF; it goes anyway. */
        private fun count(): Int = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { it.pageCount }
            }
        }.getOrNull()?.takeIf { it > 0 } ?: PrintDocumentInfo.PAGE_COUNT_UNKNOWN

        override fun onWrite(
            pages: Array<out PageRange>, to: ParcelFileDescriptor, cancel: CancellationSignal,
            callback: WriteResultCallback,
        ) {
            thread(name = "print-pdf") {
                try {
                    file.inputStream().use { from ->
                        FileOutputStream(to.fileDescriptor).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (!cancel.isCanceled) {
                                val n = from.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                    if (cancel.isCanceled) callback.onWriteCancelled()
                    else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }
    }

    /** A picture goes as one page, as big as the paper allows and the right way up. */
    private class PicturePage(context: Context, private val file: File) : PrintDocumentAdapter() {
        private val context = context.applicationContext
        @Volatile private var attributes: PrintAttributes? = null

        override fun onLayout(
            old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal,
            callback: LayoutResultCallback, extras: Bundle?,
        ) {
            if (cancel.isCanceled) return callback.onLayoutCancelled()
            attributes = new
            // Saved as a PDF from the print screen, it is named for what it now is.
            val info = PrintDocumentInfo.Builder(file.nameWithoutExtension + ".pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO)
                .setPageCount(1)
                .build()
            // A new paper size or margin puts the picture somewhere else on the page.
            callback.onLayoutFinished(info, new != old)
        }

        override fun onWrite(
            pages: Array<out PageRange>, to: ParcelFileDescriptor, cancel: CancellationSignal,
            callback: WriteResultCallback,
        ) {
            val chosen = attributes ?: return callback.onWriteFailed(null)
            thread(name = "print-picture") {
                val document = PrintedPdfDocument(context, chosen)
                try {
                    val page = document.startPage(0)
                    val area = RectF(page.info.contentRect)
                    // ImageDecoder turns the picture the way the camera said it was held, and
                    // its sizes are already that way round.
                    val picture = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                        readingSize(info.size.width, info.size.height, area.width(), area.height())
                            ?.let { (w, h) -> decoder.setTargetSize(w, h) }
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    if (cancel.isCanceled) {
                        picture.recycle()
                        document.finishPage(page)
                        return@thread callback.onWriteCancelled()
                    }
                    val placing = place(picture.width, picture.height, area.width(), area.height())
                    val w = picture.width * placing.scale
                    val h = picture.height * placing.scale
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    canvas.save()
                    canvas.translate(area.centerX(), area.centerY())
                    if (placing.turned) canvas.rotate(90f)
                    canvas.drawBitmap(picture, null, RectF(-w / 2, -h / 2, w / 2, h / 2), null)
                    canvas.restore()
                    picture.recycle()
                    document.finishPage(page)
                    if (cancel.isCanceled) return@thread callback.onWriteCancelled()
                    FileOutputStream(to.fileDescriptor).use { document.writeTo(it) }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                } catch (e: OutOfMemoryError) {
                    // A picture too big even read small: say so rather than close the app.
                    callback.onWriteFailed(e.message)
                } finally {
                    document.close()
                }
            }
        }
    }
}
