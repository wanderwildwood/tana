package com.wanderwildwood.tana.work

import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.store.LocalStore
import com.wanderwildwood.tana.store.Loc
import com.wanderwildwood.tana.store.Store
import com.wanderwildwood.tana.store.StoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class TrashTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private var clock = 1_800_000_000_000L
    private lateinit var trash: Trash
    private val day = 24L * 60 * 60 * 1000

    @Before fun setUp() {
        root = tmp.newFolder("storage").canonicalFile
        trash = Trash(listOf(root)) { clock }
    }

    private fun write(path: String, text: String = "x"): File =
        File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }

    // ---- the plain rules

    @Test fun expiryIsAfterSevenDays() {
        assertFalse(trashExpired(clock - 7 * day, clock))
        assertTrue(trashExpired(clock - 7 * day - 1, clock))
        assertFalse(trashExpired(clock + day, clock))
        assertEquals(clock + 7 * day, trashedUntil(clock))
    }

    @Test fun slotMovesOnWhileTaken() {
        assertEquals("100", trashSlot(100, emptySet()))
        assertEquals("102", trashSlot(100, setOf("100", "101")))
    }

    @Test fun sidecarRoundTripsOddNames() {
        val meta = TrashMeta("/storage/emulated/0/Docs/a\nb\\c.txt", 42)
        assertEquals(meta, parseTrashMeta(trashMetaText(meta)))
    }

    @Test fun sidecarThatClimbsOrIsRelativeIsRefused() {
        assertNull(parseTrashMeta("from: /storage/../etc/passwd\nat: 1\n"))
        assertNull(parseTrashMeta("from: Docs/a.txt\nat: 1\n"))
        assertNull(parseTrashMeta("from: /a//b\nat: 1\n"))
        assertNull(parseTrashMeta("from: /a/./b\nat: 1\n"))
        assertNull(parseTrashMeta("from: /a/b\n"))
        assertNull(parseTrashMeta("at: 3\n"))
        assertNull(parseTrashMeta(null))
        assertNotNull(parseTrashMeta("from: /a/b\nat: 3\n"))
    }

    @Test fun restoredNames() {
        assertEquals("a.txt", restoredName("a.txt") { false })
        assertEquals("a (restored).txt", restoredName("a.txt") { it == "a.txt" })
        assertEquals("a (restored 2).txt", restoredName("a.txt") { it == "a.txt" || it == "a (restored).txt" })
        assertEquals("noext (restored)", restoredName("noext") { it == "noext" })
        assertEquals(".hidden (restored)", restoredName(".hidden") { it == ".hidden" })
    }

    @Test fun pathNamesTheTrash() {
        assertTrue(pathIsInTrash("storage/emulated/0/.Cabinet-trash/1/a"))
        assertFalse(pathIsInTrash("storage/emulated/0/Docs/a"))
    }

    // ---- against real folders

    @Test fun deleteThenRestore() {
        val f = write("Docs/a.txt", "hello")
        f.setLastModified(1_700_000_000_000L)
        val item = trash.put(f)
        assertFalse(f.exists())
        val listed = trash.list()
        assertEquals(1, listed.size)
        assertEquals("a.txt", listed[0].name)
        assertEquals(File(root, "Docs/a.txt").path, listed[0].from)
        assertEquals(clock, listed[0].at)
        val back = trash.restore(item)
        assertEquals(File(root, "Docs/a.txt"), back)
        assertEquals("hello", back.readText())
        assertEquals(1_700_000_000_000L, back.lastModified())
        assertTrue(trash.list().isEmpty())
        // No sidecar or empty folder left behind.
        assertEquals(listOf(".nomedia"), File(root, TRASH_FOLDER).list()!!.toList())
    }

    @Test fun aFolderGoesWithEverythingInIt() {
        write("Pics/2024/one.jpg", "1")
        write("Pics/2024/deep/two.jpg", "2")
        val item = trash.put(File(root, "Pics"))
        assertFalse(File(root, "Pics").exists())
        assertTrue(trash.list()[0].isFolder)
        val back = trash.restore(item)
        assertEquals("2", File(back, "2024/deep/two.jpg").readText())
    }

    @Test fun restoreNeverOverwrites() {
        val item = trash.put(write("Docs/a.txt", "old"))
        write("Docs/a.txt", "new")
        val back = trash.restore(item)
        assertEquals("a (restored).txt", back.name)
        assertEquals("old", back.readText())
        assertEquals("new", File(root, "Docs/a.txt").readText())
        // And again: another one, another number.
        val second = trash.put(write("Docs/b.txt", "b1"))
        write("Docs/b.txt", "b2")
        write("Docs/b (restored).txt", "b3")
        assertEquals("b (restored 2).txt", trash.restore(second).name)
        assertEquals("b3", File(root, "Docs/b (restored).txt").readText())
    }

    @Test fun twoDeletesInTheSameMillisecondKeepApart() {
        val a = trash.put(write("a.txt", "A"))
        val b = trash.put(write("b.txt", "B"))
        assertTrue(a.slot != b.slot)
        assertEquals(2, trash.list().size)
    }

    @Test fun sameNameDeletedTwice() {
        val first = trash.put(write("Docs/a.txt", "one"))
        clock += 1000
        val second = trash.put(write("Docs/a.txt", "two"))
        assertEquals("two", trash.restore(second).readText())
        assertEquals("one", trash.restore(first).readText())
        assertEquals("a (restored).txt", File(root, "Docs/a (restored).txt").name)
    }

    @Test fun restoreMakesTheFolderIfItIsGone() {
        val item = trash.put(write("Docs/sub/a.txt"))
        File(root, "Docs").deleteRecursively()
        val back = trash.restore(item)
        assertTrue(back.exists())
        assertEquals(File(root, "Docs/sub/a.txt"), back)
    }

    @Test fun newestFirst() {
        trash.put(write("old.txt"))
        clock += 1000
        trash.put(write("new.txt"))
        assertEquals(listOf("new.txt", "old.txt"), trash.list().map { it.name })
    }

    @Test fun purgeRemovesOnlyTheExpired() {
        trash.put(write("old.txt"))
        clock += 3 * day
        trash.put(write("mid.txt"))
        clock += 5 * day // old is 8 days, mid is 5
        assertEquals(1, trash.purgeExpired())
        assertEquals(listOf("mid.txt"), trash.list().map { it.name })
        val bin = File(root, TRASH_FOLDER).list()!!.toSet()
        assertEquals(3, bin.size) // .nomedia, mid's folder and sidecar
    }

    @Test fun purgeAtExactlySevenDaysKeeps() {
        trash.put(write("a.txt"))
        clock += 7 * day
        assertEquals(0, trash.purgeExpired())
        clock += 1
        assertEquals(1, trash.purgeExpired())
    }

    @Test fun deleteForeverAndEmpty() {
        val a = trash.put(write("a.txt"))
        trash.put(write("d/b.txt"))
        trash.deleteForever(a)
        assertEquals(1, trash.list().size)
        assertEquals(1, trash.empty())
        assertTrue(trash.list().isEmpty())
    }

    @Test fun aFolderWithoutASidecarStillShows() {
        val item = trash.put(write("a.txt", "A"))
        File(root, "$TRASH_FOLDER/${item.slot}.meta").delete()
        val listed = trash.list()
        assertEquals(1, listed.size)
        assertEquals(File(root, "a.txt").path, listed[0].from)
        assertEquals("A", trash.restore(listed[0]).readText())
    }

    @Test fun aDamagedSidecarCannotSendItAnywhereElse() {
        val item = trash.put(write("Docs/a.txt", "A"))
        File(root, "$TRASH_FOLDER/${item.slot}.meta").writeText("from: /etc/../../outside/a.txt\nat: ${item.at}\n")
        val listed = trash.list()
        assertEquals(File(root, "a.txt").path, listed[0].from)
        assertEquals(File(root, "a.txt"), trash.restore(listed[0]))
    }

    @Test fun restoreRefusesAnOriginOutsideItsVolume() {
        val item = trash.put(write("a.txt"))
        val elsewhere = tmp.newFolder("elsewhere").canonicalFile
        val forged = item.copy(from = File(elsewhere, "a.txt").path)
        try {
            trash.restore(forged)
            fail("restored outside the volume")
        } catch (e: TrashException) {
            // good
        }
        assertEquals(1, trash.list().size)
        assertFalse(File(elsewhere, "a.txt").exists())
    }

    @Test fun restoreRefusesALinkedFolderThatLeadsOut() {
        val item = trash.put(write("Docs/a.txt"))
        File(root, "Docs").deleteRecursively()
        val outside = tmp.newFolder("outside").canonicalFile
        Files.createSymbolicLink(File(root, "Docs").toPath(), outside.toPath())
        try {
            trash.restore(item)
            fail("followed the link out")
        } catch (e: TrashException) {
            // good
        }
        assertEquals(0, outside.list()!!.size)
        assertEquals(1, trash.list().size)
    }

    @Test fun deletingALinkMovesTheLinkNotWhatItPointsAt() {
        val outside = tmp.newFolder("outside2").canonicalFile
        File(outside, "keep.txt").writeText("keep")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        val item = trash.put(File(root, "link"))
        assertTrue(File(outside, "keep.txt").exists())
        trash.deleteForever(item)
        assertTrue("purge followed a link", File(outside, "keep.txt").exists())
    }

    @Test fun aLinkedFolderOutOfStorageIsNotTrashed() {
        val outside = tmp.newFolder("outside3").canonicalFile
        File(outside, "f.txt").writeText("f")
        Files.createSymbolicLink(File(root, "door").toPath(), outside.toPath())
        try {
            trash.put(File(root, "door/f.txt"))
            fail("reached through a link")
        } catch (e: TrashException) {
            // good
        }
        assertTrue(File(outside, "f.txt").exists())
    }

    @Test fun neverTrashesTheVolumeItselfOrTheTrash() {
        for (f in listOf(root, File(root, TRASH_FOLDER), File(root, "../x"))) {
            try { trash.put(f); fail("trashed $f") } catch (e: TrashException) { }
        }
        val item = trash.put(write("a.txt"))
        assertTrue(trash.isInTrash(File(root, "$TRASH_FOLDER/${item.slot}/a.txt")))
        assertFalse(trash.isInTrash(File(root, "a.txt")))
    }

    @Test fun missingFileIsAnErrorNotASilentSuccess() {
        try { trash.put(File(root, "nope.txt")); fail() } catch (e: TrashException) { }
    }

    @Test fun deleteForeverRefusesAnOddSlot() {
        val item = trash.put(write("a.txt"))
        write("keep.txt", "k")
        try { trash.deleteForever(item.copy(slot = "../..")); fail() } catch (e: TrashException) { }
        assertTrue(File(root, "keep.txt").exists())
    }

    // ---- through Transfer.delete

    @Test fun transferDeleteGoesToTheTrashOnThePhoneAndForGoodElsewhere() {
        val phone = LocalStore(File("/"))
        val serverRoot = tmp.newFolder("server")
        val server = LocalStore(serverRoot)
        File(serverRoot, "s.txt").writeText("s")
        write("p.txt", "p")
        val resolve: (String) -> Store = { if (it == LocalStore.ID) phone else error(it) }
        val onPhone = phone.stat(phone.pathOf(File(root, "p.txt")))!!
        Transfer(resolve, trash = trash).delete(listOf(onPhone))
        assertFalse(File(root, "p.txt").exists())
        assertEquals(1, trash.list().size)
        // Something already in the trash is removed for good.
        val inBin = File(root, "${TRASH_FOLDER}/${trash.list()[0].slot}/p.txt")
        val entry = phone.stat(phone.pathOf(inBin))!!
        Transfer(resolve, trash = trash).delete(listOf(entry))
        assertFalse(inBin.exists())
        // No trash given (a server, say): gone.
        val s = server.stat("s.txt")!!
        Transfer({ server }, trash = null).delete(listOf(s))
        assertFalse(File(serverRoot, "s.txt").exists())
    }

    @Test fun transferDeleteThatCannotTrashKeepsTheFile() {
        val phone = LocalStore(File("/"))
        val outside = tmp.newFolder("notStorage")
        val f = File(outside, "keep.txt").apply { writeText("k") }
        val entry = phone.stat(phone.pathOf(f))!!
        try {
            Transfer({ phone }, trash = trash).delete(listOf(entry))
            fail("deleted for good")
        } catch (e: StoreException) {
            assertEquals(StoreException.Reason.CANNOT_TRASH, e.reason)
        }
        assertTrue(f.exists())
    }
}
