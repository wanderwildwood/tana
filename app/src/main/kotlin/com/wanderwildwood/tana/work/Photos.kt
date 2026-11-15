package com.wanderwildwood.tana.work

import android.os.Environment
import java.io.File

/** Where the phone's camera keeps what it takes. */
object Photos {

    /** The camera's folder on this phone, or null when it has none. */
    fun folder(): File? = folderUnder(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM))

    /**
     * The folder under [dcim] that holds the photographs. A camera keeps them one folder down
     * (DCIM/Camera), and DCIM itself is then empty but for that folder: a grid of one folder is
     * not a grid of photographs, so a folder with no files and exactly one folder in it is
     * stepped through. Anything else (photographs in DCIM itself, two cameras' folders side by
     * side) stops where the choice is the reader's. Hidden names (.thumbnails) do not count.
     */
    fun folderUnder(dcim: File): File? {
        if (!dcim.isDirectory) return null
        var at = dcim
        // No camera nests deeper than this, and a loop of links must not be followed for ever.
        repeat(3) {
            val inside = at.listFiles()?.filter { !it.name.startsWith('.') } ?: return at
            val only = inside.singleOrNull()?.takeIf { it.isDirectory } ?: return at
            at = only
        }
        return at
    }
}
