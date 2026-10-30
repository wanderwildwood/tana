package com.wanderwildwood.tana.work

import java.io.File

/**
 * Which paths another app may be handed. Anything a picked file or folder is asked for by
 * name goes through here first: only shared storage (the phone's and the SD card's), never
 * another app's own folders on it, and never by way of `..` or a link out.
 */
object PathGuard {

    /**
     * The real path of [path] when it lies at or below one of [roots], else null. [roots] are
     * the tops of shared storage. The path must be absolute, hold no `..` and no NUL; it is
     * then made canonical (links followed) and checked again, so a link inside storage that
     * leads out of it is refused too.
     */
    fun resolve(path: String, roots: List<String>): String? {
        if (path.isEmpty() || path[0] != '/' || path.contains('\u0000')) return null
        if (path.split('/').any { it == ".." }) return null
        val real = try {
            File(path).canonicalPath
        } catch (e: java.io.IOException) {
            return null
        }
        for (root in roots) {
            val top = try { File(root).canonicalPath } catch (e: java.io.IOException) { continue }
            if (within(real, top) && !private(real, top)) return real
        }
        return null
    }

    /**
     * Whether a whole folder may be handed to another app with read and write access that
     * lasts: any folder inside shared storage except the top of it and the Download folder, as
     * Android's own picker refuses them (the top is everything, Download is where other apps'
     * files land). Names compare without regard to case, as shared storage does.
     */
    fun mayGrantFolder(path: String, roots: List<String>): Boolean {
        val real = resolve(path, roots) ?: return false
        for (root in roots) {
            val top = try { File(root).canonicalPath } catch (e: java.io.IOException) { continue }
            if (!within(real, top)) continue
            if (real.equals(top, ignoreCase = true)) return false
            if (real.equals(File(top, "Download").path, ignoreCase = true)) return false
            if (real.equals(File(top, "Downloads").path, ignoreCase = true)) return false
        }
        return true
    }

    /** True for [path] itself and anything below it; "/a/bc" is not below "/a/b". */
    fun within(path: String, top: String): Boolean =
        path == top || path.startsWith(if (top.endsWith("/")) top else "$top/")

    /** Another app's own folders, which sit under shared storage but are not for sharing. */
    private fun private(real: String, top: String): Boolean =
        listOf("android/data", "android/obb").any { within(real.lowercase(), File(top, it).path.lowercase()) }
}
