package com.wanderwildwood.tana.work

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.store.LocalStore
import com.wanderwildwood.tana.store.Stores
import java.io.File

/**
 * A picture looked at before it is handed to the app that asked for one, so the reader can
 * see it is the right one rather than trust a file name like IMG_2041.jpg.
 */
object Previews {

    /** Twice the panel's long side: sharp enough, and a 50-megapixel photo still fits in memory. */
    private const val LONGEST = 1600

    fun isImage(name: String): Boolean = Names.mime(name).startsWith("image/")

    /**
     * The picture, and the file it was read from. A server's is fetched into the same place
     * [Job.Fetch] puts things, so choosing it afterwards hands on this copy instead of
     * fetching it twice. Blocks; never on the main thread.
     */
    fun load(context: Context, entry: Entry, isCancelled: () -> Boolean): Pair<File, Bitmap> {
        val file = if (entry.loc.store == LocalStore.ID) {
            Stores.phone.file(entry.loc.path)
        } else {
            File(context.cacheDir, "fetched/${System.currentTimeMillis()}/${entry.name}")
                .also { Transfer(Stores::get, isCancelled).fetch(entry, it) }
        }
        // ImageDecoder turns a photo the way its camera said to, which BitmapFactory does not.
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (longest / (sample * 2) >= LONGEST) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
        return file to bitmap
    }
}
