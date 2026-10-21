package com.wanderwildwood.tana.ui

import com.wanderwildwood.tana.store.LocalStore
import com.wanderwildwood.tana.store.Loc
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanPullTest {
    private val folder = Place.Folder(Loc(LocalStore.ID, "Download"))

    @Test
    fun aFolderOrRecentThatHasBeenReadCanBeReadAgain() {
        assertTrue(canPull(folder, Reading.DONE))
        assertTrue(canPull(Place.Recent, Reading.DONE))
    }

    @Test
    fun notWhileItIsStillBeingReadOrHasFailed() {
        assertFalse(canPull(folder, Reading.NOT_YET))
        assertFalse(canPull(folder, Reading.FAILED))
    }

    @Test
    fun homeListsNothingFetched() {
        assertFalse(canPull(Place.Home, Reading.DONE))
    }
}
