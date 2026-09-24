package com.wanderwildwood.tana.store

import com.wanderwildwood.tana.work.Cancelled
import com.wanderwildwood.tana.work.Clash
import com.wanderwildwood.tana.work.Mode
import com.wanderwildwood.tana.work.Transfer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * Against a real Samba share, and only when asked to: set TANA_SMB_HOST and TANA_SMB_SHARE
 * (and TANA_SMB_USER / TANA_SMB_PASSWORD for a share that is not open to guests). Everything
 * happens inside one scratch folder that is removed afterwards, whatever happened.
 *
 * Skipped everywhere else, including CI, which has no server to talk to.
 */
class SmbStoreLiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val host = System.getenv("TANA_SMB_HOST").orEmpty()
    private val shareName = System.getenv("TANA_SMB_SHARE").orEmpty()
    private lateinit var smb: SmbStore
    private lateinit var phone: LocalStore
    private val scratch = "_tana-test-" + System.currentTimeMillis()

    private fun resolve(id: String): Store = if (id == LocalStore.ID) phone else smb

    @Before fun setUp() {
        assumeTrue("no TANA_SMB_HOST set", host.isNotEmpty() && shareName.isNotEmpty())
        smb = SmbStore(
            Server(
                id = "live", name = "live", host = host, share = shareName,
                user = System.getenv("TANA_SMB_USER").orEmpty(),
                password = System.getenv("TANA_SMB_PASSWORD").orEmpty(),
            ),
        )
        phone = LocalStore(tmp.newFolder("phone"))
        smb.makeFolder(scratch)
    }

    @After fun tearDown() {
        if (!::smb.isInitialized) return
        runCatching {
            smb.stat(scratch)?.let { Transfer(::resolve).delete(listOf(it)) }
        }
        assertNull("the scratch folder is gone from the server", smb.stat(scratch))
        smb.close()
    }

    @Test fun theShareRootLists() {
        val names = smb.list("").map { it.name }
        assertTrue("sees its own scratch folder", scratch in names)
        assertFalse(". and .. are not entries", "." in names || ".." in names)
    }

    @Test fun aFolderGoesUpAndComesBackByteForByte() {
        val bytes = Random(7).nextBytes(5 * 1024 * 1024 + 13)
        val local = File(tmp.root, "phone/up/deep")
        local.mkdirs()
        File(local, "noise.bin").writeBytes(bytes)
        File(tmp.root, "phone/up/note.txt").writeText("hello")

        val t = Transfer(::resolve)
        t.paste(listOf(phone.stat("up")!!), Loc(smb.id, scratch), Mode.COPY, Clash.KEEP_BOTH)
        assertEquals(bytes.size.toLong(), smb.stat("$scratch/up/deep/noise.bin")!!.size)
        assertTrue(smb.stat("$scratch/up/deep")!!.isFolder)

        File(tmp.root, "phone/back").mkdir()
        t.paste(listOf(smb.stat("$scratch/up")!!), Loc(LocalStore.ID, "back"), Mode.COPY, Clash.KEEP_BOTH)
        assertTrue(File(tmp.root, "phone/back/up/deep/noise.bin").readBytes().contentEquals(bytes))
        assertEquals("hello", File(tmp.root, "phone/back/up/note.txt").readText())
    }

    @Test fun renameAndMoveWithinTheShare() {
        File(tmp.root, "phone/a.txt").writeText("a")
        val t = Transfer(::resolve)
        t.paste(listOf(phone.stat("a.txt")!!), Loc(smb.id, scratch), Mode.COPY, Clash.KEEP_BOTH)
        smb.rename("$scratch/a.txt", "$scratch/b.txt")
        assertNull(smb.stat("$scratch/a.txt"))
        smb.makeFolder("$scratch/sub")
        t.paste(listOf(smb.stat("$scratch/b.txt")!!), Loc(smb.id, "$scratch/sub"), Mode.MOVE, Clash.KEEP_BOTH)
        assertNull(smb.stat("$scratch/b.txt"))
        assertEquals(1L, smb.stat("$scratch/sub/b.txt")!!.size)
    }

    @Test fun aNameAlreadyTakenIsRefusedNotReplaced() {
        smb.makeFolder("$scratch/x")
        try {
            smb.makeFolder("$scratch/x")
            fail("made the same folder twice")
        } catch (e: StoreException) {
            assertEquals(StoreException.Reason.ALREADY_THERE, e.reason)
        }
    }

    @Test fun aStoppedUploadLeavesNothingOnTheServer() {
        File(tmp.root, "phone/big.bin").writeBytes(Random(1).nextBytes(8 * 1024 * 1024))
        var seen = 0L
        val t = Transfer(::resolve, isCancelled = { seen > 2 * 1024 * 1024 }, onProgress = { seen = it.bytesDone })
        try {
            t.paste(listOf(phone.stat("big.bin")!!), Loc(smb.id, scratch), Mode.MOVE, Clash.KEEP_BOTH)
            fail("expected to be stopped")
        } catch (e: Cancelled) {
            // expected
        }
        assertEquals("no partial file left", emptyList<String>(), smb.list(scratch).map { it.name })
        assertTrue("the original stays on the phone", File(tmp.root, "phone/big.bin").exists())
    }

    @Test fun aNewFolderIsInTheVeryNextListing() {
        smb.list(scratch)
        smb.makeFolder("$scratch/fresh")
        assertEquals(listOf("fresh"), smb.list(scratch).map { it.name })
    }

    /** A connection left idle, as a phone's is between one look and the next. */
    @Test fun aConnectionLeftIdleStillAnswersPromptly() {
        val idle = System.getenv("TANA_SMB_IDLE_SECONDS")?.toLongOrNull() ?: return
        smb.list(scratch)
        Thread.sleep(idle * 1000)
        val started = System.currentTimeMillis()
        smb.list(scratch)
        val took = System.currentTimeMillis() - started
        assertTrue("took ${took}ms after ${idle}s idle", took < 5000)
    }

    @Test fun aMissingFileIsGoneNotAnError() {
        assertNull(smb.stat("$scratch/not-here.txt"))
    }

    @Test fun aWrongShareSaysSo() {
        val wrong = SmbStore(Server(id = "w", name = "w", host = host, share = "no-such-share-here"))
        try {
            wrong.list("")
            fail("listed a share that does not exist")
        } catch (e: StoreException) {
            assertEquals(StoreException.Reason.NO_SUCH_SHARE, e.reason)
        } finally {
            wrong.close()
        }
    }

    @Test fun anUnreachableServerSaysSo() {
        // TEST-NET-1: an address that is guaranteed never to answer.
        val gone = SmbStore(Server(id = "g", name = "g", host = "192.0.2.1", share = shareName))
        try {
            gone.list("")
            fail("reached an address that cannot exist")
        } catch (e: StoreException) {
            assertEquals(StoreException.Reason.SERVER_UNREACHABLE, e.reason)
        } finally {
            gone.close()
        }
    }
}
