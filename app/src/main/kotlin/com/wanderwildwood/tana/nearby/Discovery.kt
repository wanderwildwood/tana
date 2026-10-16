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
 * Being found, and finding: a few lines of JSON to LocalSend's multicast group on the Wi-Fi, and
 * whatever the others say to it. Android drops multicast to save power unless an app holds a
 * lock for it, so the lock is held exactly as long as this runs.
 */
internal class Discovery(private val wifi: WifiManager, private val onHeard: (JSONObject, InetAddress) -> Unit) {
    private var socket: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private val group = InetAddress.getByName(Wire.GROUP)

    /** False when the port is taken, by LocalSend itself on this phone most likely. */
    fun start(network: NetworkInterface): Boolean {
        lock = wifi.createMulticastLock("tana-nearby").apply {
            setReferenceCounted(false)
            acquire()
        }
        val s = try {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(Wire.PORT))
                networkInterface = network
                timeToLive = 1
                joinGroup(InetSocketAddress(group, Wire.PORT), network)
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

    fun send(json: JSONObject) {
        val s = socket ?: return
        val bytes = json.toString().toByteArray()
        runCatching { s.send(DatagramPacket(bytes, bytes.size, group, Wire.PORT)) }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        runCatching { lock?.release() }
        lock = null
    }

    companion object {
        /**
         * The Wi-Fi, by elimination: up, not loopback, able to multicast, with a private IPv4
         * address. Tailscale's tunnel has a 100.x address, which is not a private one, so it is
         * never taken for the room's network.
         */
        fun wifiInterface(): Pair<NetworkInterface, Inet4Address>? {
            val all = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
            return all
                .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
                .sortedByDescending { it.name.startsWith("wlan") }
                .firstNotNullOfOrNull { n ->
                    n.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.let { n to it }
                }
        }
    }
}
