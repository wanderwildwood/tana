package com.wanderwildwood.tana.work

import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.store.LocalStore
import com.wanderwildwood.tana.store.Loc
import com.wanderwildwood.tana.store.Store
import com.wanderwildwood.tana.store.StoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The engine against real folders on disk, through the same store the phone uses. A second
 * "store" over another folder stands in for a server, so crossing from one store to another —
 * which is what a copy to a server is — takes the same path it takes on the phone.
 */
class TransferTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var phone: LocalStore
    private lateinit var server: Store
    private lateinit var root: File
    private lateinit var serverRoot: File

    private fun resolve(id: String): Store = when (id) {
        LocalStore.ID -> phone
        SERVER -> server
        else -> error(id)
    }

    @Before fun setUp() {
        root = tmp.newFolder("phone")
        serverRoot = tmp.newFolder("server")
        phone = LocalStore(root)
        server = Renamed(LocalStore(serverRoot), SERVER)
    }

    private fun write(path: String, text: String, base: File = root): File =
        File(base, path).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun entry(path: String, store: Store = phone): Entry = store.stat(path)!!

    private fun transfer(cancelAfterBytes: Long = Long.MAX_VALUE): Transfer {
        var seen = 0L
        return Transfer(::resolve, isCancelled = { seen > cancelAfterBytes }, onProgress = { seen = it.bytesDone })
    }

    @Test fun copiesAFolderWithEverythingInIt() {
        write("a/one.txt", "1")
        write("a/deep/two.txt", "22")
        File(root, "b").mkdir()
        val out = transfer().paste(listOf(entry("a")), Loc(LocalStore.ID, "b"), Mode.COPY, Clash.KEEP_BOTH)
        assertEquals(Outcome(1, 0), out)
        assertEquals("1", File(root, "b/a/one.txt").readText())
        assertEquals("22", File(root, "b/a/deep/two.txt").readText())
        assertTrue("the original stays", File(root, "a/one.txt").exists())
    }

    @Test fun movesAcrossStoresAndOnlyThenDeletesTheOriginal() {
        write("a/one.txt", "1")
        write("a/deep/two.txt", "22")
        transfer().paste(listOf(entry("a")), Loc(SERVER, ""), Mode.MOVE, Clash.KEEP_BOTH)
        assertEquals("22", File(serverRoot, "a/deep/two.txt").readText())
        assertFalse(File(root, "a").exists())
    }

    @Test fun aCopyIntoItsOwnFolderIsNumberedNotAsked() {
        write("x/photo.jpg", "p")
        val folder = Loc(LocalStore.ID, "x")
        assertTrue(transfer().clashes(listOf(entry("x/photo.jpg")), folder).isEmpty())
        transfer().paste(listOf(entry("x/photo.jpg")), folder, Mode.COPY, Clash.REPLACE)
        assertEquals("p", File(root, "x/photo (1).jpg").readText())
        assertEquals("p", File(root, "x/photo.jpg").readText())
    }

    @Test fun clashesAreFoundAndEachAnswerDoesWhatItSays() {
        write("src/a.txt", "new")
        write("src/b.txt", "new b")
        write("dst/a.txt", "old")
        val dst = Loc(LocalStore.ID, "dst")
        val sources = listOf(entry("src/a.txt"), entry("src/b.txt"))
        assertEquals(listOf("a.txt"), transfer().clashes(sources, dst))

        val skipped = transfer().paste(sources, dst, Mode.COPY, Clash.SKIP)
        assertEquals(Outcome(1, 1), skipped)
        assertEquals("old", File(root, "dst/a.txt").readText())

        transfer().paste(listOf(entry("src/a.txt")), dst, Mode.COPY, Clash.KEEP_BOTH)
        assertEquals("new", File(root, "dst/a (1).txt").readText())
        assertEquals("old", File(root, "dst/a.txt").readText())

        transfer().paste(listOf(entry("src/a.txt")), dst, Mode.COPY, Clash.REPLACE)
        assertEquals("new", File(root, "dst/a.txt").readText())
    }

    @Test fun aFolderCannotGoInsideItself() {
        write("a/b/c.txt", "c")
        try {
            transfer().paste(listOf(entry("a")), Loc(LocalStore.ID, "a/b"), Mode.MOVE, Clash.KEEP_BOTH)
            fail("moved a folder into itself")
        } catch (e: StoreException) {
            assertEquals(StoreException.Reason.INTO_ITSELF, e.reason)
        }
        assertTrue(File(root, "a/b/c.txt").exists())
    }

    @Test fun replacingTheFolderTheSourceIsInIsRefused() {
        // Moving d/y/y up into d, replacing, would delete d/y - which holds the source.
        write("d/y/y/f.txt", "f")
        try {
            transfer().paste(listOf(entry("d/y/y")), Loc(LocalStore.ID, "d"), Mode.MOVE, Clash.REPLACE)
            fail("replaced the source's own parent")
        } catch (e: StoreException) {
            assertEquals(StoreException.Reason.INTO_ITSELF, e.reason)
        }
        assertEquals("f", File(root, "d/y/y/f.txt").readText())
    }

    @Test fun aStoppedCopyLeavesNothingHalfWritten() {
        val big = ByteArray(3 * 1024 * 1024) { it.toByte() }
        File(root, "big.bin").writeBytes(big)
        File(root, "dst").mkdir()
        try {
            transfer(cancelAfterBytes = 1).paste(listOf(entry("big.bin")), Loc(LocalStore.ID, "dst"), Mode.COPY, Clash.KEEP_BOTH)
            fail("expected to be stopped")
        } catch (e: Cancelled) {
            // expected
        }
        assertEquals("nothing half-copied is left", 0, File(root, "dst").list()!!.size)
        assertTrue("the original is untouched", File(root, "big.bin").readBytes().contentEquals(big))
    }

    @Test fun aStoppedCrossStoreMoveKeepsTheOriginal() {
        val big = ByteArray(3 * 1024 * 1024) { (it * 7).toByte() }
        File(root, "big.bin").writeBytes(big)
        try {
            transfer(cancelAfterBytes = 1).paste(listOf(entry("big.bin")), Loc(SERVER, ""), Mode.MOVE, Clash.KEEP_BOTH)
            fail("expected to be stopped")
        } catch (e: Cancelled) {
            // expected
        }
        assertEquals("no partial file on the server", 0, serverRoot.list()!!.size)
        assertTrue(File(root, "big.bin").readBytes().contentEquals(big))
    }

    @Test fun aFailedWriteLeavesTheOldFileInPlace() {
        write("src/a.txt", "new")
        write("dst/a.txt", "old")
        val failing = object : Store by phone {
            override fun openWrite(path: String): OutputStream = object : OutputStream() {
                override fun write(b: Int) = throw java.io.IOException("disk full")
                override fun write(b: ByteArray, off: Int, len: Int) = throw java.io.IOException("disk full")
            }
        }
        val t = Transfer({ if (it == LocalStore.ID) failing else error(it) })
        try {
            t.paste(listOf(entry("src/a.txt")), Loc(LocalStore.ID, "dst"), Mode.COPY, Clash.REPLACE)
            fail("expected the write to fail")
        } catch (e: java.io.IOException) {
            // expected
        }
        assertEquals("old", File(root, "dst/a.txt").readText())
        assertEquals(listOf("a.txt"), File(root, "dst").list()!!.toList())
    }

    @Test fun deletesAFolderAndEverythingInIt() {
        write("a/b/c.txt", "c")
        write("a/d.txt", "d")
        transfer().delete(listOf(entry("a")))
        assertFalse(File(root, "a").exists())
    }

    @Test fun twoOfOneNameReplacingDoNotReplaceEachOther() {
        write("one/x.txt", "first")
        write("two/x.txt", "second")
        File(root, "dst").mkdir()
        val dst = Loc(LocalStore.ID, "dst")
        transfer().paste(listOf(entry("one/x.txt"), entry("two/x.txt")), dst, Mode.MOVE, Clash.REPLACE)
        assertEquals("first", File(root, "dst/x.txt").readText())
        assertEquals("second", File(root, "dst/x (1).txt").readText())
    }

    @Test fun twoNamesThatDifferOnlyByCaseDoNotReplaceEachOtherOnStorageThatIgnoresCase() {
        write("one/x.txt", "first")
        write("two/X.TXT", "second")
        val blindRoot = tmp.newFolder("blind")
        File(blindRoot, "dst").mkdir()
        val blind = CaseBlind(LocalStore(blindRoot))
        val dst = Loc(BLIND, "dst")
        val resolveAll = { id: String -> if (id == BLIND) blind else resolve(id) }
        Transfer(resolveAll, isCancelled = { false }, onProgress = {})
            .paste(listOf(entry("one/x.txt"), entry("two/X.TXT")), dst, Mode.MOVE, Clash.REPLACE)
        assertEquals("first", File(blindRoot, "dst/x.txt").readText())
        assertEquals("second", File(blindRoot, "dst/x (1).txt").readText())
    }

    @Test fun aFolderAndSomethingInItAreDeletedTogether() {
        write("a/b/c.txt", "c")
        val out = transfer().delete(listOf(entry("a"), entry("a/b/c.txt")))
        assertEquals(Outcome(1, 0), out)
        assertFalse(File(root, "a").exists())
    }

    @Test fun keepsTheDateOfWhatItCopies() {
        val f = write("old.txt", "o")
        f.setLastModified(1_000_000_000_000)
        File(root, "dst").mkdir()
        transfer().paste(listOf(entry("old.txt")), Loc(LocalStore.ID, "dst"), Mode.COPY, Clash.KEEP_BOTH)
        assertEquals(1_000_000_000_000, File(root, "dst/old.txt").lastModified())
    }

    @Test fun fetchBringsAServerFileToThePhone() {
        write("film.mkv", "frames", serverRoot)
        val into = File(tmp.root, "cache/fetched/film.mkv")
        transfer().fetch(entry("film.mkv", server), into)
        assertEquals("frames", into.readText())
        assertFalse(File(into.parentFile, "film.mkv.part").exists())
    }

    @Test fun aServerListingDotDotCannotLeadACopyOutOfItsFolder() {
        write("a/one.txt", "1", serverRoot)
        write("escape.txt", "x", serverRoot)
        val plain = Renamed(LocalStore(serverRoot), SERVER)
        // A server that lies: inside "a" it lists a folder called "..", and in that, a file.
        val lying = object : Store by plain {
            override fun list(path: String): List<Entry> = when (path) {
                "a" -> plain.list("a") + plain.stat("a")!!.copy(loc = Loc(SERVER, "a/.."))
                "a/.." -> listOf(plain.stat("escape.txt")!!.copy(loc = Loc(SERVER, "a/../escape.txt")))
                else -> plain.list(path)
            }
        }
        File(root, "dst").mkdir()
        val t = Transfer({ if (it == LocalStore.ID) phone else lying })
        t.paste(listOf(lying.stat("a")!!), Loc(LocalStore.ID, "dst"), Mode.COPY, Clash.KEEP_BOTH)
        assertEquals("1", File(root, "dst/a/one.txt").readText())
        assertFalse("nothing lands beside the folder", File(root, "dst/escape.txt").exists())
        assertFalse(File(root, "escape.txt").exists())
    }

    /** A LocalStore under another id, so the engine sees two stores. */
    private class Renamed(private val inner: LocalStore, override val id: String) : Store by inner {
        override fun list(path: String) = inner.list(path).map { it.copy(loc = Loc(id, it.loc.path)) }
        override fun stat(path: String) = inner.stat(path)?.let { it.copy(loc = Loc(id, it.loc.path)) }
        override fun openRead(path: String): InputStream = inner.openRead(path)
    }

    /** Storage that ignores case, as phone storage and a card do: every path is taken in lower case. */
    private class CaseBlind(private val inner: LocalStore) : Store by inner {
        override val id: String = BLIND
        private fun at(path: String) = path.lowercase()
        override fun list(path: String) = inner.list(at(path)).map { it.copy(loc = Loc(id, it.loc.path)) }
        override fun stat(path: String) = inner.stat(at(path))?.let { it.copy(loc = Loc(id, it.loc.path)) }
        override fun openRead(path: String): InputStream = inner.openRead(at(path))
        override fun openWrite(path: String): OutputStream = inner.openWrite(at(path))
        override fun makeFolder(path: String) = inner.makeFolder(at(path))
        override fun rename(from: String, to: String) = inner.rename(at(from), at(to))
        override fun replace(from: String, to: String) = inner.replace(at(from), at(to))
        override fun deleteFile(path: String) = inner.deleteFile(at(path))
        override fun deleteFolder(path: String) = inner.deleteFolder(at(path))
        override fun setModified(path: String, time: Long) = inner.setModified(at(path), time)
    }

    companion object {
        const val SERVER = "smb:test"
        const val BLIND = "blind:test"
    }
}
