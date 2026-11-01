package com.wanderwildwood.tana.work

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/*
 * The trash: where something deleted from the phone's storage waits before it is gone.
 *
 * A hidden folder at the top of each volume the file was on, `.Cabinet-trash`, so putting a
 * file there is a rename inside one filesystem: instant, atomic, and never a copy of a large file
 * (internal storage and the SD card are two filesystems, and a trash in the app's own folder
 * would turn every delete from the card into a slow copy). Each deleted thing has a folder named
 * for the moment it was deleted, holding it under its own name, and beside that a small sidecar
 * file `<slot>.meta` saying where it came from and when:
 *
 *     /storage/emulated/0/.Cabinet-trash/1761832800000/report.pdf
 *     /storage/emulated/0/.Cabinet-trash/1761832800000.meta     from: /storage/emulated/0/Documents/report.pdf
 *                                                               at: 1761832800000
 *
 * The rules (names, expiry, the sidecar's text) are plain functions, so they can be tested
 * without a phone. Nothing here ever follows a link: a link is moved, listed and removed as the
 * link it is.
 */

const val TRASH_FOLDER = ".Cabinet-trash"

/** How long a deleted thing is kept. The one place this number is written. */
const val TRASH_DAYS = 7

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val META_SUFFIX = ".meta"

/** Whether a path inside the phone's storage lies in a trash folder, by its names alone. */
fun pathIsInTrash(path: String): Boolean = path.split('/').contains(TRASH_FOLDER)

/** Anything deleted before this moment is older than [TRASH_DAYS] days. */
fun trashCutoff(now: Long): Long = now - TRASH_DAYS * DAY_MS

fun trashExpired(deletedAt: Long, now: Long): Boolean = deletedAt < trashCutoff(now)

/** When what was deleted at [deletedAt] goes for good. */
fun trashedUntil(deletedAt: Long): Long = deletedAt + TRASH_DAYS * DAY_MS

/** A name for a new folder in the trash: the moment, moved on by one while that is taken. */
fun trashSlot(at: Long, taken: Set<String>): String {
    var n = at
    while (n.toString() in taken) n++
    return n.toString()
}

/** Where a deleted thing came from (an absolute path) and when it was deleted. */
data class TrashMeta(val from: String, val at: Long)

/** A line holds one value, so a newline in a name is written as `\n` and a backslash doubled. */
private fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

private fun unescape(s: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                else -> out.append(s[i + 1])
            }
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

fun trashMetaText(meta: TrashMeta): String = "from: ${escape(meta.from)}\nat: ${meta.at}\n"

/**
 * The sidecar's meaning, or null when it says nothing usable. Whatever the file says, the path
 * must be absolute and hold no `..`, no empty step and no NUL: a sidecar edited to point
 * somewhere else is not believed.
 */
fun parseTrashMeta(text: String?): TrashMeta? {
    if (text == null) return null
    var from: String? = null
    var at: Long? = null
    for (line in text.split('\n')) {
        when {
            line.startsWith("from: ") -> from = unescape(line.removePrefix("from: "))
            line.startsWith("at: ") -> at = line.removePrefix("at: ").trim().toLongOrNull()
        }
    }
    if (from == null || at == null || !safeOrigin(from)) return null
    return TrashMeta(from, at)
}

/** Whether [path] is a plain absolute path with nothing in it that climbs or hides a step. */
fun safeOrigin(path: String): Boolean {
    if (path.length < 2 || path[0] != '/' || path.contains('\u0000')) return false
    return path.substring(1).split('/').none { it.isEmpty() || it == ".." || it == "." }
}

/**
 * Where a restored thing goes if the name [name] is taken: "report (restored).pdf", then
 * "report (restored 2).pdf" and on. [taken] says whether a name is in use. The name asked for
 * is used as it is when it is free.
 */
fun restoredName(name: String, taken: (String) -> Boolean): String {
    if (!taken(name)) return name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 1
    while (true) {
        val candidate = if (n == 1) "$stem (restored)$ext" else "$stem (restored $n)$ext"
        if (!taken(candidate)) return candidate
        n++
    }
}

/** One thing in the trash. [slot] is its folder's name, its id, and [root] the volume it is in. */
data class Trashed(val root: File, val slot: String, val name: String, val from: String, val at: Long, val isFolder: Boolean) {
    val folder: String get() = from.substringBeforeLast('/', "")
}

/** The trash could not do what was asked; [name] is what it was about. */
class TrashException(val name: String, cause: Throwable? = null) : IOException("trash: $name", cause)

/**
 * The trash across [roots], the tops of shared storage (internal and any card). [now] is the
 * clock, so the tests can set it.
 */
class Trash(roots: List<File>, private val now: () -> Long = System::currentTimeMillis) {
    private val roots: List<File> = roots.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }.distinct()

    private fun trashOf(root: File) = File(root, TRASH_FOLDER)

    /** The root [file] lies under, judged by where its folder really is, not by what its path says. */
    fun volumeOf(file: File): File? {
        val parent = file.absoluteFile.parentFile ?: return null
        val real = runCatching { parent.canonicalFile }.getOrNull() ?: return null
        return roots.filter { real.path == it.path || real.path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }
    }

    /** True for the trash folder and anything in it: deleting there is deleting for good. */
    fun isInTrash(file: File): Boolean {
        val volume = volumeOf(file) ?: return false
        val real = runCatching { file.absoluteFile.parentFile!!.canonicalFile }.getOrNull() ?: return false
        val at = File(real, file.name).path
        val top = trashOf(volume).path
        return at == top || at.startsWith("$top/")
    }

    /**
     * Moves [file] (a file, a folder with all that is in it, or a link as itself) into the trash
     * of its volume. Throws [TrashException] if it cannot, and then it is where it was: the
     * caller does not fall back to deleting it.
     */
    fun put(file: File): Trashed {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw TrashException(file.name)
        val volume = volumeOf(file) ?: throw TrashException(file.name)
        val real = file.absoluteFile.parentFile!!.canonicalFile
        val item = File(real, file.name)
        // Not the top of a volume, and not the trash itself.
        if (file.name.isEmpty() || item.path == volume.path || isInTrash(file)) throw TrashException(file.name)
        val bin = trashOf(volume)
        if (!bin.isDirectory && !bin.mkdirs() && !bin.isDirectory) throw TrashException(file.name)
        runCatching { File(bin, ".nomedia").createNewFile() }
        val at = now()
        val taken = bin.list()?.toSet() ?: emptySet()
        val slot = trashSlot(at, taken + taken.map { it.removeSuffix(META_SUFFIX) })
        val slotDir = File(bin, slot)
        val meta = File(bin, slot + META_SUFFIX)
        val inside = File(slotDir, file.name)
        val isFolder = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        try {
            if (!slotDir.mkdir()) throw TrashException(file.name)
            meta.writeText(trashMetaText(TrashMeta(item.path, at)))
            Files.move(path, inside.toPath())
        } catch (e: Exception) {
            // Put nothing half-made behind: the folder is empty and the sidecar is ours.
            meta.delete()
            slotDir.delete()
            throw if (e is TrashException) e else TrashException(file.name, e)
        }
        return Trashed(volume, slot, file.name, item.path, at, isFolder)
    }

    /** Everything in the trash, newest first. A folder with no usable sidecar still shows. */
    fun list(): List<Trashed> {
        val out = mutableListOf<Trashed>()
        for (root in roots) {
            val bin = trashOf(root)
            val names = bin.list() ?: continue
            for (slot in names) {
                val dir = File(bin, slot)
                if (slot.endsWith(META_SUFFIX) || !dir.isDirectory) continue
                val meta = parseTrashMeta(runCatching { File(bin, slot + META_SUFFIX).readText() }.getOrNull())
                val inside = dir.list()?.sorted() ?: emptyList()
                val name = meta?.from?.substringAfterLast('/')?.takeIf { it in inside } ?: inside.firstOrNull() ?: continue
                val isFolder = Files.isDirectory(File(dir, name).toPath(), LinkOption.NOFOLLOW_LINKS)
                // Without a sidecar it is said to come from the top of its volume, deleted when the
                // slot was made (the slot is the moment), so nothing in the trash is out of reach.
                out += Trashed(
                    root, slot, name,
                    from = meta?.from ?: File(root, name).path,
                    at = meta?.at ?: slot.toLongOrNull() ?: dir.lastModified(),
                    isFolder = isFolder,
                )
            }
        }
        return out.sortedByDescending { it.at }
    }

    /**
     * Puts [item] back where it came from, making the folders on the way if they are gone, and
     * under another name ("name (restored)") if the name is in use: nothing is written over.
     * Returns where it went. Throws [TrashException] if it cannot, and then it is still in the trash.
     */
    fun restore(item: Trashed): File {
        val stored = storedFile(item) ?: throw TrashException(item.name)
        if (!Files.exists(stored.toPath(), LinkOption.NOFOLLOW_LINKS)) throw TrashException(item.name)
        val from = item.from
        if (!safeOrigin(from)) throw TrashException(item.name)
        val wanted = File(from)
        val folder = wanted.parentFile ?: throw TrashException(item.name)
        // The deepest folder that exists must really be inside this volume (a link may not lead out).
        var existing: File = folder
        while (!existing.exists()) existing = existing.parentFile ?: throw TrashException(item.name)
        val real = existing.canonicalFile
        if (!(real.path == item.root.path || real.path.startsWith(item.root.path + "/"))) throw TrashException(item.name)
        if (real.path == trashOf(item.root).path || real.path.startsWith(trashOf(item.root).path + "/")) throw TrashException(item.name)
        if (!folder.isDirectory && !folder.mkdirs() && !folder.isDirectory) throw TrashException(item.name)
        val folderReal = folder.canonicalFile
        if (!(folderReal.path == item.root.path || folderReal.path.startsWith(item.root.path + "/"))) throw TrashException(item.name)
        val taken = { n: String -> Files.exists(File(folderReal, n).toPath(), LinkOption.NOFOLLOW_LINKS) }
        var name = restoredName(wanted.name, taken)
        var target = File(folderReal, name)
        while (true) {
            try {
                Files.move(stored.toPath(), target.toPath())
                break
            } catch (e: FileAlreadyExistsException) {
                // Someone made it between the look and the move; another name, never over it.
                name = restoredName(name, taken)
                target = File(folderReal, name)
            } catch (e: Exception) {
                throw TrashException(item.name, e)
            }
        }
        forget(item)
        return target
    }

    /** Removes [item] for good. */
    fun deleteForever(item: Trashed) {
        val bin = trashOf(item.root)
        val dir = File(bin, item.slot)
        // The slot must be a plain name inside this trash, whatever was asked.
        if (item.slot.isEmpty() || item.slot.contains('/') || item.slot == ".." || item.slot == ".") throw TrashException(item.name)
        if (Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) removeTree(dir.toPath())
        File(bin, item.slot + META_SUFFIX).delete()
    }

    fun empty(): Int = list().onEach { deleteForever(it) }.size

    /** Removes what has been here longer than [TRASH_DAYS] days, and sidecars with nothing beside them. */
    fun purgeExpired(): Int {
        val t = now()
        var n = 0
        for (item in list()) {
            if (trashExpired(item.at, t)) {
                runCatching { deleteForever(item) }.onSuccess { n++ }
            }
        }
        // A sidecar whose folder never arrived (a delete cut off); a minute old, so a delete
        // happening this moment is not mistaken for one.
        for (root in roots) {
            val bin = trashOf(root)
            bin.list()?.filter { it.endsWith(META_SUFFIX) }?.forEach { m ->
                val f = File(bin, m)
                if (!File(bin, m.removeSuffix(META_SUFFIX)).exists() && f.lastModified() < t - 60_000) f.delete()
            }
        }
        return n
    }

    private fun storedFile(item: Trashed): File? {
        if (item.slot.isEmpty() || item.slot.contains('/') || item.name.isEmpty() || item.name.contains('/')) return null
        return File(File(trashOf(item.root), item.slot), item.name)
    }

    private fun forget(item: Trashed) {
        val bin = trashOf(item.root)
        File(bin, item.slot + META_SUFFIX).delete()
        File(bin, item.slot).delete() // only if empty
    }

    companion object {
        /** Deletes [path] and all below it without following any link out. */
        fun removeTree(path: Path) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.newDirectoryStream(path).use { children -> for (c in children) removeTree(c) }
            }
            Files.delete(path)
        }
    }
}
