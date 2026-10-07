package com.wanderwildwood.tana.work

import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.store.Store
import com.wanderwildwood.tana.store.StoreException
import java.io.File
import java.util.UUID

/**
 * A server file as it was when it was last read: enough to tell whether it has changed since.
 * The server's etag where it gives one, and its length and date always.
 */
data class Version(val size: Long, val modified: Long, val tag: String?) {
    fun matches(entry: Entry?): Boolean {
        if (entry == null || entry.isFolder) return false
        if (tag != null && entry.tag != null) return tag == entry.tag && size == entry.size
        return size == entry.size && modified == entry.modified
    }

    companion object {
        fun of(entry: Entry) = Version(entry.size, entry.modified, entry.tag)
    }
}

/**
 * Sending a file another app saved back to the server it came from, without ever writing over
 * someone else's change.
 *
 * The new file goes up under a name of its own beside the old one, and only once it is whole
 * does it take the old one's place, in one rename: whoever reads the file meanwhile gets the
 * old one or the new one, never half. Before the upload and again just before the rename, the
 * server's file is looked at; if it is no longer the one that was read ([base]), another phone
 * or computer saved it meanwhile, and nothing is replaced.
 */
object WriteBack {

    sealed class Outcome {
        /** On the server, as [entry] now. */
        data class Saved(val entry: Entry) : Outcome()

        /** The server's file changed since it was read. Nothing on the server was touched. */
        object Changed : Outcome()
    }

    /** [base] null: there was no file at [path] when this began, and there must still be none. */
    fun put(store: Store, path: String, local: File, base: Version?): Outcome {
        if (!unchanged(store, path, base)) return Outcome.Changed
        val temp = tempName(path)
        try {
            store.openWrite(temp).use { out -> local.inputStream().use { it.copyTo(out, 64 * 1024) } }
            val sent = store.stat(temp)
            if (sent == null || sent.size != local.length()) {
                throw StoreException(StoreException.Reason.CANNOT_WRITE, path.substringAfterLast('/'))
            }
            if (!unchanged(store, path, base)) {
                runCatching { store.deleteFile(temp) }
                return Outcome.Changed
            }
            if (base == null) store.rename(temp, path) else store.replace(temp, path)
        } catch (e: StoreException) {
            runCatching { store.deleteFile(temp) }
            // Something arrived at that name between the look and the rename.
            if (base == null && e.reason == StoreException.Reason.ALREADY_THERE) return Outcome.Changed
            throw e
        } catch (e: Exception) {
            runCatching { store.deleteFile(temp) }
            throw e
        }
        val now = store.stat(path) ?: throw StoreException(StoreException.Reason.GONE, path.substringAfterLast('/'))
        if (now.size != local.length()) throw StoreException(StoreException.Reason.CANNOT_WRITE, path.substringAfterLast('/'))
        return Outcome.Saved(now)
    }

    private fun unchanged(store: Store, path: String, base: Version?): Boolean {
        val now = store.stat(path)
        return if (base == null) now == null else base.matches(now)
    }

    /**
     * Beside the file, hidden, and not ending in ".part", which Nextcloud keeps for its own
     * uploads and refuses from anyone else.
     */
    fun tempName(path: String): String {
        val folder = path.substringBeforeLast('/', "")
        val name = ".${path.substringAfterLast('/')}.tana-${UUID.randomUUID().toString().take(8)}"
        return if (folder.isEmpty()) name else "$folder/$name"
    }
}
