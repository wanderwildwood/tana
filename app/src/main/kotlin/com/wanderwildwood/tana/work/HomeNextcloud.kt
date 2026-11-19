package com.wanderwildwood.tana.work

import android.content.Context
import com.wanderwildwood.tana.store.Server
import com.wanderwildwood.tana.store.encodePath

/**
 * Home's Nextcloud account ([HomeAccount]) as one of the servers here, under the fixed id [ID].
 *
 * What is kept: the choice to use it ([Prefs.homeWho], server and user) and the stamp last
 * taken ([Prefs.homeStamp]). The server itself, with its password, is in [Prefs.servers] only
 * while Home is signed in, and only as a mirror of Home's: sealed like every server password,
 * left out of backups like everything of this app, written again on each new stamp and taken
 * out as soon as Home signs out, is gone, or holds another account.
 *
 * Everything here runs off the main thread: it asks Home, or opens and seals passwords.
 */
object HomeNextcloud {
    const val ID = "home"
    const val STORE_ID = Server.DAV_PREFIX + ID

    /** The folder a Nextcloud keeps the files of the user with id [uid] in. */
    fun folder(server: String, uid: String): String =
        server.trim().trimEnd('/') + "/remote.php/dav/files/" + encodePath(uid) + "/"

    /** A server's address as shown: without "https://" and without a slash at the end. */
    fun shown(server: String): String = server.trim().substringAfter("://").trimEnd('/')

    /** Signs in with the login name ([HomeAccount.Held.user]); the folder is by user id ([HomeAccount.Held.uid]). */
    fun server(held: HomeAccount.Held): Server = Server(
        id = ID,
        name = shown(held.server),
        host = folder(held.server, held.uid),
        share = "",
        user = held.user,
        password = held.password,
        kind = Server.DAV,
    )

    /** [servers] with [home] in the place the old one had, or at the end. */
    fun withHome(servers: List<Server>, home: Server): List<Server> =
        if (servers.any { it.id == ID }) servers.map { if (it.id == ID) home else it } else servers + home

    /**
     * The app's own sign-ins that Home's account takes the place of: the one being changed
     * ([replacing], when it is a Nextcloud or WebDAV server) and any other of this app's own
     * for the same folder and user.
     */
    fun replaced(servers: List<Server>, home: Server, replacing: String?): List<Server> =
        servers.filter { s ->
            s.id != ID && s.isDav && (s.id == replacing || sameFolder(s, home))
        }

    private fun sameFolder(s: Server, home: Server): Boolean =
        s.host.trim().trimEnd('/').equals(home.host.trimEnd('/'), ignoreCase = true) && s.user.trim() == home.user

    /** Home's account as the screens show it. */
    data class Use(val server: String, val user: String, val signedIn: Boolean)

    /** Null when Home's account is not in use. */
    fun use(prefs: Prefs): Use? {
        val who = prefs.homeWho
        if (who.isEmpty()) return null
        return Use(shown(who.substringBefore('\n')), who.substringAfter('\n'), prefs.homeStamp.isNotEmpty())
    }

    /**
     * Every change to [Prefs.servers] or [Prefs.pins] is made inside this, here and on the
     * screens alike: each one reads the list, changes it and writes it back, and two at once
     * would lose one of the changes. Home is never asked inside it, so a slow Home holds
     * nothing up.
     */
    fun <T> edit(block: () -> T): T = synchronized(lock, block)

    private val lock = Any()

    /**
     * Asks Home and does what [HomeAccount.follow] says. True when anything changed, so the
     * screens read the servers again. Not on the main thread.
     */
    fun refresh(context: Context): Boolean {
        val prefs = Prefs(context)
        val who = prefs.homeWho
        if (who.isEmpty()) return false
        val stamp = prefs.homeStamp
        val answer = HomeAccount.ask(context)
        return edit {
            // Changed while Home was being asked (by another ask, a press of "Use" or "Stop
            // using it"): that one has done what was needed, and this answer may be the older.
            if (prefs.homeWho != who || prefs.homeStamp != stamp) return@edit false
            when (val step = HomeAccount.follow(who, stamp, answer)) {
                is HomeAccount.Step.Keep -> {
                    // Same stamp, but Home may have filled in the user id since the mirror was
                    // written (a Home updated to give it): the folder then follows it.
                    val held = (answer as? HomeAccount.Answer.Has)?.held ?: return@edit false
                    val now = server(held)
                    val mirror = prefs.servers.firstOrNull { it.id == ID } ?: return@edit false
                    if (mirror.host == now.host) return@edit false
                    prefs.servers = withHome(prefs.servers, mirror.copy(host = now.host))
                    true
                }
                is HomeAccount.Step.Take -> {
                    prefs.servers = withHome(prefs.servers, server(step.held))
                    prefs.homeStamp = step.held.stamp
                    true
                }
                is HomeAccount.Step.Drop -> {
                    // Signed out or gone: the pins stay, so the same account comes back with them.
                    // Another account: it is no longer this one, and needs a press of its own.
                    prefs.servers = prefs.servers.filterNot { it.id == ID }
                    prefs.homeStamp = ""
                    if (!step.following) forget(prefs)
                    true
                }
            }
        }
    }

    /**
     * The row "Use the phone's Nextcloud account" pressed: Home's account is used from now on,
     * in place of [replacing] (the server being changed, if any) and of any of this app's own
     * sign-ins to the same folder; their pins move to it. False when Home has no account to
     * give. Not on the main thread.
     */
    fun start(context: Context, replacing: String?): Boolean {
        val held = HomeAccount.read(context) ?: return false
        val prefs = Prefs(context)
        edit { take(prefs, held, replacing) }
        return true
    }

    private fun take(prefs: Prefs, held: HomeAccount.Held, replacing: String?) {
        val home = server(held)
        val before = prefs.servers
        val replaced = replaced(before, home, replacing)
        val gone = replaced.map { it.storeId }.toSet()
        // A pin moves only from a server whose folder is the same one; paths elsewhere mean nothing here.
        val moved = replaced.filter { sameFolder(it, home) }.map { it.storeId }.toSet()
        prefs.servers = withHome(before.filterNot { it.storeId in gone }, home)
        prefs.homeWho = held.who
        prefs.homeStamp = held.stamp
        prefs.pins = prefs.pins
            .filter { it.loc.store !in gone || it.loc.store in moved }
            .map { if (it.loc.store in moved) it.copy(loc = it.loc.copy(store = STORE_ID)) else it }
            .distinctBy { it.loc }
    }

    /** "Stop using it": the choice, the stamp, the mirror and the pins to it are forgotten. */
    fun stop(context: Context) {
        val prefs = Prefs(context)
        edit {
            prefs.servers = prefs.servers.filterNot { it.id == ID }
            prefs.homeStamp = ""
            forget(prefs)
        }
    }

    private fun forget(prefs: Prefs) {
        prefs.homeWho = ""
        prefs.pins = prefs.pins.filterNot { it.loc.store == STORE_ID }
    }
}
