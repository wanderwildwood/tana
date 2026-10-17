package com.wanderwildwood.tana.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AliasTest {
    @Test
    fun theMakersNameGetsThisPhonesLetters() {
        assertEquals("Kompakt 7KQ", Alias.default("Kompakt", "Kompakt", "kompakt", "kompakt", "7kq"))
        assertEquals("Kompakt 7KQ", Alias.default("Kompakt", "Mudita Kompakt", "k1", "k1", "7KQ"))
        assertEquals("Kompakt 7KQ", Alias.default("Kompakt", "MK1", "mk1", "mk1_eea", "7KQ"))
        assertEquals("Pixel 4a 7KQ", Alias.default(null, "Pixel 4a", "sunfish", "sunfish", "7KQ"))
        assertEquals("sdk_gphone64_x86_64 7KQ", Alias.default("sdk_gphone64_x86_64", "sdk_gphone64_x86_64", "emu64xa", "sdk_gphone64_x86_64", "7KQ"))
    }

    @Test
    fun aNameTheOwnerGaveIsUsedAsItIs() {
        assertEquals("Kitchen phone", Alias.default("Kitchen phone", "Kompakt", "kompakt", "kompakt", "7KQ"))
    }

    @Test
    fun twoPhonesOfTheSameKindAreToldApart() {
        assertNotEquals(Alias.default("Kompakt", "Kompakt", "k", "k", "7KQ"), Alias.default("Kompakt", "Kompakt", "k", "k", "M3X"))
        val tags = (1..50).map { Alias.newTag() }
        tags.forEach { assertEquals(3, it.length) }
        assertNotEquals(1, tags.toSet().size)
    }
}
