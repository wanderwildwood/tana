package com.wanderwildwood.tana.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/** An own Nextcloud sign-in: the files folder is named by the user id, not the login name. */
class NextcloudUidTest {

    private val spool = java.nio.file.Files.createTempDirectory("spool").toFile()

    private fun own(host: String, user: String = "ada@example.org", uid: String = "") =
        Server(id = "s1", name = "Cloud", host = host, share = "", user = user, password = "app-password-for-tests", kind = Server.DAV, uid = uid)

    private val ocs = """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":{"enabled":true,"quota":{"free":1},"id":"ada","email":"ada@example.org"}}}"""

    @Test fun theUserIdIsReadFromOcs() {
        assertEquals("ada", DavStore.userId(200, ocs))
        assertNull(DavStore.userId(401, ocs))
        assertNull(DavStore.userId(200, "<html>not here</html>"))
        assertNull(DavStore.userId(200, """{"ocs":{"data":{"id":""}}}"""))
    }

    @Test fun anAnswerToGoByOrNot() {
        assertEquals("ada", (DavStore.uidAnswer(200, ocs) as DavStore.UidAnswer.Id).uid)
        assertTrue(DavStore.uidAnswer(404, "") is DavStore.UidAnswer.None)
        assertTrue(DavStore.uidAnswer(200, "{}") is DavStore.UidAnswer.None)
        assertTrue(DavStore.uidAnswer(401, "") is DavStore.UidAnswer.Later)
        assertTrue(DavStore.uidAnswer(503, "") is DavStore.UidAnswer.Later)
    }

    @Test fun aFilesFolderTakenApart() {
        assertEquals(
            Triple("https://cloud.example.org/nc/", "ada%40example.org", "/Photos/"),
            DavStore.filesFolder("https://cloud.example.org/nc/remote.php/dav/files/ada%40example.org/Photos/"),
        )
        assertNull(DavStore.filesFolder("https://dav.example.org/webdav/"))
    }

    @Test fun onlyAFolderNamedAfterTheLoginAndNeverCheckedIsAskedAbout() {
        val byLogin = own("https://cloud.example.org/remote.php/dav/files/ada%40example.org/")
        assertTrue(DavStore.needsUid(byLogin))
        assertFalse(DavStore.needsUid(byLogin.copy(uid = "ada")))
        assertFalse(DavStore.needsUid(own("https://cloud.example.org/remote.php/dav/files/ada/")))
        assertFalse(DavStore.needsUid(own("https://dav.example.org/webdav/")))
        assertFalse(DavStore.needsUid(byLogin.copy(kind = Server.SMB)))
    }

    @Test fun theFolderFollowsTheUserId() {
        val byLogin = own("https://cloud.example.org/remote.php/dav/files/ada%40example.org/Photos/")
        val fixed = DavStore.withUid(byLogin, DavStore.UidAnswer.Id("ada"))!!
        assertEquals("https://cloud.example.org/remote.php/dav/files/ada/Photos/", fixed.host)
        assertEquals("ada", fixed.uid)
        assertEquals("ada@example.org", fixed.user)
        // A server that does not say keeps its folder, and is not asked again.
        val kept = DavStore.withUid(byLogin, DavStore.UidAnswer.None)!!
        assertEquals(byLogin.host, kept.host)
        assertFalse(DavStore.needsUid(kept))
        // No answer to go by: asked again another time.
        assertNull(DavStore.withUid(byLogin, DavStore.UidAnswer.Later))
    }

    @Test fun theRowShowsNoUserPath() {
        assertEquals("cloud.example.org", DavStore.shown("https://cloud.example.org/remote.php/dav/files/ada%40example.org/"))
        assertEquals("cloud.example.org/nc/My Photos", DavStore.shown("https://cloud.example.org/nc/remote.php/dav/files/ada/My%20Photos/"))
        assertEquals("10.0.0.2:8080/dav", DavStore.shown("http://10.0.0.2:8080/dav/"))
    }

    @Test fun theUidIsKeptWithTheServer() {
        val s = own("https://cloud.example.org/remote.php/dav/files/ada/", uid = "ada")
        assertEquals(s, Server.listFromJson(Server.listToJson(listOf(s))).single())
        assertEquals("", Server.fromJson(org.json.JSONObject(s.toJson().apply { remove("uid") }.toString())).uid)
    }

    /** A Nextcloud on the desk that knows "ada@example.org" as the user "ada". One request per connection. */
    private fun nextcloud(block: (String, List<String>) -> Unit) {
        val heard = Collections.synchronizedList(mutableListOf<String>())
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { socket ->
            val answering = Thread {
                while (true) {
                    val c = runCatching { socket.accept() }.getOrNull() ?: break
                    c.use {
                        val reader = c.getInputStream().bufferedReader()
                        val head = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                        val (method, target) = head.first().split(' ').let { it[0] to it[1] }
                        val path = target.substringBefore('?')
                        heard += "$method $path"
                        val length = head.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                            ?.substringAfter(':')?.trim()?.toInt() ?: 0
                        repeat(length) { reader.read() }
                        val ocsAsked = head.any { it.equals("OCS-APIRequest: true", ignoreCase = true) }
                        val (status, body) = when {
                            method == "GET" && path == "/ocs/v2.php/cloud/user" && ocsAsked -> "200 OK" to ocs
                            method == "PROPFIND" && path == "/remote.php/dav/files/ada/" -> "207 Multi-Status" to
                                """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/remote.php/dav/files/ada/</d:href>""" +
                                """<d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
                            else -> "404 Not Found" to ""
                        }
                        val bytes = body.toByteArray()
                        c.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            block("http://127.0.0.1:${socket.localPort}", heard)
        }
    }

    @Test fun signingInWithAnEmailOpensTheUserIdsFolder() = nextcloud { address, heard ->
        val kept = DavStore.resolve(own(address), spool)
        assertEquals("$address/remote.php/dav/files/ada/", kept.host)
        assertEquals("ada", kept.uid)
        assertEquals("ada@example.org", kept.user)
        assertTrue(heard.toString(), heard.none { it.contains("example.org") })
    }

    @Test fun anOldFolderByLoginIsRenamedOnce() = nextcloud { address, heard ->
        val old = own("$address/remote.php/dav/files/ada%40example.org/")
        val base = DavStore.filesFolder(old.host)!!.first
        val fixed = DavStore.withUid(old, DavStore.askUid(base, old))!!
        assertEquals("$address/remote.php/dav/files/ada/", fixed.host)
        assertFalse(DavStore.needsUid(fixed))
        DavStore(fixed, spool).list("")
        assertEquals(listOf("GET /ocs/v2.php/cloud/user", "PROPFIND /remote.php/dav/files/ada/"), heard.toList())
    }
}
