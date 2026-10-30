package com.wanderwildwood.tana.work

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PathGuardTest {

    private lateinit var base: File
    private lateinit var storage: File
    private lateinit var outside: File

    @Before fun setUp() {
        base = Files.createTempDirectory("guard").toFile().canonicalFile
        storage = File(base, "emulated/0").apply { mkdirs() }
        outside = File(base, "secret").apply { mkdirs() }
        File(storage, "Download").mkdirs()
        File(storage, "Android/data/com.other").mkdirs()
        File(storage, "Android/obb").mkdirs()
        File(storage, "Android/media").mkdirs()
        File(outside, "key.txt").writeText("x")
        File(storage, "a.txt").writeText("a")
    }

    @After fun tearDown() { base.deleteRecursively() }

    private fun resolve(p: String) = PathGuard.resolve(p, listOf(storage.path))

    @Test fun storageItselfAndFilesInIt() {
        assertEquals(storage.path, resolve(storage.path))
        assertNotNull(resolve(File(storage, "a.txt").path))
        assertNotNull(resolve(File(storage, "Download/new.pdf").path))
    }

    @Test fun parentTraversalIsRefused() {
        assertNull(resolve(storage.path + "/../secret/key.txt"))
        assertNull(resolve(storage.path + "/Download/../../secret"))
        // Even one that stays inside is refused: nobody honest sends it.
        assertNull(resolve(storage.path + "/Download/../a.txt"))
    }

    @Test fun relativeEmptyAndNulAreRefused() {
        assertNull(resolve(""))
        assertNull(resolve("a.txt"))
        assertNull(resolve("emulated/0/a.txt"))
        assertNull(resolve(storage.path + "/a\u0000.txt"))
    }

    @Test fun aSiblingWithTheSamePrefixIsNotInside() {
        val sibling = File(base, "emulated/01").apply { mkdirs() }
        assertNull(resolve(sibling.path))
        assertNull(resolve(File(sibling, "x").path))
    }

    @Test fun outsideStorageIsRefused() {
        assertNull(resolve(File(outside, "key.txt").path))
        assertNull(resolve("/data/data/com.wanderwildwood.tana/shared_prefs"))
        assertNull(resolve("/"))
    }

    @Test fun aLinkOutOfStorageIsRefused() {
        val link = File(storage, "out")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Exception) {
            return // the platform cannot make links; nothing to prove here
        }
        assertNull(resolve(File(link, "key.txt").path))
        assertNull(resolve(link.path))
    }

    @Test fun anotherAppsFoldersAreRefused() {
        assertNull(resolve(File(storage, "Android/data").path))
        assertNull(resolve(File(storage, "Android/data/com.other/f").path))
        assertNull(resolve(File(storage, "Android/obb").path))
        assertNotNull(resolve(File(storage, "Android/media").path))
    }

    @Test fun severalRootsAnyOneWillDo() {
        val card = File(base, "ABCD-1234").apply { mkdirs() }
        val roots = listOf(storage.path, card.path)
        assertNotNull(PathGuard.resolve(File(card, "x.txt").path, roots))
        assertNull(PathGuard.resolve(File(outside, "key.txt").path, roots))
    }

    @Test fun withinKnowsFolderBoundaries() {
        assertTrue(PathGuard.within("/a/b", "/a/b"))
        assertTrue(PathGuard.within("/a/b/c", "/a/b"))
        assertTrue(!PathGuard.within("/a/bc", "/a/b"))
        assertTrue(PathGuard.within("/a/b", "/"))
    }
}
