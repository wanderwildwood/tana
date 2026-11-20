package com.wanderwildwood.tana.store

import com.wanderwildwood.tana.work.Names
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * A Nextcloud, or any other WebDAV server, as a store: the folder at [Server.host] and
 * everything under it.
 *
 * Plain HTTP requests, one per call: PROPFIND to list and to look at one file, GET and PUT,
 * MKCOL, MOVE and DELETE. A file being written is held in [spool] until it is whole and then
 * sent with its length, because a server behind PHP (Nextcloud is one) can take a body of
 * unknown length as an empty file.
 */
class DavStore(override val server: Server, private val spool: File) : ServerStore {
    override val id: String = server.storeId

    private val base = server.host.trimEnd('/') + "/"
    private val auth = Credentials.basic(server.user, server.password, Charsets.UTF_8)

    /** A path with an empty, "." or ".." step is never asked for: it would lead above [base]. */
    private fun url(path: String, folder: Boolean = false): String {
        if (!Names.isPlainPath(path)) throw StoreException(StoreException.Reason.GONE, path.substringAfterLast('/'))
        return base + encodePath(path) + if (folder && path.isNotEmpty()) "/" else ""
    }

    /**
     * Every request carries the password, so none is made to a plain http address out on the
     * internet: a server saved that way by an earlier version is kept, with its password, but
     * opens nothing until its address is changed to https or to one at home.
     */
    private fun request(url: String): Request.Builder {
        if (!plainHttpAllowed(url)) throw StoreException(StoreException.Reason.PLAIN_HTTP, server.host.trimEnd('/'))
        return Request.Builder().url(url).header("Authorization", auth)
    }

    override fun list(path: String): List<Entry> {
        val asked = url(path, folder = true)
        val items = propfind(asked, 1, path.substringAfterLast('/'))
            ?: throw StoreException(StoreException.Reason.GONE, path.substringAfterLast('/'))
        // The folder itself is in its own listing. Usually under the path asked for; behind a
        // proxy that rewrites paths it is the shortest one, which is the same folder.
        val self = decodeHref(asked).trimEnd('/')
        val skip = items.firstOrNull { it.path.trimEnd('/') == self } ?: items.minByOrNull { it.path.length }
        return items.filter { it !== skip }.mapNotNull { item ->
            val name = item.path.trimEnd('/').substringAfterLast('/')
            // A name that is empty, "." or ".." or holds a slash is not a file in this folder.
            if (!Names.isPlainName(name)) null else entry(SmbStore.join(path, name), item)
        }
    }

    override fun stat(path: String): Entry? {
        if (path.isEmpty()) return Entry(Loc(id, ""), isFolder = true, size = 0, modified = 0)
        val item = propfind(url(path), 0, path.substringAfterLast('/'))?.firstOrNull() ?: return null
        return entry(path, item)
    }

    private fun entry(path: String, item: DavItem) = Entry(
        loc = Loc(id, path),
        isFolder = item.isFolder,
        size = if (item.isFolder) 0 else item.length.coerceAtLeast(0),
        modified = item.modified,
        tag = item.etag,
    )

    override fun openRead(path: String): InputStream {
        val name = path.substringAfterLast('/')
        val response = send(request(url(path)).get().build(), name)
        if (!response.isSuccessful) {
            response.close()
            throw problem(response.code, name)
        }
        val body = response.body ?: run { response.close(); throw StoreException(StoreException.Reason.SERVER_ERROR, name) }
        return object : FilterInputStream(body.byteStream()) {
            override fun close() {
                try { super.close() } finally { response.close() }
            }
        }
    }

    override fun openWrite(path: String): OutputStream {
        val name = path.substringAfterLast('/')
        spool.mkdirs()
        val held = File.createTempFile("put-", ".part", spool)
        return object : FilterOutputStream(FileOutputStream(held)), AbortableOutput {
            private var closed = false
            private var aborted = false

            override fun abort() {
                aborted = true
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
            }

            override fun close() {
                if (closed) return
                closed = true
                try {
                    super.close()
                    // A copy stopped or failed half way is not sent: only the whole file is.
                    if (aborted) return
                    val put = request(url(path)).put(held.asRequestBody(OCTETS)).build()
                    send(put, name).use { response ->
                        if (!response.isSuccessful) throw problem(response.code, name)
                    }
                } finally {
                    held.delete()
                }
            }
        }
    }

    override fun makeFolder(path: String) {
        val name = path.substringAfterLast('/')
        if (stat(path) != null) throw StoreException(StoreException.Reason.ALREADY_THERE, name)
        send(request(url(path, folder = true)).method("MKCOL", null).build(), name).use { response ->
            when {
                response.isSuccessful -> Unit
                response.code == 405 -> throw StoreException(StoreException.Reason.ALREADY_THERE, name)
                response.code == 401 || response.code == 403 -> throw problem(response.code, name)
                else -> throw StoreException(StoreException.Reason.CANNOT_MAKE_FOLDER, name)
            }
        }
    }

    override fun rename(from: String, to: String) = move(from, to, overwrite = false)

    override fun replace(from: String, to: String) = move(from, to, overwrite = true)

    private fun move(from: String, to: String, overwrite: Boolean) {
        val name = from.substringAfterLast('/')
        val folder = stat(from)?.isFolder ?: throw StoreException(StoreException.Reason.GONE, name)
        val request = request(url(from, folder)).method("MOVE", null)
            .header("Destination", url(to, folder))
            .header("Overwrite", if (overwrite) "T" else "F")
            .build()
        send(request, name).use { response ->
            when {
                response.isSuccessful -> Unit
                // "Overwrite: F" and something is there.
                response.code == 412 -> throw StoreException(StoreException.Reason.ALREADY_THERE, to.substringAfterLast('/'))
                response.code == 401 || response.code == 403 || response.code == 404 -> throw problem(response.code, name)
                else -> throw StoreException(StoreException.Reason.CANNOT_RENAME, name)
            }
        }
    }

    override fun deleteFile(path: String) = delete(path, folder = false)

    override fun deleteFolder(path: String) {
        // A WebDAV DELETE of a folder takes everything in it. The contract here is an empty
        // folder only, so that is checked first.
        if (list(path).isNotEmpty()) throw StoreException(StoreException.Reason.CANNOT_DELETE, path.substringAfterLast('/'))
        delete(path, folder = true)
    }

    private fun delete(path: String, folder: Boolean) {
        val name = path.substringAfterLast('/')
        send(request(url(path, folder)).delete().build(), name).use { response ->
            when {
                response.isSuccessful || response.code == 404 -> Unit
                response.code == 401 || response.code == 403 -> throw problem(response.code, name)
                else -> throw StoreException(StoreException.Reason.CANNOT_DELETE, name)
            }
        }
    }

    override fun setModified(path: String, time: Long) {
        if (time <= 0) return
        // Nextcloud keeps the date it is given here; a server that does not simply says no, and a
        // date that did not carry over is not worth stopping a copy for.
        val stamp = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneOffset.UTC),
        )
        val body = """<?xml version="1.0"?><d:propertyupdate xmlns:d="DAV:"><d:set><d:prop>""" +
            "<d:lastmodified>${time / 1000}</d:lastmodified><d:getlastmodified>$stamp</d:getlastmodified>" +
            "</d:prop></d:set></d:propertyupdate>"
        runCatching {
            send(request(url(path)).method("PROPPATCH", body.toRequestBody(XML)).build(), path.substringAfterLast('/')).close()
        }
    }

    override fun close() {}

    /** The listing at [url], or null when nothing is there. */
    private fun propfind(url: String, depth: Int, subject: String): List<DavItem>? {
        val request = request(url).method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .header("Depth", depth.toString())
            .build()
        return send(request, subject).use { response ->
            when {
                response.code == 404 -> null
                response.code == 207 -> try {
                    response.body!!.byteStream().use(::parseMultistatus)
                } catch (e: Exception) {
                    throw StoreException(StoreException.Reason.NOT_WEBDAV, server.host, e)
                }
                response.code == 401 || response.code == 403 -> throw problem(response.code, subject)
                // A web page, or a server that does not know PROPFIND.
                else -> throw StoreException(StoreException.Reason.NOT_WEBDAV, server.host)
            }
        }
    }

    private fun send(request: Request, subject: String): Response = try {
        client.newCall(request).execute()
    } catch (e: IOException) {
        if (e is SSLException || e.cause is CertificateException) {
            throw StoreException(StoreException.Reason.UNTRUSTED, server.host, e)
        }
        throw StoreException(StoreException.Reason.SERVER_UNREACHABLE, hostOnly(server.host), e)
    }

    private fun problem(code: Int, subject: String) = when (code) {
        401 -> StoreException(StoreException.Reason.LOGIN_REFUSED, subject)
        403 -> StoreException(StoreException.Reason.NOT_ALLOWED, subject)
        404, 409 -> StoreException(StoreException.Reason.GONE, subject)
        507 -> StoreException(StoreException.Reason.CANNOT_WRITE, subject)
        else -> StoreException(StoreException.Reason.SERVER_ERROR, subject)
    }

    /** What a Nextcloud said when asked whose account a login is ([askUid]). */
    sealed class UidAnswer {
        /** The account's user id. */
        class Id(val uid: String) : UidAnswer()

        /** The server answered, but with no user id: not a Nextcloud, or one too old to say. */
        object None : UidAnswer()

        /** No answer to go by: the server could not be reached, or refused the login. Ask again another time. */
        object Later : UidAnswer()
    }

    companion object {
        private val OCTETS = "application/octet-stream".toMediaType()
        private val XML = "application/xml; charset=utf-8".toMediaType()

        private const val PROPFIND_BODY = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:getetag/><d:resourcetype/><d:getlastmodified/><d:getcontentlength/></d:prop></d:propfind>"""

        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // A MOVE answered with a redirect must not be followed as a GET.
            .followRedirects(false)
            .build()

        private fun hostOnly(address: String) = address.toHttpUrlOrNull()?.host ?: address

        /**
         * Whether [address] may be used: any https one, or an http one whose host is on a home or
         * VPN network (a private or Tailscale address, "localhost", a name with no dots, or one
         * ending .local, .lan, .home.arpa, .internal or .ts.net). Memo's and Datebook's rule.
         */
        fun plainHttpAllowed(address: String): Boolean {
            val url = address.toHttpUrlOrNull() ?: return false
            if (url.scheme == "https") return true
            val host = url.host.lowercase()
            if (host.contains(':') || host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) return isPrivateAddress(host)
            return !host.contains('.') || listOf(".local", ".lan", ".home.arpa", ".internal", ".ts.net").any { host.endsWith(it) }
        }

        /** A private, loopback, link-local or Tailscale (100.64/10) address literal; never a name. */
        internal fun isPrivateAddress(literal: String): Boolean {
            val addr = literal.substringBefore('%').lowercase().trim()
            val v4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(addr)
            val v6 = ':' in addr && Regex("^[0-9a-f.:]+$").matches(addr)
            if (!v4 && !v6) return false
            val bytes = runCatching { java.net.InetAddress.getByName(addr).address }.getOrNull() ?: return false
            val a = bytes[0].toInt() and 0xFF
            val b = bytes[1].toInt() and 0xFF
            if (bytes.size == 4) {
                return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                    (a == 169 && b == 254) || (a == 100 && b in 64..127)
            }
            val loopback = bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1
            return loopback || (a and 0xFE) == 0xFC || (a == 0xFE && (b and 0xC0) == 0x80)
        }

        /**
         * What was typed, as an address: "https://" put in front if no scheme was given, and one
         * slash at the end. Null if it is not an address at all.
         */
        fun normalise(typed: String): String? {
            val trimmed = typed.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val parsed = withScheme.toHttpUrlOrNull() ?: return null
            return parsed.toString().trimEnd('/') + "/"
        }

        /**
         * The folder to keep for what was typed, as the server to keep. A Nextcloud is usually
         * given by its address alone; its files are under /remote.php/dav/files/<user id>/, and
         * that is tried first when the address has no path. The user id is asked of the server
         * ([askUid], Nextcloud's `ocs/v2.php/cloud/user`), because it can differ from the login
         * name (an email address, another case); a server that does not say is tried with the
         * login name. Anything else is taken as the folder itself. Throws the problem when
         * neither answers as a WebDAV folder these credentials open.
         */
        fun resolve(server: Server, spool: File, askUid: (String, Server) -> UidAnswer = ::askUid): Server {
            val typed = normalise(server.host) ?: throw StoreException(StoreException.Reason.NOT_WEBDAV, server.host)
            val path = typed.toHttpUrlOrNull()?.encodedPath.orEmpty()
            if (path == "/" && server.user.isNotBlank()) {
                val login = server.user.trim()
                val uid = (askUid(typed, server) as? UidAnswer.Id)?.uid ?: login
                val nextcloud = typed + "remote.php/dav/files/" + encodePath(uid) + "/"
                val tried = runCatching { DavStore(server.copy(host = nextcloud), spool).list("") }
                if (tried.isSuccess) return server.copy(host = nextcloud, uid = uid)
                // A refused login is the answer, not a reason to try the plain address.
                (tried.exceptionOrNull() as? StoreException)?.let {
                    if (it.reason == StoreException.Reason.LOGIN_REFUSED || it.reason == StoreException.Reason.SERVER_UNREACHABLE ||
                        it.reason == StoreException.Reason.UNTRUSTED
                    ) throw it
                }
            }
            DavStore(server.copy(host = typed), spool).list("")
            return server.copy(host = typed, uid = "")
        }

        /** The user id in Nextcloud's answer to `ocs/v2.php/cloud/user?format=json` (`ocs.data.id`), or null. */
        fun userId(code: Int, body: String): String? {
            if (code != 200) return null
            return runCatching {
                org.json.JSONObject(body).getJSONObject("ocs").getJSONObject("data").getString("id").trim().takeIf { it.isNotEmpty() }
            }.getOrNull()
        }

        /** What [userId] makes of an answer with [code] and [body]. */
        fun uidAnswer(code: Int, body: String): UidAnswer = when {
            code == 401 || code == 403 || code >= 500 -> UidAnswer.Later
            else -> userId(code, body)?.let { UidAnswer.Id(it) } ?: UidAnswer.None
        }

        /**
         * Asks the Nextcloud at [address] (the server's own address, no files path) whose
         * account [server]'s login is. Never to a plain http address out on the internet.
         */
        fun askUid(address: String, server: Server): UidAnswer {
            val url = address.trimEnd('/') + "/ocs/v2.php/cloud/user?format=json"
            if (!plainHttpAllowed(url)) return UidAnswer.Later
            return try {
                val request = Request.Builder().url(url)
                    .header("Authorization", Credentials.basic(server.user.trim(), server.password, Charsets.UTF_8))
                    .header("OCS-APIRequest", "true")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { uidAnswer(it.code, it.body?.string().orEmpty()) }
            } catch (_: Exception) {
                UidAnswer.Later
            }
        }

        private const val FILES = "/remote.php/dav/files/"

        /**
         * A Nextcloud files address taken apart: the server's own address (with a slash at the
         * end), the folder's user segment as written, and what follows it. Null for any other.
         */
        fun filesFolder(host: String): Triple<String, String, String>? {
            val at = host.indexOf(FILES)
            if (at < 0 || !host.contains("://")) return null
            val after = host.substring(at + FILES.length)
            val segment = after.substringBefore('/')
            if (segment.isEmpty()) return null
            return Triple(host.substring(0, at + 1), segment, after.substring(segment.length))
        }

        /**
         * Whether [server], one of the app's own sign-ins, is a Nextcloud files folder named
         * after the login name and never checked against the server's user id: kept so by an
         * earlier version, which built the folder from the login name.
         */
        fun needsUid(server: Server): Boolean {
            if (!server.isDav || server.uid.isNotEmpty() || server.user.isBlank()) return false
            val segment = filesFolder(server.host)?.second ?: return false
            return segment == encodePath(server.user.trim())
        }

        /**
         * [server] after the server answered [answer] about its user id ([needsUid]): the folder
         * renamed after the id, and the id kept so it is not asked again. Null when there is
         * nothing to go by yet ([UidAnswer.Later]).
         */
        fun withUid(server: Server, answer: UidAnswer): Server? {
            val (base, _, rest) = filesFolder(server.host) ?: return null
            return when (answer) {
                is UidAnswer.Later -> null
                is UidAnswer.None -> server.copy(uid = server.user.trim())
                is UidAnswer.Id -> server.copy(host = base + FILES.trimStart('/') + encodePath(answer.uid) + rest, uid = answer.uid)
            }
        }

        /**
         * A WebDAV server's address as shown: no scheme, no slash at the end, and for a Nextcloud
         * no "/remote.php/dav/files/<user>" (the user's name or id is nobody else's to show, and
         * the account is said elsewhere); a folder below it is shown, decoded.
         */
        fun shown(host: String): String {
            val trimmed = host.trim()
            val folder = filesFolder(trimmed)
            val text = if (folder == null) trimmed else {
                val below = folder.third.trim('/').split('/').filter { it.isNotEmpty() }
                    .joinToString("/") { runCatching { decodeHref(it) }.getOrDefault(it) }
                folder.first.trimEnd('/') + if (below.isEmpty()) "" else "/$below"
            }
            return text.substringAfter("://").trimEnd('/')
        }
    }
}
