package com.wanderwildwood.tana.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.wanderwildwood.tana.MainActivity
import com.wanderwildwood.tana.R
import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.store.LocalStore
import com.wanderwildwood.tana.store.Loc
import com.wanderwildwood.tana.store.Stores
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** What a fetched file is for, once it is on the phone. */
enum class Purpose { OPEN, OPEN_WITH, SHARE, INSTALL, BROWSE }

/** One piece of work for the service. */
sealed interface Job {
    data class Paste(val sources: List<Entry>, val dest: Loc, val mode: Mode, val clash: Clash) : Job
    data class Delete(val entries: List<Entry>) : Job
    data class Fetch(val entries: List<Entry>, val purpose: Purpose) : Job
    data class Compress(val sources: List<Entry>, val dest: Loc, val name: String) : Job
    /** An archive's contents beside it: its one top folder as it is, or several things in a folder named after it. */
    data class Extract(val archive: Entry, val dest: Loc) : Job
}

sealed interface Work {
    data object Idle : Work
    data class Running(val job: Job, val progress: Progress?) : Work
    data class Finished(val job: Job, val outcome: Outcome, val fetched: List<File> = emptyList()) : Work
    data class Failed(val job: Job, val problem: Throwable) : Work
    data class Stopped(val job: Job) : Work
}

/**
 * The one piece of work under way, where the screens can see it.
 *
 * One at a time. A second copy started while the first is running would halve the speed of
 * both and double the ways the two could trip over each other's files, for no gain the
 * reader would notice on a phone.
 */
object Transfers {
    private val _work = MutableStateFlow<Work>(Work.Idle)
    val work: StateFlow<Work> = _work

    private val cancelled = AtomicBoolean(false)
    @Volatile private var pending: Job? = null

    /** False when something is already running; the screen says so rather than queueing. */
    fun start(context: Context, job: Job): Boolean {
        synchronized(this) {
            if (_work.value is Work.Running) return false
            cancelled.set(false)
            pending = job
            _work.value = Work.Running(job, null)
        }
        context.startForegroundService(Intent(context, TransferService::class.java))
        return true
    }

    fun stop() = cancelled.set(true)

    /** The screen has shown the result; back to nothing. */
    fun seen() {
        synchronized(this) {
            if (_work.value !is Work.Running) _work.value = Work.Idle
        }
    }

    internal fun take(): Job? = synchronized(this) { pending.also { pending = null } }
    internal fun isCancelled() = cancelled.get()
    internal fun post(work: Work) { _work.value = work }
}

/**
 * Runs the work in [Transfers] with a notification up, so it carries on with the screen off
 * or the app put away, and can be stopped from the notification as well as the app.
 */
class TransferService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Transfers.stop()
            return START_NOT_STICKY
        }
        // Before anything that might return early: a service started in the foreground that
        // does not call this within a few seconds takes the whole app down with it.
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notify_channel), NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION, notification(getString(R.string.notify_starting), null))

        val job = Transfers.take()
        if (job == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        Thread({ run(job, manager) }, "tana-transfer").start()
        return START_NOT_STICKY
    }

    private fun run(job: Job, manager: NotificationManager) {
        var lastShown = 0L
        val transfer = Transfer(
            resolve = Stores::get,
            isCancelled = Transfers::isCancelled,
            onProgress = { progress ->
                // An e-ink panel and a notification shade both pay for every redraw. Once a
                // second, or when a file finishes, is as often as anyone reads it.
                val now = System.currentTimeMillis()
                if (now - lastShown >= 1000) {
                    lastShown = now
                    Transfers.post(Work.Running(job, progress))
                    manager.notify(NOTIFICATION, notification(title(job), progress))
                }
            },
        )
        val result: Work = try {
            when (job) {
                is Job.Paste -> Work.Finished(job, transfer.paste(job.sources, job.dest, job.mode, job.clash))
                is Job.Delete -> Work.Finished(job, transfer.delete(job.entries))
                is Job.Fetch -> {
                    val folder = File(cacheDir, "fetched/${System.currentTimeMillis()}")
                    val files = job.entries.map { entry ->
                        File(folder, entry.name).also { transfer.fetch(entry, it) }
                    }
                    Work.Finished(job, Outcome(files.size, 0), files)
                }
                is Job.Compress -> {
                    transfer.compress(job.sources, job.dest, job.name)
                    Work.Finished(job, Outcome(job.sources.size, 0))
                }
                is Job.Extract -> {
                    // An archive that is not on the phone is fetched first: a zip has to be
                    // read from the end, which a stream from a server cannot do.
                    val file = if (job.archive.loc.store == LocalStore.ID) {
                        Stores.phone.file(job.archive.loc.path)
                    } else {
                        File(cacheDir, "fetched/${System.currentTimeMillis()}/${job.archive.name}").also { transfer.fetch(job.archive, it) }
                    }
                    val zip = Stores.zip(file)
                    val top = zip.list("")
                    val outcome = if (top.size == 1) {
                        // One thing at the top — most archives are a folder zipped whole — goes
                        // straight beside the archive, numbered if its name is taken, rather than
                        // into a second folder of the same name around it.
                        transfer.paste(top, job.dest, Mode.COPY, Clash.KEEP_BOTH)
                    } else {
                        val to = Stores.get(job.dest.store)
                        val stem = job.archive.name.substringBeforeLast('.', job.archive.name)
                        val folder = job.dest.child(Names.unique(stem) { to.stat(job.dest.child(it).path) != null })
                        to.makeFolder(folder.path)
                        transfer.paste(top, folder, Mode.COPY, Clash.KEEP_BOTH)
                    }
                    Work.Finished(job, outcome)
                }
            }
        } catch (e: Cancelled) {
            Work.Stopped(job)
        } catch (e: Exception) {
            Work.Failed(job, e)
        }
        Transfers.post(result)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun title(job: Job): String = when (job) {
        is Job.Paste -> getString(if (job.mode == Mode.MOVE) R.string.notify_moving else R.string.notify_copying)
        is Job.Delete -> getString(R.string.notify_deleting)
        is Job.Fetch -> getString(R.string.notify_fetching)
        is Job.Compress -> getString(R.string.notify_compressing)
        is Job.Extract -> getString(R.string.notify_extracting)
    }

    private fun notification(title: String, progress: Progress?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notify_stop), stop).build())
        if (progress != null) {
            builder.setContentText(getString(R.string.notify_progress, progress.filesDone, progress.filesTotal))
            if (progress.bytesTotal > 0) {
                builder.setProgress(1000, (progress.bytesDone * 1000 / progress.bytesTotal).toInt(), false)
            }
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "transfers"
        private const val NOTIFICATION = 1
        private const val ACTION_STOP = "com.wanderwildwood.tana.STOP"
    }
}
