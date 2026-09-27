package com.wanderwildwood.tana.work

import android.content.Intent

/**
 * Another app asking for a file (`ACTION_GET_CONTENT`, or the older `ACTION_PICK`): what kinds it will take, and whether
 * it will take more than one. The browser then shows only folders and files of those kinds,
 * and choosing a file hands it back instead of opening it.
 */
data class Picking(val types: List<String>, val several: Boolean) {

    /** Whether a file of this name is one the asking app will take. */
    fun accepts(name: String): Boolean {
        val mime = Names.mime(name)
        return types.any { t ->
            t == "*/*" || t.equals(mime, ignoreCase = true) ||
                (t.endsWith("/*") && mime.startsWith(t.dropLast(1), ignoreCase = true))
        }
    }

    companion object {
        fun from(intent: Intent?): Picking? {
            if (intent?.action != Intent.ACTION_GET_CONTENT && intent?.action != Intent.ACTION_PICK) return null
            // The extra, when given, is the whole list; the type alone is the older way to ask.
            val listed = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.filter { it.isNotBlank() }.orEmpty()
            val types = listed.ifEmpty { listOfNotNull(intent.type?.takeIf { it.isNotBlank() }) }.ifEmpty { listOf("*/*") }
            return Picking(types, intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        }
    }
}
