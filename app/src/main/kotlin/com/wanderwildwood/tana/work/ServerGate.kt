package com.wanderwildwood.tana.work

import android.content.Context
import android.os.Looper
import com.wanderwildwood.tana.store.DavStore
import com.wanderwildwood.tana.store.Stores
import java.util.concurrent.atomic.AtomicLong

/**
 * What is settled before a server is used ([Stores.beforeServer]), on the thread that is about
 * to use it:
 *
 * - Home's account: after the app comes to the front ([cameBack]) Home is asked again before
 *   the first request to its server, so a password Home has since let go (signed out, another
 *   account) is never sent. The copy kept from before is not trusted until Home has answered.
 * - An own Nextcloud sign-in kept by an earlier version, with its folder named after the login
 *   name: the server is asked once for the user id and the folder named after that
 *   ([DavStore.needsUid]).
 *
 * Never on the main thread: there nothing is asked, and the screens ask Home on resume anyway.
 */
object ServerGate {
    /** Raised each time the app comes to the front; Home has been asked since when [asked] has caught up. */
    private val wanted = AtomicLong(1)
    @Volatile private var asked = 0L
    private val asking = Any()

    /** The app came to the front: Home is asked again before its server is next used. */
    fun cameBack() {
        wanted.incrementAndGet()
    }

    /**
     * Asks Home and does as it says ([HomeNextcloud.refresh]); true when anything changed. Every
     * ask goes through here, so one that is under way is waited for, not run twice.
     */
    fun askHome(context: Context): Boolean = synchronized(asking) {
        val upTo = wanted.get()
        val changed = HomeNextcloud.refresh(context)
        if (asked < upTo) asked = upTo
        changed
    }

    fun before(context: Context, storeId: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        if (storeId == HomeNextcloud.STORE_ID) {
            if (asked >= wanted.get()) return
            runCatching { askHome(context) }
            // Whatever Home said is what the store is opened with: a new password, or none.
            Stores.setServers(Prefs(context).servers)
        } else if (storeId.startsWith(com.wanderwildwood.tana.store.Server.DAV_PREFIX)) {
            val server = Stores.server(storeId) ?: return
            if (server.id == HomeNextcloud.ID || !DavStore.needsUid(server) || storeId in tried) return
            synchronized(uidAsking) {
                if (storeId in tried) return
                tried += storeId
                if (fixUid(context, storeId)) Stores.setServers(Prefs(context).servers)
            }
        }
    }

    /** Own servers asked for their user id since the app started; one that could not answer is asked again next start. */
    private val tried = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val uidAsking = Any()

    /** True when the server was changed. */
    private fun fixUid(context: Context, storeId: String): Boolean {
        val prefs = Prefs(context)
        val saved = prefs.servers.firstOrNull { it.storeId == storeId }?.takeIf { DavStore.needsUid(it) } ?: return false
        val base = DavStore.filesFolder(saved.host)?.first ?: return false
        val fixed = DavStore.withUid(saved, DavStore.askUid(base, saved)) ?: return false
        return HomeNextcloud.edit {
            // Changed meanwhile (edited, removed): that change stands.
            val now = prefs.servers
            if (now.none { it == saved }) return@edit false
            prefs.servers = now.map { if (it == saved) fixed else it }
            true
        }
    }
}
