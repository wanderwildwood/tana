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
 * Read-only. The picker offers nothing but choosing, and writing to a server from another
 * app's save dialog is a different thing with different risks. The phone's own storage is
 * not repeated here; Android's picker already shows it.
 */
class ServersProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

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
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
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
        val (store, path) = split(documentId)
        if (path.isEmpty()) {
            val server = servers().firstOrNull { it.storeId == store } ?: throw FileNotFoundException(documentId)
            folderRow(cursor, documentId, label(server))
            return cursor
        }
        servers()
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
            .onFailure { android.util.Log.w(TAG, "stat failed for a server file", it) }
        val parent = path.substringBeforeLast('/', "")
        return runCatching { onNetwork { source.list(parent) } }
            .onFailure { android.util.Log.w(TAG, "listing failed for a server folder", it) }
            .getOrNull()
            ?.firstOrNull { it.loc.path == path }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        if (parentDocumentId == TOP) {
            servers().forEach { folderRow(cursor, idOf(it.storeId, ""), label(it)) }
            return cursor
        }
        val (store, path) = split(parentDocumentId)
        servers()
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

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == TOP) return documentId != TOP
        val (parentStore, parentPath) = split(parentDocumentId)
        val (store, path) = split(documentId)
        return store == parentStore && (parentPath.isEmpty() || path.startsWith("$parentPath/"))
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode.contains('w')) throw FileNotFoundException("read only: $documentId")
        val (store, path) = split(documentId)
        servers()
        val source = Stores.get(store)
        val entry = find(store, path) ?: throw FileNotFoundException(documentId)
        // Fetched whole, as opening a server's file anywhere in this app is: a picked file is
        // read from start to end by whoever asked, and most apps need to be able to seek in it.
        val folder = File(context!!.cacheDir, "fetched/picked-${(store + path).hashCode().toUInt()}")
        val file = File(folder, entry.name)
        if (!(file.exists() && file.length() == entry.size && (entry.modified == 0L || file.lastModified() == entry.modified))) {
            folder.mkdirs()
            val part = File(folder, entry.name + ".part")
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
            file.delete()
            part.renameTo(file)
            if (entry.modified > 0) file.setLastModified(entry.modified)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun label(server: Server) = server.name.ifBlank { server.host }

    private fun folderRow(cursor: MatrixCursor, id: String, name: String) {
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, name)
            .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            .add(Document.COLUMN_FLAGS, 0)
    }

    private fun row(cursor: MatrixCursor, entry: Entry) {
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, idOf(entry.loc.store, entry.loc.path))
            .add(Document.COLUMN_DISPLAY_NAME, entry.name)
            .add(Document.COLUMN_MIME_TYPE, if (entry.isFolder) Document.MIME_TYPE_DIR else Names.mime(entry.name))
            .add(Document.COLUMN_SIZE, if (entry.isFolder) null else entry.size)
            .add(Document.COLUMN_LAST_MODIFIED, entry.modified.takeIf { it > 0 })
            .add(Document.COLUMN_FLAGS, 0)
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

        private const val ROOT_ID = "servers"

        /** The one place itself, whose folders are the servers. No bar, so no store's id. */
        private const val TOP = "servers"

        /** A document is "<store>|<path>"; a store id never has a bar in it, a path might. */
        fun idOf(store: String, path: String) = "$store|$path"

        fun split(id: String): Pair<String, String> =
            id.substringBefore('|') to id.substringAfter('|', "")

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
}
