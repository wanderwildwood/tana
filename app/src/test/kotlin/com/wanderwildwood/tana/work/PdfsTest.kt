package com.wanderwildwood.tana.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfsTest {

    @Test fun pdfsAreKnownByTheirExtension() {
        assertTrue(Pdfs.canRead("Manual.PDF"))
        assertTrue(!Pdfs.canRead("notes.pdf.txt"))
    }

    @Test fun aTypedPageNumber() {
        assertEquals(0, Pdfs.pageFrom("1", 80))
        assertEquals(79, Pdfs.pageFrom(" 80 ", 80))
        assertNull(Pdfs.pageFrom("0", 80))
        assertNull(Pdfs.pageFrom("81", 80))
        assertNull(Pdfs.pageFrom("", 80))
        assertNull(Pdfs.pageFrom("twelve", 80))
    }

    @Test fun aPageIsKnownByNameAndSize() {
        assertEquals(PageMarks.key("guide.pdf", 1000), PageMarks.key("guide.pdf", 1000))
        assertNotEquals(PageMarks.key("guide.pdf", 1000), PageMarks.key("guide.pdf", 1001))
        assertNotEquals(PageMarks.key("guide.pdf", 1000), PageMarks.key("other.pdf", 1000))
        // Hashed: the name itself is not what is stored.
        assertTrue("guide" !in PageMarks.key("guide.pdf", 1000))
    }

    @Test fun aMarkReadsBackAsItWasWritten() {
        assertEquals(12 to 1_760_000_000_000L, PageMarks.decode(PageMarks.encode(12, 1_760_000_000_000L)))
        assertNull(PageMarks.decode("nonsense"))
        assertNull(PageMarks.decode("12"))
        assertNull(PageMarks.decode("-1@5"))
    }

    @Test fun onlyTheMostLatelyReadAreKept() {
        val read = (1..105).associate { "book$it" to it.toLong() }
        val gone = PageMarks.overflow(read, PageMarks.KEEP)
        assertEquals((1..5).map { "book$it" }.toSet(), gone.toSet())
        assertTrue(PageMarks.overflow(read.filterKeys { it != "book1" && it != "book2" && it != "book3" && it != "book4" && it != "book5" }, PageMarks.KEEP).isEmpty())
    }

    // An A4 page in points, on a space 480 wide and 700 tall.
    private val pw = 595f
    private val ph = 842f
    private val bw = 480f
    private val bh = 700f

    @Test fun aPageFitsWholeAndCentred() {
        val f = Frame.fit(pw, ph, bw, bh)
        val s = Frame.fitScale(pw, ph, bw, bh)
        assertEquals(1f, f.zoom)
        // Narrower than tall, it is the width that fits, and the height that is centred.
        assertEquals(0f, f.left, 0.01f)
        assertEquals((bh - ph * s) / 2, f.top, 0.01f)
    }

    @Test fun whatIsUnderTheFingersStaysThere() {
        val f = Frame.fit(pw, ph, bw, bh)
        val s = Frame.fitScale(pw, ph, bw, bh)
        val cx = 240f
        val cy = 350f
        // The point of the page under the fingers before, in points.
        val px = (cx - f.left) / s
        val py = (cy - f.top) / s
        val z = f.pinched(2f, cx, cy, 0f, 0f, pw, ph, bw, bh)
        assertEquals(2f, z.zoom)
        assertEquals(cx, z.left + px * s * 2, 0.5f)
        assertEquals(cy, z.top + py * s * 2, 0.5f)
    }

    @Test fun zoomStopsAtEachEnd() {
        val f = Frame.fit(pw, ph, bw, bh)
        assertEquals(f, f.pinched(0.5f, 240f, 350f, 0f, 0f, pw, ph, bw, bh))
        assertEquals(Frame.MOST, f.pinched(50f, 240f, 350f, 0f, 0f, pw, ph, bw, bh).zoom)
    }

    @Test fun aZoomedPageIsNotMovedPastItsEdges() {
        val z = Frame.fit(pw, ph, bw, bh).pinched(3f, 240f, 350f, 0f, 0f, pw, ph, bw, bh)
        val shown = z.scale(pw, ph, bw, bh)
        val right = z.moved(10_000f, 10_000f, pw, ph, bw, bh)
        assertEquals(0f, right.left, 0.01f)
        assertEquals(0f, right.top, 0.01f)
        val left = z.moved(-10_000f, -10_000f, pw, ph, bw, bh)
        assertEquals(bw - pw * shown, left.left, 0.01f)
        assertEquals(bh - ph * shown, left.top, 0.01f)
    }
}
