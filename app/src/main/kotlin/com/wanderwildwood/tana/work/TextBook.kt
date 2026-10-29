package com.wanderwildwood.tana.work

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.BitSet

/** Which files the text reader takes, and how a file that is not text is told from one that is. */
object Texts {

    /** A spreadsheet's csv is left out: it opens as a table, as it always did. */
    private val PLAIN = setOf("txt", "text", "log", "json", "xml", "yml", "yaml", "ini", "conf", "cfg", "properties", "toml", "srt", "nfo")
    private val MARKDOWN = setOf("md", "markdown", "mkd")

    /** A line longer than this many bytes is shown as more than one, so no row is a megabyte of text. */
    const val MAX_LINE = 4000

    /** Past this the file is left to another app: the index of its lines would be most of a phone's memory. */
    const val MAX_BYTES = 128L * 1024 * 1024

    const val MIME = "text/plain"

    fun canRead(name: String) = Names.extension(name).let { it in PLAIN || it in MARKDOWN }

    fun isMarkdown(name: String) = Names.extension(name) in MARKDOWN

    /** A type another app names when it asks for a text file to be opened. */
    fun isTextMime(mime: String?) = mime != null && (mime == "text/plain" || mime == "text/markdown" || mime == "text/x-markdown")

    fun isMarkdownMime(mime: String?) = mime == "text/markdown" || mime == "text/x-markdown"

    /**
     * Whether the first bytes of a file are not text: a zero byte anywhere, a byte order mark of
     * UTF-16 or UTF-32 (which this reader does not decode), or more than a tenth of them
     * control characters other than tab, line breaks, form feed and escape.
     */
    fun looksBinary(head: ByteArray, length: Int = head.size): Boolean {
        if (length == 0) return false
        if (length >= 2) {
            val a = head[0].toInt() and 0xFF
            val b = head[1].toInt() and 0xFF
            if ((a == 0xFF && b == 0xFE) || (a == 0xFE && b == 0xFF)) return true
        }
        var odd = 0
        for (i in 0 until length) {
            val c = head[i].toInt() and 0xFF
            if (c == 0) return true
            if (c < 0x20 && c != 9 && c != 10 && c != 12 && c != 13 && c != 27) odd++
        }
        return odd * 10 > length
    }
}

/** Where a text's bytes come from; a file, or in tests an array. */
interface ByteSource : Closeable {
    val size: Long

    /** Reads up to [len] bytes from [pos] into [into] at [off]; the count read, or -1 at the end. */
    fun read(pos: Long, into: ByteArray, off: Int, len: Int): Int
}

class FileSource(private val file: File, private val deleteOnClose: Boolean = false) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    @Synchronized
    override fun read(pos: Long, into: ByteArray, off: Int, len: Int): Int {
        raf.seek(pos)
        return raf.read(into, off, len)
    }

    override fun close() {
        raf.close()
        if (deleteOnClose) file.delete()
    }
}

class ArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long = bytes.size.toLong()

    override fun read(pos: Long, into: ByteArray, off: Int, len: Int): Int {
        if (pos >= bytes.size) return -1
        val n = minOf(len.toLong(), bytes.size - pos).toInt()
        System.arraycopy(bytes, pos.toInt(), into, off, n)
        return n
    }

    override fun close() = Unit
}

/** Said when a file is not text, or is more than the reader takes. */
class NotText : IOException()
class TooBig : IOException()

/**
 * A text file as a list of lines, indexed once and read a line at a time. Only the position
 * each line starts at is kept (8 bytes a line), so a file of many megabytes costs the index
 * and the few lines on screen, never the text. A line longer than [Texts.MAX_LINE] bytes is
 * cut into more than one, never in the middle of a character.
 *
 * Lines are UTF-8; a line that is not valid UTF-8 is read as Windows-1252 instead, so an old
 * file shows its accents rather than failing. A UTF-8 byte order mark is skipped.
 *
 * For a Markdown file the lines inside a fenced code block are marked as they are indexed, so
 * a row can be drawn without reading the rows above it.
 */
class TextBook private constructor(
    private val source: ByteSource,
    private var starts: LongArray,
    val lineCount: Int,
    private val fences: BitSet?,
    private val code: BitSet?,
) : Closeable {

    private val end = source.size
    private val block = ByteArray(Texts.MAX_LINE + 16)

    /** The line [index] from 0, without its line break. */
    @Synchronized
    fun line(index: Int): String {
        require(index in 0 until lineCount)
        val from = starts[index]
        val to = if (index + 1 < lineCount) starts[index + 1] else end
        var length = (to - from).toInt().coerceAtMost(block.size)
        length = source.read(from, block, 0, length).coerceAtLeast(0)
        // Take the line break off, whichever it was.
        while (length > 0 && (block[length - 1] == '\n'.code.toByte() || block[length - 1] == '\r'.code.toByte())) length--
        return decode(block, length)
    }

    /** Whether the row is a ``` or ~~~ line of a Markdown file, which is not shown. */
    fun isFence(index: Int) = fences?.get(index) == true

    /** Whether the row is inside a fenced code block of a Markdown file. */
    fun isCode(index: Int) = code?.get(index) == true

    override fun close() = source.close()

    companion object {
        private val UTF8: Charset = Charsets.UTF_8
        private val WIN: Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

        fun decode(bytes: ByteArray, length: Int): String {
            return try {
                UTF8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length)).toString()
            } catch (e: CharacterCodingException) {
                String(bytes, 0, length, WIN)
            }
        }

        /**
         * Indexes [source] and keeps it open for [TextBook.line]. Throws [NotText] when its
         * first bytes are not text and [TooBig] when it is past [Texts.MAX_BYTES]; the source
         * is closed then.
         */
        fun open(source: ByteSource, markdown: Boolean): TextBook {
            try {
                if (source.size > Texts.MAX_BYTES) throw TooBig()
                val head = ByteArray(8192)
                val got = source.read(0, head, 0, head.size).coerceAtLeast(0)
                if (Texts.looksBinary(head, got)) throw NotText()
                return index(source, markdown, bom = got >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte())
            } catch (e: Throwable) {
                runCatching { source.close() }
                throw e
            }
        }

        private fun index(source: ByteSource, markdown: Boolean, bom: Boolean): TextBook {
            var starts = LongArray(1024)
            var count = 0
            fun add(at: Long) {
                if (count == starts.size) starts = starts.copyOf(starts.size * 2)
                starts[count++] = at
            }
            val fences = if (markdown) BitSet() else null
            val code = if (markdown) BitSet() else null
            var inFence = false

            // The head of the line being read, to tell a fence from the text.
            val head = ByteArray(12)
            var headLen = 0
            var lineStart = if (bom) 3L else 0L
            var lineBytes = 0
            var continued = false

            fun finish() {
                // The line just ended is row count-1.
                if (fences == null || code == null || continued) return
                val row = count - 1
                val text = String(head, 0, headLen, Charsets.ISO_8859_1).trimStart(' ')
                val fence = text.startsWith("```") || text.startsWith("~~~")
                if (fence) {
                    fences.set(row)
                    inFence = !inFence
                } else if (inFence) {
                    code.set(row)
                }
            }

            val size = source.size
            val buffer = ByteArray(64 * 1024)
            var pos = lineStart
            var open = false
            while (pos < size) {
                val n = source.read(pos, buffer, 0, buffer.size)
                if (n <= 0) break
                for (i in 0 until n) {
                    val b = buffer[i]
                    val at = pos + i
                    if (!open) {
                        add(at)
                        open = true
                        headLen = 0
                        lineBytes = 0
                        lineStart = at
                    } else if (lineBytes >= Texts.MAX_LINE && ((b.toInt() and 0xC0) != 0x80 || lineBytes >= Texts.MAX_LINE + 3) && b != '\n'.code.toByte()) {
                        // Too long: carry on in a row of its own, at a character's start.
                        finish()
                        continued = true
                        add(at)
                        headLen = 0
                        lineBytes = 0
                    }
                    if (b == '\n'.code.toByte()) {
                        finish()
                        continued = false
                        open = false
                        continue
                    }
                    if (headLen < head.size) head[headLen++] = b
                    lineBytes++
                }
                pos += n
            }
            if (open) {
                finish()
            }
            return TextBook(source, starts, count, fences, code)
        }
    }
}
