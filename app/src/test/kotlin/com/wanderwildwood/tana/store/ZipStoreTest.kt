package com.wanderwildwood.tana.store

import com.wanderwildwood.tana.work.Cancelled
import com.wanderwildwood.tana.work.Clash
import com.wanderwildwood.tana.work.Mode
import com.wanderwildwood.tana.work.Transfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class ZipStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun phone() = LocalStore(tmp.root)

    private fun zipOf(file: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(file.outputStream()).use { z ->
            entries.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
    }

    @Test fun listsFoldersThatWereNeverWrittenAsEntries() {
        val f = File(tmp.root, "a.zip")
        zipOf(f, "top.txt" to "t", "deep/er/file.txt" to "f")
        val z = ZipStore(f, ZipStore.idFor(f))
        assertEquals(setOf("top.txt", "deep"), z.list("").map { it.name }.toSet())
        assertTrue(z.stat("deep/er")!!.isFolder)
        assertEquals("f", z.openRead("deep/er/file.txt").reader().readText())
    }

    @Test fun anEntryThatClimbsOutIsNeverListed() {
        val f = File(tmp.root, "evil.zip")
        zipOf(f, "../../escaped.txt" to "x", "/abs.txt" to "y", "ok/../../bad.txt" to "z", "fine.txt" to "ok")
        val z = ZipStore(f, ZipStore.idFor(f))
        assertEquals(listOf("fine.txt"), z.list("").map { it.name })
        assertNull(z.stat("escaped.txt"))
    }

    @Test fun nothingInsideCanBeChanged() {
        val f = File(tmp.root, "a.zip")
        zipOf(f, "x.txt" to "x")
        val z = ZipStore(f, ZipStore.idFor(f))
        try {
            z.deleteFile("x.txt")
            fail("deleted from inside a zip")
        } catch (e: StoreException) {
            assertEquals(StoreException.Reason.READ_ONLY, e.reason)
        }
        assertTrue(f.length() > 0)
    }

    @Test fun compressThenExtractGivesBackTheSameBytes() {
        val bytes = Random(3).nextBytes(700_000)
        File(tmp.root, "src/Photos/2026").mkdirs()
        File(tmp.root, "src/Photos/2026/a.jpg").writeBytes(bytes)
        File(tmp.root, "src/Photos/note.txt").writeText("hello")
        File(tmp.root, "src/Photos/empty").mkdir()
        val phone = phone()
        val zips = HashMap<String, ZipStore>()
        fun resolve(id: String): Store = if (id == LocalStore.ID) phone else zips.getOrPut(id) {
            ZipStore(File(id.removePrefix(ZipStore.PREFIX)), id)
        }
        val t = Transfer(::resolve)
        val name = t.compress(listOf(phone.stat("src/Photos")!!), Loc(LocalStore.ID, "src"), "Photos.zip")
        assertEquals("Photos.zip", name)
        assertFalse("no temporary file left", File(tmp.root, "src/Photos.zip.part").exists())

        val archive = File(tmp.root, "src/Photos.zip")
        val z = resolve(ZipStore.idFor(archive))
        File(tmp.root, "out").mkdir()
        Transfer(::resolve).paste(z.list(""), Loc(LocalStore.ID, "out"), Mode.COPY, Clash.KEEP_BOTH)
        assertTrue(File(tmp.root, "out/Photos/2026/a.jpg").readBytes().contentEquals(bytes))
        assertEquals("hello", File(tmp.root, "out/Photos/note.txt").readText())
        assertTrue("an empty folder survives the trip", File(tmp.root, "out/Photos/empty").isDirectory)
    }

    @Test fun aSecondArchiveOfTheSameNameIsNumbered() {
        File(tmp.root, "d").mkdir()
        File(tmp.root, "d/x.txt").writeText("x")
        File(tmp.root, "d/x.zip").writeText("not really")
        val phone = phone()
        val name = Transfer({ phone }).compress(listOf(phone.stat("d/x.txt")!!), Loc(LocalStore.ID, "d"), "x.zip")
        assertEquals("x (1).zip", name)
        assertEquals("not really", File(tmp.root, "d/x.zip").readText())
    }

    @Test fun aStoppedCompressLeavesNothing() {
        File(tmp.root, "big.bin").writeBytes(Random(9).nextBytes(3 * 1024 * 1024))
        val phone = phone()
        var seen = 0L
        val t = Transfer({ phone }, isCancelled = { seen > 1 }, onProgress = { seen = it.bytesDone })
        try {
            t.compress(listOf(phone.stat("big.bin")!!), Loc(LocalStore.ID, ""), "big.zip")
            fail("expected to be stopped")
        } catch (e: Cancelled) {
            // expected
        }
        assertEquals(listOf("big.bin"), tmp.root.list()!!.sorted())
    }
}
