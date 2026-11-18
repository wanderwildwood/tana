package com.wanderwildwood.tana.work

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.wanderwildwood.tana.R
import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.store.Server
import com.wanderwildwood.tana.store.StoreException
import com.wanderwildwood.tana.store.Stores
import java.io.File
import java.io.FileNotFoundException

/**
 * The servers added here, inside Android's own file picker.
 *
 * Every app that asks Android for a file -- Signal, a browser's upload button -- gets
 * Android's picker, which no other app can stand in for. What an app can do is appear inside
 * it: one place in the picker's list, beside the phone's own storage, holding each server as
 * a folder, and a file chosen from one is fetched here and handed over.
 *
 * One place named after this app, not one per server: Android's picker folds an app's own
 * picking screen into that app's place, behind a small arrow beside it, and stops listing the
 * app on its own. Named after a server, nothing said this app's picker -- the one that shows
 * a picture before it is chosen -- was there at all.
 *
 * A file chosen here can be read, and written back by the app that chose it (see
 * [openForWriting]). A server's folders also take new files and folders, so another app's
 * "Save as" and its choose-a-folder screens can write into a Nextcloud through this app, and
 * files and folders here can be renamed and deleted. The phone's own storage is not repeated
 * here; Android's picker already shows it.
 */
class ServersProvider : DocumentsProvider() {

    override fun onCreate(): Boolean {
        val ctx = context!!
        Stores.spool = File(ctx.cacheDir, "spool")
        // Saves still going up when the app was last stopped: kept, not left in the cache.
        val writing = File(ctx.cacheDir, WRITING)
        if (writing.isDirectory) {
            NETWORK.execute {
                runCatching { UnsentSaves.rescue(ctx, writing) }
                    .onFailure { warn("could not look for saves left unsent", it) }
            }
        }
        return true
    }

    private fun servers(): List<Server> {
        val list = Prefs(context!!).servers
        Stores.setServers(list)
        return list
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        // With no servers there is no place, and the picker lists this app by its name instead.
        val servers = servers().ifEmpty { return cursor }
        cursor.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_DOCUMENT_ID, TOP)
            .add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
            .add(Root.COLUMN_SUMMARY, servers.joinToString(", ") { label(it) })
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_CREATE)
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            .add(Root.COLUMN_MIME_TYPES, "*/*")
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        if (documentId == TOP) {
            folderRow(cursor, TOP, context!!.getString(R.string.app_name))
            return cursor
        }
        val (store, path) = ours(documentId)
        if (path.isEmpty()) {
            val server = servers().firstOrNull { it.storeId == store } ?: throw FileNotFoundException(documentId)
            folderRow(cursor, documentId, label(server))
            return cursor
        }
        val entry = find(store, path) ?: throw FileNotFoundException(documentId)
        row(cursor, entry)
        return cursor
    }

    /**
     * One file or folder on a server. Asked for directly first; failing that, looked for in
     * its folder's listing, which is how the picker came to show it in the first place.
     */
    private fun find(store: String, path: String): Entry? {
        seen[idOf(store, path)]?.let { return it }
        val source = Stores.get(store)
        runCatching { onNetwork { source.stat(path) } }
            .onSuccess { if (it != null) return it }
            .onFailure { warn("stat failed for a server file", it) }
        val parent = path.substringBeforeLast('/', "")
        return runCatching { onNetwork { source.list(parent) } }
            .onFailure { warn("listing failed for a server folder", it) }
            .getOrNull()
            ?.firstOrNull { it.loc.path == path }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        if (parentDocumentId == TOP) {
            servers().forEach { folderRow(cursor, idOf(it.storeId, ""), label(it)) }
            return cursor
        }
        val (store, path) = ours(parentDocumentId)
        val hidden = Prefs(context!!).showHidden
        runCatching { onNetwork { Stores.get(store).list(path) } }
            .onSuccess { entries ->
                entries.forEach { seen[idOf(it.loc.store, it.loc.path)] = it }
                entries.filter { hidden || !it.name.startsWith(".") }
                    .sortedWith(compareBy<Entry>({ !it.isFolder }, { it.name.lowercase() }))
                    .forEach { row(cursor, it) }
            }
            .onFailure {
                // Said in the picker, where the reader is, rather than as an empty folder that
                // would read as a server with nothing on it.
                cursor.extras = Bundle().apply {
                    putString(DocumentsContract.EXTRA_ERROR, context!!.getString(R.string.provider_unreachable))
                }
            }
        return cursor
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (parentDocumentId == TOP) throw FileNotFoundException("a server is chosen first")
        val (store, parentPath) = ours(parentDocumentId)
        val source = Stores.get(store)
        val clean = displayName.replace('/', '_').trim().ifEmpty { context!!.getString(R.string.provider_new_file) }
        if (!Names.isPlainName(clean)) throw FileNotFoundException("not a name: $displayName")
        val folder = mimeType == Document.MIME_TYPE_DIR
        try {
            return onNetwork {
                // A name already taken gets a number, as the contract asks, so a save never
                // replaces a file that is there.
                val taken = source.list(parentPath).map { it.name.lowercase() }.toSet()
                var name = clean
                var n = 1
                while (name.lowercase() in taken) {
                    val dot = clean.lastIndexOf('.').takeIf { it > 0 && !folder }
                    name = if (dot == null) "$clean ($n)" else clean.substring(0, dot) + " ($n)" + clean.substring(dot)
                    n++
                }
                val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
                if (folder) source.makeFolder(path) else source.openWrite(path).close()
                source.stat(path)?.let { seen[idOf(store, path)] = it }
                changed(parentDocumentId)
                idOf(store, path)
            }
        } catch (e: Exception) {
            warn("could not create on a server", e)
            throw FileNotFoundException("could not create $displayName: ${e.message}")
        }
    }

    override fun deleteDocument(documentId: String) {
        val (store, path) = ours(documentId)
        if (path.isEmpty()) throw FileNotFoundException("a server cannot be removed here")
        try {
            onNetwork { deleteTree(Stores.get(store), path) }
        } catch (e: Exception) {
            warn("could not delete on a server", e)
            throw FileNotFoundException("could not delete ${path.substringAfterLast('/')}: ${e.message}")
        }
        forget(documentId)
        changed(idOf(store, path.substringBeforeLast('/', "")))
    }

    /** A folder goes with what is in it, as a delete from another app means. */
    private fun deleteTree(source: com.wanderwildwood.tana.store.Store, path: String) {
        val entry = source.stat(path) ?: return
        if (!entry.isFolder) return source.deleteFile(path)
        source.list(path).forEach { deleteTree(source, it.loc.path) }
        source.deleteFolder(path)
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val (store, path) = ours(documentId)
        if (path.isEmpty()) throw FileNotFoundException("a server cannot be renamed here")
        val clean = displayName.replace('/', '_').trim()
        if (!Names.isPlainName(clean)) throw FileNotFoundException("no name")
        val parent = path.substringBeforeLast('/', "")
        val to = if (parent.isEmpty()) clean else "$parent/$clean"
        if (to == path) return null
        try {
            onNetwork { Stores.get(store).rename(path, to) }
        } catch (e: Exception) {
            warn("could not rename on a server", e)
            throw FileNotFoundException("could not rename: ${e.message}")
        }
        forget(documentId)
        changed(idOf(store, parent))
        return idOf(store, to)
    }

    private fun forget(documentId: String) {
        val inside = documentId.substringAfter('|')
        seen.keys.removeAll { it == documentId || it.substringAfter('|').startsWith("$inside/") && it.substringBefore('|') == documentId.substringBefore('|') }
        read.remove(documentId)
    }

    /** Tells whoever is showing [folderId] that its list changed. */
    private fun changed(folderId: String) {
        context?.contentResolver?.notifyChange(
            DocumentsContract.buildChildDocumentsUri(context!!.packageName + ".servers", folderId), null,
        )
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        isChild(parentDocumentId, documentId, servers().map { it.storeId }.toSet())

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val (store, path) = ours(documentId)
        // A save of this file still going up: whatever comes next is about the file after it.
        awaitWriting(documentId)
        if (!mode.contains('w')) {
            conflicts.remove(documentId)?.let { name -> throw FileNotFoundException(context!!.getString(R.string.provider_changed, name)) }
            return ParcelFileDescriptor.open(fetch(documentId, store, path, signal), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        return openForWriting(documentId, store, path, mode, signal)
    }

    /**
     * The file as it is on the server now, fetched whole, as opening a server's file anywhere
     * in this app is: a picked file is read from start to end by whoever asked, and most apps
     * need to be able to seek in it. What was fetched is remembered ([read]), so a save that
     * follows can tell whether the server's file changed in between.
     */
    private fun fetch(documentId: String, store: String, path: String, signal: CancellationSignal?): File {
        val source = Stores.get(store)
        // Asked afresh rather than from the last listing: a file someone else saved since must
        // never be answered with the copy from before.
        var entry = statNow(store, path) ?: throw FileNotFoundException(documentId)
        val folder = File(context!!.cacheDir, "fetched/picked-${(store + path).hashCode().toUInt()}")
        val file = File(folder, entry.name)
        val known = read[documentId]
        val kept = file.exists() && file.length() == entry.size && when {
            known != null -> known.matches(entry)
            entry.tag != null -> false
            else -> entry.modified == 0L || file.lastModified() == entry.modified
        }
        if (kept) {
            read[documentId] = Version.of(entry)
            return file
        }
        folder.mkdirs()
        val part = File(folder, entry.name + ".part")
        // Fetched again if the file changed while it was coming down, so that what is handed
        // over and what is remembered as read are the same file.
        for (attempt in 1..3) {
            try {
                onNetwork { source.openRead(path).use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            signal?.throwIfCanceled()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                        }
                    }
                } }
            } catch (e: Exception) {
                part.delete()
                throw FileNotFoundException("could not fetch $documentId: ${e.message}")
            }
            val after = statNow(store, path) ?: run { part.delete(); throw FileNotFoundException(documentId) }
            if (Version.of(entry).matches(after) && part.length() == after.size) {
                entry = after
                break
            }
            entry = after
            if (attempt == 3) {
                part.delete()
                throw FileNotFoundException("$documentId kept changing while it was fetched")
            }
        }
        file.delete()
        part.renameTo(file)
        if (entry.modified > 0) file.setLastModified(entry.modified)
        read[documentId] = Version.of(entry)
        seen[documentId] = entry
        return file
    }

    private fun statNow(store: String, path: String): Entry? =
        try {
            onNetwork { Stores.get(store).stat(path) }
        } catch (e: Exception) {
            warn("stat failed for a server file", e)
            throw FileNotFoundException("could not reach the server: ${e.message}")
        }

    /**
     * Writing, for an app that saves the file it opened -- a password vault, an editor.
     *
     * The app writes into a copy here; when it closes the file, the copy goes to the server by
     * [WriteBack], which refuses to replace a file someone else saved since it was read. A
     * refusal cannot be said to the app's close, which has already returned, so it is said to
     * its next read of the file: an app that reads back what it saved (a careful one does)
     * is told the file changed on the server and was not replaced. What it wrote is never
     * thrown away on a save that did not go through: it is kept in Download, and a
     * notification says where ([UnsentSaves]). Until the upload is done,
     * any other open of the file waits for it.
     */
    private fun openForWriting(documentId: String, store: String, path: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val existing = statNow(store, path)
        if (existing?.isFolder == true) throw FileNotFoundException("$documentId is a folder")
        // What the app read, or, if it never read it through here, the file as it is now.
        val base = read[documentId] ?: existing?.let { Version.of(it) }
        val name = path.substringAfterLast('/')
        val local = File(File(context!!.cacheDir, WRITING), java.util.UUID.randomUUID().toString()).apply { mkdirs() }.let { File(it, name) }
        val flags = ParcelFileDescriptor.parseMode(mode)
        // "rw" and "wa" keep what is there, so they start from it.
        val start = if (flags and ParcelFileDescriptor.MODE_TRUNCATE == 0 && existing != null) {
            fetch(documentId, store, path, signal).also { it.copyTo(local, overwrite = true) }
        } else null
        val startSize = start?.length() ?: 0L
        val done = java.util.concurrent.CompletableFuture<Unit>()
        writing[documentId] = done
        val ctx = context!!
        return try {
            ParcelFileDescriptor.open(local, flags, closer()) { problem ->
                NETWORK.execute {
                    // Why what the app wrote did not reach the server, if it did not. Then the
                    // copy here is the only one, and it is kept where the reader can find it
                    // ([UnsentSaves]) before this one goes.
                    var unsent: String? = null
                    try {
                        if (problem != null) {
                            // The app's side ended with an error, went away, or let the file go
                            // without closing it: what it wrote may be whole or half done. Half a
                            // file must never replace the server's, so it is not sent, but kept
                            // as any unsent save is -- unless there is nothing new in it.
                            warn("a save was not closed cleanly", problem)
                            if (local.length() > 0 && !(start != null && sameBytes(local, start, startSize))) {
                                val why = ctx.getString(R.string.unsent_unfinished)
                                failed[documentId] = why
                                unsent = why
                            }
                        } else if (!upload(documentId, store, path, local, base)) {
                            unsent = ctx.getString(R.string.unsent_changed)
                        }
                    } catch (e: Exception) {
                        warn("a save to a server failed", e)
                        val why = whyNotSent(ctx, e)
                        failed[documentId] = why
                        unsent = why
                    } finally {
                        val safe = unsent == null || UnsentSaves.keep(ctx, local, name, unsent)
                        // Kept back only when it could be kept nowhere else.
                        if (safe) local.parentFile?.deleteRecursively()
                        writing.remove(documentId, done)
                        done.complete(Unit)
                    }
                }
            }
        } catch (e: Exception) {
            writing.remove(documentId, done)
            done.complete(Unit)
            local.parentFile?.deleteRecursively()
            throw FileNotFoundException("could not open $documentId for writing: ${e.message}")
        }
    }

    /**
     * Why a save did not reach the server, in words from strings.xml. Never the exception's
     * own message, which names the server's address and the file, and which a notification
     * would show on the lock screen.
     */
    private fun whyNotSent(ctx: android.content.Context, e: Exception): String {
        val reason = ((e as? StoreException) ?: (e.cause as? StoreException))?.reason
        return ctx.getString(
            when {
                reason == StoreException.Reason.LOGIN_REFUSED -> R.string.problem_login_refused
                reason == StoreException.Reason.SERVER_UNREACHABLE -> R.string.unsent_unreachable
                reason == null && e is java.io.IOException -> R.string.unsent_unreachable
                else -> R.string.unsent_refused
            },
        )
    }

    /** True when the file is on the server; false when the server's file changed and was left as it was. */
    private fun upload(documentId: String, store: String, path: String, local: File, base: Version?): Boolean {
        return when (val outcome = WriteBack.put(Stores.get(store), path, local, base)) {
            WriteBack.Outcome.Changed -> {
                android.util.Log.w(TAG, "a save was refused: the server's file changed since it was read")
                conflicts[documentId] = path.substringAfterLast('/')
                false
            }
            is WriteBack.Outcome.Saved -> {
                // What was sent is what the server has now: kept as the fetched copy, so the
                // app reading it back is not made to wait for a download of its own file.
                val folder = File(context!!.cacheDir, "fetched/picked-${(store + path).hashCode().toUInt()}").apply { mkdirs() }
                val file = File(folder, outcome.entry.name)
                local.copyTo(file, overwrite = true)
                if (outcome.entry.modified > 0) file.setLastModified(outcome.entry.modified)
                read[documentId] = Version.of(outcome.entry)
                seen[documentId] = outcome.entry
                true
            }
        }
    }

    private fun awaitWriting(documentId: String) {
        val pending = writing[documentId] ?: return
        runCatching { pending.get(5, java.util.concurrent.TimeUnit.MINUTES) }
        // A save that failed outright (the server went away) is said the same way a refused
        // one is: on the next read, so the app knows its file did not get there.
        failed.remove(documentId)?.let { reason ->
            throw FileNotFoundException(context!!.getString(R.string.provider_save_failed, path(documentId), reason))
        }
    }

    private fun path(documentId: String) = split(documentId).second.substringAfterLast('/')

    /**
     * The store and path of a document on one of the servers added here. Nothing else this
     * app can reach -- the phone's storage, a zip, folders other apps gave it -- is ever a
     * document of this place, whatever id an app makes up.
     */
    private fun ours(id: String): Pair<String, String> = split(id, servers().map { it.storeId }.toSet())

    /** Where the closing of a file being written is heard. One thread, started when first needed. */
    private fun closer(): android.os.Handler = synchronized(this) {
        handler ?: android.os.HandlerThread("tana-writeback").let { thread ->
            thread.start()
            android.os.Handler(thread.looper).also { handler = it }
        }
    }

    private var handler: android.os.Handler? = null

    /** By document: the version last handed to an app, a save still going up, and saves that did not go through. */
    private val read = java.util.concurrent.ConcurrentHashMap<String, Version>()
    private val writing = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Unit>>()
    private val conflicts = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val failed = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun label(server: Server) = server.name.ifBlank { server.host }

    /** A server's top folder (or the place holding the servers, [TOP]): takes new things, cannot be renamed or deleted. */
    private fun folderRow(cursor: MatrixCursor, id: String, name: String) {
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, name)
            .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            .add(Document.COLUMN_FLAGS, if (id == TOP) 0 else Document.FLAG_DIR_SUPPORTS_CREATE)
    }

    private fun row(cursor: MatrixCursor, entry: Entry) {
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, idOf(entry.loc.store, entry.loc.path))
            .add(Document.COLUMN_DISPLAY_NAME, entry.name)
            .add(Document.COLUMN_MIME_TYPE, if (entry.isFolder) Document.MIME_TYPE_DIR else Names.mime(entry.name))
            .add(Document.COLUMN_SIZE, if (entry.isFolder) null else entry.size)
            .add(Document.COLUMN_LAST_MODIFIED, entry.modified.takeIf { it > 0 })
            // A file can be saved back by the app that opened it (see openForWriting); a folder
            // takes new things; both can be renamed and deleted.
            .add(
                Document.COLUMN_FLAGS,
                Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                    if (entry.isFolder) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE,
            )
    }

    /**
     * What the picker was last shown, by document. Asking about a file just listed -- which is
     * what every app does the moment one is chosen -- then needs no round trip to the server.
     */
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    /**
     * Network work, on a thread of its own. A provider runs its calls on whatever thread the
     * binder hands it, carrying the caller's StrictMode rules with it: an app that asks about
     * a chosen file from its main thread -- Messaging does -- turns every server call here
     * into NetworkOnMainThreadException. A fresh thread carries no such rule.
     */
    private fun <T> onNetwork(work: () -> T): T = try {
        NETWORK.submit(java.util.concurrent.Callable { work() }).get()
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    }

    companion object {
        private const val TAG = "tana.provider"
        private val NETWORK = java.util.concurrent.Executors.newCachedThreadPool()

        /** Under the cache: one folder per save from another app until it has gone up. */
        private const val WRITING = "writing"

        private const val ROOT_ID = "servers"

        /** The one place itself, whose folders are the servers. No bar, so no store's id. */
        private const val TOP = "servers"

        /** A document is "<store>|<path>"; a store id never has a bar in it, a path might. */
        fun idOf(store: String, path: String) = "$store|$path"

        /**
         * The store and path of a document id. An id whose path has an empty, "." or ".."
         * step is refused: the server would resolve it to a place outside the folder another
         * app was given.
         */
        fun split(id: String): Pair<String, String> {
            val path = id.substringAfter('|', "")
            if (!Names.isPlainPath(path)) throw FileNotFoundException("no such document")
            return id.substringBefore('|') to path
        }

        /** Whether [a] holds what [b] held when it was [size] bytes long: the same bytes, start to end. */
        fun sameBytes(a: File, b: File, size: Long): Boolean {
            if (a.length() != size || b.length() != size) return false
            val x = a.inputStream().buffered()
            val y = b.inputStream().buffered()
            try {
                var c: Int
                do {
                    c = x.read()
                    if (c != y.read()) return false
                } while (c >= 0)
                return true
            } finally {
                x.close()
                y.close()
            }
        }

        /** As [split], and refused unless the store is one of [servers] (their store ids). */
        fun split(id: String, servers: Set<String>): Pair<String, String> {
            val parts = split(id)
            if (parts.first !in servers) throw FileNotFoundException("no such document")
            return parts
        }

        /**
         * Whether [id] is inside [parent], for a folder an app was given. Inside the place
         * itself ([TOP]) are only the servers added here and what is on them.
         */
        fun isChild(parent: String, id: String, servers: Set<String>): Boolean {
            if (id == TOP) return false
            val (store, path) = try { split(id, servers) } catch (e: FileNotFoundException) { return false }
            if (parent == TOP) return true
            val (parentStore, parentPath) = try { split(parent, servers) } catch (e: FileNotFoundException) { return false }
            return store == parentStore && (parentPath.isEmpty() || path.startsWith("$parentPath/"))
        }

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        /** Tells Android's picker the list of servers changed. */
        fun rootsChanged(context: android.content.Context) {
            context.contentResolver.notifyChange(
                DocumentsContract.buildRootsUri(context.packageName + ".servers"), null,
            )
        }
    }

    /**
     * A failure, for the log. A release build names only its kind: the full exception carries
     * file names and the server's address.
     */
    private fun warn(message: String, e: Throwable) {
        if (com.wanderwildwood.tana.BuildConfig.DEBUG) {
            android.util.Log.w(TAG, message, e)
        } else {
            android.util.Log.w(TAG, "$message: ${(e as? StoreException)?.reason ?: e.javaClass.simpleName}")
        }
    }
}
