package com.wanderwildwood.tana.store

/**
 * Every store the app knows, by id: the phone, and one per server the reader has added.
 *
 * Shared between the screens and the copy service, so a server's connection is opened once
 * and used by both rather than each keeping its own.
 */
object Stores {
    val phone = LocalStore()

    private val servers = mutableMapOf<String, SmbStore>()

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
    fun get(id: String): Store = if (id == LocalStore.ID) phone else servers[id]
        ?: throw StoreException(StoreException.Reason.GONE, id)

    @Synchronized
    fun server(id: String): Server? = servers[id]?.server
}
