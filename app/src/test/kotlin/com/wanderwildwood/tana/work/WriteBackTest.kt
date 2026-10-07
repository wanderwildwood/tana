package com.wanderwildwood.tana.work

import com.wanderwildwood.tana.store.LocalStore
import com.wanderwildwood.tana.store.Store
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * [WriteBack] over a folder standing in for a server, with someone else's save arriving at
 * each point where one could: before the upload, during it, and not at all.
 */
class WriteBackTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The stand-in server, which runs [during] once, just after a file has gone up whole. */
    private class Meddling(val inner: LocalStore) : Store by inner {
        var during: (() -> Unit)? = null

        override fun openWrite(path: String): OutputStream = object : FilterOutputStream(inner.openWrite(path)) {
            override fun close() {
                super.close()
                during?.invoke()
                during = null
            }
        }

        override fun replace(from: String, to: String) = inner.replace(from, to)
    }

    private fun setUp(): Triple<Meddling, File, File> {
        val root = tmp.newFolder("server")
        File(root, "Passwords").mkdirs()
        val onServer = File(root, "Passwords/passwords.kdbx").apply { writeBytes(ByteArray(1000) { 1 }) }
        val local = tmp.newFile("edited").apply { writeBytes(ByteArray(1200) { 2 }) }
        return Triple(Meddling(LocalStore(root)), onServer, local)
    }

    private fun leftovers(store: Store) = store.list("Passwords").map { it.name }.sorted()

    @Test fun replacesTheFileWhenNothingChanged() {
        val (store, onServer, local) = setUp()
        val base = Version.of(store.stat("Passwords/passwords.kdbx")!!)
        val outcome = WriteBack.put(store, "Passwords/passwords.kdbx", local, base)
        assertTrue(outcome is WriteBack.Outcome.Saved)
        assertArrayEquals(local.readBytes(), onServer.readBytes())
        assertEquals(listOf("passwords.kdbx"), leftovers(store))
    }

    @Test fun refusesWhenTheServerFileChangedBeforeTheSave() {
        val (store, onServer, local) = setUp()
        val base = Version.of(store.stat("Passwords/passwords.kdbx")!!)
        val theirs = ByteArray(1100) { 3 }
        onServer.writeBytes(theirs)
        val outcome = WriteBack.put(store, "Passwords/passwords.kdbx", local, base)
        assertEquals(WriteBack.Outcome.Changed, outcome)
        assertArrayEquals(theirs, onServer.readBytes())
        assertEquals(listOf("passwords.kdbx"), leftovers(store))
    }

    @Test fun refusesWhenTheServerFileChangedDuringTheUpload() {
        val (store, onServer, local) = setUp()
        val base = Version.of(store.stat("Passwords/passwords.kdbx")!!)
        // The same length as before, so only the date tells it apart.
        val theirs = ByteArray(1000) { 4 }
        store.during = {
            onServer.writeBytes(theirs)
            onServer.setLastModified(onServer.lastModified() + 5000)
        }
        val outcome = WriteBack.put(store, "Passwords/passwords.kdbx", local, base)
        assertEquals(WriteBack.Outcome.Changed, outcome)
        assertArrayEquals(theirs, onServer.readBytes())
        assertEquals("the upload under its own name is cleaned away", listOf("passwords.kdbx"), leftovers(store))
    }

    @Test fun aNewFileIsNotWrittenOverOneThatArrivedMeanwhile() {
        val (store, _, local) = setUp()
        val outcome = WriteBack.put(store, "Passwords/passwords.kdbx", local, base = null)
        assertEquals(WriteBack.Outcome.Changed, outcome)
    }

    @Test fun anEtagDecidesWhereThereIsOne() {
        val entry = com.wanderwildwood.tana.store.Entry(
            com.wanderwildwood.tana.store.Loc("dav:x", "a"), isFolder = false, size = 10, modified = 1000, tag = "\"one\"",
        )
        val base = Version.of(entry)
        assertTrue(base.matches(entry))
        // Same length, same second, another etag: someone saved it.
        assertTrue(!base.matches(entry.copy(tag = "\"two\"")))
        assertTrue(!base.matches(entry.copy(size = 11)))
    }

    @Test fun theUploadNameIsHiddenAndNotAPartFile() {
        val name = WriteBack.tempName("Passwords/passwords.kdbx")
        assertTrue(name.startsWith("Passwords/.passwords.kdbx.tana-"))
        assertTrue(!name.endsWith(".part"))
    }
}
