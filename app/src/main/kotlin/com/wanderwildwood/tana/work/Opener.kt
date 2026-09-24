package com.wanderwildwood.tana.work

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handing a file on to another app: to open, to open with a chosen app, to share, to install.
 *
 * Only real files on the phone. A server's file is fetched into the cache first (see
 * [Job.Fetch]) and comes here as the copy.
 */
object Opener {

    /** False when nothing on the phone can take it; the screen says so. */
    fun hand(context: Context, files: List<File>, purpose: Purpose): Boolean {
        // A zip fetched to be looked inside is opened by this app, not handed on.
        if (files.isEmpty() || purpose == Purpose.BROWSE) return true
        val uris = files.map { uri(context, it) }
        val intent = when (purpose) {
            Purpose.SHARE -> share(files, uris)
            Purpose.OPEN, Purpose.OPEN_WITH, Purpose.INSTALL, Purpose.BROWSE -> {
                val file = files.first()
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uris.first(), Names.mime(file.name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val launched = when (purpose) {
            // A chooser every time for these two: "open with" is the whole point of the one,
            // and sharing without seeing where to would be a guess.
            Purpose.OPEN_WITH, Purpose.SHARE -> Intent.createChooser(intent, null)
            else -> intent
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launched)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun share(files: List<File>, uris: List<Uri>): Intent {
        val mimes = files.map { Names.mime(it.name) }.distinct()
        // One kind of thing is shared as that kind, so a photo goes to apps that take photos;
        // a mixture is shared as anything, and fewer apps will offer to take it.
        val mime = mimes.singleOrNull() ?: mimes.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        // The grant rides on ClipData; without it the receiving app is handed addresses it
        // is not allowed to open.
        intent.clipData = ClipData.newRawUri(null, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        return intent.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)

    /** Files fetched from a server a day or more ago; they were only ever there to be handed on. */
    fun tidyFetched(context: Context) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        File(context.cacheDir, "fetched").listFiles()?.forEach { folder ->
            if (folder.lastModified() < cutoff) folder.deleteRecursively()
        }
    }
}
