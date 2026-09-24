package com.wanderwildwood.tana.store

import java.io.InputStream
import java.io.OutputStream

/**
 * Where something is: which store holds it, and the path inside that store.
 *
 * Paths are '/'-separated with no slash at either end, and "" is the store's root. The phone's
 * own storage is one store whose paths are absolute paths with the leading slash taken off, so
 * the SD card and internal storage are the same store and a move between them is only a
 * rename that happens to fail. A server share is a store of its own.
 */
data class Loc(val store: String, val path: String) {
    val name: String get() = path.substringAfterLast('/')

    val parent: Loc? get() = if (path.isEmpty()) null else Loc(store, path.substringBeforeLast('/', ""))

    fun child(name: String) = Loc(store, if (path.isEmpty()) name else "$path/$name")

    /** True for this place itself and anything beneath it. */
    fun isWithin(other: Loc): Boolean =
        store == other.store &&
            (other.path.isEmpty() || path == other.path || path.startsWith(other.path + "/"))
}

/** One thing in a folder. [size] is meaningless for a folder; [modified] is 0 when not known. */
data class Entry(
    val loc: Loc,
    val isFolder: Boolean,
    val size: Long,
    val modified: Long,
) {
    val name: String get() = loc.name
}

/**
 * The handful of things every place files can live has to do. The phone and a server share
 * both answer them, so copying, moving, searching and deleting are written once, over this,
 * and do not know or care which of the two they are working on.
 *
 * Every call may block, and a server's may block for as long as the network does. None of
 * them is to be called on the main thread.
 */
interface Store {
    val id: String

    fun list(path: String): List<Entry>

    /** What is at [path], or null when nothing is. */
    fun stat(path: String): Entry?

    fun openRead(path: String): InputStream

    /** Creates the file, or empties one that is already there. */
    fun openWrite(path: String): OutputStream

    fun makeFolder(path: String)

    /** Within this store only. Throws rather than replace anything already at [to]. */
    fun rename(from: String, to: String)

    fun deleteFile(path: String)

    /** Only an empty folder; emptying it is the caller's work. */
    fun deleteFolder(path: String)

    /** Keeps a copy's date the same as its original's, where the store can. */
    fun setModified(path: String, time: Long) {}
}

/**
 * Something a store could not do. It carries a reason rather than a sentence, so the words
 * the reader sees come out of strings.xml like every other word in the app; [subject] is the
 * name the sentence is about, where there is one.
 */
class StoreException(
    val reason: Reason,
    val subject: String = "",
    cause: Throwable? = null,
) : Exception("$reason $subject", cause) {
    enum class Reason {
        FOLDER_UNREADABLE,
        GONE,
        ALREADY_THERE,
        CANNOT_MAKE_FOLDER,
        CANNOT_RENAME,
        CANNOT_DELETE,
        CANNOT_WRITE,
        SERVER_UNREACHABLE,
        LOGIN_REFUSED,
        NO_SUCH_SHARE,
        NOT_ALLOWED,
        SERVER_ERROR,
        INTO_ITSELF,
    }
}
