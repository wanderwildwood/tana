package com.wanderwildwood.tana.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridRowsTest {

    @Test fun theKompaktFitsFourRowsWhereASquareMissedByAPixel() {
        // 480 wide, the list 557 tall, the bar 54: a square row is 142, and four of them 568.
        val row = gridRowPx(480, 557, 54)
        assertEquals(139, row)
        assertTrue(row * 4 <= 557)
    }

    @Test fun rowsNeverOverrunThePage() {
        for (height in 100..1600 step 7) {
            val row = gridRowPx(480, height, 54)
            val rows = height / row
            assertTrue("height $height", row * rows <= height && height - row * rows < rows)
        }
    }

    @Test fun aSquareStaysNearSquare() {
        // From a page of three squares up, a row is within a fifth of square.
        for (height in 426..1600) {
            val row = gridRowPx(480, height, 54)
            assertTrue("height $height row $row", row in 113..171)
        }
    }

    @Test fun aSliverOfAPageIsOneRow() {
        assertEquals(40, gridRowPx(480, 40, 54))
        assertEquals(1, gridRowPx(0, 0, 0))
    }
}
