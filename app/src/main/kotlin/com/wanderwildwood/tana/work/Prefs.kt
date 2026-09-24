package com.wanderwildwood.tana.work

import android.content.Context
import com.wanderwildwood.tana.store.Loc
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

    var servers: List<Server>
        get() = Server.listFromJson(prefs.getString("servers", null))
        set(value) = prefs.edit().putString("servers", Server.listToJson(value)).apply()

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
