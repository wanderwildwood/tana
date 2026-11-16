package com.wanderwildwood.tana.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainHttpTest {

    @Test
    fun httpsIsAlwaysFine() {
        assertTrue(DavStore.plainHttpAllowed("https://cloud.example.org/"))
    }

    @Test
    fun httpOnAHomeOrVpnNetworkIsFine() {
        for (a in listOf(
            "http://192.168.1.5/", "http://10.0.0.2:8080/", "http://172.20.0.4/", "http://100.101.102.103/",
            "http://[fd12::5]/", "http://nextcloud/", "http://cloud.local/", "http://box.lan/",
            "http://pc.tail1234.ts.net/", "http://localhost:8080/",
        )) assertTrue(a, DavStore.plainHttpAllowed(a))
    }

    @Test
    fun httpToTheInternetIsRefused() {
        for (a in listOf(
            "http://cloud.example.org/", "http://8.8.8.8/", "http://203.0.113.9:8080/", "http://172.32.0.1/",
            "http://evil.local.example.org/", "http://[2001:db8::1]/", "not an address",
        )) assertFalse(a, DavStore.plainHttpAllowed(a))
    }
}

/** A server saved on plain http by an earlier version: kept, but nothing is sent to it. */
class SavedPlainHttpServerTest {

    private val spool = java.nio.file.Files.createTempDirectory("spool").toFile()

    private fun dav(host: String) =
        Server(id = "s1", name = "Files", host = host, share = "", user = "ada", password = "app-password-for-tests", kind = Server.DAV)

    @Test
    fun aServerOnTheInternetIsNotAsked() {
        val e = runCatching { DavStore(dav("http://dav.example.org/remote.php/dav/files/ada/"), spool).list("") }.exceptionOrNull()
        org.junit.Assert.assertEquals(StoreException.Reason.PLAIN_HTTP, (e as? StoreException)?.reason)
    }

    @Test
    fun aServerAtHomeIsStillAsked() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { socket ->
            var heard = ""
            val answer = Thread {
                socket.accept().use { c ->
                    val reader = c.getInputStream().bufferedReader()
                    heard = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.joinToString("\n")
                    c.getOutputStream().write("HTTP/1.1 500 No\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }.apply { start() }
            runCatching { DavStore(dav("http://127.0.0.1:${socket.localPort}/dav/"), spool).list("") }
            answer.join(5000)
            assertTrue(heard, heard.startsWith("PROPFIND ") && heard.contains("Authorization: Basic "))
        }
    }
}
