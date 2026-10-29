package com.wanderwildwood.tana.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TextBookTest {

    private fun book(text: String, markdown: Boolean = false) =
        TextBook.open(ArraySource(text.toByteArray()), markdown)

    @Test fun extensionsDecideWhatOpensHere() {
        assertTrue(Texts.canRead("notes.TXT"))
        assertTrue(Texts.canRead("README.md"))
        assertTrue(Texts.canRead("a.markdown"))
        assertTrue(Texts.canRead("app.log"))
        assertTrue(Texts.canRead("c.json"))
        assertTrue(Texts.canRead("c.yaml"))
        assertTrue(Texts.canRead("c.ini"))
        assertFalse(Texts.canRead("table.csv"))
        assertFalse(Texts.canRead("photo.jpg"))
        assertFalse(Texts.canRead("notes.txt.zip"))
        assertTrue(Texts.isMarkdown("a.MD"))
        assertFalse(Texts.isMarkdown("a.txt"))
        assertTrue(Texts.isTextMime("text/plain"))
        assertTrue(Texts.isMarkdownMime("text/markdown"))
        assertFalse(Texts.isTextMime("application/pdf"))
    }

    @Test fun binaryIsToldFromText() {
        assertFalse(Texts.looksBinary("plain words\nand tabs\t\n".toByteArray()))
        assertFalse(Texts.looksBinary("café – naïve\n".toByteArray()))
        assertTrue(Texts.looksBinary(byteArrayOf(0x50, 0x4B, 3, 4, 0, 0, 1)))
        assertTrue(Texts.looksBinary(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'a'.code.toByte(), 0)))
        assertTrue(Texts.looksBinary(ByteArray(100) { 1 }))
    }

    @Test fun aBinaryFileIsRefused() {
        try {
            TextBook.open(ArraySource(byteArrayOf(1, 2, 0, 3, 4)), false)
            fail()
        } catch (e: NotText) {
        }
    }

    @Test fun linesAreFoundWhateverTheBreak() {
        val b = book("one\r\ntwo\n\nfour")
        assertEquals(4, b.lineCount)
        assertEquals(listOf("one", "two", "", "four"), (0 until 4).map(b::line))
        assertEquals(2, book("a\nb\n").lineCount)
        assertEquals(0, book("").lineCount)
    }

    @Test fun aByteOrderMarkIsSkipped() {
        val b = TextBook.open(ArraySource(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi\nyou".toByteArray()), false)
        assertEquals("hi", b.line(0))
        assertEquals("you", b.line(1))
    }

    @Test fun utf8IsReadAndOtherBytesFallBack() {
        val bytes = "héllo ✓\n".toByteArray() + byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte(), '\n'.code.toByte())
        val b = TextBook.open(ArraySource(bytes), false)
        assertEquals("héllo ✓", b.line(0))
        assertEquals("café", b.line(1))
    }

    @Test fun aVeryLongLineIsCutIntoRowsWithoutBreakingACharacter() {
        val text = "é".repeat(5000) + "\nnext"
        val b = book(text)
        assertTrue(b.lineCount >= 3)
        val joined = (0 until b.lineCount - 1).joinToString("") { b.line(it) }
        assertEquals("é".repeat(5000), joined)
        assertEquals("next", b.line(b.lineCount - 1))
        (0 until b.lineCount).forEach { assertFalse(b.line(it).contains('�')) }
    }

    @Test fun aFileOfManyMegabytesIsIndexedAndReadAtAnyLine() {
        val sb = StringBuilder()
        val lines = 200_000
        for (i in 0 until lines) sb.append("line ").append(i).append(" of a long file, with some words in it\n")
        val bytes = sb.toString().toByteArray()
        assertTrue(bytes.size > 7_000_000)
        val b = TextBook.open(ArraySource(bytes), false)
        assertEquals(lines, b.lineCount)
        assertEquals("line 0 of a long file, with some words in it", b.line(0))
        assertEquals("line 123456 of a long file, with some words in it", b.line(123456))
        assertEquals("line 199999 of a long file, with some words in it", b.line(lines - 1))
    }

    @Test fun aFileTooLargeIsRefused() {
        val big = object : ByteSource {
            override val size = Texts.MAX_BYTES + 1
            override fun read(pos: Long, into: ByteArray, off: Int, len: Int) = -1
            override fun close() = Unit
        }
        try {
            TextBook.open(big, false)
            fail()
        } catch (e: TooBig) {
        }
    }

    @Test fun fencedCodeIsMarkedWhileIndexing() {
        val b = book("intro\n```kotlin\nval x = 1\n\n```\nafter\n~~~\nmore\n~~~", markdown = true)
        assertEquals(listOf(false, true, false, false, true, false, true, false, true), (0 until b.lineCount).map(b::isFence))
        assertEquals(listOf(false, false, true, true, false, false, false, true, false), (0 until b.lineCount).map(b::isCode))
        val plain = book("```\nx\n```")
        assertFalse(plain.isFence(0))
    }
}
