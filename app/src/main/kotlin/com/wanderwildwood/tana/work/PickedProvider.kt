package com.wanderwildwood.tana.work

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * The files and folders this app hands back to other apps that asked for them (see
 * PickActivity): a file chosen for "open", a new file made for "save as", a folder chosen
 * for "choose a folder".
 *
 * A DocumentsProvider rather than a plain provider because a folder has to be answered as a
 * tree: the asking app lists it, makes files in it and reads them back through
 * `DocumentsContract`, and only a document provider speaks that. The same provider serves a
 * single file just as well, and a grant to it can be kept across restarts, which an app that
 * asked to "open a document" expects.
 *
 * It lists no place of its own, so Android's own picker never shows it: nothing reaches a
 * document here except through an address this app handed out with a grant. A document is
 * named by its path, and every path is checked by [PathGuard] -- shared storage only, no
 * other app's folders, no `..` -- before anything is read, made, renamed or deleted.
 */
class PickedProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    private fun roots(): List<String> {
        val ctx = context!!
        val volumes = ctx.getSystemService(StorageManager::class.java).storageVolumes
            .mapNotNull { it.directory?.path }
        return (listOf(Environment.getExternalStorageDirectory().path) + volumes).distinct()
    }

    private fun file(documentId: String?): File =
        PathGuard.resolve(documentId.orEmpty(), roots())?.let(::File)
            ?: throw FileNotFoundException("No such document")

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(android.provider.DocumentsContract.Root.COLUMN_ROOT_ID))

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: COLUMNS)
        val f = file(documentId)
        if (!f.exists()) throw FileNotFoundException("No such document")
        row(cursor, f)
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String?, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: COLUMNS)
        val dir = file(parentDocumentId)
        val children = dir.listFiles() ?: throw FileNotFoundException("Not a folder")
        // Anything that would not pass the check on its own (a link out) is left out.
        children.sortedBy { it.name.lowercase() }.forEach { child ->
            if (PathGuard.resolve(child.absolutePath, roots()) != null) row(cursor, child)
        }
        return cursor
    }

    private fun row(cursor: MatrixCursor, f: File) {
        val dir = f.isDirectory
        var flags = if (dir) {
            Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            Document.FLAG_SUPPORTS_WRITE
        }
        if (f.canWrite() || dir) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, f.path)
            .add(Document.COLUMN_DISPLAY_NAME, f.name)
            .add(Document.COLUMN_SIZE, if (dir) null else f.length())
            .add(Document.COLUMN_MIME_TYPE, if (dir) Document.MIME_TYPE_DIR else Names.mime(f.name).let { if (it == "*/*") "application/octet-stream" else it })
            .add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    override fun getDocumentType(documentId: String?): String {
        val f = file(documentId)
        return if (f.isDirectory) Document.MIME_TYPE_DIR else Names.mime(f.name).let { if (it == "*/*") "application/octet-stream" else it }
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        val parent = PathGuard.resolve(parentDocumentId.orEmpty(), roots()) ?: return false
        val child = PathGuard.resolve(documentId.orEmpty(), roots()) ?: return false
        return PathGuard.within(child, parent)
    }

    /**
     * Where a document sits: the ids from the folder the asking app was given down to the
     * document, both included. An app that edits a file in place asks this to find the folder
     * the file is in (a music player saving a song's details, for one).
     *
     * For an address under a granted folder, Android passes that folder as [parentDocumentId]
     * and has already refused, through [isChildDocument], a document outside it; the same
     * boundary is checked here again, so nothing above the granted folder is ever named. With
     * no parent (only Android itself, holding MANAGE_DOCUMENTS, can ask that way) the answer
     * starts at the top of the storage the document is on; there is no root id to give, as
     * this provider lists no roots.
     */
    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String?): DocumentsContract.Path {
        val child = file(childDocumentId)
        if (!child.exists()) throw FileNotFoundException("No such document")
        if (parentDocumentId == null) {
            val top = roots().mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }
                .filter { PathGuard.within(child.path, it) }
                .maxByOrNull { it.length }
            val ids = top?.let { PathGuard.chain(child.path, it) } ?: throw FileNotFoundException("No such document")
            return DocumentsContract.Path(null, ids)
        }
        val parent = file(parentDocumentId)
        val ids = PathGuard.chain(child.path, parent.path) ?: throw FileNotFoundException("Not in that folder")
        // The first id is the folder as the asking app named it, which is what Android compares.
        return DocumentsContract.Path(null, listOf(parentDocumentId) + ids.drop(1))
    }

    override fun openDocument(documentId: String?, mode: String?, signal: CancellationSignal?): ParcelFileDescriptor {
        val f = file(documentId)
        if (f.isDirectory) throw FileNotFoundException("A folder cannot be opened as a file")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode ?: "r"))
    }

    override fun createDocument(parentDocumentId: String?, mimeType: String?, displayName: String?): String {
        val parent = file(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Not a folder")
        val name = displayName.orEmpty().trim()
        if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\u0000' in name) {
            throw IllegalArgumentException("Not a usable name")
        }
        // A name already taken is never overwritten here: the new one is "name (1)".
        val target = freeName(parent, name)
        // Checked before it is made, not after: a name that is not for sharing is never created.
        file(target.absolutePath)
        val made = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!made) throw FileNotFoundException("Could not make ${target.name}")
        return file(target.absolutePath).path
    }

    override fun deleteDocument(documentId: String?) {
        val f = file(documentId)
        if (roots().any { runCatching { File(it).canonicalPath }.getOrNull() == f.path }) {
            throw SecurityException("Not a storage root")
        }
        if (!f.deleteRecursively()) throw FileNotFoundException("Could not delete ${f.name}")
    }

    override fun renameDocument(documentId: String?, displayName: String?): String {
        val f = file(documentId)
        val name = displayName.orEmpty().trim()
        if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\u0000' in name) {
            throw IllegalArgumentException("Not a usable name")
        }
        val target = File(f.parentFile, name)
        // Checked before the move, not after: a file is never renamed to somewhere it could
        // then not be reached.
        file(target.absolutePath)
        if (target.exists() || !f.renameTo(target)) throw FileNotFoundException("Could not rename ${f.name}")
        return file(target.absolutePath).path
    }

    companion object {
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        /** [name] in [dir], or "name (1)", "name (2)" ... if it is taken. */
        fun freeName(dir: File, name: String): File {
            var candidate = File(dir, name)
            if (!candidate.exists()) return candidate
            val dot = name.lastIndexOf('.').takeIf { it > 0 }
            val stem = if (dot != null) name.substring(0, dot) else name
            val ext = if (dot != null) name.substring(dot) else ""
            var n = 1
            while (candidate.exists()) candidate = File(dir, "$stem ($n)$ext").also { n++ }
            return candidate
        }
    }
}
