package com.wanderwildwood.tana.work

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickingTest {

    private val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private val persist = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    private val prefix = Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    private fun of(action: String, type: String? = "*/*", types: List<String> = emptyList(), many: Boolean = false, title: String? = null, flags: Int = 0) =
        Picking.of(action, type, types, many, title, flags)

    @Test fun getContentIsReadOnlyAndNotKept() {
        val p = of(Intent.ACTION_GET_CONTENT, "image/*")!!
        assertEquals(Ask.FILE, p.ask)
        assertEquals(read, p.grantFlags())
    }

    @Test fun openDocumentIsKeptAndWritableOnlyIfAsked() {
        assertEquals(read or persist, of(Intent.ACTION_OPEN_DOCUMENT)!!.grantFlags())
        assertEquals(read or write or persist, of(Intent.ACTION_OPEN_DOCUMENT, flags = write)!!.grantFlags())
    }

    @Test fun treeIsReadWriteKeptAndCoversWhatIsBelow() {
        val p = of(Intent.ACTION_OPEN_DOCUMENT_TREE, null)!!
        assertEquals(Ask.TREE, p.ask)
        assertEquals(read or write or persist or prefix, p.grantFlags())
    }

    @Test fun createDocumentCarriesTheTitleAndReadWrite() {
        val p = of(Intent.ACTION_CREATE_DOCUMENT, "text/plain", title = "notes.txt")!!
        assertEquals(Ask.SAVE, p.ask)
        assertEquals("notes.txt", p.title)
        assertEquals(read or write or persist, p.grantFlags())
        assertFalse(p.several)
    }

    @Test fun multipleIsHonouredForFilesOnly() {
        assertTrue(of(Intent.ACTION_OPEN_DOCUMENT, many = true)!!.several)
        assertFalse(of(Intent.ACTION_CREATE_DOCUMENT, many = true)!!.several)
        assertFalse(of(Intent.ACTION_OPEN_DOCUMENT_TREE, null, many = true)!!.several)
    }

    @Test fun otherActionsAreNotOurs() {
        assertNull(of(Intent.ACTION_VIEW))
        assertNull(Picking.of(null, null, emptyList(), false, null, 0))
        assertNotNull(of(Intent.ACTION_PICK, "image/*"))
    }

    @Test fun theExtraListWinsOverTheType() {
        val p = of(Intent.ACTION_OPEN_DOCUMENT, "*/*", listOf("image/png", "application/pdf"))!!
        assertEquals(listOf("image/png", "application/pdf"), p.types)
        assertTrue(p.acceptsMime("application/pdf"))
        assertTrue(p.acceptsMime("image/png"))
        assertFalse(p.acceptsMime("text/plain"))
    }

    @Test fun noTypeMeansAnything() {
        val p = of(Intent.ACTION_OPEN_DOCUMENT, null)!!
        assertEquals(listOf("*/*"), p.types)
    }

    @Test fun wildcardsMatchByKind() {
        assertTrue(Picking.matches("image/*", "image/png"))
        assertFalse(Picking.matches("image/*", "video/mp4"))
        assertTrue(Picking.matches("*/*", "anything/at-all"))
        assertTrue(Picking.matches("text/plain", "TEXT/PLAIN"))
        assertFalse(Picking.matches("text/plain", "text/html"))
    }

    @Test fun theCallerIsEmptyUntilTheActivityNamesIt() {
        assertEquals("", of(Intent.ACTION_OPEN_DOCUMENT_TREE)!!.caller)
        assertEquals("Messaging", of(Intent.ACTION_OPEN_DOCUMENT_TREE)!!.copy(caller = "Messaging").caller)
    }
}
