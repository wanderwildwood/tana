package com.wanderwildwood.tana.nearby

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

internal class Request(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val body: InputStream,
    val remote: InetAddress,
    /** The certificate the other end showed, when it showed one. */
    val certificate: X509Certificate?,
    val socket: Socket,
)

internal class Response(val status: Int, val json: JSONObject? = null)

/**
 * Just enough HTTP/1.1 for LocalSend's few routes: a request line, headers, and a body by length
 * or in chunks. A library would bring a great deal more than this needs; the whole of it is here.
 * One thread per connection, and connections are kept open between requests, as LocalSend's
 * client expects.
 */
internal class HttpServer(private val tls: SSLContext, private val handle: (Request) -> Response) {
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    private val open = Collections.synchronizedSet(mutableSetOf<Socket>())

    /** The port it ended up on: [preferred] when that is free, any port when not. */
    fun start(preferred: Int): Int {
        val s = tls.serverSocketFactory.createServerSocket() as SSLServerSocket
        s.reuseAddress = true
        // Asked for, not required: LocalSend shows one, and a browser or older client may not.
        s.wantClientAuth = true
        try {
            s.bind(InetSocketAddress(preferred))
        } catch (e: IOException) {
            s.bind(InetSocketAddress(0))
        }
        server = s
        val p = Executors.newCachedThreadPool()
        pool = p
        Thread({
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                if (open.size >= MAX_CONNECTIONS) {
                    runCatching { client.close() }
                    continue
                }
                open += client
                runCatching { p.execute { serve(client) } }.onFailure { runCatching { client.close() } }
            }
        }, "nearby-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
        pool?.shutdownNow()
        pool = null
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 60_000
            val certificate = runCatching {
                (socket as SSLSocket).session.peerCertificates.firstOrNull() as? X509Certificate
            }.getOrNull()
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
            while (true) {
                var line = readLine(input) ?: return
                if (line.isEmpty()) line = readLine(input) ?: return
                val parts = line.split(' ')
                if (parts.size < 3) return
                val headers = HashMap<String, String>()
                while (true) {
                    val h = readLine(input) ?: return
                    if (h.isEmpty()) break
                    if (headers.size > 100) return
                    val colon = h.indexOf(':')
                    if (colon > 0) headers[h.substring(0, colon).trim().lowercase()] = h.substring(colon + 1).trim()
                }
                val body: InputStream = when {
                    headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> Chunked(input)
                    else -> Limited(input, headers["content-length"]?.toLongOrNull() ?: 0)
                }
                if (headers["expect"].equals("100-continue", ignoreCase = true)) {
                    output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                    output.flush()
                }
                val target = parts[1]
                val request = Request(
                    method = parts[0],
                    path = target.substringBefore('?'),
                    query = query(target.substringAfter('?', "")),
                    body = body,
                    remote = socket.inetAddress,
                    certificate = certificate,
                    socket = socket,
                )
                val response = runCatching { handle(request) }.getOrElse { Response(500) }
                // Whatever the handler did not read is read past, so the next request starts in
                // the right place; a large unread body is cheaper to hang up on.
                val drained = runCatching { drain(body) }.getOrDefault(false)
                write(output, response)
                if (!drained || headers["connection"].equals("close", ignoreCase = true) || parts[2] != "HTTP/1.1") return
            }
        } catch (_: IOException) {
        } finally {
            open -= socket
            runCatching { socket.close() }
        }
    }

    private fun write(output: BufferedOutputStream, response: Response) {
        val bytes = response.json?.toString()?.toByteArray() ?: ByteArray(0)
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            if (response.json != null) append("Content-Type: application/json\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: keep-alive\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    private fun drain(body: InputStream): Boolean {
        val buffer = ByteArray(8192)
        var left = MAX_DRAIN
        while (left > 0) {
            val n = body.read(buffer)
            if (n < 0) return true
            left -= n
        }
        return false
    }

    companion object {
        private const val MAX_CONNECTIONS = 16
        private const val MAX_DRAIN = 1L shl 20
        private const val MAX_LINE = 8192

        /** One line, without its CR LF. Null at the end of the stream. */
        fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > MAX_LINE) throw IOException("line too long")
            }
        }

        fun query(q: String): Map<String, String> =
            q.split('&').filter { it.isNotEmpty() }.associate {
                val k = it.substringBefore('=')
                val v = it.substringAfter('=', "")
                java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
            }

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            422 -> "Unprocessable Entity"
            429 -> "Too Many Requests"
            else -> "Error"
        }
    }
}

/** A body of a known length: the stream ends where it does. */
internal class Limited(private val input: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
        if (left <= 0) return -1
        val b = input.read()
        if (b < 0) throw IOException("ended early")
        left--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = input.read(b, off, minOf(len.toLong(), left).toInt())
        if (n < 0) throw IOException("ended early")
        left -= n
        return n
    }
}

/** A body sent in chunks, each with its length in hex before it, ending at a chunk of none. */
internal class Chunked(private val input: InputStream) : InputStream() {
    private var left = 0L
    private var done = false

    private fun next(): Boolean {
        if (done) return false
        if (left > 0) return true
        val line = HttpServer.readLine(input) ?: throw IOException("ended early")
        val size = line.substringBefore(';').trim().ifEmpty {
            // The CR LF after the chunk before.
            HttpServer.readLine(input)?.substringBefore(';')?.trim() ?: throw IOException("ended early")
        }.toLong(16)
        if (size == 0L) {
            while ((HttpServer.readLine(input) ?: "").isNotEmpty()) Unit
            done = true
            return false
        }
        left = size
        return true
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!next()) return -1
        val n = input.read(b, off, minOf(len.toLong(), left).toInt())
        if (n < 0) throw IOException("ended early")
        left -= n
        return n
    }
}
