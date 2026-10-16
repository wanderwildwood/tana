package com.wanderwildwood.tana.nearby

import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Being found, and finding: a few lines of JSON to LocalSend's multicast group, and whatever the
 * others say to it. On every network the phone is on at once: the Wi-Fi, and a Wi-Fi Direct
 * network when there is one. Android drops multicast to save power unless an app holds a lock
 * for it, so the lock is held exactly as long as this runs.
 */
internal class Discovery(private val wifi: WifiManager, private val onHeard: (JSONObject, InetAddress) -> Unit) {
    private var socket: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private val group = InetAddress.getByName(Wire.GROUP)
    private val joined = mutableMapOf<String, NetworkInterface>()

    /** False when the port is taken, by LocalSend itself on this phone most likely. */
    fun open(): Boolean {
        lock = wifi.createMulticastLock("tana-nearby").apply {
            setReferenceCounted(false)
            acquire()
        }
        val s = try {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(Wire.PORT))
                timeToLive = 1
            }
        } catch (e: Exception) {
            stop()
            return false
        }
        socket = s
        Thread({
            val buffer = ByteArray(64 * 1024)
            while (!s.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { s.receive(packet) }.getOrNull() ?: break
                val json = runCatching { JSONObject(String(packet.data, packet.offset, packet.length)) }.getOrNull() ?: continue
                runCatching { onHeard(json, packet.address) }
            }
        }, "nearby-multicast").apply { isDaemon = true }.start()
        return true
    }

    /**
     * The group joined on each of [networks] not joined yet, and left on any no longer there.
     * True when one was joined that was not before, which is when to announce again.
     */
    @Synchronized
    fun follow(networks: List<NetworkInterface>): Boolean {
        val s = socket ?: return false
        val names = networks.map { it.name }.toSet()
        joined.keys.filter { it !in names }.forEach { name ->
            joined.remove(name)?.let { runCatching { s.leaveGroup(InetSocketAddress(group, Wire.PORT), it) } }
        }
        var fresh = false
        networks.filter { it.name !in joined }.forEach { n ->
            if (runCatching { s.joinGroup(InetSocketAddress(group, Wire.PORT), n) }.isSuccess) {
                joined[n.name] = n
                fresh = true
            }
        }
        return fresh
    }

    /** Out on every network joined. */
    @Synchronized
    fun send(json: JSONObject) {
        val s = socket ?: return
        val bytes = json.toString().toByteArray()
        joined.values.forEach { n ->
            runCatching {
                s.networkInterface = n
                s.send(DatagramPacket(bytes, bytes.size, group, Wire.PORT))
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        joined.clear()
        runCatching { lock?.release() }
        lock = null
    }

    companion object {
        /**
         * The local networks, by elimination: up, not loopback, able to multicast, with a private
         * IPv4 address. Tailscale's tunnel has a 100.x address, which is not a private one, so it
         * is never taken for the room's network. Wi-Fi first; a Wi-Fi Direct network is named p2p.
         */
        fun networks(): List<Pair<NetworkInterface, Inet4Address>> {
            val all = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
            return all
                .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
                .sortedByDescending { it.name.startsWith("wlan") }
                .mapNotNull { n ->
                    n.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.let { n to it }
                }
        }

        fun isDirect(n: NetworkInterface) = n.name.startsWith("p2p")
    }
}
