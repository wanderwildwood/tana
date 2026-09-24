package com.wanderwildwood.tana.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AskedTest {
    @Test fun internalStorage() {
        assertEquals("/storage/emulated/0/Music/Albums", Asked.fromDocumentId("primary:Music/Albums")?.path)
        assertEquals("/storage/emulated/0", Asked.fromDocumentId("primary:")?.path)
    }

    @Test fun anSdCard() {
        assertEquals("/storage/74C4-3B51/Books", Asked.fromDocumentId("74C4-3B51:Books")?.path)
    }

    @Test fun somethingThatIsNotAVolumeAddress() {
        assertNull(Asked.fromDocumentId("msf:1234"))
        assertNull(Asked.fromDocumentId("nonsense"))
    }
}
