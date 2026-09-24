package com.wanderwildwood.tana.work

import android.content.Context
import com.wanderwildwood.tana.store.Loc
import com.wanderwildwood.tana.store.SafRoot
import com.wanderwildwood.tana.store.Server
import org.json.JSONArray
import org.json.JSONObject

/** A folder kept on the start page, under the name the reader will know it by. */
data class Pin(val loc: Loc, val label: String)

/** What the app remembers between runs. All of it is small, so all of it is one file. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("tana", Context.MODE_PRIVATE)

    var sort: Sort
        get() = Sort(
            by = runCatching { SortBy.valueOf(prefs.getString("sort_by", null) ?: "") }.getOrDefault(SortBy.NAME),
            reversed = prefs.getBoolean("sort_reversed", false),
        )
        set(value) = prefs.edit().putString("sort_by", value.by.name).putBoolean("sort_reversed", value.reversed).apply()

    var showHidden: Boolean
        get() = prefs.getBoolean("show_hidden", false)
        set(value) = prefs.edit().putBoolean("show_hidden", value).apply()

    /**
     * Passwords are sealed on the way in and opened on the way out (see [Secrets]); nothing
     * else in the app ever sees the sealed form. One saved in the clear by 0.1 is sealed the
     * first time the list is read.
     */
    var servers: List<Server>
        get() {
            val stored = Server.listFromJson(prefs.getString("servers", null))
            val opened = stored.map { it.copy(password = Secrets.open(it.password)) }
            if (stored.any { it.password.isNotEmpty() && !Secrets.isSealed(it.password) }) servers = opened
            return opened
        }
        set(value) = prefs.edit()
            .putString("servers", Server.listToJson(value.map { it.copy(password = Secrets.seal(it.password)) }))
            .apply()

    var others: List<SafRoot>
        get() {
            val array = runCatching { JSONArray(prefs.getString("others", "[]")) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                runCatching {
                    val o = array.getJSONObject(i)
                    SafRoot(o.getString("id"), o.getString("label"), o.optString("note"), o.getString("uri"))
                }.getOrNull()
            }
        }
        set(value) {
            val array = JSONArray()
            value.forEach { array.put(JSONObject().put("id", it.id).put("label", it.label).put("note", it.note).put("uri", it.uri)) }
            prefs.edit().putString("others", array.toString()).apply()
        }

    var pins: List<Pin>
        get() {
            val array = runCatching { JSONArray(prefs.getString("pins", "[]")) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                runCatching {
                    val o = array.getJSONObject(i)
                    Pin(Loc(o.getString("store"), o.getString("path")), o.getString("label"))
                }.getOrNull()
            }
        }
        set(value) {
            val array = JSONArray()
            value.forEach { array.put(JSONObject().put("store", it.loc.store).put("path", it.loc.path).put("label", it.label)) }
            prefs.edit().putString("pins", array.toString()).apply()
        }
}
