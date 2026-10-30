package com.wanderwildwood.tana.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicencesTest {

    private val raw = File("src/main/res/raw")

    @Test
    fun theListNamesEveryComponentWithALicenceAndALink() {
        assertTrue(Licences.components.isNotEmpty())
        for (c in Licences.components) {
            assertTrue(c.name, c.name.isNotBlank() && c.licence.isNotBlank())
            assertTrue(c.link, c.link.startsWith("https://"))
        }
    }

    @Test
    fun theSourceLinkIsTheAppsPublicRepository() {
        assertEquals("https://github.com/wanderwildwood/tana", Licences.SOURCE)
    }

    @Test
    fun everyLicenceNamedHasItsFullTextBundled() {
        assertEquals(listOf("Apache-2.0", "MIT", "OFL-1.1"), Licences.distinctLicences().sorted())
        assertTrue(File(raw, "licence_gpl3.txt").readText().contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(File(raw, "licence_apache2.txt").readText().contains("Apache License"))
        assertTrue(File(raw, "licence_mit.txt").readText().contains("Permission is hereby granted"))
        assertTrue(File(raw, "licence_ofl.txt").readText().contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue(Licences.components.any { it.name.startsWith("Lato") && it.licence == "OFL-1.1" })
        assertTrue(Licences.components.any { it.name.startsWith("smbj") })
    }

    @Test
    fun theGplTextTurnsIntoReadableParagraphs() {
        val p = Licences.paragraphs(File(raw, "licence_gpl3.txt").readText())
        assertTrue(p.size > 100)
        assertTrue(p.none { it.contains("\n") || it.isBlank() })
        assertEquals("GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007", p.first())
    }
}
