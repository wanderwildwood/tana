package com.wanderwildwood.tana.work

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ThumbnailUtils
import android.util.LruCache
import android.util.Size
import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.store.LocalStore
import com.wanderwildwood.tana.store.Stores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * The small square pictures of the grid view: a photograph or a video on the phone, cut square
 * from its middle. Made once and kept, in memory while the app runs and as small files in the
 * app's cache between runs, so a page of the grid that has been seen comes back at once.
 *
 * Only for what is on the phone. A file on a server would have to be fetched whole to be drawn
 * small, a folder of photographs at a time; those keep their icon and name.
 */
object Thumbs {

    /** A side, in pixels: a third of the Kompakt's 480 is 160, so this stays sharp there. */
    private const val SIDE = 240
    private const val KEEP_FILES = 600

    private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    /** What could not be drawn, so it is not tried again each time its page comes round. */
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())
    /** Two at a time: a page of twelve fills steadily and the phone stays answerable. */
    private val turns = Semaphore(2)
    @Volatile private var tidied = false

    fun can(entry: Entry): Boolean {
        if (entry.isFolder || entry.loc.store != LocalStore.ID) return false
        val mime = Names.mime(entry.name)
        return mime.startsWith("image/") || mime.startsWith("video/")
    }

    fun isVideo(entry: Entry): Boolean = Names.mime(entry.name).startsWith("video/")

    /** Changes when the file does, so a photograph edited in place is drawn again. */
    internal fun key(path: String, modified: Long, size: Long): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$path|$modified|$size".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun key(entry: Entry) = key(entry.loc.path, entry.modified, entry.size)

    /** The picture if it is already in memory; never blocks. */
    fun ready(entry: Entry): Bitmap? = memory.get(key(entry))

    /** True once [load] has tried and could not draw it. */
    fun cannot(entry: Entry): Boolean = key(entry) in failed

    /** The picture, read from the cache or made; null when it cannot be drawn. */
    suspend fun load(context: Context, entry: Entry): Bitmap? {
        val key = key(entry)
        memory.get(key)?.let { return it }
        if (key in failed) return null
        return turns.withPermit {
            withContext(Dispatchers.IO) {
                memory.get(key) ?: run {
                    val dir = File(context.cacheDir, "thumbs")
                    val kept = File(dir, "$key.jpg")
                    val bitmap = runCatching { BitmapFactory.decodeFile(kept.path) }.getOrNull()
                        ?: runCatching { make(Stores.phone.file(entry.loc.path), isVideo(entry)) }.getOrNull()
                            ?.also { made -> keep(dir, kept, made) }
                    if (bitmap == null) failed.add(key) else memory.put(key, bitmap)
                    bitmap
                }
            }
        }
    }

    private fun make(file: File, video: Boolean): Bitmap {
        if (video) return square(ThumbnailUtils.createVideoThumbnail(file, Size(SIDE * 2, SIDE * 2), null))
        // ImageDecoder turns a photo the way its camera said to, which BitmapFactory does not.
        val whole = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val (w, h) = fit(info.size.width, info.size.height, SIDE)
            decoder.setTargetSize(w, h)
            // Not a hardware bitmap: it is cut square and written to the cache.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return square(whole)
    }

    /** [width] x [height] scaled so its shorter side is [side]; never enlarged, never under a pixel. */
    internal fun fit(width: Int, height: Int, side: Int): Pair<Int, Int> {
        val shorter = minOf(width, height)
        if (shorter <= side) return maxOf(width, 1) to maxOf(height, 1)
        val scale = side.toDouble() / shorter
        return maxOf((width * scale).toInt(), 1) to maxOf((height * scale).toInt(), 1)
    }

    private fun square(whole: Bitmap): Bitmap {
        val side = minOf(whole.width, whole.height, SIDE)
        val cut = Bitmap.createBitmap(whole, (whole.width - side) / 2, (whole.height - side) / 2, side, side)
        if (cut !== whole) whole.recycle()
        return cut
    }

    private fun keep(dir: File, kept: File, bitmap: Bitmap) {
        runCatching {
            dir.mkdirs()
            if (!tidied) {
                tidied = true
                // The oldest go once there are more than a few folders' worth.
                val all = dir.listFiles().orEmpty()
                if (all.size > KEEP_FILES) all.sortedBy { it.lastModified() }.take(all.size - KEEP_FILES / 2).forEach { it.delete() }
            }
            val part = File(dir, kept.name + ".part")
            part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            part.renameTo(kept)
        }
    }
}
