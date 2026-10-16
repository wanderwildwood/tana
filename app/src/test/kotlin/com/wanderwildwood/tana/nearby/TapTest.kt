package com.wanderwildwood.tana.nearby

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapTest {
    private val ok = byteArrayOf(0x90.toByte(), 0x00)

    /** Both ends of a tap, joined: what the reader would collect from the card. */
    private fun readAll(payload: ByteArray?): ByteArray? {
        var selected = false
        val (sel, now) = Tap.answer(Tap.select(), selected, payload)
        selected = now
        if (!sel.contentEquals(ok)) return null
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val (r, s) = Tap.answer(Tap.read(out.size()), selected, payload)
            selected = s
            assertArrayEquals(ok, r.copyOfRange(r.size - 2, r.size))
            if (r.size == 2) return out.toByteArray()
            out.write(r, 0, r.size - 2)
        }
    }

    @Test
    fun aPayloadLongerThanOneReadArrivesWhole() {
        val payload = ByteArray(457) { (it * 7).toByte() }
        assertArrayEquals(payload, readAll(payload))
    }

    @Test
    fun withNothingOfferedTheAppIsNotFound() {
        assertEquals(null, readAll(null))
        val (reply, selected) = Tap.answer(Tap.select(), false, null)
        assertArrayEquals(byteArrayOf(0x6A, 0x82.toByte()), reply)
        assertFalse(selected)
    }

    @Test
    fun anotherAppsAidIsNotAnswered() {
        val other = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x07, 0xA0.toByte(), 0, 0, 0, 0x03, 0x10, 0x10, 0x00)
        val (reply, selected) = Tap.answer(other, false, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(0x6A, 0x82.toByte()), reply)
        assertFalse(selected)
    }

    @Test
    fun readingBeforeSelectingIsRefused() {
        val (reply, selected) = Tap.answer(Tap.read(0), false, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(0x69, 0x86.toByte()), reply)
        assertFalse(selected)
    }

    @Test
    fun selectingOursSelects() {
        assertTrue(Tap.answer(Tap.select(), false, byteArrayOf(1)).second)
    }
}
