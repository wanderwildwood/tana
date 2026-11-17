package com.wanderwildwood.tana.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CaseRenameTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aNewCaseOfTheSameNameIsTheSameFile() {
        val dir = tmp.newFolder("d")
        val source = File(dir, "notes.txt").apply { writeText("n") }
        assertTrue(onlyCaseDiffers(source, File(dir, "Notes.txt")))
    }

    @Test fun anotherFileOfThatNameIsNot() {
        val dir = tmp.newFolder("d")
        val source = File(dir, "notes.txt").apply { writeText("n") }
        val other = File(dir, "Notes.txt").apply { writeText("other") }
        // Only on storage that tells the two apart can both be there; then it is a clash.
        if (dir.list()!!.size == 2) assertFalse(onlyCaseDiffers(source, other))
        assertFalse(onlyCaseDiffers(source, File(dir, "other.txt")))
    }
}
