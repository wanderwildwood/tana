package com.wanderwildwood.tana.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore

/** The LocalSend key file: locked with a sealed random password, carried over from the fixed one. */
class IdentityTest {

    // A stand-in for the keystore: sealed is "sealed:" in front, and a lost key opens nothing.
    private val seal = { plain: String -> "sealed:$plain" }
    private val open = { stored: String -> stored.removePrefix("sealed:") }
    private val lost = { _: String -> "" }

    private fun dir(): File = Files.createTempDirectory("nearby").toFile()

    private fun opensWith(file: File, password: String) = runCatching {
        KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, password.toCharArray()) } }
    }.isSuccess

    @Test
    fun anOldFileKeepsItsFingerprintAndLosesTheFixedPassword() {
        val d = dir()
        val before = Identity.writeLegacy(d)
        assertEquals(before, Identity.load(d, seal, open).fingerprint)
        val keyFile = File(d, "nearby.p12.key")
        assertTrue(keyFile.readText().startsWith("sealed:"))
        assertFalse("the fixed password must no longer open it", opensWith(File(d, "nearby.p12"), "tana"))
        assertTrue(opensWith(File(d, "nearby.p12"), open(keyFile.readText())))
        assertEquals(listOf("nearby.p12", "nearby.p12.key"), d.list()!!.sorted())
        // And the next start reads it under the new one.
        assertEquals(before, Identity.load(d, seal, open).fingerprint)
    }

    @Test
    fun aNewPhoneMakesOneAndKeepsIt() {
        val d = dir()
        val first = Identity.load(d, seal, open).fingerprint
        assertEquals(first, Identity.load(d, seal, open).fingerprint)
        assertFalse(opensWith(File(d, "nearby.p12"), "tana"))
    }

    @Test
    fun aLostKeystoreKeyMakesANewIdentityRatherThanFailing() {
        val d = dir()
        val first = Identity.load(d, seal, open).fingerprint
        val second = Identity.load(d, seal, lost).fingerprint
        assertNotEquals(first, second)
        // The new one is kept, under a password a working keystore opens.
        assertEquals(second, Identity.load(d, seal, open).fingerprint)
    }

    @Test
    fun aKeystoreThatWillNotSealLeavesNothingReadable() {
        val d = dir()
        Identity.load(d, { "" }, open)
        assertTrue(d.list()!!.isEmpty())
    }

    @Test
    fun aStopHalfwayThroughStillOpensTheOldFile() {
        val d = dir()
        val before = Identity.writeLegacy(d)
        // The sealed password was written, the new file never replaced the old one.
        File(d, "nearby.p12.key").writeText("sealed:not-the-files-password")
        assertEquals(before, Identity.load(d, seal, open).fingerprint)
        assertFalse(opensWith(File(d, "nearby.p12"), "tana"))
    }
}
