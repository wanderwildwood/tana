package com.wanderwildwood.tana.work

import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.store.Loc
import com.wanderwildwood.tana.store.Store
import com.wanderwildwood.tana.store.StoreException
import java.io.File

enum class Mode { COPY, MOVE }

/** What to do when something of the same name is already where things are going. */
enum class Clash { KEEP_BOTH, REPLACE, SKIP }

data class Progress(
    val filesDone: Int,
    val filesTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val current: String,
)

data class Outcome(val done: Int, val skipped: Int)

class Cancelled : Exception()

/**
 * Copying, moving and deleting, written once over [Store] so it works the same between two
 * folders on the phone, from the phone to a server, and from one server to another.
 *
 * Three rules keep a failure from costing anything:
 *
 * - A file is written under a temporary name and only takes its real name once every byte is
 *   there and the size has been checked. A copy cut off half way leaves no file that looks
 *   finished, and the temporary one is removed.
 * - A move that cannot be a rename is a copy and then a delete, and the original is deleted
 *   only after the whole of it — every file in the folder — has arrived.
 * - It stops at the first thing that fails and says what that was. Carrying on past a failure
 *   would leave the reader to work out which of two hundred files did not make it.
 */
class Transfer(
    private val resolve: (String) -> Store,
    private val isCancelled: () -> Boolean = { false },
    private val onProgress: (Progress) -> Unit = {},
) {
    private var filesDone = 0
    private var filesTotal = 0
    private var bytesDone = 0L
    private var bytesTotal = 0L

    /** The names among [sources] that are already taken in [dest], and so need a decision. */
    fun clashes(sources: List<Entry>, dest: Loc): List<String> {
        val here = resolve(dest.store).list(dest.path).map { it.name }.toSet()
        // Pasting a copy into the folder it came from is not a clash: it can only mean
        // "another one", so it always becomes "name (1)" without asking.
        return sources.filter { it.loc.parent != dest && it.name in here }.map { it.name }
    }

    fun paste(sources: List<Entry>, dest: Loc, mode: Mode, clash: Clash): Outcome {
        for (source in sources) {
            if (source.isFolder && dest.isWithin(source.loc)) {
                throw StoreException(StoreException.Reason.INTO_ITSELF, source.name)
            }
        }

        val plans = sources.map { it to flatten(it) }
        filesTotal = plans.sumOf { (_, items) -> items.count { !it.second.isFolder } }
        bytesTotal = plans.sumOf { (_, items) -> items.sumOf { it.second.size } }

        val to = resolve(dest.store)
        var done = 0
        var skipped = 0

        for ((source, items) in plans) {
            checkCancelled()
            val from = resolve(source.loc.store)

            // Moving something into the folder it is already in moves nothing.
            if (mode == Mode.MOVE && source.loc.parent == dest) {
                count(items)
                done++
                continue
            }

            val name = when {
                mode == Mode.COPY && source.loc.parent == dest -> Names.unique(source.name) { to.stat(dest.child(it).path) != null }
                to.stat(dest.child(source.name).path) == null -> source.name
                clash == Clash.SKIP -> null
                clash == Clash.KEEP_BOTH -> Names.unique(source.name) { to.stat(dest.child(it).path) != null }
                else -> source.name
            }
            if (name == null) {
                count(items)
                skipped++
                continue
            }
            val target = dest.child(name)

            // A file replacing a file keeps the old one until the new one has fully arrived
            // (see copyFile). Anything involving a folder cannot be swapped in one step, so
            // the old one goes first.
            var replacing = false
            if (clash == Clash.REPLACE && name == source.name) {
                to.stat(target.path)?.let { existing ->
                    // Replacing the folder the source lives in would delete the source with it.
                    if (source.loc.isWithin(existing.loc)) {
                        throw StoreException(StoreException.Reason.INTO_ITSELF, source.name)
                    }
                    if (existing.isFolder || source.isFolder) {
                        deleteTree(to, existing, counting = false)
                    } else {
                        replacing = true
                    }
                }
            }

            if (mode == Mode.MOVE && source.loc.store == dest.store && !replacing) {
                val renamed = try {
                    from.rename(source.loc.path, target.path)
                    true
                } catch (e: StoreException) {
                    false
                }
                if (renamed) {
                    count(items)
                    done++
                    continue
                }
            }

            copyTree(from, to, target, items, replacing)
            if (mode == Mode.MOVE) deleteTree(from, source, counting = false)
            done++
        }
        return Outcome(done, skipped)
    }

    fun delete(entries: List<Entry>): Outcome {
        val plans = entries.map { it to flatten(it) }
        filesTotal = plans.sumOf { (_, items) -> items.size }
        for ((entry, _) in plans) {
            deleteTree(resolve(entry.loc.store), entry, counting = true)
        }
        return Outcome(entries.size, 0)
    }

    /** Brings one file from wherever it is to a real file on the phone, for another app to open. */
    fun fetch(entry: Entry, into: File) {
        filesTotal = 1
        bytesTotal = entry.size
        into.parentFile?.mkdirs()
        val from = resolve(entry.loc.store)
        val part = File(into.parentFile, into.name + PART)
        try {
            from.openRead(entry.loc.path).use { input ->
                part.outputStream().use { output -> pump(input, output, entry.name) }
            }
            into.delete()
            if (!part.renameTo(into)) throw StoreException(StoreException.Reason.CANNOT_WRITE, entry.name)
            into.setLastModified(entry.modified)
        } finally {
            part.delete()
        }
        filesDone = 1
    }

    /** The entry and, for a folder, everything in it, parents before children, with each item's path below the entry. */
    private fun flatten(entry: Entry): List<Pair<String, Entry>> {
        val out = mutableListOf<Pair<String, Entry>>()
        fun walk(e: Entry, relative: String) {
            checkCancelled()
            out += relative to e
            if (e.isFolder) {
                for (child in resolve(e.loc.store).list(e.loc.path)) {
                    walk(child, if (relative.isEmpty()) child.name else "$relative/${child.name}")
                }
            }
        }
        walk(entry, "")
        return out
    }

    private fun copyTree(from: Store, to: Store, target: Loc, items: List<Pair<String, Entry>>, replacing: Boolean) {
        for ((relative, item) in items) {
            checkCancelled()
            val dest = if (relative.isEmpty()) target else Loc(target.store, "${target.path}/$relative")
            if (item.isFolder) {
                if (to.stat(dest.path)?.isFolder != true) to.makeFolder(dest.path)
            } else {
                copyFile(from, item, to, dest, replacing)
            }
        }
    }

    private fun copyFile(from: Store, item: Entry, to: Store, dest: Loc, replacing: Boolean = false) {
        val folder = dest.parent ?: dest
        val partName = Names.unique(dest.name + PART) { to.stat(folder.child(it).path) != null }
        val part = folder.child(partName)
        var oldGone = false
        try {
            from.openRead(item.loc.path).use { input ->
                to.openWrite(part.path).use { output -> pump(input, output, item.name) }
            }
            val written = to.stat(part.path)?.size
            if (written != item.size) throw StoreException(StoreException.Reason.CANNOT_WRITE, item.name)
            if (replacing) {
                to.deleteFile(dest.path)
                oldGone = true
            }
            to.rename(part.path, dest.path)
        } catch (e: Exception) {
            // Once the old file is gone the temporary one is the only whole copy left, so it
            // stays, under its temporary name, rather than being tidied away with the rest.
            if (!oldGone) runCatching { to.deleteFile(part.path) }
            throw e
        }
        to.setModified(dest.path, item.modified)
        filesDone++
        report(item.name)
    }

    private fun pump(input: java.io.InputStream, output: java.io.OutputStream, name: String) {
        val buffer = ByteArray(BUFFER)
        var lastReport = 0L
        while (true) {
            checkCancelled()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            bytesDone += n
            lastReport += n
            if (lastReport >= REPORT_EVERY) {
                lastReport = 0
                report(name)
            }
        }
    }

    /** Children before their folder, so each folder is empty by the time it is deleted. */
    private fun deleteTree(store: Store, entry: Entry, counting: Boolean) {
        checkCancelled()
        if (entry.isFolder) {
            for (child in store.list(entry.loc.path)) deleteTree(store, child, counting)
            store.deleteFolder(entry.loc.path)
        } else {
            store.deleteFile(entry.loc.path)
        }
        if (counting) {
            filesDone++
            report(entry.name)
        }
    }

    private fun count(items: List<Pair<String, Entry>>) {
        filesDone += items.count { !it.second.isFolder }
        bytesDone += items.sumOf { it.second.size }
        report(items.firstOrNull()?.second?.name ?: "")
    }

    private fun report(current: String) =
        onProgress(Progress(filesDone, filesTotal, bytesDone, bytesTotal, current))

    private fun checkCancelled() {
        if (isCancelled()) throw Cancelled()
    }

    companion object {
        const val PART = ".part"
        private const val BUFFER = 256 * 1024
        private const val REPORT_EVERY = 1L shl 20
    }
}
