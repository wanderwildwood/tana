package com.wanderwildwood.tana.store

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The phone's own storage, internal and SD card alike, through plain paths.
 *
 * [root] is only there so the tests can run against a scratch folder; on the phone it is "/".
 */
class LocalStore(private val root: File = File("/")) : Store {
    override val id: String = ID

    fun file(path: String): File = if (path.isEmpty()) root else File(root, path)

    /** The other way: the path this store would give [file]. */
    fun pathOf(file: File): String = file.absolutePath.removePrefix(root.absolutePath).trim('/')

    override fun list(path: String): List<Entry> {
        val dir = file(path)
        // listFiles() answers null for "not a folder" and "not allowed" alike, and an empty
        // list would say the folder was empty when it may be nothing of the kind.
        val children = dir.listFiles() ?: throw StoreException(
            if (dir.isDirectory) StoreException.Reason.FOLDER_UNREADABLE else StoreException.Reason.GONE,
            dir.name,
        )
        return children.map { it.toEntry(Loc(id, pathOf(it))) }
    }

    override fun stat(path: String): Entry? {
        val f = file(path)
        return if (f.exists()) f.toEntry(Loc(id, path)) else null
    }

    override fun openRead(path: String): InputStream = FileInputStream(file(path))

    override fun openWrite(path: String): OutputStream = try {
        FileOutputStream(file(path))
    } catch (e: java.io.FileNotFoundException) {
        // What Java calls a file it may not create, whatever the real reason was.
        throw StoreException(StoreException.Reason.CANNOT_WRITE, file(path).name, e)
    }

    override fun makeFolder(path: String) {
        val f = file(path)
        if (f.exists()) throw StoreException(StoreException.Reason.ALREADY_THERE, f.name)
        if (!f.mkdir()) throw StoreException(StoreException.Reason.CANNOT_MAKE_FOLDER, f.name)
    }

    override fun rename(from: String, to: String) {
        val target = file(to)
        if (target.exists()) throw StoreException(StoreException.Reason.ALREADY_THERE, target.name)
        // Fails across the line between internal storage and the SD card, which are two
        // filesystems; a move then falls back to copying and deleting.
        if (!file(from).renameTo(target)) throw StoreException(StoreException.Reason.CANNOT_RENAME, file(from).name)
    }

    override fun deleteFile(path: String) {
        val f = file(path)
        if (!f.delete() && f.exists()) throw StoreException(StoreException.Reason.CANNOT_DELETE, f.name)
    }

    override fun deleteFolder(path: String) {
        val f = file(path)
        if (!f.delete() && f.exists()) throw StoreException(StoreException.Reason.CANNOT_DELETE, f.name)
    }

    override fun setModified(path: String, time: Long) {
        if (time > 0) file(path).setLastModified(time)
    }

    private fun File.toEntry(loc: Loc) = Entry(
        loc = loc,
        isFolder = isDirectory,
        size = if (isDirectory) 0 else length(),
        modified = lastModified(),
    )

    companion object {
        const val ID = "phone"
    }
}
