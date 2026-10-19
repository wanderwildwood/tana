package com.wanderwildwood.tana.work

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/** A PDF to read: a file on the phone, or what another app handed over by its address. */
sealed interface PdfSource {
    data class OfFile(val file: File) : PdfSource
    data class OfUri(val uri: Uri) : PdfSource
}

/** A PDF that is locked with a password. Android's reader opens no locked ones. */
class PdfLocked : IOException()

/** One page drawn for the screen, and the frame it was drawn at. */
class PdfShot(
    val page: Int,
    val frame: Frame,
    val bitmap: Bitmap,
    /** The page's own size, in points. */
    val pageWidth: Float,
    val pageHeight: Float,
)

/**
 * A PDF open to be read, with Android's own reader ([PdfRenderer]): no library of ours, and
 * nothing that leaves the phone. Opened off the main thread, since a file handed over by
 * another app may first have to be copied.
 *
 * Android's reader shows one page at a time and is not safe to use from two threads, so
 * drawing and closing take turns on this object's lock. [closeLater] closes without making
 * the main thread wait for a page that is half drawn.
 */
class PdfBook private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** The file's name, as it was given, for the top bar. */
    val name: String,
    /** What [PageMarks] knows it by. */
    val key: String,
    /** A copy made only so it could be read, deleted when the book is closed. */
    private val copy: File?,
) : Closeable {

    val pages: Int = renderer.pageCount

    private var closed = false

    /**
     * Page [index], drawn into a bitmap the size of the space it is shown in, at [frame];
     * null for the page whole, in the middle. The page's own background is transparent, so the
     * bitmap is filled white first, which is also what the margins around a page are.
     */
    @Synchronized
    fun draw(index: Int, width: Int, height: Int, frame: Frame?): PdfShot {
        check(!closed) { "closed" }
        renderer.openPage(index).use { page ->
            val pw = page.width.toFloat()
            val ph = page.height.toFloat()
            val f = frame ?: Frame.fit(pw, ph, width.toFloat(), height.toFloat())
            val scale = f.scale(pw, ph, width.toFloat(), height.toFloat())
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val placed = Matrix().apply {
                postScale(scale, scale)
                postTranslate(f.left, f.top)
            }
            page.render(bitmap, null, placed, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return PdfShot(index, f, bitmap, pw, ph)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { fd.close() }
        copy?.parentFile?.deleteRecursively()
    }

    /** Closed on a thread of its own, once any page being drawn is done. */
    fun closeLater() {
        thread(name = "pdf-close") { close() }
    }

    companion object {
        private const val COPIES = "reading"

        /**
         * Opens [source]. Throws [PdfLocked] for a PDF with a password and an IOException
         * for anything that is not a PDF Android can read; nothing is left open either way.
         */
        fun open(context: Context, source: PdfSource): PdfBook {
            tidy(context)
            var copy: File? = null
            val name: String
            val fd: ParcelFileDescriptor = when (source) {
                is PdfSource.OfFile -> {
                    name = source.file.name
                    ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
                is PdfSource.OfUri -> {
                    if (source.uri.scheme == "file") {
                        val file = File(source.uri.path ?: throw IOException("no path"))
                        name = file.name
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    } else {
                        name = nameOf(context, source.uri)
                        // Android's reader jumps about in the file. An app that hands over a
                        // stream it cannot jump in (a pipe) gets its file copied here first.
                        val direct = runCatching { context.contentResolver.openFileDescriptor(source.uri, "r") }.getOrNull()
                        if (direct != null && seekable(direct)) {
                            direct
                        } else {
                            runCatching { direct?.close() }
                            copy = copyOut(context, source.uri, name)
                            ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
                        }
                    }
                }
            }
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: Throwable) {
                runCatching { fd.close() }
                copy?.parentFile?.deleteRecursively()
                // Android says a locked PDF is a security matter, and a broken one an IOException.
                throw if (e is SecurityException) PdfLocked() else e as? IOException ?: IOException(e)
            }
            if (renderer.pageCount == 0) {
                renderer.close()
                runCatching { fd.close() }
                copy?.parentFile?.deleteRecursively()
                throw IOException("no pages")
            }
            return PdfBook(fd, renderer, name, PageMarks.key(name, fd.statSize), copy)
        }

        private fun seekable(fd: ParcelFileDescriptor): Boolean = try {
            Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_CUR)
            true
        } catch (e: ErrnoException) {
            false
        }

        /** The name to show for [source], when it has not been opened, or could not be. */
        fun nameOf(context: Context, source: PdfSource): String = when (source) {
            is PdfSource.OfFile -> source.file.name
            is PdfSource.OfUri -> if (source.uri.scheme == "file") File(source.uri.path.orEmpty()).name else nameOf(context, source.uri)
        }

        private fun nameOf(context: Context, uri: Uri): String {
            val given = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
                }
            }.getOrNull()
            return given?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "PDF"
        }

        private fun copyOut(context: Context, uri: Uri, name: String): File {
            val folder = File(context.cacheDir, "$COPIES/${UUID.randomUUID()}").apply { mkdirs() }
            // Only the last part of the name, and nothing that would make it a path.
            val file = File(folder, name.substringAfterLast('/').ifBlank { "document.pdf" })
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("nothing to read")
            input.use { from -> file.outputStream().use { to -> from.copyTo(to) } }
            return file
        }

        /** Copies left by a reader that never got to close: the app stopped, or the phone did. */
        private fun tidy(context: Context) {
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            File(context.cacheDir, COPIES).listFiles()?.forEach { folder ->
                if (folder.lastModified() < cutoff) folder.deleteRecursively()
            }
        }
    }
}
