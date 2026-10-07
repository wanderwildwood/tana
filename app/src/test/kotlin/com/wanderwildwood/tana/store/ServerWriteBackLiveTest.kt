package com.wanderwildwood.tana.store

import com.wanderwildwood.tana.work.Version
import com.wanderwildwood.tana.work.WriteBack
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * Saving back to a real server, and only when one is named: a WebDAV one by TANA_DAV_URL
 * (with TANA_DAV_USER and TANA_DAV_PASSWORD), a Samba one by TANA_SMB_HOST and TANA_SMB_SHARE.
 * Each named server is tested; everything happens in one scratch folder removed afterwards.
 */
class ServerWriteBackLiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val stores = mutableListOf<ServerStore>()
    private val scratch = "_tana-test-" + System.currentTimeMillis()

    @Before fun setUp() {
        System.getenv("TANA_DAV_URL")?.takeIf { it.isNotEmpty() }?.let { url ->
            val server = Server(
                id = "live", name = "live", host = url, share = "",
                user = System.getenv("TANA_DAV_USER").orEmpty(),
                password = System.getenv("TANA_DAV_PASSWORD").orEmpty(),
                kind = Server.DAV,
            )
            val spool = tmp.newFolder("spool")
            stores += DavStore(server.copy(host = DavStore.resolve(server, spool)), spool)
        }
        System.getenv("TANA_SMB_HOST")?.takeIf { it.isNotEmpty() }?.let { host ->
            stores += SmbStore(
                Server(
                    id = "live", name = "live", host = host, share = System.getenv("TANA_SMB_SHARE").orEmpty(),
                    user = System.getenv("TANA_SMB_USER").orEmpty(),
                    password = System.getenv("TANA_SMB_PASSWORD").orEmpty(),
                ),
            )
        }
        assumeTrue("no server named", stores.isNotEmpty())
    }

    @After fun tearDown() {
        stores.forEach { store ->
            runCatching { store.list(scratch).forEach { if (it.isFolder) store.deleteFolder(it.loc.path) else store.deleteFile(it.loc.path) } }
            runCatching { store.deleteFolder(scratch) }
            assertNull("the scratch folder is gone from ${store.id}", store.stat(scratch))
            store.close()
        }
    }

    private fun bytes(n: Int) = Random.nextBytes(n)

    private fun write(store: Store, path: String, data: ByteArray) = store.openWrite(path).use { it.write(data) }

    private fun read(store: Store, path: String) = store.openRead(path).use { it.readBytes() }

    @Test fun listOpenSaveRenameAndDelete() = stores.forEach { store ->
        store.makeFolder(scratch)
        val data = bytes(300_000)
        write(store, "$scratch/a file.bin", data)
        assertArrayEquals(data, read(store, "$scratch/a file.bin"))
        assertEquals(listOf("a file.bin"), store.list(scratch).map { it.name })
        assertEquals(300_000L, store.stat("$scratch/a file.bin")!!.size)

        store.makeFolder("$scratch/inner")
        try {
            store.makeFolder("$scratch/inner")
            throw AssertionError("a second folder of the same name was made")
        } catch (e: StoreException) {
            assertEquals(StoreException.Reason.ALREADY_THERE, e.reason)
        }
        store.rename("$scratch/a file.bin", "$scratch/inner/b.bin")
        assertNull(store.stat("$scratch/a file.bin"))
        assertArrayEquals(data, read(store, "$scratch/inner/b.bin"))

        write(store, "$scratch/c.bin", bytes(10))
        try {
            store.rename("$scratch/c.bin", "$scratch/inner/b.bin")
            throw AssertionError("a rename replaced a file")
        } catch (e: StoreException) {
            assertTrue(e.reason == StoreException.Reason.ALREADY_THERE || e.reason == StoreException.Reason.CANNOT_RENAME)
        }
        assertArrayEquals("a refused rename left the file alone", data, read(store, "$scratch/inner/b.bin"))

        try {
            store.deleteFolder("$scratch/inner")
            throw AssertionError("a folder with something in it was deleted")
        } catch (e: StoreException) {
            assertEquals(StoreException.Reason.CANNOT_DELETE, e.reason)
        }
        store.deleteFile("$scratch/inner/b.bin")
        store.deleteFolder("$scratch/inner")
        assertNull(store.stat("$scratch/inner"))
    }

    @Test fun aSaveReplacesTheFileAndARaceIsRefused() = stores.forEach { store ->
        store.makeFolder(scratch)
        val path = "$scratch/passwords.kdbx"
        write(store, path, bytes(5000))
        val base = Version.of(store.stat(path)!!)
        val ours = tmp.newFile().apply { writeBytes(bytes(5100)) }
        val saved = WriteBack.put(store, path, ours, base)
        assertTrue(saved is WriteBack.Outcome.Saved)
        assertArrayEquals(ours.readBytes(), read(store, path))
        assertEquals(listOf("passwords.kdbx"), store.list(scratch).map { it.name })

        // Someone else saves it now, the same length, so only the date or etag tells.
        val after = Version.of(store.stat(path)!!)
        Thread.sleep(1100)
        val theirs = bytes(5100)
        write(store, path, theirs)
        val mine = tmp.newFile().apply { writeBytes(bytes(5200)) }
        assertEquals(WriteBack.Outcome.Changed, WriteBack.put(store, path, mine, after))
        assertArrayEquals("their save is still there", theirs, read(store, path))
        assertEquals(listOf("passwords.kdbx"), store.list(scratch).map { it.name })
    }
}
