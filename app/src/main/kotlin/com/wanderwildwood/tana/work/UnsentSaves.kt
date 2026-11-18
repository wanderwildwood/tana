package com.wanderwildwood.tana.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import com.wanderwildwood.tana.MainActivity
import com.wanderwildwood.tana.R
import com.wanderwildwood.tana.store.Stores
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A save another app made to a server's file (see ServersProvider) that did not get there:
 * the server could not be reached, or its file changed since it was read and was not
 * replaced. The app's close has already returned, so what it wrote exists only here. It is
 * kept where the reader can find it -- the phone's Download folder, in [FOLDER] -- under a
 * name that says it was not sent, and a notification says so.
 */
object UnsentSaves {

    const val FOLDER = "Unsent saves"
    private const val CHANNEL = "unsent"

    /**
     * Keeps [local], the copy the app wrote to [name], and tells the reader why it did not reach
     * the server. True when the copy is safe somewhere other than [local], which may then go.
     */
    fun keep(context: Context, local: File, name: String, why: String): Boolean {
        val kept = keptName(name, SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date()))
        val where = runCatching { intoDownloads(context, local, kept) }
            .onFailure { warn("could not keep an unsent save in Download", it) }
            .getOrNull()
            ?: runCatching { intoApp(context, local, kept) }
                .onFailure { warn("could not keep an unsent save in the app", it) }
                .getOrNull()
        runCatching { tell(context, name, why, where) }
            .onFailure { warn("could not tell of an unsent save", it) }
        return where != null
    }

    /**
     * A failure, for the log. A release build names only its kind: the full exception carries
     * the kept file's name.
     */
    private fun warn(message: String, e: Throwable) {
        if (com.wanderwildwood.tana.BuildConfig.DEBUG) {
            android.util.Log.w(TAG, message, e)
        } else {
            android.util.Log.w(TAG, "$message: ${e.javaClass.simpleName}")
        }
    }

    /**
     * The kept copy as a path on the phone, for opening its folder with the copy picked out:
     * [where] as [keep] gives it, under Download or inside the app.
     */
    private fun fileOf(where: String): File {
        val downloads = Environment.DIRECTORY_DOWNLOADS + "/"
        return if (where.startsWith(downloads)) {
            File(Stores.phone.file(Volumes.downloads().path), where.removePrefix(downloads))
        } else {
            File(where)
        }
    }

    /**
     * "notes.txt" → "notes (not sent 2026-11-17 14.05).txt": plainly not the server's file, and
     * still opening in the same app. Dots rather than a colon, which some storage refuses.
     */
    fun keptName(name: String, stamp: String): String {
        val ext = Names.extension(name)
        val base = if (ext.isEmpty()) name else name.dropLast(ext.length + 1)
        val marked = "$base (not sent $stamp)"
        return if (ext.isEmpty()) marked else "$marked.${name.takeLast(ext.length)}"
    }

    /** Into Download/[FOLDER] through MediaStore, which needs no permission. Where it went, as the reader sees it. */
    private fun intoDownloads(context: Context, local: File, kept: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, kept)
            put(MediaStore.MediaColumns.MIME_TYPE, Names.mime(kept).takeIf { it != "*/*" } ?: "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            val out = resolver.openOutputStream(uri) ?: throw java.io.IOException("no stream for $kept")
            out.use { o -> local.inputStream().use { it.copyTo(o, 64 * 1024) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        // MediaStore numbers a name already taken; the name it settled on is the one to give.
        val shown = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: kept
        return Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/" + shown
    }

    /**
     * Failing Download (a full phone), into the app's own storage, which is not cleared as its
     * cache is. The notification opens this folder in the app, where the copy can be moved or
     * shared out once there is room.
     */
    private fun intoApp(context: Context, local: File, kept: String): String {
        val folder = File(context.filesDir, "unsent").apply { mkdirs() }
        val file = File(folder, Names.unique(kept) { File(folder, it).exists() })
        local.copyTo(file)
        if (file.length() != local.length()) {
            file.delete()
            throw java.io.IOException("short copy of $kept")
        }
        return file.path
    }

    private fun tell(context: Context, name: String, why: String, where: String?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.unsent_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val id = NOTIFICATION_BASE + (System.currentTimeMillis() % 100_000).toInt()
        // Opens the folder the copy is in, with the copy picked out (Asked.reveal).
        val show = Intent(context, MainActivity::class.java)
        if (where != null) {
            show.action = Asked.SHOW_FILE
            show.putExtra(Asked.EXTRA_PATH, fileOf(where).absolutePath)
        }
        val open = PendingIntent.getActivity(
            context, id, show, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when {
            where == null -> context.getString(R.string.unsent_not_kept, why)
            where.startsWith(Environment.DIRECTORY_DOWNLOADS + "/") -> context.getString(R.string.unsent_kept, why, where)
            else -> context.getString(R.string.unsent_kept_app, why)
        }
        // On the lock screen, only that a save did not go: not the file's name or where it is.
        val shown = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(context.getString(R.string.unsent_public))
            .build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(context.getString(R.string.unsent_title, name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(shown)
            .build()
        // One per save, so a second failure does not hide the first.
        manager.notify(id, notification)
    }

    private const val TAG = "tana.unsent"

    /** Clear of TransferService's own notification (1). */
    private const val NOTIFICATION_BASE = 1000
}
