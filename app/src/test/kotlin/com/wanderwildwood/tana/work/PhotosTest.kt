package com.wanderwildwood.tana.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhotosTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun dcim(): File = tmp.newFolder("DCIM")

    @Test fun noDcimIsNoFolder() {
        assertNull(Photos.folderUnder(File(tmp.root, "DCIM")))
    }

    @Test fun theOneCameraFolderIsSteppedInto() {
        val dcim = dcim()
        val camera = File(dcim, "Camera").apply { mkdirs() }
        File(camera, "a.jpg").writeText("x")
        File(camera, "b.jpg").writeText("x")
        assertEquals(camera, Photos.folderUnder(dcim))
    }

    @Test fun hiddenNamesDoNotCount() {
        val dcim = dcim()
        File(dcim, ".thumbnails").mkdirs()
        File(dcim, ".nomedia").writeText("")
        val camera = File(dcim, "Camera").apply { mkdirs() }
        assertEquals(camera, Photos.folderUnder(dcim))
    }

    @Test fun photographsInDcimItselfStayThere() {
        val dcim = dcim()
        File(dcim, "Camera").mkdirs()
        File(dcim, "a.jpg").writeText("x")
        assertEquals(dcim, Photos.folderUnder(dcim))
    }

    @Test fun twoFoldersLeaveTheChoiceToTheReader() {
        val dcim = dcim()
        File(dcim, "Camera").mkdirs()
        File(dcim, "Other").mkdirs()
        assertEquals(dcim, Photos.folderUnder(dcim))
    }

    @Test fun anEmptyDcimIsItself() {
        val dcim = dcim()
        assertEquals(dcim, Photos.folderUnder(dcim))
    }
}
