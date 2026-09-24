package com.wanderwildwood.tana.store

/**
 * Every store the app knows, by id: the phone, one per server, one per folder added from
 * another app, and a zip archive for as long as one is open.
 *
 * Shared between the screens and the copy service, so a server's connection is opened once
 * and used by both rather than each keeping its own.
 */
object Stores {
    val phone = LocalStore()

    private val servers = mutableMapOf<String, SmbStore>()
    private val others = mutableMapOf<String, SafStore>()
    private val zips = mutableMapOf<String, Pair<Long, ZipStore>>()

    @Synchronized
    fun setOthers(list: List<SafRoot>, resolver: android.content.ContentResolver) {
        val wanted = list.associateBy { it.storeId }
        others.keys.retainAll(wanted.keys)
        wanted.forEach { (id, root) ->
            if (others[id]?.root != root) others[id] = SafStore(root, resolver)
        }
    }

    /**
     * A zip, opened on first use and again whenever the archive has changed on disk since, so
     * one rewritten underneath is never read from a stale index.
     */
    @Synchronized
    fun zip(archive: java.io.File): ZipStore {
        val id = ZipStore.idFor(archive)
        val stamp = archive.lastModified()
        zips[id]?.let { (seen, store) -> if (seen == stamp) return store else store.close() }
        return ZipStore(archive, id).also { zips[id] = stamp to it }
    }

    fun isReadOnly(id: String) = id.startsWith(ZipStore.PREFIX)

    @Synchronized
    fun setServers(list: List<Server>) {
        val wanted = list.associateBy { it.storeId }
        // A server whose details changed gets a fresh store; the old connection was made
        // with the old address or the old password and must not be reused.
        servers.entries.removeAll { (id, store) ->
            val keep = wanted[id] == store.server
            if (!keep) store.close()
            !keep
        }
        wanted.forEach { (id, server) -> servers.getOrPut(id) { SmbStore(server) } }
    }

    @Synchronized
    fun get(id: String): Store = when {
        id == LocalStore.ID -> phone
        id.startsWith(ZipStore.PREFIX) -> zip(java.io.File(id.removePrefix(ZipStore.PREFIX)))
        else -> servers[id] ?: others[id]
    } ?: throw StoreException(StoreException.Reason.GONE, id)

    @Synchronized
    fun server(id: String): Server? = servers[id]?.server
}
