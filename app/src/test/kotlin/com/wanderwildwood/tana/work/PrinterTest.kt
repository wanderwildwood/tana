package com.wanderwildwood.tana.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterTest {

    // A4 inside half-inch margins, in points.
    private val across = 595f - 72f
    private val down = 842f - 72f

    @Test fun pdfsAndPicturesPrint() {
        assertTrue(Printer.canPrint("Manual.PDF"))
        assertTrue(Printer.canPrint("beach.jpg"))
        assertTrue(Printer.canPrint("IMG_0001.HEIC"))
        assertFalse(Printer.canPrint("notes.txt"))
        assertFalse(Printer.canPrint("song.mp3"))
        assertFalse(Printer.canPrint("jpg"))
    }

    @Test fun anUprightPictureFillsUprightPaper() {
        val p = Printer.place(3000, 4000, across, down)
        assertFalse(p.turned)
        assertEquals(minOf(across / 3000, down / 4000), p.scale, 1e-6f)
    }

    @Test fun aWidePictureIsTurnedOnUprightPaper() {
        val p = Printer.place(4000, 3000, across, down)
        assertTrue(p.turned)
        // Turned, its 4000 pixels run down the page and its 3000 across.
        assertEquals(minOf(across / 3000, down / 4000), p.scale, 1e-6f)
        assertFalse(Printer.place(4000, 3000, down, across).turned)
        assertFalse(Printer.place(3000, 3000, across, down).turned)
    }

    @Test fun aBigPhotoIsReadNoFinerThanThePaperShows() {
        val (w, h) = Printer.readingSize(6000, 8000, across, down)!!
        // Fitted, its 6000 pixels fill the 523 points across: 523 / 72 * 300 dots.
        assertEquals(across / 72f * Printer.DPI, w.toFloat(), 2f)
        assertEquals(w.toFloat() / h, 6000f / 8000f, 0.01f)
    }

    @Test fun aSmallPictureIsReadWhole() {
        assertNull(Printer.readingSize(640, 480, across, down))
    }
}
