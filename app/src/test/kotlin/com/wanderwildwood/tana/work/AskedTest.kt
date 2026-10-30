package com.wanderwildwood.tana.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AskedTest {
    @Test fun internalStorage() {
        assertEquals("/storage/emulated/0/Music/Albums", Asked.fromDocumentId("primary:Music/Albums")?.path)
        assertEquals("/storage/emulated/0", Asked.fromDocumentId("primary:")?.path)
    }

    @Test fun anSdCard() {
        assertEquals("/storage/74C4-3B51/Books", Asked.fromDocumentId("74C4-3B51:Books")?.path)
    }

    @Test fun somethingThatIsNotAVolumeAddress() {
        assertNull(Asked.fromDocumentId("msf:1234"))
        assertNull(Asked.fromDocumentId("nonsense"))
    }

    @Test fun aPathIsTurnedIntoItsFolderAndName() {
        val r = Asked.revealPath("/storage/emulated/0/Download/my photo \u00e9.gif")!!
        assertEquals("storage/emulated/0/Download", r.folder.path)
        assertEquals("my photo \u00e9.gif", r.name)
    }

    @Test fun aPathIsTidiedBeforeItIsUsed() {
        val r = Asked.revealPath("/storage/emulated/0/Download/../Pictures//./x.png")!!
        assertEquals("storage/emulated/0/Pictures", r.folder.path)
        assertEquals("x.png", r.name)
    }

    @Test fun aPathThatCannotBeShownIsRefused() {
        assertNull(Asked.revealPath(null))
        assertNull(Asked.revealPath(""))
        assertNull(Asked.revealPath("Download/x.gif"))
        assertNull(Asked.revealPath("/"))
    }

    @Test fun theNameIsFoundExactlyThenIgnoringCase() {
        val names = listOf("a.txt", "B.TXT", "b.txt")
        assertEquals(2, Asked.indexOfName(names, "b.txt"))
        assertEquals(1, Asked.indexOfName(listOf("a.txt", "B.TXT"), "b.txt"))
        assertEquals(-1, Asked.indexOfName(names, "c.txt"))
    }
}
