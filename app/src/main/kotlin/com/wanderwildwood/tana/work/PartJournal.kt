package com.wanderwildwood.tana.work

import android.content.Context
import com.wanderwildwood.tana.store.Loc
import com.wanderwildwood.tana.store.Stores
import java.util.UUID

/**
 * The partial copies a transfer has made and not yet finished with, so ones a killed process
 * left behind can be removed.
 *
 * A copy is written as `name.part` and renamed when whole, and a failure or a cancel deletes the
 * part. A process that is killed does neither, and the part stays -- in the folder it was going
 * to, on the phone or on a server -- where nothing would ever look for it again.
 *
 * Each note carries the process that made it, and only notes from an earlier process are swept:
 * a transfer running now is never touched. And a part that has become the only whole copy -- the
 * old file already gone, the rename still to come -- is struck off before that happens, so it is
 * never swept either.
 */
object PartJournal {
    private const val PREFS = "tana-parts"
    private const val KEY = "parts"
    private const val SEP = "\u0001"
    private val thisProcess = UUID.randomUUID().toString()

    @Synchronized
    fun started(context: Context, loc: Loc) = edit(context) { it += entry(loc) }

    @Synchronized
    fun done(context: Context, loc: Loc) = edit(context) { it -= entry(loc) }

    /** Removes what earlier processes left. Best effort: a store that cannot be reached keeps its notes. */
    @Synchronized
    fun sweepLeftovers(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = prefs.getStringSet(KEY, emptySet()).orEmpty()
        val keep = mutableSetOf<String>()
        for (e in all) {
            val parts = e.split(SEP)
            if (parts.size != 3) continue
            val (process, store, path) = parts
            if (process == thisProcess) {
                keep += e
                continue
            }
            val removed = runCatching {
                val s = Stores.get(store)
                removeQuietly(s, path)
                true
            }.getOrDefault(false)
            if (!removed) keep += e
        }
        prefs.edit().putStringSet(KEY, keep).apply()
    }

    private fun removeQuietly(store: com.wanderwildwood.tana.store.Store, path: String) {
        val entry = store.stat(path) ?: return
        if (entry.isFolder) {
            store.list(path).forEach { removeQuietly(store, it.loc.path) }
            store.deleteFolder(path)
        } else {
            store.deleteFile(path)
        }
    }

    private fun entry(loc: Loc) = listOf(thisProcess, loc.store, loc.path).joinToString(SEP)

    private fun edit(context: Context, change: (MutableSet<String>) -> Unit) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        change(set)
        // Written at once: a note that is still in memory when the process dies is no note.
        prefs.edit().putStringSet(KEY, set).commit()
    }
}
