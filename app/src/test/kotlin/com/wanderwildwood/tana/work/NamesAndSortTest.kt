package com.wanderwildwood.tana.work

import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.store.LocalStore
import com.wanderwildwood.tana.store.Loc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NamesAndSortTest {

    @Test fun extensions() {
        assertEquals("jpg", Names.extension("Photo.JPG"))
        assertEquals("gz", Names.extension("backup.tar.gz"))
        assertEquals("", Names.extension(".nomedia"))
        assertEquals("", Names.extension("README"))
        assertEquals("", Names.extension("trailing."))
    }

    @Test fun uniqueNamesKeepTheExtension() {
        val taken = setOf("a.txt", "a (1).txt", "notes")
        assertEquals("b.txt", Names.unique("b.txt") { it in taken })
        assertEquals("a (2).txt", Names.unique("a.txt") { it in taken })
        assertEquals("notes (1)", Names.unique("notes") { it in taken })
    }

    @Test fun typedNames() {
        assertEquals("Holiday", Names.valid("  Holiday "))
        assertNull(Names.valid(""))
        assertNull(Names.valid(".."))
        assertNull(Names.valid("a/b"))
    }

    @Test fun kindsGoByExtensionAndNeverMatchFolders() {
        assertTrue(Kind.DOCUMENTS.matches("book.EPUB", false))
        assertFalse(Kind.DOCUMENTS.matches("book.epub", true))
        assertTrue(Kind.ANY.matches("anything", true))
        assertTrue(Kind.APPS.matches("files.apk", false))
    }

    private fun e(name: String, folder: Boolean = false, size: Long = 0, modified: Long = 0) =
        Entry(Loc(LocalStore.ID, "x/$name"), folder, size, modified)

    @Test fun foldersComeFirstWhateverTheOrder() {
        val list = listOf(e("b.txt", size = 9), e("Zed", folder = true), e("a.txt", size = 1), e("Alpha", folder = true))
        assertEquals(listOf("Alpha", "Zed", "a.txt", "b.txt"), Sort(SortBy.NAME).apply(list).map { it.name })
        assertEquals(listOf("Zed", "Alpha", "b.txt", "a.txt"), Sort(SortBy.NAME, reversed = true).apply(list).map { it.name })
        assertEquals(listOf("Alpha", "Zed", "b.txt", "a.txt"), Sort(SortBy.SIZE).apply(list).map { it.name })
    }

    @Test fun numbersSortAsNumbers() {
        val list = listOf(e("Track 10.mp3"), e("Track 2.mp3"), e("Track 1.mp3"), e("track 3.mp3"))
        assertEquals(
            listOf("Track 1.mp3", "Track 2.mp3", "track 3.mp3", "Track 10.mp3"),
            Sort(SortBy.NAME).apply(list).map { it.name },
        )
    }

    @Test fun datesNewestFirstThenTurnedRound() {
        val list = listOf(e("old", modified = 1), e("new", modified = 3), e("mid", modified = 2))
        assertEquals(listOf("new", "mid", "old"), Sort(SortBy.DATE).apply(list).map { it.name })
        assertEquals(listOf("old", "mid", "new"), Sort(SortBy.DATE).pressed(SortBy.DATE).apply(list).map { it.name })
    }

    @Test fun pressingAnotherOrderStartsItTheUsualWayRound() {
        assertEquals(Sort(SortBy.SIZE, false), Sort(SortBy.NAME, true).pressed(SortBy.SIZE))
    }

    @Test fun anEmptyNameDoesNotBreakTheOrder() {
        val list = listOf(e(""), e("1"), e("a"))
        assertEquals(3, Sort(SortBy.NAME).apply(list).size)
    }

    @Test fun placesKnowWhatIsInsideThem() {
        val sd = Loc(LocalStore.ID, "storage/74C4-3B51")
        assertTrue(Loc(LocalStore.ID, "storage/74C4-3B51/Music").isWithin(sd))
        assertTrue(sd.isWithin(sd))
        assertFalse(Loc(LocalStore.ID, "storage/74C4-3B51-other").isWithin(sd))
        assertFalse(Loc("smb:x", "storage/74C4-3B51/Music").isWithin(sd))
        assertEquals(Loc(LocalStore.ID, ""), Loc(LocalStore.ID, "storage").parent)
    }
}
