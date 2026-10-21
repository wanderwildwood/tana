package com.wanderwildwood.tana.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeTurnTest {
    @Test
    fun upTurnsOnWhereverTheListIs() {
        assertEquals(SwipeTurn.Forward, swipeTurn(-5f, atTop = true, canPull = true))
        assertEquals(SwipeTurn.Forward, swipeTurn(-5f, atTop = false, canPull = false))
    }

    @Test
    fun downOnALaterPageTurnsBackEvenWhenTheListCanPull() {
        assertEquals(SwipeTurn.Back, swipeTurn(5f, atTop = false, canPull = true))
        assertEquals(SwipeTurn.Back, swipeTurn(5f, atTop = false, canPull = false))
    }

    @Test
    fun downOnTheFirstPagePullsOnlyWhenTheListAsks() {
        assertEquals(SwipeTurn.Pull, swipeTurn(5f, atTop = true, canPull = true))
        assertEquals(SwipeTurn.None, swipeTurn(5f, atTop = true, canPull = false))
    }

    @Test
    fun noMovementDoesNothing() {
        assertEquals(SwipeTurn.None, swipeTurn(0f, atTop = true, canPull = true))
        assertEquals(SwipeTurn.None, swipeTurn(0f, atTop = false, canPull = false))
    }
}
