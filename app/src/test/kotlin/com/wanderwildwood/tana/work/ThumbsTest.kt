package com.wanderwildwood.tana.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThumbsTest {

    @Test fun theShorterSideBecomesTheSide() {
        assertEquals(320 to 240, Thumbs.fit(4000, 3000, 240))
        assertEquals(240 to 320, Thumbs.fit(3000, 4000, 240))
        assertEquals(240 to 240, Thumbs.fit(1000, 1000, 240))
    }

    @Test fun aSmallPictureIsNotEnlarged() {
        assertEquals(100 to 80, Thumbs.fit(100, 80, 240))
        assertEquals(240 to 240, Thumbs.fit(240, 240, 240))
    }

    @Test fun aSliverKeepsAtLeastOnePixel() {
        assertEquals(1 to 1, Thumbs.fit(0, 0, 240))
        assertEquals(240, Thumbs.fit(100000, 300, 240).second)
    }

    @Test fun theKeyChangesWhenTheFileDoes() {
        val a = Thumbs.key("storage/emulated/0/DCIM/Camera/a.jpg", 1000, 5)
        assertEquals(a, Thumbs.key("storage/emulated/0/DCIM/Camera/a.jpg", 1000, 5))
        assertNotEquals(a, Thumbs.key("storage/emulated/0/DCIM/Camera/a.jpg", 2000, 5))
        assertNotEquals(a, Thumbs.key("storage/emulated/0/DCIM/Camera/a.jpg", 1000, 6))
        assertNotEquals(a, Thumbs.key("storage/emulated/0/DCIM/Camera/b.jpg", 1000, 5))
        assertEquals(40, a.length)
    }
}
