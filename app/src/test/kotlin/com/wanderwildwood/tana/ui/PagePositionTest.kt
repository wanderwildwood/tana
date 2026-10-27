package com.wanderwildwood.tana.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PagePositionTest {
    private val row = 70
    private val viewport = 700

    /** [count] rows of [row] px laid out from [first], the way the list holds them on a page. */
    private fun laid(first: Int, count: Int, height: Int = row) =
        List(count) { LaidRow(first + it, it * height, height) }

    @Test
    fun aListThatFitsOnePageSaysNothing() {
        assertNull(pagePosition(laid(0, 5), 5, viewport))
        assertNull(pagePosition(laid(0, 10), 10, viewport))
        assertNull(pagePosition(emptyList(), 0, viewport))
    }

    @Test
    fun theFirstPage() {
        // 234 rows; ten whole rows show and an eleventh is cut by the foot.
        val rows = laid(0, 10) + LaidRow(10, 700, row)
        assertEquals(PagePosition(1, 10, 234, 1, 24), pagePosition(rows, 234, viewport))
    }

    @Test
    fun aMiddlePage() {
        val rows = laid(40, 10) + LaidRow(50, 700, row)
        assertEquals(PagePosition(41, 50, 234, 5, 24), pagePosition(rows, 234, viewport))
    }

    @Test
    fun theLastPageIsAlwaysNOfN() {
        // A short last page: rows 231 to 234 only.
        assertEquals(PagePosition(231, 234, 234, 24, 24), pagePosition(laid(230, 4), 234, viewport))
        // A last page that is exactly full.
        assertEquals(PagePosition(221, 230, 230, 23, 23), pagePosition(laid(220, 10), 230, viewport))
    }

    @Test
    fun turningForwardFromTheTopCountsTheSamePagesAsTheTotal() {
        val rowsInList = 234
        var first = 0
        var turns = 1
        var total = 0
        while (true) {
            val count = minOf(10, rowsInList - first)
            val rows = laid(first, count) + if (first + count < rowsInList) listOf(LaidRow(first + count, 700, row)) else emptyList()
            val at = pagePosition(rows, rowsInList, viewport)!!
            assertEquals(turns, at.page)
            total = at.pages
            if (first + count >= rowsInList) break
            first += count
            turns++
        }
        assertEquals(total, turns)
    }

    @Test
    fun rowsOfSlightlyDifferentHeightsStillCountPagesOfTheirOwn() {
        // Pages of nine whole rows at 76 px with a tenth cut off.
        val rows = laid(18, 9, 76) + LaidRow(27, 684, 76)
        val at = pagePosition(rows, 100, viewport)!!
        assertEquals(19, at.first)
        assertEquals(27, at.last)
        assertEquals(3, at.page)
        assertEquals(12, at.pages)
    }

    @Test
    fun aRowTallerThanTheScreenIsAPageOfOne() {
        val at = pagePosition(listOf(LaidRow(3, -200, 1500)), 6, viewport)!!
        assertEquals(4, at.first)
        assertEquals(4, at.last)
    }
}
