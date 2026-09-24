package com.wanderwildwood.tana.store

import org.json.JSONArray
import org.json.JSONObject

/**
 * A shared drive on a server, as the reader added it.
 *
 * A blank [user] means signing in as a guest, which is how a Samba share with `guest ok`
 * lets anyone on the network in. The password, where there is one, is kept in the app's own
 * private storage, which no other app can read and which is left out of backups.
 */
data class Server(
    val id: String,
    val name: String,
    val host: String,
    val share: String,
    val user: String = "",
    val password: String = "",
    val domain: String = "",
) {
    val storeId: String get() = "$PREFIX$id"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("host", host)
        .put("share", share)
        .put("user", user)
        .put("password", password)
        .put("domain", domain)

    companion object {
        const val PREFIX = "smb:"

        fun fromJson(o: JSONObject) = Server(
            id = o.getString("id"),
            name = o.optString("name"),
            host = o.optString("host"),
            share = o.optString("share"),
            user = o.optString("user"),
            password = o.optString("password"),
            domain = o.optString("domain"),
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
