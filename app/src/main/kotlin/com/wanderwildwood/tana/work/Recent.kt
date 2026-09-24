package com.wanderwildwood.tana.work

import android.content.Context
import android.provider.MediaStore
import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.store.LocalStore
import com.wanderwildwood.tana.store.Loc
import com.wanderwildwood.tana.store.Stores
import java.io.File

/**
 * Files on the phone changed in the last month, newest first.
 *
 * Asked of Android's own index of storage rather than found by walking every folder, which on
 * a full SD card would take long enough to read as broken. The index can lag a file that
 * arrived a moment ago, and it knows nothing about the servers; a search does both.
 */
object Recent {
    private const val LIMIT = 100
    private const val DAYS = 30

    fun list(context: Context, showHidden: Boolean): List<Entry> {
        val since = System.currentTimeMillis() / 1000 - DAYS * 24 * 60 * 60
        val projection = arrayOf(MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.DATE_MODIFIED)
        val out = mutableListOf<Entry>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            projection,
            // Files only: the index has a row for every folder too, and a folder is "changed"
            // whenever anything inside it is.
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} >= ? AND ${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL",
            arrayOf(since.toString()),
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val data = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            while (cursor.moveToNext() && out.size < LIMIT) {
                val path = cursor.getString(data) ?: continue
                val file = File(path)
                if (!showHidden && path.split('/').any { it.startsWith('.') }) continue
                if (!file.isFile) continue
                out += Entry(
                    loc = Loc(LocalStore.ID, Stores.phone.pathOf(file)),
                    isFolder = false,
                    size = file.length(),
                    modified = file.lastModified(),
                )
            }
        }
        return out
    }
}
