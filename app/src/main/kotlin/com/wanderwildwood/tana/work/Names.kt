package com.wanderwildwood.tana.work

import android.webkit.MimeTypeMap
import java.util.Locale

/**
 * Names: what a file is by its extension, and what to call a copy when the name is taken.
 */
object Names {

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        // A leading dot is a hidden file's name, not an extension: ".nomedia" has none.
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /**
     * "photo.jpg" → "photo (1).jpg", then "(2)", and so on, until [taken] says no. The number
     * goes before the extension so the copy still opens in the same app.
     */
    fun unique(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val ext = extension(name)
        val base = if (ext.isEmpty()) name else name.dropLast(ext.length + 1)
        var n = 1
        while (true) {
            val candidate = if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"
            if (!taken(candidate)) return candidate
            n++
        }
    }

    /**
     * A name the reader typed, or null when it cannot be one: blank, or carrying a slash,
     * or one of the two names every folder already has.
     */
    fun valid(typed: String): String? {
        val name = typed.trim()
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name.contains('/') || name.contains('\\') || name.contains('\u0000')) return null
        return name
    }

    /** What Android thinks the file is, for handing it to another app. */
    fun mime(name: String): String {
        val ext = extension(name)
        if (ext == "apk") return APK_MIME
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    const val APK_MIME = "application/vnd.android.package-archive"
}

/**
 * The kinds a search can narrow to. By extension, because that is all a server listing
 * tells you without opening every file, and it is what the reader means by "a document".
 */
enum class Kind(val extensions: Set<String>) {
    ANY(emptySet()),
    DOCUMENTS(setOf("pdf", "epub", "txt", "md", "doc", "docx", "odt", "rtf", "html", "htm", "mobi", "azw3", "fb2", "djvu", "cbz", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp")),
    IMAGES(setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "tif", "tiff", "dng", "nef", "cr2", "arw")),
    AUDIO(setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav", "wma", "amr", "mka", "aiff")),
    VIDEO(setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "wmv", "3gp", "ts", "mpg", "mpeg")),
    ARCHIVES(setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst")),
    APPS(setOf("apk", "apks", "xapk"));

    fun matches(name: String, isFolder: Boolean): Boolean =
        this == ANY || (!isFolder && Names.extension(name) in extensions)
}
