package com.wanderwildwood.tana.nearby

import android.content.Context
import android.media.MediaScannerConnection
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.store.LocalStore
import com.wanderwildwood.tana.store.Loc
import com.wanderwildwood.tana.store.Stores
import com.wanderwildwood.tana.work.Names
import com.wanderwildwood.tana.work.PartJournal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** A device on the same Wi-Fi that can be sent to. */
data class Peer(val fingerprint: String, val alias: String, val note: String?)

/** Someone asking to send files here, waiting on Accept or Decline. */
data class Incoming(val from: String, val files: Int, val bytes: Long, val names: List<String>, val fits: Boolean)

/** A send or a receive: under way, or how it ended. */
sealed interface Errand {
    data class Asking(val to: String) : Errand
    data class NeedsPin(val to: String, val wrong: Boolean) : Errand
    data class Sending(val to: String, val done: Int, val total: Int, val percent: Int) : Errand
    data class Sent(val to: String, val files: Int) : Errand
    data class Declined(val by: String) : Errand
    data class Receiving(val from: String, val done: Int, val total: Int, val percent: Int) : Errand
    data class Received(val from: String, val files: Int, val failed: Int) : Errand
    data class Failed(val why: Why, val who: String) : Errand

    val underWay: Boolean get() = this is Asking || this is NeedsPin || this is Sending || this is Receiving
}

enum class Why { UNREACHABLE, BUSY, STOPPED_THERE, NOT_READABLE, OTHER }

/** A Wi-Fi Direct network, for when there is no Wi-Fi: this phone's, or another's it joined. */
sealed interface Away {
    data object Starting : Away
    data class Hosting(val network: String, val passphrase: String) : Away
    data class Joining(val to: String) : Away
    data class Joined(val to: String) : Away
    data class Failed(val why: AwayWhy) : Away
}

enum class AwayWhy { WIFI_OFF, LOCATION_OFF, UNSUPPORTED, BUSY, NO_ANSWER, OTHER }

data class NearbyState(
    val running: Boolean = false,
    val alias: String = "",
    /** This phone's address on the Wi-Fi; null while it is not on one. */
    val address: String? = null,
    /** Another app on this phone has LocalSend's port: it cannot hear announcements. */
    val portTaken: Boolean = false,
    val peers: List<Peer> = emptyList(),
    val incoming: Incoming? = null,
    val errand: Errand? = null,
    val away: Away? = null,
    /** A tap handed over an invite, and joining it waits on the location permission. */
    val wantsPermission: Boolean = false,
)

/**
 * Sending to and receiving from LocalSend on other devices on the same Wi-Fi. It runs only while
 * the Nearby page is open: the server is up, the phone is announced, and others can find it and
 * offer files; when the page is closed, all of it stops and nothing listens. Every offer is asked
 * about, and what is accepted lands in Downloads.
 */
object Nearby {
    private val _state = MutableStateFlow(NearbyState())
    val state: StateFlow<NearbyState> = _state

    private lateinit var app: Context
    private var scope: CoroutineScope? = null
    private var identity: Identity? = null
    @Volatile private var me: Device? = null
    private var server: HttpServer? = null
    private var discovery: Discovery? = null
    private val devices = ConcurrentHashMap<String, Device>()
    private val lock = Any()

    private val json = "application/json".toMediaType()
    private val octets = "application/octet-stream".toMediaType()
    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun start(context: Context, alias: String) {
        if (scope != null) return
        app = context.applicationContext
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        _state.value = NearbyState(running = true, alias = alias)
        sc.launch {
            val id = identity ?: runCatching { Identity.load(app) }.getOrNull()?.also { identity = it } ?: return@launch
            val s = HttpServer(id.serverContext(), ::handle)
            val port = runCatching { s.start(Wire.PORT) }.getOrNull() ?: return@launch
            server = s
            me = Device(
                alias = _state.value.alias,
                model = android.os.Build.MODEL,
                type = "mobile",
                fingerprint = id.fingerprint,
                port = port,
                https = true,
            )
            val d = Discovery(app.getSystemService(WifiManager::class.java), ::heard)
            val heard = d.open()
            discovery = d
            _state.update { it.copy(portTaken = !heard) }
            // The networks are looked at again every few seconds: the reader may be joining the
            // Wi-Fi now, and a Wi-Fi Direct network comes and goes. A new one is announced on.
            while (isActive) {
                val networks = Discovery.networks()
                if (d.follow(networks.map { it.first })) announce()
                val wifi = networks.firstOrNull { !Discovery.isDirect(it.first) }?.second?.hostAddress
                _state.update { it.copy(address = wifi) }
                delay(5000)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            asking?.complete(false)
            receiving?.let { end(it, null) }
        }
        sending?.cancel()
        current?.cancel()
        direct?.let { d ->
            direct = null
            CoroutineScope(Dispatchers.IO).launch {
                d.leave()
                d.close()
            }
        }
        waitingToJoin = null
        server?.stop()
        server = null
        discovery?.stop()
        discovery = null
        scope?.cancel()
        scope = null
        me = null
        devices.clear()
        letGo()
        _state.value = NearbyState()
    }

    /** The list emptied and the phone announced again: everyone listening answers afresh. */
    fun lookAgain() {
        devices.clear()
        publish()
        announce()
    }

    fun rename(alias: String) {
        _state.update { it.copy(alias = alias) }
        me = me?.copy(alias = alias)
        announce()
    }

    /** Three times, as LocalSend does, since a multicast packet is easily lost. */
    private fun announce() {
        val sc = scope ?: return
        sc.launch {
            for (wait in longArrayOf(100, 500, 2000)) {
                delay(wait)
                val m = me ?: return@launch
                discovery?.send(m.json(announce = true))
            }
        }
    }

    // ---------------------------------------------------------------- who is there

    private fun heard(o: JSONObject, from: InetAddress) {
        val m = me ?: return
        val d = Device.from(o, from.hostAddress ?: return) ?: return
        if (d.fingerprint.equals(m.fingerprint, ignoreCase = true)) return
        put(d)
        // An announcement is answered at its sender's server; an older LocalSend's answer by
        // multicast says so with announce false, and is not answered again.
        if (o.optBoolean("announce", true)) scope?.launch { register(d) }
    }

    private fun register(d: Device) {
        val m = me ?: return
        runCatching {
            val request = okhttp3.Request.Builder().url(d.url("register")).post(m.json().toString().toRequestBody(json)).build()
            clientFor(d).newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val answer = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                    answer?.let { Device.from(it, d.address, d.port, d.https) }?.let { put(it.copy(port = d.port, https = d.https)) }
                }
            }
        }
    }

    private fun put(d: Device) {
        devices[d.fingerprint.uppercase()] = d
        publish()
    }

    private fun publish() {
        _state.update { s ->
            s.copy(peers = devices.values.sortedBy { it.alias.lowercase() }.map { Peer(it.fingerprint.uppercase(), it.alias, it.model) })
        }
    }

    private fun clientFor(d: Device): OkHttpClient {
        val id = identity
        if (!d.https || id == null) return base
        val trust = FingerprintTrust(d.fingerprint)
        return base.newBuilder()
            .sslSocketFactory(id.clientContext(trust).socketFactory, trust)
            // A self-signed certificate names no host; the fingerprint is what is checked.
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    // ---------------------------------------------------------------- the server's routes

    private fun handle(r: Request): Response {
        if (!r.path.startsWith(Wire.BASE)) return Response(404)
        return when (r.path.removePrefix(Wire.BASE)) {
            "register" -> registered(r)
            "info" -> me?.let { Response(200, it.json()) } ?: Response(500)
            "prepare-upload" -> prepareUpload(r)
            "upload" -> upload(r)
            "cancel" -> cancelled(r)
            else -> Response(404)
        }
    }

    private fun bodyJson(r: Request): JSONObject? {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = r.body.read(buffer)
            if (n < 0) break
            bytes.write(buffer, 0, n)
            if (bytes.size() > 2 shl 20) return null
        }
        return runCatching { JSONObject(bytes.toString("UTF-8")) }.getOrNull()
    }

    /** Who a request is from: what it says, with the fingerprint its certificate proves in place of the one it claims. */
    private fun sender(r: Request, o: JSONObject?): Device? {
        val d = o?.let { Device.from(it, r.remote.hostAddress ?: return null) } ?: return null
        val proven = r.certificate?.let { Identity.fingerprintOf(it) }
        return if (proven != null) d.copy(fingerprint = proven) else d
    }

    private fun registered(r: Request): Response {
        val m = me ?: return Response(500)
        sender(r, bodyJson(r))?.let { if (!it.fingerprint.equals(m.fingerprint, ignoreCase = true)) put(it) }
        return Response(200, m.json())
    }

    // ---------------------------------------------------------------- receiving

    private class Session(
        val id: String,
        val from: String,
        val ip: InetAddress,
        val offers: Map<String, Offer>,
        val tokens: Map<String, String>,
    ) {
        val total: Long = offers.values.sumOf { it.size }
        val started = ConcurrentHashMap.newKeySet<String>()
        val reserved = mutableSetOf<String>()
        val sockets = ConcurrentHashMap.newKeySet<java.net.Socket>()
        val bytes = AtomicLong()
        @Volatile var ok = 0
        @Volatile var failed = 0
        @Volatile var lastHeard = System.currentTimeMillis()
        @Volatile var lastShown = 0L
        @Volatile var over = false
    }

    private var asking: CompletableFuture<Boolean>? = null
    private var receiving: Session? = null

    fun answer(accept: Boolean) {
        synchronized(lock) { asking?.complete(accept) }
    }

    private fun prepareUpload(r: Request): Response {
        val o = bodyJson(r) ?: return Response(400)
        val from = sender(r, o.optJSONObject("info")) ?: return Response(400)
        val files = o.optJSONObject("files") ?: return Response(400)
        val offers = files.keys().asSequence().mapNotNull { files.optJSONObject(it)?.let(Offer::from) }.associateBy { it.id }
        if (offers.isEmpty()) return Response(400)
        val bytes = offers.values.sumOf { it.size }
        val fits = runCatching { StatFs(downloads().path).availableBytes > bytes + ROOM }.getOrDefault(false)
        val ask = CompletableFuture<Boolean>()
        synchronized(lock) {
            if (asking != null || _state.value.errand?.underWay == true) return Response(409)
            asking = ask
            _state.update {
                it.copy(incoming = Incoming(from.alias, offers.size, bytes, offers.values.map { o -> o.name }.sorted().take(5), fits))
            }
        }
        // Two minutes to answer; after that it is a no, and the sender is told so.
        val yes = runCatching { ask.get(120, TimeUnit.SECONDS) }.getOrDefault(false)
        synchronized(lock) {
            asking = null
            _state.update { it.copy(incoming = null) }
            if (!yes || !fits || scope == null) return Response(403)
            val session = Session(
                id = UUID.randomUUID().toString(),
                from = from.alias,
                ip = r.remote,
                offers = offers,
                tokens = offers.keys.associateWith { UUID.randomUUID().toString() },
            )
            receiving = session
            _state.update { it.copy(errand = Errand.Receiving(from.alias, 0, offers.size, 0)) }
            hold()
            watch(session)
            val answer = JSONObject().put("sessionId", session.id).put("files", JSONObject(session.tokens))
            return Response(200, answer)
        }
    }

    /** A sender that goes quiet for a minute has gone; the receive ends rather than waiting for good. */
    private fun watch(s: Session) {
        scope?.launch {
            while (!s.over) {
                delay(5000)
                if (!s.over && System.currentTimeMillis() - s.lastHeard > 60_000) {
                    synchronized(lock) { end(s, Errand.Failed(Why.STOPPED_THERE, s.from)) }
                }
            }
        }
    }

    private fun upload(r: Request): Response {
        val s = receiving ?: return Response(403)
        val fileId = r.query["fileId"] ?: return Response(400)
        if (r.query["sessionId"] != s.id || r.query["token"] != s.tokens[fileId] || r.remote != s.ip) return Response(403)
        val offer = s.offers[fileId] ?: return Response(403)
        if (!s.started.add(fileId)) return Response(409)
        s.sockets += r.socket
        s.lastHeard = System.currentTimeMillis()
        var status = 500
        try {
            val target = place(s, offer)
            val part = File(target.parentFile, Names.unique(target.name + ".part") { File(target.parentFile, it).exists() })
            val loc = Loc(LocalStore.ID, Stores.phone.pathOf(part))
            PartJournal.started(app, loc)
            try {
                val digest = offer.sha256?.let { MessageDigest.getInstance("SHA-256") }
                val count = part.outputStream().use { out -> pump(r.body, s) { b, n -> out.write(b, 0, n); digest?.update(b, 0, n) } }
                status = when {
                    count != offer.size -> 400
                    digest != null && !digest.digest().joinToString("") { "%02x".format(it) }.equals(offer.sha256, ignoreCase = true) -> 422
                    !part.renameTo(target) -> 500
                    else -> 200
                }
                if (status == 200) {
                    offer.modified?.let { m -> runCatching { target.setLastModified(java.time.Instant.parse(m).toEpochMilli()) } }
                    MediaScannerConnection.scanFile(app, arrayOf(target.path), null, null)
                }
            } finally {
                if (part.exists()) part.delete()
                PartJournal.done(app, loc)
            }
        } catch (_: Exception) {
        } finally {
            s.sockets -= r.socket
            synchronized(lock) {
                if (status == 200) s.ok++ else s.failed++
                if (!s.over) {
                    if (s.ok + s.failed == s.offers.size) end(s, Errand.Received(s.from, s.ok, s.failed)) else show(s, force = true)
                }
            }
        }
        return Response(status)
    }

    private inline fun pump(input: InputStream, s: Session, write: (ByteArray, Int) -> Unit): Long {
        val buffer = ByteArray(256 * 1024)
        var count = 0L
        while (true) {
            if (s.over) throw IOException("stopped")
            val n = input.read(buffer)
            if (n < 0) return count
            write(buffer, n)
            count += n
            s.bytes.addAndGet(n.toLong())
            s.lastHeard = System.currentTimeMillis()
            show(s, force = false)
        }
    }

    /** Words and a percentage, no more than every second and a half: each change is a repaint on this panel. */
    private fun show(s: Session, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - s.lastShown < 1500) return
        s.lastShown = now
        val percent = if (s.total > 0) (s.bytes.get() * 100 / s.total).toInt() else 100
        _state.update { st ->
            if (receiving !== s) st else st.copy(errand = Errand.Receiving(s.from, s.ok + s.failed, s.offers.size, percent.coerceIn(0, 100)))
        }
    }

    /**
     * Where a file lands: Downloads, in the folders its name carries. Nothing in a name can step
     * outside Downloads; a name already taken gets a number, as a copy would.
     */
    private fun place(s: Session, offer: Offer): File {
        val parts = offer.name.split('/', '\\')
            .map { seg -> seg.filter { it >= ' ' }.trim().take(200) }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .ifEmpty { listOf("file") }
        val dir = parts.dropLast(1).fold(downloads()) { d, seg -> File(d, seg) }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot make ${dir.name}")
        synchronized(s) {
            val name = Names.unique(parts.last()) { File(dir, it).exists() || File(dir, it).path in s.reserved }
            val file = File(dir, name)
            s.reserved += file.path
            return file
        }
    }

    private fun cancelled(r: Request): Response {
        synchronized(lock) {
            val s = receiving
            if (s != null && r.query["sessionId"] == s.id && r.remote == s.ip) end(s, Errand.Failed(Why.STOPPED_THERE, s.from))
        }
        return Response(200)
    }

    /** The receive over, one way or another. Files still coming are cut off, and their parts deleted. */
    private fun end(s: Session, errand: Errand?) {
        if (s.over) return
        s.over = true
        if (receiving === s) receiving = null
        s.sockets.forEach { runCatching { it.close() } }
        letGo()
        _state.update { it.copy(errand = errand) }
    }

    private fun downloads(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    // ---------------------------------------------------------------- sending

    private var sending: Job? = null
    @Volatile private var current: Call? = null
    @Volatile private var sendingTo: Pair<Device, String>? = null
    private var waitingForPin: Pair<Device, List<Entry>>? = null

    fun send(fingerprint: String, entries: List<Entry>) {
        val sc = scope ?: return
        val d = devices[fingerprint] ?: return
        synchronized(lock) {
            if (asking != null || _state.value.errand?.underWay == true) return
            _state.update { it.copy(errand = Errand.Asking(d.alias)) }
        }
        sending = sc.launch { send(d, entries, pin = null) }
    }

    fun pin(pin: String) {
        val sc = scope ?: return
        val (d, entries) = waitingForPin ?: return
        waitingForPin = null
        _state.update { it.copy(errand = Errand.Asking(d.alias)) }
        sending = sc.launch { send(d, entries, pin) }
    }

    private suspend fun send(d: Device, entries: List<Entry>, pin: String?) {
        val m = me ?: return
        val items = try {
            expand(entries)
        } catch (e: Exception) {
            finish(Errand.Failed(Why.NOT_READABLE, d.alias))
            return
        }
        val offers = items.map { (entry, name) ->
            Offer(
                id = UUID.randomUUID().toString(),
                name = name,
                size = entry.size,
                type = Names.mime(entry.name).takeIf { it != "*/*" } ?: "application/octet-stream",
                modified = entry.modified.takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).toString() },
            )
        }
        val client = clientFor(d)
        hold()
        try {
            val files = JSONObject().apply { offers.forEach { put(it.id, it.json()) } }
            val body = JSONObject().put("info", m.json()).put("files", files).toString().toRequestBody(json)
            val url = d.url("prepare-upload") + (pin?.let { "?pin=" + enc(it) } ?: "")
            // The other side answers when its owner presses Accept, however long that takes.
            val call = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
                .newCall(okhttp3.Request.Builder().url(url).post(body).build())
            current = call
            val (code, text) = call.execute().use { it.code to (it.body?.string().orEmpty()) }
            coroutineContext.ensureActive()
            when (code) {
                200 -> Unit
                204 -> return finish(Errand.Sent(d.alias, offers.size))
                401 -> {
                    waitingForPin = d to entries
                    return finish(Errand.NeedsPin(d.alias, wrong = pin != null))
                }
                403 -> return finish(Errand.Declined(d.alias))
                409, 429 -> return finish(Errand.Failed(Why.BUSY, d.alias))
                else -> return finish(Errand.Failed(Why.OTHER, d.alias))
            }
            val answer = JSONObject(text)
            val session = answer.getString("sessionId")
            val tokens = answer.getJSONObject("files")
            sendingTo = d to session
            val accepted = offers.indices.filter { tokens.has(offers[it].id) }
            val total = accepted.sumOf { offers[it].size }.coerceAtLeast(1)
            var sent = 0L
            var lastShown = 0L
            accepted.forEachIndexed { done, i ->
                coroutineContext.ensureActive()
                val offer = offers[i]
                val entry = items[i].first
                val upload = object : RequestBody() {
                    override fun contentType() = octets
                    override fun contentLength() = offer.size
                    override fun writeTo(sink: BufferedSink) {
                        Stores.get(entry.loc.store).openRead(entry.loc.path).use { input ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                sink.write(buffer, 0, n)
                                sent += n
                                val now = System.currentTimeMillis()
                                if (now - lastShown > 1500) {
                                    lastShown = now
                                    _state.update { it.copy(errand = Errand.Sending(d.alias, done, accepted.size, (sent * 100 / total).toInt().coerceIn(0, 100))) }
                                }
                            }
                        }
                    }
                }
                val target = d.url("upload") + "?sessionId=${enc(session)}&fileId=${enc(offer.id)}&token=${enc(tokens.getString(offer.id))}"
                val c = client.newCall(okhttp3.Request.Builder().url(target).post(upload).build())
                current = c
                val status = c.execute().use { it.code }
                if (status != 200) {
                    return finish(Errand.Failed(if (status == 403 || status == 409) Why.STOPPED_THERE else Why.OTHER, d.alias))
                }
                _state.update { it.copy(errand = Errand.Sending(d.alias, done + 1, accepted.size, (sent * 100 / total).toInt().coerceIn(0, 100))) }
            }
            finish(Errand.Sent(d.alias, accepted.size))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: com.wanderwildwood.tana.store.StoreException) {
            finish(Errand.Failed(Why.NOT_READABLE, d.alias))
        } catch (e: Exception) {
            // A stop pressed here cancels the call, which arrives as an IOException; the errand
            // was already put away by then.
            if (coroutineContext.isActive) {
                android.util.Log.w(TAG, "send to ${d.address}:${d.port} failed", e)
                finish(Errand.Failed(Why.UNREACHABLE, d.alias))
            }
        } finally {
            current = null
            sendingTo = null
            letGo()
        }
    }

    private fun finish(errand: Errand) = _state.update { it.copy(errand = errand) }

    /** Files as they are; folders as every file in them, named by their path from the folder chosen. */
    private fun expand(entries: List<Entry>): List<Pair<Entry, String>> {
        val out = mutableListOf<Pair<Entry, String>>()
        fun walk(e: Entry, name: String) {
            if (e.isFolder) Stores.get(e.loc.store).list(e.loc.path).forEach { walk(it, "$name/${it.name}") }
            else out += e to name
        }
        entries.forEach { walk(it, it.name) }
        return out
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------------------------------------------------------------- away from Wi-Fi

    private var direct: Direct? = null
    @Volatile private var offered: ByteArray? = null
    @Volatile private var waitingToJoin: Invite? = null

    /** What a tap hands over: null unless this phone has a network up and the page open. */
    fun invite(): ByteArray? = offered

    private fun direct(): Direct = direct ?: Direct(app).also { direct = it }

    private fun wifiOn(): Boolean = app.getSystemService(WifiManager::class.java)?.isWifiEnabled == true

    /**
     * Android 12 refuses to start or join a network while location is off, permission or no: its
     * check notes a location access, and that note fails with location off. Once the network is
     * up, location can go off again and the network stays.
     */
    private fun locationOn(): Boolean = app.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true

    /** What stands in the way of starting or joining, or null when nothing does. */
    private fun blocked(): AwayWhy? = when {
        !wifiOn() -> AwayWhy.WIFI_OFF
        !locationOn() -> AwayWhy.LOCATION_OFF
        else -> null
    }

    /** A Wi-Fi Direct network of this phone's own. The location permission is already granted. */
    fun startNetwork() {
        val sc = scope ?: return
        val m = me ?: return
        if (_state.value.away.let { it is Away.Starting || it is Away.Hosting || it is Away.Joining }) return
        blocked()?.let { why -> return _state.update { it.copy(away = Away.Failed(why)) } }
        _state.update { it.copy(away = Away.Starting) }
        sc.launch {
            val name = Direct.networkName(m.alias)
            val passphrase = Direct.passphrase()
            try {
                val frequency = direct().host(name, passphrase)
                offered = Invite(name, passphrase, frequency, m.alias, m.port, m.fingerprint).bytes()
                _state.update { it.copy(away = Away.Hosting(name, passphrase)) }
            } catch (e: Direct.Failed) {
                _state.update { it.copy(away = Away.Failed(why(e.reason))) }
            }
        }
    }

    fun stopNetwork() {
        offered = null
        val d = direct
        _state.update { it.copy(away = null) }
        scope?.launch { d?.leave() }
    }

    /** Another phone's invite, read by a tap. Called on NFC's own thread. */
    internal fun tapped(invite: Invite) {
        if (scope == null || _state.value.away is Away.Hosting || _state.value.away is Away.Starting) return
        if (invite.fingerprint.equals(me?.fingerprint, ignoreCase = true)) return
        if (app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            waitingToJoin = invite
            _state.update { it.copy(wantsPermission = true) }
            return
        }
        join(invite)
    }

    fun permissionAnswered(granted: Boolean) {
        _state.update { it.copy(wantsPermission = false) }
        val invite = waitingToJoin ?: return
        waitingToJoin = null
        if (granted) join(invite)
    }

    private fun join(invite: Invite) {
        val sc = scope ?: return
        blocked()?.let { why -> return _state.update { it.copy(away = Away.Failed(why)) } }
        _state.update { it.copy(away = Away.Joining(invite.alias)) }
        sc.launch {
            try {
                val host = direct().join(invite)
                val d = Device(invite.alias, null, "mobile", invite.fingerprint, invite.port, https = true, address = host.hostAddress ?: return@launch)
                put(d)
                _state.update { it.copy(away = Away.Joined(invite.alias)) }
                // So the other phone lists this one too, without waiting on an announcement.
                register(d)
            } catch (e: Direct.Failed) {
                _state.update { it.copy(away = Away.Failed(if (e.reason == WifiP2pManager.BUSY) AwayWhy.NO_ANSWER else why(e.reason))) }
            }
        }
    }

    private fun why(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> AwayWhy.UNSUPPORTED
        WifiP2pManager.BUSY -> AwayWhy.BUSY
        else -> AwayWhy.OTHER
    }

    // ---------------------------------------------------------------- either way

    /**
     * Whatever is under way, stopped from this end. A send tells the other side, so it is not
     * left holding half a file and waiting.
     */
    fun stopErrand() {
        synchronized(lock) {
            receiving?.let { end(it, null) }
        }
        sending?.cancel()
        current?.cancel()
        sendingTo?.let { (d, session) ->
            scope?.launch {
                runCatching {
                    val request = okhttp3.Request.Builder().url(d.url("cancel") + "?sessionId=" + enc(session)).post(ByteArray(0).toRequestBody(null)).build()
                    clientFor(d).newCall(request).execute().close()
                }
            }
        }
        waitingForPin = null
        _state.update { it.copy(errand = null) }
    }

    fun errandSeen() {
        _state.update { if (it.errand?.underWay == true) it else it.copy(errand = null) }
    }

    // A transfer keeps the phone awake and its Wi-Fi up, and lets go the moment it ends.
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    private fun hold() {
        if (wake == null) {
            wake = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tana:nearby").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = app.getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tana:nearby").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    @Synchronized
    private fun letGo() {
        runCatching { wake?.release() }
        wake = null
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    private const val TAG = "tana.nearby"

    /** Room left over after a receive, so that filling Downloads does not fill the phone. */
    private const val ROOM = 64L shl 20
}
