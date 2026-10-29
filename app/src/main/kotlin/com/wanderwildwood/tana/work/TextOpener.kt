package com.wanderwildwood.tana.work

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Opens what [TextBook] reads from: a file on the phone as it is, and anything another app
 * hands over by address copied into the cache first (a stream cannot be jumped about in, and
 * the lines are read where they lie). The copy goes when the book is closed.
 */
object TextOpener {

    private const val FOLDER = "reading-text"

    fun open(context: Context, source: PdfSource, markdown: Boolean): TextBook {
        tidy(context)
        return when (source) {
            is PdfSource.OfFile -> TextBook.open(FileSource(source.file), markdown)
            is PdfSource.OfUri -> {
                if (source.uri.scheme == "file") {
                    TextBook.open(FileSource(File(source.uri.path ?: throw IOException("no path"))), markdown)
                } else {
                    val dir = File(context.cacheDir, FOLDER).apply { mkdirs() }
                    val copy = File(dir, UUID.randomUUID().toString())
                    try {
                        context.contentResolver.openInputStream(source.uri)?.use { input ->
                            copy.outputStream().use { out ->
                                val buffer = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    total += n
                                    if (total > Texts.MAX_BYTES) throw TooBig()
                                    out.write(buffer, 0, n)
                                }
                            }
                        } ?: throw IOException("cannot open")
                        TextBook.open(FileSource(copy, deleteOnClose = true), markdown)
                    } catch (e: Throwable) {
                        copy.delete()
                        throw e
                    }
                }
            }
        }
    }

    /** Copies left by a reading that was cut short, a day or more old. */
    private fun tidy(context: Context) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        File(context.cacheDir, FOLDER).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}
