package com.wanderwildwood.tana.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UnsentSavesTest {

    @get:Rule val tmp = TemporaryFolder()

    private val stamp = "2026-11-17 14.05"

    @Test fun keptNameSaysNotSentAndKeepsTheExtension() {
        assertEquals("notes (not sent 2026-11-17 14.05).txt", UnsentSaves.keptName("notes.txt", stamp))
        assertEquals("Vault (not sent 2026-11-17 14.05).KDBX", UnsentSaves.keptName("Vault.KDBX", stamp))
        assertEquals("backup.tar (not sent 2026-11-17 14.05).gz", UnsentSaves.keptName("backup.tar.gz", stamp))
    }

    @Test fun keptNameWithoutAnExtension() {
        assertEquals("README (not sent 2026-11-17 14.05)", UnsentSaves.keptName("README", stamp))
        assertEquals(".nomedia (not sent 2026-11-17 14.05)", UnsentSaves.keptName(".nomedia", stamp))
        assertEquals("trailing. (not sent 2026-11-17 14.05)", UnsentSaves.keptName("trailing.", stamp))
    }

    @Test fun keptNameHasNoColonOrSlash() {
        val name = UnsentSaves.keptName("a.txt", stamp)
        assertEquals(false, name.contains(':') || name.contains('/'))
    }

    @Test fun leftoverSavesAreThoseUntouchedForAWhile() {
        val writing = tmp.newFolder("writing")
        val now = 10_000_000L
        val old = java.io.File(java.io.File(writing, "a").apply { mkdirs() }, "notes.txt").apply {
            writeText("Ada Whitlock\n")
            setLastModified(now - UnsentSaves.SETTLE_MS - 1000)
        }
        java.io.File(java.io.File(writing, "b").apply { mkdirs() }, "vault.kdbx").apply {
            writeText("Tomas Reyes\n")
            setLastModified(now - 1000)
        }
        java.io.File(writing, "stray.txt").apply { writeText("x"); setLastModified(now - UnsentSaves.SETTLE_MS - 1000) }
        assertEquals(listOf(old), UnsentSaves.leftovers(writing, now))
    }

    @Test fun aSaveLeftUnclosedIsKeptOnlyWhenSomethingInItIsNew() {
        val start = tmp.newFile("start.txt").apply { writeText("Ada Whitlock\n") }
        val same = tmp.newFile("same.txt").apply { writeText("Ada Whitlock\n") }
        val changed = tmp.newFile("changed.txt").apply { writeText("Ada Whitlocx\n") }
        val longer = tmp.newFile("longer.txt").apply { writeText("Ada Whitlock\nTomas Reyes\n") }
        val size = start.length()
        assertTrue(ServersProvider.sameBytes(same, start, size))
        assertFalse(ServersProvider.sameBytes(changed, start, size))
        assertFalse(ServersProvider.sameBytes(longer, start, size))
        // The copy it started from changed under it: not the same as when it started.
        start.writeText("Ada Whitlock\nmore\n")
        assertFalse(ServersProvider.sameBytes(same, start, size))
    }
}
