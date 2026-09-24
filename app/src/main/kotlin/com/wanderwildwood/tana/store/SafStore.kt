package com.wanderwildwood.tana.store

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * A folder another app offers through Android's document picker — Termux's home, say — which
 * no path can reach. The reader chose it once in the system picker, and the grant that came
 * back covers that folder and what is inside it.
 *
 * Android names what is in such a folder by document ids, not paths, so paths here are made of
 * names, as everywhere else in the app, and turned into ids as they are walked. The ids are
 * remembered as folders are listed, so a folder is only walked once.
 */
class SafStore(val root: SafRoot, private val resolver: ContentResolver) : Store {
    override val id: String = root.storeId
    private val tree: Uri = Uri.parse(root.uri)
    private val ids = HashMap<String, String>()

    init {
        ids[""] = DocumentsContract.getTreeDocumentId(tree)
    }

    private fun uri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    private fun <T> guard(subject: String, block: () -> T): T = try {
        block()
    } catch (e: StoreException) {
        throw e
    } catch (e: SecurityException) {
        throw StoreException(StoreException.Reason.NOT_ALLOWED, subject, e)
    } catch (e: FileNotFoundException) {
        throw StoreException(StoreException.Reason.GONE, subject, e)
    } catch (e: IllegalArgumentException) {
        throw StoreException(StoreException.Reason.GONE, subject, e)
    }

    @Synchronized
    private fun docId(path: String): String {
        ids[path]?.let { return it }
        val parent = path.substringBeforeLast('/', "")
        list(parent)
        return ids[path] ?: throw StoreException(StoreException.Reason.GONE, path.substringAfterLast('/'))
    }

    @Synchronized
    override fun list(path: String): List<Entry> = guard(path.substringAfterLast('/').ifEmpty { root.label }) {
        val parentId = docId(path)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = mutableListOf<Entry>()
        resolver.query(
            children,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val childId = c.getString(0) ?: continue
                val name = c.getString(1)?.takeIf { it.isNotEmpty() && '/' !in it } ?: continue
                val childPath = if (path.isEmpty()) name else "$path/$name"
                val isFolder = c.getString(2) == Document.MIME_TYPE_DIR
                ids[childPath] = childId
                out += Entry(
                    loc = Loc(id, childPath),
                    isFolder = isFolder,
                    size = if (isFolder || c.isNull(3)) 0 else c.getLong(3),
                    modified = if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        } ?: throw StoreException(StoreException.Reason.FOLDER_UNREADABLE, path.substringAfterLast('/').ifEmpty { root.label })
        out
    }

    override fun stat(path: String): Entry? {
        if (path.isEmpty()) return Entry(Loc(id, ""), isFolder = true, size = 0, modified = 0)
        val name = path.substringAfterLast('/')
        return try {
            list(path.substringBeforeLast('/', "")).firstOrNull { it.name == name }
        } catch (e: StoreException) {
            if (e.reason == StoreException.Reason.GONE) null else throw e
        }
    }

    override fun openRead(path: String): InputStream = guard(path.substringAfterLast('/')) {
        resolver.openInputStream(uri(docId(path))) ?: throw StoreException(StoreException.Reason.FOLDER_UNREADABLE, path)
    }

    override fun openWrite(path: String): OutputStream = guard(path.substringAfterLast('/')) {
        val name = path.substringAfterLast('/')
        val existing = stat(path)
        val target = if (existing != null) {
            uri(docId(path))
        } else {
            // A neutral type, so a provider that fits a file's name to its type does not add
            // an extension of its own; the real name is given by the rename that follows.
            DocumentsContract.createDocument(resolver, uri(docId(path.substringBeforeLast('/', ""))), "application/octet-stream", name)
                ?.also { remember(path, it) }
                ?: throw StoreException(StoreException.Reason.CANNOT_WRITE, name)
        }
        resolver.openOutputStream(target, "wt") ?: throw StoreException(StoreException.Reason.CANNOT_WRITE, name)
    }

    override fun makeFolder(path: String): Unit = guard(path.substringAfterLast('/')) {
        val name = path.substringAfterLast('/')
        if (stat(path) != null) throw StoreException(StoreException.Reason.ALREADY_THERE, name)
        val made = DocumentsContract.createDocument(resolver, uri(docId(path.substringBeforeLast('/', ""))), Document.MIME_TYPE_DIR, name)
            ?: throw StoreException(StoreException.Reason.CANNOT_MAKE_FOLDER, name)
        remember(path, made)
    }

    override fun rename(from: String, to: String): Unit = guard(from.substringAfterLast('/')) {
        val fromParent = from.substringBeforeLast('/', "")
        val toParent = to.substringBeforeLast('/', "")
        val newName = to.substringAfterLast('/')
        if (stat(to) != null) throw StoreException(StoreException.Reason.ALREADY_THERE, newName)
        var current = uri(docId(from))
        if (fromParent != toParent) {
            // Not every provider can move; one that cannot says so, and the copy-then-delete
            // that a move falls back on does the job instead.
            current = runCatching {
                DocumentsContract.moveDocument(resolver, current, uri(docId(fromParent)), uri(docId(toParent)))
            }.getOrNull() ?: throw StoreException(StoreException.Reason.CANNOT_RENAME, from.substringAfterLast('/'))
        }
        if (from.substringAfterLast('/') != newName) {
            current = DocumentsContract.renameDocument(resolver, current, newName)
                ?: throw StoreException(StoreException.Reason.CANNOT_RENAME, from.substringAfterLast('/'))
        }
        forget(from)
        remember(to, current)
    }

    override fun deleteFile(path: String): Unit = guard(path.substringAfterLast('/')) {
        if (!DocumentsContract.deleteDocument(resolver, uri(docId(path)))) {
            throw StoreException(StoreException.Reason.CANNOT_DELETE, path.substringAfterLast('/'))
        }
        forget(path)
    }

    override fun deleteFolder(path: String) = deleteFile(path)

    @Synchronized
    private fun remember(path: String, documentUri: Uri) {
        ids[path] = DocumentsContract.getDocumentId(documentUri)
    }

    @Synchronized
    private fun forget(path: String) {
        ids.keys.removeAll { it == path || it.startsWith("$path/") }
    }
}

/** A folder from another app, as the reader added it: its tree address and what to call it. */
data class SafRoot(val id: String, val label: String, val note: String, val uri: String) {
    val storeId: String get() = "$PREFIX$id"

    companion object {
        const val PREFIX = "saf:"
    }
}
