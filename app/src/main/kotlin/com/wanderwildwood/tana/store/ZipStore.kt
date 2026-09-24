package com.wanderwildwood.tana.store

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * A zip archive, opened like a folder. Read only: copying out of it is extracting, and
 * everything that would change it is refused, because rewriting an archive in place is a
 * different job from managing files and a failed one loses the lot.
 *
 * Entry names that climb out of the archive (`../`) or start at the root are left out. An
 * archive made to write outside the folder it is extracted into is an old trick; here such
 * an entry simply is not listed, so there is nothing to copy.
 */
class ZipStore(val archive: File, override val id: String) : Store {
    private val zip = try {
        ZipFile(archive)
    } catch (e: Exception) {
        throw StoreException(StoreException.Reason.FOLDER_UNREADABLE, archive.name, e)
    }

    private val entries = HashMap<String, ZipEntry>()
    private val folders = HashMap<String, MutableList<Entry>>().apply { put("", mutableListOf()) }
    private val stats = HashMap<String, Entry>()

    init {
        for (ze in zip.entries()) {
            val path = clean(ze.name) ?: continue
            val isFolder = ze.isDirectory
            addParents(path)
            if (isFolder) {
                folder(path, ze.time)
            } else if (path !in stats) {
                entries[path] = ze
                add(Entry(Loc(id, path), isFolder = false, size = ze.size.coerceAtLeast(0), modified = ze.time))
            }
        }
    }

    private fun addParents(path: String) {
        var parent = path.substringBeforeLast('/', "")
        val chain = mutableListOf<String>()
        while (parent.isNotEmpty()) {
            chain += parent
            parent = parent.substringBeforeLast('/', "")
        }
        chain.asReversed().forEach { folder(it, 0) }
    }

    private fun folder(path: String, time: Long) {
        if (path in stats) return
        folders.getOrPut(path) { mutableListOf() }
        add(Entry(Loc(id, path), isFolder = true, size = 0, modified = time.coerceAtLeast(0)))
    }

    private fun add(entry: Entry) {
        stats[entry.loc.path] = entry
        folders.getOrPut(entry.loc.path.substringBeforeLast('/', "")) { mutableListOf() } += entry
    }

    override fun list(path: String): List<Entry> =
        folders[path]?.toList() ?: throw StoreException(StoreException.Reason.GONE, path.substringAfterLast('/'))

    override fun stat(path: String): Entry? =
        if (path.isEmpty()) Entry(Loc(id, ""), isFolder = true, size = 0, modified = archive.lastModified()) else stats[path]

    override fun openRead(path: String): InputStream {
        val ze = entries[path] ?: throw StoreException(StoreException.Reason.GONE, path.substringAfterLast('/'))
        return zip.getInputStream(ze)
    }

    private fun refuse(): Nothing = throw StoreException(StoreException.Reason.READ_ONLY, archive.name)

    override fun openWrite(path: String): OutputStream = refuse()
    override fun makeFolder(path: String) = refuse()
    override fun rename(from: String, to: String) = refuse()
    override fun deleteFile(path: String) = refuse()
    override fun deleteFolder(path: String) = refuse()

    fun close() = runCatching { zip.close() }

    companion object {
        const val PREFIX = "zip:"

        fun idFor(archive: File) = PREFIX + archive.absolutePath

        /** A zip entry's name as a path here, or null when it must not be one. */
        fun clean(name: String): String? {
            val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.isEmpty() || parts.any { it == ".." }) return null
            if (name.startsWith("/") || name.startsWith("\\")) return null
            return parts.joinToString("/")
        }
    }
}
