package com.wanderwildwood.tana.work

import android.content.Context
import java.security.MessageDigest

/**
 * The page each PDF was left at, so it opens there again. Kept on the phone, for the
 * [KEEP] read most lately; an older one is forgotten and opens at its first page.
 *
 * A PDF is known by its name and its size in bytes, not by where it is. The same file
 * arrives by different roads — from a folder here, as an email's attachment, from a
 * download — and an attachment comes with a new address every time it is opened, so an
 * address would hardly ever be met twice. Two different PDFs with the same name and the same
 * size, to the byte, are the one case this gets wrong, and they share a page. The name is
 * kept only as a hash, so the list of what was read is not lying about in the app's settings.
 */
class PageMarks(context: Context) {

    private val prefs = context.getSharedPreferences("pdf_pages", Context.MODE_PRIVATE)

    /** The page [key] was left at, counted from 0, or null if it was never read or is forgotten. */
    fun get(key: String): Int? = prefs.getString(key, null)?.let(::decode)?.first

    fun put(key: String, page: Int) {
        val now = System.currentTimeMillis()
        val read = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let(::decode)?.let { k to it.second } }.toMap() + (key to now)
        val edit = prefs.edit().putString(key, encode(page, now))
        overflow(read, KEEP).forEach { edit.remove(it) }
        edit.apply()
    }

    companion object {
        const val KEEP = 100

        /** What a PDF is known by: its name and its size, hashed. See the class. */
        fun key(name: String, size: Long): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$name\u0000$size".toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** A page and when it was read, as one value: "12@1760000000000". */
        fun encode(page: Int, at: Long): String = "$page@$at"

        fun decode(value: String): Pair<Int, Long>? {
            val page = value.substringBefore('@').toIntOrNull() ?: return null
            val at = value.substringAfter('@', "").toLongOrNull() ?: return null
            return if (page >= 0) page to at else null
        }

        /** Of PDFs and when each was last read, the ones past the [keep] most lately read. */
        fun overflow(read: Map<String, Long>, keep: Int): List<String> =
            read.entries.sortedByDescending { it.value }.drop(keep).map { it.key }
    }
}
