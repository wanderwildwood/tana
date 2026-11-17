package com.wanderwildwood.tana.work

import org.junit.Assert.assertEquals
import org.junit.Test

class UnsentSavesTest {

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
}
