package com.wanderwildwood.tana.work

import com.wanderwildwood.tana.store.Entry
import java.text.Collator
import java.util.Locale

enum class SortBy { NAME, DATE, SIZE, TYPE }

/**
 * How a folder is laid out. Folders always come first, whatever the order: on a list that
 * moves four rows at a time, a folder buried among two hundred photographs is a folder lost.
 *
 * The first press on an order picks the direction a person usually wants from it — A to Z,
 * newest first, largest first — and a second press on the same one turns it round.
 */
data class Sort(val by: SortBy = SortBy.NAME, val reversed: Boolean = false) {

    fun pressed(on: SortBy): Sort = if (on == by) copy(reversed = !reversed) else Sort(on, false)

    fun apply(entries: List<Entry>): List<Entry> {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
        val byName = Comparator<Entry> { a, b -> naturalCompare(a.name, b.name, collator) }
        val natural: Comparator<Entry> = when (by) {
            SortBy.NAME -> byName
            SortBy.DATE -> compareByDescending<Entry> { it.modified }.then(byName)
            SortBy.SIZE -> compareByDescending<Entry> { it.size }.then(byName)
            SortBy.TYPE -> compareBy<Entry> { Names.extension(it.name) }.then(byName)
        }
        val ordered = if (reversed) natural.reversed() else natural
        return entries.sortedWith(compareByDescending<Entry> { it.isFolder }.then(ordered))
    }

    companion object {
        /**
         * "Track 2" before "Track 10". A plain string order puts 10 before 2, which on an
         * audiobook's chapters or a camera's photographs is the wrong order every time.
         */
        fun naturalCompare(a: String, b: String, collator: Collator): Int {
            val x = chunks(a)
            val y = chunks(b)
            for (i in 0 until minOf(x.size, y.size)) {
                val p = x[i]
                val q = y[i]
                val c = if (p.isNotEmpty() && q.isNotEmpty() && p[0].isDigit() && q[0].isDigit()) {
                    val pn = p.trimStart('0')
                    val qn = q.trimStart('0')
                    if (pn.length != qn.length) pn.length - qn.length else pn.compareTo(qn)
                } else {
                    collator.compare(p, q)
                }
                if (c != 0) return c
            }
            return x.size - y.size
        }

        private fun chunks(s: String): List<String> {
            if (s.isEmpty()) return listOf("")
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var digits = s[0].isDigit()
            for (ch in s) {
                if (ch.isDigit() != digits) {
                    out += cur.toString()
                    cur.clear()
                    digits = ch.isDigit()
                }
                cur.append(ch)
            }
            out += cur.toString()
            return out
        }
    }
}
