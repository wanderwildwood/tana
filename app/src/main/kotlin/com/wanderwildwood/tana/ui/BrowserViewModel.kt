package com.wanderwildwood.tana.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wanderwildwood.tana.R
import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.store.LocalStore
import com.wanderwildwood.tana.store.Loc
import com.wanderwildwood.tana.store.Server
import com.wanderwildwood.tana.store.SmbStore
import com.wanderwildwood.tana.store.StoreException
import com.wanderwildwood.tana.store.Stores
import com.wanderwildwood.tana.work.Clash
import com.wanderwildwood.tana.work.Job
import com.wanderwildwood.tana.work.Kind
import com.wanderwildwood.tana.work.Mode
import com.wanderwildwood.tana.work.Names
import com.wanderwildwood.tana.work.Opener
import com.wanderwildwood.tana.work.Pin
import com.wanderwildwood.tana.work.Prefs
import com.wanderwildwood.tana.work.Purpose
import com.wanderwildwood.tana.work.Recent
import com.wanderwildwood.tana.work.Search
import com.wanderwildwood.tana.work.Sort
import com.wanderwildwood.tana.work.SortBy
import com.wanderwildwood.tana.work.Transfer
import com.wanderwildwood.tana.work.Transfers
import com.wanderwildwood.tana.work.Volume
import com.wanderwildwood.tana.work.Volumes
import com.wanderwildwood.tana.work.Work
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

sealed interface Place {
    data object Home : Place
    data object Recent : Place
    data class Folder(val loc: Loc) : Place
}

enum class Reading { NOT_YET, DONE, FAILED }

/** Things picked up with Copy or Move, waiting for Paste. */
data class Carry(val entries: List<Entry>, val mode: Mode)

/** A paste that found names already taken, waiting on the reader's answer. */
data class Clashing(val carry: Carry, val dest: Loc, val names: List<String>)

/** A one-line thing to tell the reader, in place of a list's worth of text. */
sealed interface Notice {
    data object Busy : Notice
    data object NothingOpens : Notice
    data object FoldersNotShared : Notice
    data class Problem(val problem: Throwable) : Notice
}

/** What the info dialog knows so far about one thing. Filled in as it is worked out. */
data class Info(
    val entry: Entry,
    val where: String,
    val files: Int? = null,
    val bytes: Long? = null,
    val sha256: String? = null,
    val hashing: Boolean = false,
)

data class SearchState(
    val roots: List<Loc>,
    val within: String,
    val words: String = "",
    val kind: Kind = Kind.ANY,
    val results: List<Entry> = emptyList(),
    val running: Boolean = false,
    val ran: Boolean = false,
)

data class UiState(
    val place: Place = Place.Home,
    val entries: List<Entry> = emptyList(),
    val reading: Reading = Reading.DONE,
    val failure: Throwable? = null,
    val selection: Set<Loc> = emptySet(),
    val carry: Carry? = null,
    val clashing: Clashing? = null,
    val sort: Sort = Sort(),
    val showHidden: Boolean = false,
    val volumes: List<Volume> = emptyList(),
    val servers: List<Server> = emptyList(),
    val pins: List<Pin> = emptyList(),
    val notice: Notice? = null,
    val info: Info? = null,
    val search: SearchState? = null,
) {
    val selecting: Boolean get() = selection.isNotEmpty()
    val folder: Loc? get() = (place as? Place.Folder)?.loc
    fun selected(): List<Entry> = entries.filter { it.loc in selection }
}

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val prefs = Prefs(application)

    private val _state = MutableStateFlow(
        UiState(
            sort = prefs.sort,
            showHidden = prefs.showHidden,
            servers = prefs.servers,
            pins = prefs.pins,
        ),
    )
    val state: StateFlow<UiState> = _state

    /** Where Back goes. Home is always at the bottom and is never pushed. */
    private val history = ArrayDeque<Place>()
    private var listing: CoroutineJob? = null
    private var searching: CoroutineJob? = null
    @Volatile private var searchCancelled = false

    init {
        Stores.setServers(prefs.servers)
        Opener.tidyFetched(application)
        refreshVolumes()
        viewModelScope.launch {
            Transfers.work.collect { work -> onWork(work) }
        }
    }

    // ---------------------------------------------------------------- where you are

    fun go(place: Place) {
        val current = _state.value.place
        if (place == current) return
        history.addLast(current)
        show(place)
    }

    /** False when there is nowhere further back, and Back should leave the app. */
    fun back(): Boolean {
        val s = _state.value
        when {
            s.info != null -> _state.update { it.copy(info = null) }
            s.search != null -> closeSearch()
            s.selecting -> clearSelection()
            s.place == Place.Home -> return false
            else -> show(history.removeLastOrNull() ?: Place.Home)
        }
        return true
    }

    private fun show(place: Place) {
        _state.update { it.copy(place = place, selection = emptySet(), entries = emptyList(), failure = null) }
        refresh()
    }

    fun refresh() {
        listing?.cancel()
        val place = _state.value.place
        if (place == Place.Home) {
            refreshVolumes()
            _state.update { it.copy(reading = Reading.DONE) }
            return
        }
        _state.update { it.copy(reading = Reading.NOT_YET, failure = null) }
        listing = viewModelScope.launch {
            val hidden = _state.value.showHidden
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (place) {
                        Place.Recent -> Recent.list(app, hidden)
                        is Place.Folder -> Stores.get(place.loc.store).list(place.loc.path)
                            .filter { hidden || !it.name.startsWith('.') }
                        Place.Home -> emptyList()
                    }
                }
            }
            if (!isActive || _state.value.place != place) return@launch
            result.fold(
                onSuccess = { list ->
                    val sorted = if (place == Place.Recent) list else _state.value.sort.apply(list)
                    _state.update { s ->
                        s.copy(
                            entries = sorted,
                            reading = Reading.DONE,
                            selection = s.selection.intersect(sorted.map { it.loc }.toSet()),
                        )
                    }
                },
                onFailure = { e -> _state.update { it.copy(reading = Reading.FAILED, failure = e, entries = emptyList()) } },
            )
        }
    }

    fun refreshVolumes() {
        _state.update {
            it.copy(volumes = Volumes.list(app, app.getString(R.string.volume_phone), app.getString(R.string.volume_card)))
        }
    }

    /** The folder a place hangs from, and what to call it: a volume on the phone, or a server. */
    fun rootOf(loc: Loc): Pair<Loc, String> {
        val s = _state.value
        if (loc.store != LocalStore.ID) {
            val server = s.servers.firstOrNull { it.storeId == loc.store }
            return Loc(loc.store, "") to (server?.name ?: loc.store)
        }
        val volume = s.volumes.filter { loc.isWithin(it.loc) }.maxByOrNull { it.loc.path.length }
        return if (volume != null) volume.loc to volume.label else Loc(LocalStore.ID, "") to "/"
    }

    // ---------------------------------------------------------------- the rows

    fun press(entry: Entry) {
        val s = _state.value
        if (s.selecting) {
            toggle(entry)
            return
        }
        if (entry.isFolder) {
            go(Place.Folder(entry.loc))
        } else {
            open(entry, if (Names.extension(entry.name) == "apk") Purpose.INSTALL else Purpose.OPEN)
        }
    }

    fun toggle(entry: Entry) {
        _state.update { s ->
            val sel = if (entry.loc in s.selection) s.selection - entry.loc else s.selection + entry.loc
            s.copy(selection = sel)
        }
    }

    fun selectAll() = _state.update { s -> s.copy(selection = s.entries.map { it.loc }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun open(entry: Entry, purpose: Purpose) = hand(listOf(entry), purpose)

    fun share() {
        val files = _state.value.selected().filter { !it.isFolder }
        if (files.isEmpty()) {
            notice(Notice.FoldersNotShared)
            return
        }
        hand(files, Purpose.SHARE)
        clearSelection()
    }

    private fun hand(entries: List<Entry>, purpose: Purpose) {
        if (entries.all { it.loc.store == LocalStore.ID }) {
            val files = entries.map { Stores.phone.file(it.loc.path) }
            if (!Opener.hand(app, files, purpose)) notice(Notice.NothingOpens)
        } else {
            start(Job.Fetch(entries, purpose))
        }
    }

    // ---------------------------------------------------------------- copy, move, paste, delete

    fun pickUp(mode: Mode) {
        val picked = _state.value.selected()
        if (picked.isEmpty()) return
        _state.update { it.copy(carry = Carry(picked, mode), selection = emptySet()) }
    }

    fun putDown() = _state.update { it.copy(carry = null) }

    fun paste() {
        val s = _state.value
        val carry = s.carry ?: return
        val dest = s.folder ?: return
        viewModelScope.launch {
            val names = withContext(Dispatchers.IO) {
                runCatching { Transfer(Stores::get).clashes(carry.entries, dest) }
            }.getOrElse {
                notice(Notice.Problem(it))
                return@launch
            }
            if (names.isEmpty()) {
                paste(carry, dest, Clash.KEEP_BOTH)
            } else {
                _state.update { it.copy(clashing = Clashing(carry, dest, names)) }
            }
        }
    }

    fun answerClash(clash: Clash?) {
        val c = _state.value.clashing ?: return
        _state.update { it.copy(clashing = null) }
        if (clash != null) paste(c.carry, c.dest, clash)
    }

    private fun paste(carry: Carry, dest: Loc, clash: Clash) {
        if (start(Job.Paste(carry.entries, dest, carry.mode, clash))) {
            _state.update { it.copy(carry = null) }
        }
    }

    fun delete() {
        val picked = _state.value.selected()
        if (picked.isEmpty()) return
        if (start(Job.Delete(picked))) clearSelection()
    }

    private fun start(job: Job): Boolean {
        val started = Transfers.start(app, job)
        if (!started) notice(Notice.Busy)
        return started
    }

    private fun onWork(work: Work) {
        when (work) {
            is Work.Finished -> {
                val job = work.job
                if (job is Job.Fetch) {
                    if (!Opener.hand(app, work.fetched, job.purpose)) notice(Notice.NothingOpens)
                    Transfers.seen()
                } else {
                    refresh()
                }
            }
            is Work.Failed, is Work.Stopped -> refresh()
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- naming

    fun rename(entry: Entry, typed: String) {
        val name = Names.valid(typed) ?: return
        if (name == entry.name) return
        val target = entry.loc.parent?.child(name) ?: return
        io { Stores.get(entry.loc.store).rename(entry.loc.path, target.path) }
    }

    fun newFolder(typed: String) {
        val name = Names.valid(typed) ?: return
        val folder = _state.value.folder ?: return
        io { Stores.get(folder.store).makeFolder(folder.child(name).path) }
    }

    /** A quick operation on the IO thread, then the folder read again whether it worked or not. */
    private fun io(block: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching(block) }.onFailure { notice(Notice.Problem(it)) }
            clearSelection()
            refresh()
        }
    }

    // ---------------------------------------------------------------- sorting and hidden files

    fun sortBy(by: SortBy) {
        val sort = _state.value.sort.pressed(by)
        prefs.sort = sort
        _state.update { s -> s.copy(sort = sort, entries = if (s.place == Place.Recent) s.entries else sort.apply(s.entries)) }
    }

    fun toggleHidden() {
        val hidden = !_state.value.showHidden
        prefs.showHidden = hidden
        _state.update { it.copy(showHidden = hidden) }
        refresh()
    }

    // ---------------------------------------------------------------- the start page

    fun pin(entry: Entry) {
        val pins = _state.value.pins
        if (pins.any { it.loc == entry.loc }) return
        save(pins + Pin(entry.loc, entry.name.ifEmpty { rootOf(entry.loc).second }))
        clearSelection()
    }

    fun unpin(pin: Pin) = save(_state.value.pins - pin)

    private fun save(pins: List<Pin>) {
        prefs.pins = pins
        _state.update { it.copy(pins = pins) }
    }

    /**
     * Checks a server can be reached and the share opened before keeping it, so a typing
     * mistake is found now rather than the next time the reader is somewhere else. Null
     * means it worked; otherwise the problem, for the dialog to show.
     */
    suspend fun saveServer(draft: Server): Throwable? {
        val server = if (draft.id.isEmpty()) draft.copy(id = UUID.randomUUID().toString()) else draft
        val problem = withContext(Dispatchers.IO) {
            val probe = SmbStore(server)
            try {
                probe.list("")
                null
            } catch (e: Exception) {
                e
            } finally {
                probe.close()
            }
        }
        if (problem != null) return problem
        val servers = _state.value.servers.filterNot { it.id == server.id } + server
        prefs.servers = servers
        Stores.setServers(servers)
        _state.update { it.copy(servers = servers) }
        return null
    }

    fun removeServer(server: Server) {
        val servers = _state.value.servers.filterNot { it.id == server.id }
        prefs.servers = servers
        Stores.setServers(servers)
        save(_state.value.pins.filterNot { it.loc.store == server.storeId })
        _state.update { it.copy(servers = servers) }
    }

    // ---------------------------------------------------------------- info

    fun info(entry: Entry) {
        val (root, label) = rootOf(entry.loc)
        val below = entry.loc.parent?.path?.removePrefix(root.path)?.trim('/').orEmpty()
        val where = if (below.isEmpty()) label else "$label / ${below.replace("/", " / ")}"
        _state.update { it.copy(info = Info(entry, where), selection = emptySet()) }
        if (entry.isFolder) {
            viewModelScope.launch {
                val sums = withContext(Dispatchers.IO) { runCatching { measure(entry) }.getOrNull() }
                _state.update { s ->
                    if (s.info?.entry != entry) s else s.copy(info = s.info!!.copy(files = sums?.first, bytes = sums?.second))
                }
            }
        }
    }

    private fun measure(entry: Entry): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        fun walk(e: Entry) {
            if (_state.value.info?.entry != entry) return
            if (e.isFolder) Stores.get(e.loc.store).list(e.loc.path).forEach { walk(it) } else {
                files++
                bytes += e.size
            }
        }
        walk(entry)
        return files to bytes
    }

    /** SHA-256, on request only: reading a large file end to end is not free. */
    fun hash() {
        val info = _state.value.info ?: return
        if (info.entry.isFolder || info.hashing) return
        _state.update { it.copy(info = info.copy(hashing = true)) }
        viewModelScope.launch {
            val hex = withContext(Dispatchers.IO) {
                runCatching {
                    val digest = MessageDigest.getInstance("SHA-256")
                    Stores.get(info.entry.loc.store).openRead(info.entry.loc.path).use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
            }
            _state.update { s ->
                if (s.info?.entry != info.entry) s else s.copy(info = s.info!!.copy(sha256 = hex.getOrNull(), hashing = false))
            }
            hex.exceptionOrNull()?.let { notice(Notice.Problem(it)) }
        }
    }

    fun closeInfo() = _state.update { it.copy(info = null) }

    // ---------------------------------------------------------------- search

    fun openSearch() {
        val s = _state.value
        val (roots, within) = when (val place = s.place) {
            is Place.Folder -> listOf(place.loc) to (if (place.loc.path == rootOf(place.loc).first.path) rootOf(place.loc).second else place.loc.name)
            else -> s.volumes.map { it.loc } to app.getString(R.string.search_everywhere)
        }
        _state.update { it.copy(search = SearchState(roots, within), selection = emptySet()) }
    }

    fun closeSearch() {
        searchCancelled = true
        searching?.cancel()
        _state.update { it.copy(search = null) }
    }

    fun searchFor(words: String, kind: Kind) {
        val search = _state.value.search ?: return
        searchCancelled = true
        searching?.cancel()
        _state.update { it.copy(search = search.copy(words = words, kind = kind, results = emptyList(), running = true, ran = true)) }
        searchCancelled = false
        val hidden = _state.value.showHidden
        searching = viewModelScope.launch(Dispatchers.IO) {
            Search(Stores::get, isCancelled = { searchCancelled }).run(search.roots, words, kind, hidden) { found ->
                _state.update { s -> s.search?.let { s.copy(search = it.copy(results = it.results + found)) } ?: s }
            }
            _state.update { s -> s.search?.let { s.copy(search = it.copy(running = false)) } ?: s }
        }
    }

    /** A result pressed: a folder is gone into, a file opened, and the search put away. */
    fun pressResult(entry: Entry) {
        closeSearch()
        if (entry.isFolder) go(Place.Folder(entry.loc)) else {
            entry.loc.parent?.let { go(Place.Folder(it)) }
            press(entry)
        }
    }

    // ---------------------------------------------------------------- notices

    private fun notice(n: Notice) = _state.update { it.copy(notice = n) }

    fun noticeSeen() = _state.update { it.copy(notice = null) }
}

/** The words for a problem, from strings.xml. */
fun describe(app: android.content.Context, problem: Throwable): String {
    val e = problem as? StoreException ?: (problem.cause as? StoreException)
    if (e == null) return app.getString(R.string.problem_other, problem.message ?: problem.javaClass.simpleName)
    val id = when (e.reason) {
        StoreException.Reason.FOLDER_UNREADABLE -> R.string.problem_folder_unreadable
        StoreException.Reason.GONE -> R.string.problem_gone
        StoreException.Reason.ALREADY_THERE -> R.string.problem_already_there
        StoreException.Reason.CANNOT_MAKE_FOLDER -> R.string.problem_cannot_make_folder
        StoreException.Reason.CANNOT_RENAME -> R.string.problem_cannot_rename
        StoreException.Reason.CANNOT_DELETE -> R.string.problem_cannot_delete
        StoreException.Reason.CANNOT_WRITE -> R.string.problem_cannot_write
        StoreException.Reason.SERVER_UNREACHABLE -> R.string.problem_server_unreachable
        StoreException.Reason.LOGIN_REFUSED -> R.string.problem_login_refused
        StoreException.Reason.NO_SUCH_SHARE -> R.string.problem_no_such_share
        StoreException.Reason.NOT_ALLOWED -> R.string.problem_not_allowed
        StoreException.Reason.SERVER_ERROR -> R.string.problem_server_error
        StoreException.Reason.INTO_ITSELF -> R.string.problem_into_itself
    }
    return app.getString(id, e.subject)
}

/** "3.2 MB". Android's own formatter, which follows the phone's language. */
fun size(app: android.content.Context, bytes: Long): String = android.text.format.Formatter.formatShortFileSize(app, bytes)

