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
/** What this phone offers a phone held against it. */
internal sealed interface TapOffer {
    /** No network here, or the page is closed: not the card being asked for. */
    data object None : TapOffer
    /** A network is being started (or started again); asked again in a moment, it will be up. */
    data object NotReady : TapOffer
    class Ready(val bytes: ByteArray) : TapOffer
}

/** What reading another phone held against this one found. */
internal sealed interface TapRead {
    data class Offered(val invite: Invite) : TapRead
    /** Its network is not up yet: hold on and read again. */
    data object NotReady : TapRead
    /** Not offering one, or no longer held there. */
    data object Nothing : TapRead
}

internal object Tap {
    /** Proprietary (F0), then "tana", then a version. Must match res/xml/tap_service.xml. */
    val AID = byteArrayOf(0xF0.toByte(), 0x74, 0x61, 0x6E, 0x61, 0x01)
    private val OK = byteArrayOf(0x90.toByte(), 0x00)
    private val NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
    private val NOT_ALLOWED = byteArrayOf(0x69, 0x86.toByte())
    /** "Conditions of use not satisfied": this app, but its network is not up yet. */
    private val NOT_READY = byteArrayOf(0x69, 0x85.toByte())
    private const val CHUNK = 200

    fun select(): ByteArray = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)

    fun read(offset: Int): ByteArray =
        byteArrayOf(0x00, 0xB0.toByte(), (offset shr 8).toByte(), (offset and 0xff).toByte(), CHUNK.toByte())

    /**
     * The answer to one command, and whether this app is now the one selected. Only a network
     * that is really up is handed over: while one is starting the answer is "not ready", so a
     * phone held there early waits rather than taking a name and password for nothing.
     */
    fun answer(apdu: ByteArray, selected: Boolean, offer: TapOffer): Pair<ByteArray, Boolean> {
        if (apdu.size >= 4 && apdu[1] == 0xA4.toByte() && apdu[2] == 0x04.toByte()) {
            val ours = apdu.size >= 5 + AID.size && apdu.copyOfRange(5, 5 + AID.size).contentEquals(AID)
            return when {
                !ours -> NOT_FOUND to false
                offer is TapOffer.Ready -> OK to true
                offer is TapOffer.NotReady -> NOT_READY to false
                else -> NOT_FOUND to false
            }
        }
        if (apdu.size >= 4 && apdu[1] == 0xB0.toByte()) {
            val payload = (offer as? TapOffer.Ready)?.bytes
            if (!selected || payload == null) return NOT_ALLOWED to false
            val offset = ((apdu[2].toInt() and 0xff) shl 8) or (apdu[3].toInt() and 0xff)
            if (offset >= payload.size) return OK to true
            return payload.copyOfRange(offset, minOf(payload.size, offset + CHUNK)) + OK to true
        }
        return NOT_ALLOWED to selected
    }

    /** What a reader makes of the answer to its SELECT. */
    fun selectAnswered(r: ByteArray): TapRead? = when {
        ok(r) -> null
        r.contentEquals(NOT_READY) -> TapRead.NotReady
        else -> TapRead.Nothing
    }

    /** The other phone, held as a card: its invite, not yet, or nothing (or no longer held). */
    fun readFrom(tag: Tag): TapRead {
        val iso = IsoDep.get(tag) ?: return TapRead.Nothing
        return runCatching {
            iso.use {
                it.connect()
                it.timeout = 2000
                selectAnswered(it.transceive(select()))?.let { early -> return early }
                val out = ByteArrayOutputStream()
                while (out.size() < 4096) {
                    val r = it.transceive(read(out.size()))
                    if (!ok(r)) return TapRead.Nothing
                    if (r.size == 2) break
                    out.write(r, 0, r.size - 2)
                }
                Invite.from(out.toByteArray())?.let { invite -> TapRead.Offered(invite) } ?: TapRead.Nothing
            }
        }.getOrElse { TapRead.Nothing }
    }

    fun ok(r: ByteArray) = r.size >= 2 && r[r.size - 2] == 0x90.toByte() && r[r.size - 1] == 0x00.toByte()
}

/**
 * This phone, answering as a card when another is held to it. It answers only while the Nearby
 * page is open and a network is up there (or "not ready" while one is starting); the rest of the
 * time it says it is not the card being asked for, as any phone would.
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
