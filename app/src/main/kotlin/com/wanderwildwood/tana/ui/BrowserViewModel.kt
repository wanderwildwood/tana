package com.wanderwildwood.tana.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wanderwildwood.tana.R
import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.store.LocalStore
import com.wanderwildwood.tana.store.Loc
import com.wanderwildwood.tana.work.Asked
import com.wanderwildwood.tana.store.Server
import com.wanderwildwood.tana.store.SafRoot
import com.wanderwildwood.tana.store.DavStore
import com.wanderwildwood.tana.store.SmbStore
import com.wanderwildwood.tana.store.ZipStore
import com.wanderwildwood.tana.store.StoreException
import com.wanderwildwood.tana.store.Stores
import com.wanderwildwood.tana.work.Clash
import com.wanderwildwood.tana.work.Job
import com.wanderwildwood.tana.work.Kind
import com.wanderwildwood.tana.work.Mode
import com.wanderwildwood.tana.work.Names
import com.wanderwildwood.tana.work.Opener
import com.wanderwildwood.tana.work.Pdfs
import com.wanderwildwood.tana.work.Texts
import com.wanderwildwood.tana.work.Ask
import com.wanderwildwood.tana.work.Picking
import com.wanderwildwood.tana.work.Pin
import com.wanderwildwood.tana.work.Prefs
import com.wanderwildwood.tana.work.Previews
import com.wanderwildwood.tana.work.Purpose
import com.wanderwildwood.tana.work.Recent
import com.wanderwildwood.tana.work.Search
import com.wanderwildwood.tana.work.Sheets
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    data object NothingPrints : Notice
    data object FoldersNotShared : Notice
    data object FileGone : Notice
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

/** A picture opened before it is chosen for another app. [file] and [image] arrive together once it has been read. */
data class Preview(
    val entry: Entry,
    val file: java.io.File? = null,
    val image: android.graphics.Bitmap? = null,
    val failure: Throwable? = null,
)

/**
 * A spreadsheet open to be looked through. [sheets] arrive once it has been read; [found] is
 * which rows of the sheet on screen hold every word looked for, or null when nothing is.
 */
data class SheetView(
    val entry: Entry,
    val sheets: List<com.wanderwildwood.tana.work.Sheet>? = null,
    val failure: Throwable? = null,
    val at: Int = 0,
    val words: String = "",
    val found: List<Int>? = null,
    val finding: Boolean = false,
) {
    val sheet: com.wanderwildwood.tana.work.Sheet? get() = sheets?.getOrNull(at)
}

/** A PDF open to be read. [file] is there from the start for one on the phone, and once it is fetched for one on a server. */
data class PdfView(val entry: Entry, val file: java.io.File? = null)

/** A text file open to be read; [file] as for [PdfView]. */
data class TextView(val entry: Entry, val file: java.io.File? = null)

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
    val others: List<SafRoot> = emptyList(),
    val pins: List<Pin> = emptyList(),
    val notice: Notice? = null,
    val info: Info? = null,
    val search: SearchState? = null,
    val preview: Preview? = null,
    val sheet: SheetView? = null,
    val pdf: PdfView? = null,
    val text: TextView? = null,
    /** A file another app asked to have shown: the list pages to it once, then this is cleared. */
    val revealed: Loc? = null,
    /** The Nearby page, open: what is to be sent from it, or nothing when it was opened to receive. */
    val nearby: List<Entry>? = null,
    /** How a swipe down on the folder's first page is going; see [BrowserViewModel.pull]. */
    val pulled: Pulled? = null,
) {
    val selecting: Boolean get() = selection.isNotEmpty()
    val folder: Loc? get() = (place as? Place.Folder)?.loc
    /** Inside a zip: things can be copied out, nothing can be changed. */
    val readOnly: Boolean get() = folder?.let { Stores.isReadOnly(it.store) } == true
    /** What is chosen: among the search's results while a search is open, else in the folder. */
    fun selected(): List<Entry> = (search?.results ?: entries).filter { it.loc in selection }
}

/** A folder read again from a swipe down, said in a line above it rather than by blanking it. */
sealed interface Pulled {
    data object Asking : Pulled
    data object Done : Pulled
    data class Failed(val problem: Throwable) : Pulled
}

/** A folder or Recent can be read again once it has been read; Home lists nothing fetched. */
internal fun canPull(place: Place, reading: Reading): Boolean = place != Place.Home && reading == Reading.DONE

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val prefs = Prefs(application)

    private val _state = MutableStateFlow(
        UiState(
            sort = prefs.sort,
            showHidden = prefs.showHidden,
            servers = prefs.servers,
            others = prefs.others,
            pins = prefs.pins,
        ),
    )
    val state: StateFlow<UiState> = _state

    /** Set when another app opened this to choose a file; see [Picking]. */
    var picking: Picking? = null

    private val _picked = kotlinx.coroutines.flow.MutableSharedFlow<List<java.io.File>>(extraBufferCapacity = 1)
    /** The files chosen for the app that asked, as files on the phone. */
    val picked: kotlinx.coroutines.flow.SharedFlow<List<java.io.File>> = _picked

    /** A file to print. Android's print screen opens over a window, so the window does it. */
    private val _printing = kotlinx.coroutines.flow.MutableSharedFlow<java.io.File>(extraBufferCapacity = 1)
    val printing: kotlinx.coroutines.flow.SharedFlow<java.io.File> = _printing

    fun cannotPrint() = notice(Notice.NothingPrints)

    /** The name typed for a file being saved for another app; a file tapped in the list is put here. */
    var saveField by androidx.compose.runtime.mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    val saveName: String get() = saveField.text

    /**
     * What a folder shows: while choosing for another app, only what it will take. Choosing a
     * folder shows folders alone; saving shows every file, so the names taken are in sight.
     */
    fun shown(entries: List<Entry>): List<Entry> {
        val p = picking ?: return entries
        return when (p.ask) {
            Ask.FILE -> entries.filter { it.isFolder || p.accepts(it.name) }
            Ask.TREE -> entries.filter { it.isFolder }
            Ask.SAVE -> entries
        }
    }

    /** Only a file is chosen from a list by pressing and holding; a folder or a save is not. */
    private val choosingFiles: Boolean get() = (picking?.ask ?: Ask.FILE) == Ask.FILE

    /** Whether the folder on screen, and the name typed, already name something there. */
    fun saveTarget(name: String): java.io.File? =
        _state.value.folder?.takeIf { it.store == LocalStore.ID }?.let { Stores.phone.file(it.path) }?.resolve(name.trim())

    /** The folder on screen, handed back to the app that asked for one. */
    fun chooseFolder() {
        val loc = _state.value.folder?.takeIf { it.store == LocalStore.ID } ?: return
        _picked.tryEmit(listOf(Stores.phone.file(loc.path)))
    }

    /**
     * The file named [name] made in the folder on screen and handed back. An empty new file
     * is made here; the asking app then writes into it. One already there is handed back as
     * it is, which the screen has asked about first.
     */
    fun saveHere(name: String) {
        val target = saveTarget(name) ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || '/' in trimmed || trimmed == "." || trimmed == ".." || target.isDirectory) return
        try {
            if (!target.exists() && !target.createNewFile()) throw java.io.IOException(trimmed)
        } catch (e: java.io.IOException) {
            notice(Notice.Problem(StoreException(StoreException.Reason.CANNOT_WRITE, trimmed, e)))
            return
        }
        _picked.tryEmit(listOf(target))
    }

    /** The chosen files, handed back; one only, if the asking app takes one. */
    fun pickSelected() {
        val p = picking ?: return
        val files = _state.value.selected().filter { !it.isFolder }
        if (files.isEmpty()) return
        hand(if (p.several) files else files.take(1), Purpose.PICK)
        clearSelection()
    }

    /** Where Back goes. Home is always at the bottom and is never pushed. */
    private val history = ArrayDeque<Place>()
    private var listing: CoroutineJob? = null
    private var searching: CoroutineJob? = null
    @Volatile private var searchCancelled = false

    init {
        Stores.setServers(_state.value.servers)
        Stores.setOthers(_state.value.others, application.contentResolver)
        Opener.tidyFetched(application)
        refreshVolumes()
        viewModelScope.launch {
            Transfers.work.collect { work -> onWork(work) }
        }
    }

    // ---------------------------------------------------------------- where you are

    /** A file to select once its folder has been read, and the folder it was asked for in. */
    private var revealing: Asked.Reveal? = null
    private var cameToShow: Place? = null

    /**
     * Another app asked for [what] to be shown: its folder opens (nothing stacked behind it, so
     * Back leaves for the app that asked), the file is found, selected and paged to, or, if it
     * is gone, the folder is left as it is and the line above says so.
     */
    fun reveal(what: Asked.Reveal) {
        history.clear()
        revealing = what
        val place = Place.Folder(what.folder)
        cameToShow = place
        _state.update {
            it.copy(
                search = null, info = null, preview = null, sheet = null, pdf = null, text = null, nearby = null,
                notice = null, revealed = null,
            )
        }
        if (_state.value.place != place) show(place) else { _state.update { it.copy(selection = emptySet()) }; refresh() }
    }

    fun revealShown() = _state.update { it.copy(revealed = null) }

    private fun foundReveal(sorted: List<Entry>) {
        val what = revealing ?: return
        revealing = null
        val visible = shown(sorted)
        val at = Asked.indexOfName(visible.map { it.name }, what.name)
        if (at < 0) {
            notice(Notice.FileGone)
        } else {
            val loc = visible[at].loc
            _state.update { it.copy(selection = setOf(loc), revealed = loc) }
        }
    }

    fun go(place: Place) {
        cameToShow = null
        revealing = null
        val current = _state.value.place
        if (place == current) return
        val up = (place as? Place.Folder)?.loc
        if (up != null && current is Place.Folder && current.loc.isWithin(up)) {
            // Going up is going back, not going on: drop the folders below it from the
            // history rather than stacking one more Back to press later. Back from there then
            // goes wherever you were before you came down into this tree.
            while ((history.lastOrNull() as? Place.Folder)?.loc?.isWithin(up) == true) {
                history.removeLast()
            }
        } else {
            history.addLast(current)
        }
        show(place)
    }

    /**
     * Straight to a folder another app asked to see. Back from it goes to the start page:
     * whatever the reader had open before belongs to a different errand.
     */
    fun openAt(loc: Loc) {
        searchCancelled = true
        searching?.cancel()
        history.clear()
        _state.update { it.copy(search = null, info = null, selection = emptySet()) }
        if (_state.value.place != Place.Folder(loc)) show(Place.Folder(loc)) else refresh()
    }

    /** False when there is nowhere further back, and Back should leave the app. */
    fun back(): Boolean {
        val s = _state.value
        when {
            s.nearby != null -> closeNearby()
            s.sheet != null -> closeSheet()
            s.pdf != null -> closePdf()
            s.text != null -> closeText()
            s.preview != null -> closePreview()
            s.info != null -> _state.update { it.copy(info = null) }
            s.search != null && s.selecting -> clearSelection()
            s.search != null -> closeSearch()
            s.selecting -> clearSelection()
            s.place == Place.Home -> return false
            // Shown a file by another app: Back goes back to that app, not down a path.
            cameToShow == s.place -> { cameToShow = null; return false }
            else -> show(history.removeLastOrNull() ?: Place.Home)
        }
        return true
    }

    private fun show(place: Place) {
        _state.update { it.copy(place = place, selection = emptySet(), entries = emptyList(), failure = null, pulled = null) }
        refresh()
    }

    /**
     * A swipe down at the top of a folder: the same listing as [refresh], but the rows stay on
     * screen while it is read, and a server that does not answer leaves them there and says why
     * in the line above, rather than swapping the folder for an error.
     */
    fun pull() {
        val start = _state.value
        if (!canPull(start.place, start.reading) || start.pulled == Pulled.Asking) return
        val place = start.place
        listing?.cancel()
        _state.update { it.copy(pulled = Pulled.Asking) }
        listing = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { read(place, _state.value.showHidden) } }
            if (!isActive || _state.value.place != place) return@launch
            result.fold(
                onSuccess = { list -> show(place, list); _state.update { it.copy(pulled = Pulled.Done) } },
                onFailure = { e -> _state.update { it.copy(pulled = Pulled.Failed(e)) } },
            )
        }
    }

    /** The line above the folder has been said for long enough. */
    fun clearPulled() = _state.update { if (it.pulled == Pulled.Asking) it else it.copy(pulled = null) }

    private fun read(place: Place, hidden: Boolean): List<Entry> = when (place) {
        Place.Recent -> Recent.list(app, hidden)
        is Place.Folder -> Stores.get(place.loc.store).list(place.loc.path)
            .filter { hidden || !it.name.startsWith('.') }
        Place.Home -> emptyList()
    }

    private fun show(place: Place, list: List<Entry>) {
        val sorted = if (place == Place.Recent) list else _state.value.sort.apply(list)
        _state.update { s ->
            s.copy(
                entries = sorted,
                reading = Reading.DONE,
                selection = s.selection.intersect(sorted.map { it.loc }.toSet()),
            )
        }
        if (revealing != null && place == Place.Folder(revealing!!.folder)) foundReveal(sorted)
    }

    fun refresh() {
        listing?.cancel()
        val place = _state.value.place
        if (place == Place.Home) {
            refreshVolumes()
            _state.update { it.copy(reading = Reading.DONE) }
            return
        }
        _state.update { it.copy(reading = Reading.NOT_YET, failure = null, pulled = null) }
        listing = viewModelScope.launch {
            val hidden = _state.value.showHidden
            val result = withContext(Dispatchers.IO) { runCatching { read(place, hidden) } }
            if (!isActive || _state.value.place != place) return@launch
            result.fold(
                onSuccess = { list -> show(place, list) },
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
        if (loc.store.startsWith(ZipStore.PREFIX)) return Loc(loc.store, "") to loc.store.substringAfterLast('/')
        if (loc.store != LocalStore.ID) {
            val server = s.servers.firstOrNull { it.storeId == loc.store }
            val other = s.others.firstOrNull { it.storeId == loc.store }
            return Loc(loc.store, "") to (server?.name ?: other?.label ?: loc.store)
        }
        val volume = s.volumes.filter { loc.isWithin(it.loc) }.maxByOrNull { it.loc.path.length }
        return if (volume != null) volume.loc to volume.label else Loc(LocalStore.ID, "") to "/"
    }

    /** What to call the folder something is in: its name, or the volume's or server's at a root. */
    fun folderLabel(loc: Loc): String? {
        val parent = loc.parent ?: return null
        val (root, label) = rootOf(parent)
        return if (parent.path == root.path) label else parent.name
    }

    // ---------------------------------------------------------------- the rows

    fun press(entry: Entry) {
        val s = _state.value
        // Choosing a picture for another app, it is looked at first, chosen or not.
        if (picking != null && choosingFiles && !entry.isFolder && Previews.isImage(entry.name)) {
            preview(entry)
            return
        }
        if (s.selecting) {
            toggle(entry)
            return
        }
        val p = picking
        if (entry.isFolder) {
            go(Place.Folder(entry.loc))
        } else if (p != null && p.ask == Ask.SAVE) {
            // Saving: a file in the list is a name already taken, put in the box to be replaced.
            saveField = androidx.compose.ui.text.input.TextFieldValue(entry.name)
        } else if (p != null && p.ask == Ask.TREE) {
            Unit
        } else if (p != null) {
            // Choosing for another app, a zip is a file like any other. Several allowed:
            // tapping chooses, and Attach hands them all back.
            if (p.several) toggle(entry) else hand(listOf(entry), Purpose.PICK)
        } else if (Names.extension(entry.name) == "zip") {
            browse(entry)
        } else if (Pdfs.canRead(entry.name)) {
            read(entry)
        } else if (Texts.canRead(entry.name)) {
            readText(entry)
        } else if (Sheets.canView(entry.name)) {
            view(entry)
        } else {
            open(entry, if (Names.extension(entry.name) == "apk") Purpose.INSTALL else Purpose.OPEN)
        }
    }

    // ---------------------------------------------------------------- a picture, looked at first

    private var previewing: CoroutineJob? = null

    fun preview(entry: Entry) {
        previewing?.cancel()
        _state.update { it.copy(preview = Preview(entry)) }
        previewing = viewModelScope.launch {
            val job = coroutineContext
            val result = withContext(Dispatchers.IO) {
                runCatching { Previews.load(app, entry) { !job.isActive } }
            }
            if (!isActive || _state.value.preview?.entry != entry) return@launch
            _state.update { s ->
                s.copy(
                    preview = result.fold(
                        onSuccess = { (file, image) -> Preview(entry, file, image) },
                        onFailure = { Preview(entry, failure = it) },
                    ),
                )
            }
        }
    }

    fun closePreview() {
        previewing?.cancel()
        _state.update { it.copy(preview = null) }
    }

    /**
     * The picture on screen, chosen: handed straight back when the asking app takes one;
     * added to (or taken out of) the chosen ones when it takes several.
     */
    fun choosePreviewed() {
        val p = picking ?: return
        val shown = _state.value.preview ?: return
        closePreview()
        when {
            p.several -> toggle(shown.entry)
            shown.file != null -> _picked.tryEmit(listOf(shown.file))
            else -> hand(listOf(shown.entry), Purpose.PICK)
        }
    }

    fun toggle(entry: Entry) {
        if (!choosingFiles) return
        _state.update { s ->
            val sel = if (entry.loc in s.selection) s.selection - entry.loc else s.selection + entry.loc
            s.copy(selection = sel)
        }
    }

    fun selectAll() = _state.update { s -> s.copy(selection = (s.search?.results ?: s.entries).map { it.loc }.toSet()) }

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
            if (purpose == Purpose.PICK) _picked.tryEmit(files)
            else if (purpose == Purpose.PRINT) files.firstOrNull()?.let { _printing.tryEmit(it) }
            else if (!Opener.hand(app, files, purpose)) notice(Notice.NothingOpens)
        } else {
            start(Job.Fetch(entries, purpose))
        }
    }

    // ---------------------------------------------------------------- nearby

    /** Opened to receive, from the start page, or with what is chosen, to send it. */
    fun openNearby(sending: List<Entry> = emptyList()) =
        _state.update { it.copy(nearby = sending, selection = emptySet()) }

    fun closeNearby() = _state.update { it.copy(nearby = null) }

    /**
     * What other devices see this phone called: the reader's choice, else the name they gave the
     * phone in Settings, else the maker's name with three letters of this phone's own after it.
     */
    fun nearbyName(): String = prefs.nearbyName ?: com.wanderwildwood.tana.nearby.Alias.default(
        android.provider.Settings.Global.getString(app.contentResolver, android.provider.Settings.Global.DEVICE_NAME),
        android.os.Build.MODEL,
        android.os.Build.DEVICE,
        android.os.Build.PRODUCT,
        prefs.nearbyTag ?: com.wanderwildwood.tana.nearby.Alias.newTag().also { prefs.nearbyTag = it },
    )

    fun setNearbyName(name: String) {
        prefs.nearbyName = name
    }

    // ---------------------------------------------------------------- copy, move, paste, delete

    fun pickUp(mode: Mode) {
        val picked = _state.value.selected()
        if (picked.isEmpty()) return
        if (_state.value.search != null) closeSearch()
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
        if (start(Job.Delete(picked))) {
            // Results are not looked for again, so what is going goes from them now.
            val gone = picked.map { it.loc }.toSet()
            _state.update { s -> s.copy(search = s.search?.let { it.copy(results = it.results.filterNot { e -> e.loc in gone }) }) }
            clearSelection()
        }
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
                if (job is Job.Fetch && job.purpose == Purpose.VIEW) {
                    work.fetched.firstOrNull()?.let { file -> _state.value.sheet?.let { readSheet(it.entry, file) } }
                    work.fetched.firstOrNull()?.let { file ->
                        _state.update { s -> s.pdf?.takeIf { it.file == null }?.let { s.copy(pdf = it.copy(file = file)) } ?: s }
                        _state.update { s -> s.text?.takeIf { it.file == null }?.let { s.copy(text = it.copy(file = file)) } ?: s }
                    }
                    Transfers.seen()
                } else if (job is Job.Fetch && job.purpose == Purpose.BROWSE) {
                    work.fetched.firstOrNull()?.let { go(Place.Folder(Loc(ZipStore.idFor(it), ""))) }
                    Transfers.seen()
                } else if (job is Job.Fetch && job.purpose == Purpose.PICK) {
                    // Only the picking screen answers this; the app's own window leaves it be.
                    if (picking != null) {
                        _picked.tryEmit(work.fetched)
                        Transfers.seen()
                    }
                } else if (job is Job.Fetch && job.purpose == Purpose.PRINT) {
                    work.fetched.firstOrNull()?.let { _printing.tryEmit(it) }
                    Transfers.seen()
                } else if (job is Job.Fetch) {
                    if (!Opener.hand(app, work.fetched, job.purpose)) notice(Notice.NothingOpens)
                    Transfers.seen()
                } else {
                    refresh()
                }
            }
            is Work.Failed, is Work.Stopped -> {
                // A spreadsheet still waiting on its fetch will never get it now; the page
                // closes, and the trouble is said where every failed fetch says it.
                _state.value.sheet?.takeIf { it.sheets == null && it.failure == null && it.entry.loc.store != LocalStore.ID }
                    ?.let { closeSheet() }
                _state.value.pdf?.takeIf { it.file == null }?.let { closePdf() }
                _state.value.text?.takeIf { it.file == null }?.let { closeText() }
                refresh()
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- spreadsheets

    private var sheeting: CoroutineJob? = null
    private var finding: CoroutineJob? = null
    /** The sheet last looked in, each row's cells folded for Find and run together: made once, not per search. */
    private var folded: Pair<com.wanderwildwood.tana.work.Sheet, List<String>>? = null

    /** A spreadsheet opened here, to be looked through. One not on the phone is fetched first. */
    fun view(entry: Entry) {
        _state.update { it.copy(sheet = SheetView(entry), selection = emptySet()) }
        if (entry.loc.store == LocalStore.ID) {
            readSheet(entry, Stores.phone.file(entry.loc.path))
        } else if (!start(Job.Fetch(listOf(entry), Purpose.VIEW))) {
            _state.update { it.copy(sheet = null) }
        }
    }

    private fun readSheet(entry: Entry, file: java.io.File) {
        sheeting?.cancel()
        sheeting = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Sheets.read(file) } }
            if (!isActive || _state.value.sheet?.entry != entry) return@launch
            _state.update { s ->
                s.copy(sheet = s.sheet?.let { v -> result.fold({ v.copy(sheets = it) }, { v.copy(failure = it) }) })
            }
        }
    }

    fun showSheet(index: Int) {
        _state.update { s -> s.sheet?.let { s.copy(sheet = it.copy(at = index, found = null)) } ?: s }
        _state.value.sheet?.let { find(it.words) }
    }

    /**
     * The rows holding every one of [words], in any cell, any case, with or without accents
     * (creme finds crème, which a phone's keyboard makes slow to type). Worked out a moment
     * after the typing stops, off the main thread: a big sheet is a lot of cells to look in.
     */
    fun find(words: String) {
        finding?.cancel()
        val wanted = Sheets.fold(words).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (wanted.isEmpty()) {
            _state.update { s -> s.sheet?.let { s.copy(sheet = it.copy(words = words, found = null, finding = false)) } ?: s }
            return
        }
        _state.update { s -> s.sheet?.let { s.copy(sheet = it.copy(words = words, finding = true)) } ?: s }
        finding = viewModelScope.launch {
            delay(300)
            val view = _state.value.sheet ?: return@launch
            val sheet = view.sheet ?: return@launch
            val found = withContext(Dispatchers.Default) {
                // The cells of a row run together with a character no word can hold, so a word
                // is found inside one cell and never across two.
                val rows = folded?.takeIf { it.first === sheet }?.second
                    ?: sheet.body.map { row -> row.joinToString("\u0000", transform = Sheets::fold) }
                        .also { folded = sheet to it }
                ensureActive()
                rows.indices.filter { i ->
                    if (i % 2000 == 0) ensureActive()
                    wanted.all { rows[i].contains(it) }
                }
            }
            if (!isActive) return@launch
            _state.update { s ->
                s.sheet?.takeIf { it.at == view.at && it.words == words }?.let { s.copy(sheet = it.copy(found = found, finding = false)) } ?: s
            }
        }
    }

    fun closeSheet() {
        sheeting?.cancel()
        finding?.cancel()
        folded = null
        _state.update { it.copy(sheet = null) }
    }

    // ---------------------------------------------------------------- PDFs

    /** A PDF opened here, to be read. One not on the phone is fetched first, as a spreadsheet is. */
    fun read(entry: Entry) {
        if (entry.loc.store == LocalStore.ID) {
            _state.update { it.copy(pdf = PdfView(entry, Stores.phone.file(entry.loc.path)), selection = emptySet()) }
        } else {
            _state.update { it.copy(pdf = PdfView(entry), selection = emptySet()) }
            if (!start(Job.Fetch(listOf(entry), Purpose.VIEW))) _state.update { it.copy(pdf = null) }
        }
    }

    fun closePdf() = _state.update { it.copy(pdf = null) }

    /** The PDF on screen could not be read: put away, and handed to another app to try. */
    fun handPdfOn() {
        val shown = _state.value.pdf ?: return
        closePdf()
        open(shown.entry, Purpose.OPEN_WITH)
    }

    // ---------------------------------------------------------------- text

    /** A text file opened here, to be read. One not on the phone is fetched first, as a PDF is. */
    fun readText(entry: Entry) {
        if (entry.loc.store == LocalStore.ID) {
            _state.update { it.copy(text = TextView(entry, Stores.phone.file(entry.loc.path)), selection = emptySet()) }
        } else {
            _state.update { it.copy(text = TextView(entry), selection = emptySet()) }
            if (!start(Job.Fetch(listOf(entry), Purpose.VIEW))) _state.update { it.copy(text = null) }
        }
    }

    fun closeText() = _state.update { it.copy(text = null) }

    /** The text on screen is not text, or too large: put away, and handed to another app to try. */
    fun handTextOn() {
        val shown = _state.value.text ?: return
        closeText()
        open(shown.entry, Purpose.OPEN_WITH)
    }

    // ---------------------------------------------------------------- zips

    /** Into a zip as if it were a folder. One not on the phone is fetched first. */
    fun browse(entry: Entry) {
        if (entry.loc.store == LocalStore.ID) {
            go(Place.Folder(Loc(ZipStore.idFor(Stores.phone.file(entry.loc.path)), "")))
        } else {
            start(Job.Fetch(listOf(entry), Purpose.BROWSE))
        }
    }

    /** The chosen things into one zip, here. One thing is named after itself; several, "Archive". */
    fun compress() {
        val s = _state.value
        val folder = s.folder ?: return
        if (s.readOnly) return
        val picked = s.selected().ifEmpty { return }
        val one = picked.singleOrNull()
        val base = when {
            one == null -> app.getString(R.string.archive_name)
            one.isFolder -> one.name
            else -> one.name.substringBeforeLast('.', one.name)
        }
        if (start(Job.Compress(picked, folder, "$base.zip"))) clearSelection()
    }

    /** A zip's contents into a new folder beside it, named after it. */
    fun extractHere(entry: Entry) {
        val s = _state.value
        val folder = s.folder ?: return
        if (s.readOnly) return
        if (start(Job.Extract(entry, folder))) clearSelection()
    }

    // ---------------------------------------------------------------- folders from other apps

    /**
     * A folder the reader picked in Android's own picker. The grant is made to last, then the
     * folder is kept on the start page under its own name, with the app it belongs to beneath.
     */
    fun addOther(uri: android.net.Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return
        val label = runCatching {
            app.contentResolver.query(
                android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, docId),
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: docId
        val note = runCatching {
            val info = app.packageManager.resolveContentProvider(uri.authority ?: "", 0)
            info?.applicationInfo?.loadLabel(app.packageManager)?.toString()
        }.getOrNull() ?: uri.authority.orEmpty()
        val others = _state.value.others.filterNot { it.uri == uri.toString() } +
            SafRoot(UUID.randomUUID().toString(), label, note, uri.toString())
        prefs.others = others
        Stores.setOthers(others, app.contentResolver)
        _state.update { it.copy(others = others) }
    }

    fun removeOther(root: SafRoot) {
        runCatching {
            app.contentResolver.releasePersistableUriPermission(
                android.net.Uri.parse(root.uri),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val others = _state.value.others.filterNot { it.id == root.id }
        prefs.others = others
        Stores.setOthers(others, app.contentResolver)
        save(_state.value.pins.filterNot { it.loc.store == root.storeId })
        _state.update { it.copy(others = others) }
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
        var server = if (draft.id.isEmpty()) draft.copy(id = UUID.randomUUID().toString()) else draft
        // A server that never answers must not leave the form "checking" for ever: after
        // twenty seconds that is its answer. The attempt itself runs on and ends by its own
        // network timeouts; nothing waits for it.
        val attempt = viewModelScope.async(Dispatchers.IO) {
            if (server.isDav) {
                // A Nextcloud given by its address alone is kept as the folder its files are in.
                return@async try {
                    server = server.copy(host = DavStore.resolve(server, java.io.File(getApplication<Application>().cacheDir, "spool")))
                    null
                } catch (e: Exception) {
                    e
                }
            }
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
        val answered = withTimeoutOrNull(CHECK_SECONDS * 1000L) { attempt.await() to true } ?: (null to false)
        val problem: Throwable? = if (answered.second) answered.first
        else StoreException(StoreException.Reason.SERVER_UNREACHABLE, draft.host.trim().substringAfter("://").substringBefore('/'))
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
            is Place.Folder -> listOf(place.loc) to app.getString(
                R.string.search_within,
                if (place.loc.path == rootOf(place.loc).first.path) rootOf(place.loc).second else place.loc.name,
            )
            else -> s.volumes.map { it.loc } to app.getString(R.string.search_everywhere)
        }
        _state.update { it.copy(search = SearchState(roots, within), selection = emptySet()) }
    }

    fun closeSearch() {
        searchCancelled = true
        searching?.cancel()
        _state.update { it.copy(search = null, selection = emptySet()) }
    }

    fun searchFor(words: String, kind: Kind) {
        val search = _state.value.search ?: return
        searchCancelled = true
        searching?.cancel()
        _state.update { it.copy(search = search.copy(words = words, kind = kind, results = emptyList(), running = true, ran = true), selection = emptySet()) }
        searchCancelled = false
        val hidden = _state.value.showHidden
        searching = viewModelScope.launch(Dispatchers.IO) {
            Search(Stores::get, isCancelled = { searchCancelled }).run(search.roots, words, kind, hidden) { found ->
                _state.update { s -> s.search?.let { s.copy(search = it.copy(results = Sort().apply(it.results + found))) } ?: s }
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

/** How long adding a server waits to hear from it before saying it cannot be reached. */
private const val CHECK_SECONDS = 20

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
        StoreException.Reason.READ_ONLY -> R.string.problem_read_only
        StoreException.Reason.NOT_WEBDAV -> R.string.problem_not_webdav
        StoreException.Reason.UNTRUSTED -> R.string.problem_untrusted
    }
    return app.getString(id, e.subject)
}

/** "3.2 MB". Android's own formatter, which follows the phone's language. */
fun size(app: android.content.Context, bytes: Long): String = android.text.format.Formatter.formatShortFileSize(app, bytes)

