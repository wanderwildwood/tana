package com.wanderwildwood.tana.work

import android.content.Intent

/** What the asking app wants: a file, a folder, or a place to put a new file. */
enum class Ask { FILE, TREE, SAVE }

/**
 * Another app asking for a file (`ACTION_GET_CONTENT`, `ACTION_OPEN_DOCUMENT`, the older
 * `ACTION_PICK`), for a folder (`ACTION_OPEN_DOCUMENT_TREE`) or for a place to save one
 * (`ACTION_CREATE_DOCUMENT`): what kinds of file it will take, whether it will take more than
 * one, and what grant it wants on the answer. The browser then shows only folders and files of
 * those kinds, and choosing hands the answer back instead of opening it.
 *
 * [title] is the name a saved file is first offered under. [write] and [persist] are what the
 * answer's address is granted with; see [grantFlags].
 */
data class Picking(
    val types: List<String>,
    val several: Boolean,
    val ask: Ask = Ask.FILE,
    val title: String = "",
    val write: Boolean = false,
    val persist: Boolean = false,
    val prefix: Boolean = false,
) {

    /** Whether a file of this name is one the asking app will take. */
    fun accepts(name: String): Boolean = acceptsMime(Names.mime(name))

    fun acceptsMime(mime: String): Boolean = types.any { matches(it, mime) }

    /** The `FLAG_GRANT_*` flags the answer is returned with. */
    fun grantFlags(): Int {
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (write) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (persist) flags = flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        if (prefix) flags = flags or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        return flags
    }

    companion object {
        fun matches(wanted: String, mime: String): Boolean =
            wanted == "*/*" || wanted.equals(mime, ignoreCase = true) ||
                (wanted.endsWith("/*") && mime.startsWith(wanted.dropLast(1), ignoreCase = true))

        /**
         * The pure half of [from]. A folder or a new file is always given read and write, as
         * Android's own picker gives them; a file is given write only if the asking app said it
         * would write. Only the "document" requests can be kept across a restart.
         */
        fun of(
            action: String?,
            type: String?,
            mimeTypes: List<String>,
            several: Boolean,
            title: String?,
            flags: Int,
        ): Picking? {
            val listed = mimeTypes.filter { it.isNotBlank() }
            // The extra, when given, is the whole list; the type alone is the older way to ask.
            val types = listed.ifEmpty { listOfNotNull(type?.takeIf { it.isNotBlank() }) }.ifEmpty { listOf("*/*") }
            val wantsWrite = flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
            return when (action) {
                Intent.ACTION_GET_CONTENT, Intent.ACTION_PICK ->
                    Picking(types, several, Ask.FILE, write = wantsWrite)
                Intent.ACTION_OPEN_DOCUMENT ->
                    Picking(types, several, Ask.FILE, write = wantsWrite, persist = true)
                Intent.ACTION_OPEN_DOCUMENT_TREE ->
                    Picking(emptyList(), false, Ask.TREE, write = true, persist = true, prefix = true)
                Intent.ACTION_CREATE_DOCUMENT ->
                    Picking(types, false, Ask.SAVE, title.orEmpty(), write = true, persist = true)
                else -> null
            }
        }

        fun from(intent: Intent?): Picking? {
            intent ?: return null
            return of(
                intent.action,
                intent.type,
                intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList().orEmpty(),
                intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
                intent.getStringExtra(Intent.EXTRA_TITLE),
                intent.flags,
            )
        }
    }
}
