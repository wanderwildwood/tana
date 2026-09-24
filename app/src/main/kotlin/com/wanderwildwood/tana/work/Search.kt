package com.wanderwildwood.tana.work

import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.store.Loc
import com.wanderwildwood.tana.store.Store
import com.wanderwildwood.tana.store.StoreException
import java.util.Locale

/**
 * Finds things by name below a folder, walking it. The words typed must all appear in the
 * name, in any order and either case: "tolkien hobbit" finds "The Hobbit - Tolkien.epub".
 *
 * A folder that cannot be read is stepped over rather than ending the search: one locked
 * folder among a thousand should not hide what is in the other nine hundred and ninety-nine.
 */
class Search(
    private val resolve: (String) -> Store,
    private val isCancelled: () -> Boolean = { false },
) {
    /** Results arrive in batches through [onFound] as they are found, rather than all at the end. */
    fun run(
        roots: List<Loc>,
        words: String,
        kind: Kind,
        showHidden: Boolean,
        limit: Int = 300,
        onFound: (List<Entry>) -> Unit,
    ): Int {
        val terms = words.lowercase(Locale.getDefault()).split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty() && kind == Kind.ANY) return 0
        var found = 0
        val batch = mutableListOf<Entry>()

        fun walk(dir: Loc) {
            if (isCancelled() || found >= limit) return
            val children = try {
                resolve(dir.store).list(dir.path)
            } catch (e: StoreException) {
                return
            }
            val folders = mutableListOf<Loc>()
            for (child in children) {
                if (!showHidden && child.name.startsWith('.')) continue
                val name = child.name.lowercase(Locale.getDefault())
                if (terms.all { it in name } && kind.matches(child.name, child.isFolder)) {
                    batch += child
                    found++
                    if (found >= limit) break
                }
                if (child.isFolder) folders += child.loc
            }
            if (batch.isNotEmpty()) {
                onFound(batch.toList())
                batch.clear()
            }
            // Android keeps every app's private files under Android/data and Android/obb,
            // which it will not let any other app into; walking it finds nothing and costs
            // a long wait on a full phone.
            folders.filterNot { it.name == "Android" && it.parent?.let { p -> isVolumeRoot(p) } == true }
                .forEach { walk(it) }
        }

        roots.forEach { walk(it) }
        return found
    }

    private fun isVolumeRoot(loc: Loc) =
        loc.path == "storage/emulated/0" || (loc.path.startsWith("storage/") && loc.path.count { it == '/' } == 1)
}
