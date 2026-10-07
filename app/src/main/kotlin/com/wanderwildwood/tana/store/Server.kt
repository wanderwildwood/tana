package com.wanderwildwood.tana.store

import org.json.JSONArray
import org.json.JSONObject

/**
 * A shared drive on a server, as the reader added it: a Samba share, or a Nextcloud or other
 * WebDAV server ([isDav]).
 *
 * For a Samba share, [host] is the server's address and [share] the shared folder's name, and
 * a blank [user] means signing in as a guest, which is how a Samba share with `guest ok` lets
 * anyone on the network in. For WebDAV, [host] is the full address of the folder everything is
 * under ("http://192.168.1.20:11000/remote.php/dav/files/me/") and [share] is not used.
 *
 * The password, where there is one, is kept in the app's own private storage, which no other
 * app can read and which is left out of backups.
 */
data class Server(
    val id: String,
    val name: String,
    val host: String,
    val share: String,
    val user: String = "",
    val password: String = "",
    val domain: String = "",
    val kind: String = SMB,
) {
    val isDav: Boolean get() = kind == DAV

    val storeId: String get() = (if (isDav) DAV_PREFIX else PREFIX) + id

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("host", host)
        .put("share", share)
        .put("user", user)
        .put("password", password)
        .put("domain", domain)
        .put("kind", kind)

    companion object {
        const val PREFIX = "smb:"
        const val DAV_PREFIX = "dav:"

        const val SMB = "smb"
        const val DAV = "dav"

        fun fromJson(o: JSONObject) = Server(
            id = o.getString("id"),
            name = o.optString("name"),
            host = o.optString("host"),
            share = o.optString("share"),
            user = o.optString("user"),
            password = o.optString("password"),
            domain = o.optString("domain"),
            kind = o.optString("kind", SMB).ifEmpty { SMB },
        )

        fun listFromJson(text: String?): List<Server> {
            if (text.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                runCatching { fromJson(array.getJSONObject(i)) }.getOrNull()
            }
        }

        fun listToJson(servers: List<Server>): String =
            JSONArray().apply { servers.forEach { put(it.toJson()) } }.toString()
    }
}

/** A store that is one of the servers above, reached over the network and closed when let go. */
interface ServerStore : Store {
    val server: Server

    fun close()
}
