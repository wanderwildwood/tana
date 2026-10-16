package com.wanderwildwood.tana.nearby

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import java.security.SecureRandom
import kotlin.coroutines.resume

/**
 * A Wi-Fi network of the phone's own, for when there is none to share: Wi-Fi Direct, with this
 * phone as the network and the other joining it. Neither phone searches for the other. The one
 * that starts the network names it and sets its password, and the other is handed both by a tap
 * (see [Tap]), so Android's search for nearby phones is never used. Android 12 still asks for
 * the location permission, and for location to be on, to start or join one; nothing here reads
 * a location.
 */
@SuppressLint("MissingPermission")
internal class Direct(context: Context) {
    private val manager = context.getSystemService(WifiP2pManager::class.java)
    private val channel = manager?.initialize(context, context.mainLooper, null)

    class Failed(val reason: Int) : Exception("Wi-Fi Direct failed: $reason")

    /** The network started, under [name] with [passphrase]; its frequency, for the one joining. */
    suspend fun host(name: String, passphrase: String): Int {
        val m = manager ?: throw Failed(WifiP2pManager.P2P_UNSUPPORTED)
        val c = channel ?: throw Failed(WifiP2pManager.P2P_UNSUPPORTED)
        // One left over from an earlier start is taken down first: only one can be up.
        runCatching { call { m.removeGroup(c, it) } }
        val config = WifiP2pConfig.Builder()
            .setNetworkName(name)
            .setPassphrase(passphrase)
            .enablePersistentMode(false)
            .build()
        call { m.createGroup(c, config, it) }
        repeat(20) {
            val g = group()
            if (g != null && g.isGroupOwner) return g.frequency
            delay(500)
        }
        throw Failed(WifiP2pManager.ERROR)
    }

    /** Joins the network an invite names; the address of the phone that started it. */
    suspend fun join(invite: Invite): InetAddress {
        val m = manager ?: throw Failed(WifiP2pManager.P2P_UNSUPPORTED)
        val c = channel ?: throw Failed(WifiP2pManager.P2P_UNSUPPORTED)
        val config = WifiP2pConfig.Builder()
            .setNetworkName(invite.network)
            .setPassphrase(invite.passphrase)
            .apply { if (invite.frequency > 0) setGroupOperatingFrequency(invite.frequency) }
            .enablePersistentMode(false)
            .build()
        call { m.connect(c, config, it) }
        // Joining is a scan, an association and an address: some seconds, rarely more than ten.
        repeat(40) {
            val info = info()
            if (info?.groupFormed == true && !info.isGroupOwner) info.groupOwnerAddress?.let { return it }
            delay(1000)
        }
        runCatching { call { m.cancelConnect(c, it) } }
        throw Failed(WifiP2pManager.BUSY)
    }

    /** Whatever network this phone started or joined, taken down. */
    suspend fun leave() {
        val m = manager ?: return
        val c = channel ?: return
        runCatching { call { m.cancelConnect(c, it) } }
        runCatching { call { m.removeGroup(c, it) } }
    }

    fun close() {
        runCatching { channel?.close() }
    }

    private suspend fun group(): WifiP2pGroup? = suspendCancellableCoroutine { k ->
        manager!!.requestGroupInfo(channel!!) { k.resume(it) }
    }

    private suspend fun info(): WifiP2pInfo? = suspendCancellableCoroutine { k ->
        manager!!.requestConnectionInfo(channel!!) { k.resume(it) }
    }

    private suspend fun call(start: (WifiP2pManager.ActionListener) -> Unit) {
        val failed = suspendCancellableCoroutine { k ->
            start(object : WifiP2pManager.ActionListener {
                override fun onSuccess() = k.resume(null)
                override fun onFailure(reason: Int) = k.resume(reason)
            })
        }
        if (failed != null) throw Failed(failed)
    }

    companion object {
        private val random = SecureRandom()
        private const val LETTERS = "abcdefghjkmnpqrstuvwxyz23456789"

        /**
         * Android insists a Wi-Fi Direct network's name begin "DIRECT-" and two characters; the
         * phone's name follows, so a laptop's list of networks shows whose it is.
         */
        fun networkName(alias: String): String {
            val two = (1..2).map { LETTERS[random.nextInt(LETTERS.length)] }.joinToString("")
            val plain = alias.filter { it.code in 0x21..0x7e || it == ' ' }.trim().replace(' ', '-').take(20)
            return "DIRECT-$two" + if (plain.isEmpty()) "" else "-$plain"
        }

        /** Eight letters and digits, none that read as another on this screen (no l, 1, o, 0, i). */
        fun passphrase(): String = (1..8).map { LETTERS[random.nextInt(LETTERS.length)] }.joinToString("")
    }
}
