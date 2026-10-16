package com.wanderwildwood.tana.nearby

import org.json.JSONObject

/**
 * What LocalSend's protocol (v2, github.com/localsend/protocol) puts on the wire: a device, and a
 * file offered to one. Written by hand from the published protocol; none of LocalSend's own code
 * is in this app.
 */
internal object Wire {
    const val PORT = 53317
    const val GROUP = "224.0.0.167"
    const val VERSION = "2.1"
    const val BASE = "/api/localsend/v2/"
}

/** Another device running LocalSend, or this phone. [address] is where it was heard from. */
internal data class Device(
    val alias: String,
    val model: String?,
    val type: String?,
    val fingerprint: String,
    val port: Int,
    val https: Boolean,
    val address: String = "",
) {
    /** As LocalSend sends it: in an announcement (with [announce]), to /register, and in prepare-upload. */
    fun json(announce: Boolean? = null): JSONObject = JSONObject()
        .put("alias", alias)
        .put("version", Wire.VERSION)
        .put("deviceModel", model ?: JSONObject.NULL)
        .put("deviceType", type ?: JSONObject.NULL)
        .put("fingerprint", fingerprint)
        .put("port", port)
        .put("protocol", if (https) "https" else "http")
        .put("download", false)
        .apply { if (announce != null) put("announce", announce) }

    fun url(route: String): String = "${if (https) "https" else "http"}://$address:$port${Wire.BASE}$route"

    companion object {
        /** Null when it is not a device: no alias, or no fingerprint to know it by. */
        fun from(o: JSONObject, address: String, port: Int = Wire.PORT, https: Boolean = true): Device? {
            val alias = o.optString("alias").trim()
            val fingerprint = o.optString("fingerprint")
            if (alias.isEmpty() || fingerprint.isEmpty()) return null
            return Device(
                alias = alias.take(64),
                model = o.optString("deviceModel").takeIf { it.isNotBlank() && it != "null" }?.take(64),
                type = o.optString("deviceType").takeIf { it.isNotBlank() && it != "null" },
                fingerprint = fingerprint,
                port = o.optInt("port", port).takeIf { it in 1..65535 } ?: port,
                https = when (o.optString("protocol")) {
                    "http" -> false
                    "https" -> true
                    else -> https
                },
                address = address,
            )
        }
    }
}

/** One file in a transfer. [name] may carry folders, "Holiday/IMG_1.jpg", when a folder is sent. */
internal data class Offer(
    val id: String,
    val name: String,
    val size: Long,
    val type: String,
    val sha256: String? = null,
    val modified: String? = null,
) {
    fun json(): JSONObject = JSONObject()
        .put("id", id)
        .put("fileName", name)
        .put("size", size)
        .put("fileType", type)
        .apply {
            if (sha256 != null) put("sha256", sha256)
            if (modified != null) put("metadata", JSONObject().put("modified", modified))
        }

    companion object {
        fun from(o: JSONObject): Offer? {
            val id = o.optString("id")
            val name = o.optString("fileName")
            val size = o.optLong("size", -1)
            if (id.isEmpty() || name.isEmpty() || size < 0) return null
            return Offer(
                id = id,
                name = name,
                size = size,
                type = o.optString("fileType"),
                sha256 = o.optString("sha256").takeIf { it.length == 64 },
                modified = o.optJSONObject("metadata")?.optString("modified")?.takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }
}
