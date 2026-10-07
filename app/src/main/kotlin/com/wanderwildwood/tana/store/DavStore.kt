package com.wanderwildwood.tana.store

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

    private fun url(path: String, folder: Boolean = false): String =
        base + encodePath(path) + if (folder && path.isNotEmpty()) "/" else ""

    private fun request(url: String) = Request.Builder().url(url).header("Authorization", auth)

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
            if (name.isEmpty()) null else entry(SmbStore.join(path, name), item)
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
        return object : FilterOutputStream(FileOutputStream(held)) {
            private var closed = false

            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
            }

            override fun close() {
                if (closed) return
                closed = true
                try {
                    super.close()
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
         * The folder to keep for what was typed. A Nextcloud is usually given by its address
         * alone; its files are under /remote.php/dav/files/<user>/, and that is tried first when
         * the address has no path. Anything else is taken as the folder itself. Throws the
         * problem when neither answers as a WebDAV folder these credentials open.
         */
        fun resolve(server: Server, spool: File): String {
            val typed = normalise(server.host) ?: throw StoreException(StoreException.Reason.NOT_WEBDAV, server.host)
            val path = typed.toHttpUrlOrNull()?.encodedPath.orEmpty()
            if (path == "/" && server.user.isNotBlank()) {
                val nextcloud = typed + "remote.php/dav/files/" + encodePath(server.user.trim()) + "/"
                val tried = runCatching { DavStore(server.copy(host = nextcloud), spool).list("") }
                if (tried.isSuccess) return nextcloud
                // A refused login is the answer, not a reason to try the plain address.
                (tried.exceptionOrNull() as? StoreException)?.let {
                    if (it.reason == StoreException.Reason.LOGIN_REFUSED || it.reason == StoreException.Reason.SERVER_UNREACHABLE ||
                        it.reason == StoreException.Reason.UNTRUSTED
                    ) throw it
                }
            }
            DavStore(server.copy(host = typed), spool).list("")
            return typed
        }
    }
}
