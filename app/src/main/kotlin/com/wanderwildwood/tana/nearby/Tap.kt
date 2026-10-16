package com.wanderwildwood.tana.nearby

import android.nfc.Tag
import android.nfc.cardemulation.HostApduService
import android.nfc.tech.IsoDep
import android.os.Bundle
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * What one phone hands another when they are held together: how to join the Wi-Fi Direct network
 * it has started, and who it is there. The certificate's fingerprint comes with it, so the phone
 * that joins sends only to the phone it touched.
 */
internal data class Invite(
    val network: String,
    val passphrase: String,
    val frequency: Int,
    val alias: String,
    val port: Int,
    val fingerprint: String,
) {
    fun bytes(): ByteArray = JSONObject()
        .put("n", network)
        .put("p", passphrase)
        .put("f", frequency)
        .put("a", alias)
        .put("o", port)
        .put("x", fingerprint)
        .toString().toByteArray()

    companion object {
        fun from(bytes: ByteArray): Invite? = runCatching {
            val o = JSONObject(String(bytes))
            Invite(
                network = o.getString("n"),
                passphrase = o.getString("p"),
                frequency = o.optInt("f", 0),
                alias = o.getString("a").take(64),
                port = o.getInt("o").takeIf { it in 1..65535 }!!,
                fingerprint = o.getString("x").takeIf { it.length == 64 }!!,
            )
        }.getOrNull()
    }
}

/**
 * The few commands a tap is made of, after ISO 7816-4: SELECT this app's AID, then READ BINARY
 * from an offset until a read comes back empty. One phone answers them as a card would (see
 * [TapService]); the other asks them, holding the first as it would a card.
 */
internal object Tap {
    /** Proprietary (F0), then "tana", then a version. Must match res/xml/tap_service.xml. */
    val AID = byteArrayOf(0xF0.toByte(), 0x74, 0x61, 0x6E, 0x61, 0x01)
    private val OK = byteArrayOf(0x90.toByte(), 0x00)
    private val NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
    private val NOT_ALLOWED = byteArrayOf(0x69, 0x86.toByte())
    private const val CHUNK = 200

    fun select(): ByteArray = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)

    fun read(offset: Int): ByteArray =
        byteArrayOf(0x00, 0xB0.toByte(), (offset shr 8).toByte(), (offset and 0xff).toByte(), CHUNK.toByte())

    /**
     * The answer to one command, and whether this app is now the one selected. [payload] is null
     * when there is nothing to hand over, which is all of the time but while a network is up.
     */
    fun answer(apdu: ByteArray, selected: Boolean, payload: ByteArray?): Pair<ByteArray, Boolean> {
        if (apdu.size >= 4 && apdu[1] == 0xA4.toByte() && apdu[2] == 0x04.toByte()) {
            val ours = apdu.size >= 5 + AID.size && apdu.copyOfRange(5, 5 + AID.size).contentEquals(AID)
            return if (ours && payload != null) OK to true else NOT_FOUND to false
        }
        if (apdu.size >= 4 && apdu[1] == 0xB0.toByte()) {
            if (!selected || payload == null) return NOT_ALLOWED to false
            val offset = ((apdu[2].toInt() and 0xff) shl 8) or (apdu[3].toInt() and 0xff)
            if (offset >= payload.size) return OK to true
            return payload.copyOfRange(offset, minOf(payload.size, offset + CHUNK)) + OK to true
        }
        return NOT_ALLOWED to selected
    }

    /** The other phone, held as a card: its invite, or null when it is not offering one. */
    fun readFrom(tag: Tag): Invite? {
        val iso = IsoDep.get(tag) ?: return null
        return runCatching {
            iso.use {
                it.connect()
                it.timeout = 2000
                if (!ok(it.transceive(select()))) return null
                val out = ByteArrayOutputStream()
                while (out.size() < 4096) {
                    val r = it.transceive(read(out.size()))
                    if (!ok(r)) return null
                    if (r.size == 2) break
                    out.write(r, 0, r.size - 2)
                }
                Invite.from(out.toByteArray())
            }
        }.getOrNull()
    }

    private fun ok(r: ByteArray) = r.size >= 2 && r[r.size - 2] == 0x90.toByte() && r[r.size - 1] == 0x00.toByte()
}

/**
 * This phone, answering as a card when another is held to it. It answers only while it has a
 * network up and the Nearby page open; the rest of the time it says it is not the card being
 * asked for, as any phone would.
 */
class TapService : HostApduService() {
    private var selected = false

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        val (reply, now) = Tap.answer(apdu, selected, Nearby.invite())
        selected = now
        return reply
    }

    override fun onDeactivated(reason: Int) {
        selected = false
    }
}
