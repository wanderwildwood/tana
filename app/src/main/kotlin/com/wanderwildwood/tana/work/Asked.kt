package com.wanderwildwood.tana.work

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.wanderwildwood.tana.store.LocalStore
import com.wanderwildwood.tana.store.Loc
import com.wanderwildwood.tana.store.Stores
import java.io.File

/**
 * Where another app asked to be shown, when it opened this one as the phone's file manager:
 * "show the downloads", or "open this folder". Null for an ordinary launch, which opens on
 * the start page.
 *
 * A folder arrives as a plain path, or as the address Android's own file picker gives it —
 * `primary:Download` on internal storage, `74C4-3B51:Music` on a card — which is turned back
 * into the path it stands for. Anything that cannot be, opens on the start page rather than
 * somewhere wrong.
 */
object Asked {
    private const val EXTERNAL = "com.android.externalstorage.documents"
    private const val DOWNLOADS = "com.android.providers.downloads.documents"

    fun place(intent: Intent?): Loc? {
        intent ?: return null
        if (intent.action == DownloadManagerViewDownloads) return Volumes.downloads()
        if (intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        val file = when (uri.scheme) {
            "file" -> uri.path?.let(::File)
            "content" -> fromDocument(uri)
            else -> null
        } ?: return null
        if (!file.isDirectory) return null
        return Loc(LocalStore.ID, Stores.phone.pathOf(file))
    }

    /** What [reveal] found: the folder to open and the name of the file to show in it. */
    data class Reveal(val folder: Loc, val name: String)

    /**
     * `com.wanderwildwood.tana.action.SHOW_FILE` with the file's absolute path as the string
     * extra `path`, or a `file://` address as the data: open the file's folder, with the file
     * found, paged to and selected. Null for any other intent, or a path with no folder to
     * open (relative, or the root).
     */
    fun reveal(intent: Intent?): Reveal? {
        intent ?: return null
        if (intent.action != SHOW_FILE) return null
        val raw = intent.getStringExtra(EXTRA_PATH)
            ?: intent.data?.takeIf { it.scheme == "file" }?.path
        return revealPath(raw)
    }

    fun revealPath(raw: String?): Reveal? {
        if (raw.isNullOrBlank() || !raw.startsWith("/")) return null
        // /sdcard and the like are links to the real place; the folder listing names the real one.
        var file = File(raw).normalize()
        runCatching { File(raw).canonicalFile }.getOrNull()?.takeIf { it.parentFile != null }?.let { file = it }
        val parent = file.parentFile ?: return null
        if (file.name.isEmpty()) return null
        return Reveal(Loc(LocalStore.ID, Stores.phone.pathOf(parent)), file.name)
    }

    /** Where a file is in a list of names: its own spelling first, else the same ignoring case; -1 if absent. */
    fun indexOfName(names: List<String>, name: String): Int {
        val exact = names.indexOf(name)
        return if (exact >= 0) exact else names.indexOfFirst { it.equals(name, ignoreCase = true) }
    }

    const val SHOW_FILE = "com.wanderwildwood.tana.action.SHOW_FILE"
    const val EXTRA_PATH = "path"

    private fun fromDocument(uri: Uri): File? {
        return when (uri.authority) {
            DOWNLOADS -> File(Stores.phone.file(Volumes.downloads().path).path)
            EXTERNAL -> {
                val id = runCatching {
                    if (DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(null, uri)) {
                        DocumentsContract.getTreeDocumentId(uri)
                    } else {
                        DocumentsContract.getDocumentId(uri)
                    }
                }.getOrNull() ?: return null
                fromDocumentId(id)
            }
            else -> null
        }
    }

    /** `primary:Music/Albums` → /storage/emulated/0/Music/Albums; `74C4-3B51:x` → /storage/74C4-3B51/x. */
    fun fromDocumentId(id: String): File? {
        val volume = id.substringBefore(':', "")
        // Internal storage, or a card's volume id; anything else is some other provider's
        // numbering and not a place on disk.
        if (volume != "primary" && !Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}").matches(volume)) return null
        val below = id.substringAfter(':')
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (below.isEmpty()) File(root) else File(root, below)
    }

    /** DownloadManager.ACTION_VIEW_DOWNLOADS, spelt out so this file needs nothing else. */
    const val DownloadManagerViewDownloads = "android.intent.action.VIEW_DOWNLOADS"
}
