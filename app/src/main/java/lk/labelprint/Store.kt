package lk.labelprint

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val DF: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy MM dd")
fun today(): String = LocalDate.now().format(DF)

data class Tpl(
    val name: String = "",
    val mfd: String = today(),
    val exp: String = today(),
    val batch: String = "",
    val price: String = "",
    val w: Int = 30, val h: Int = 20, val gap: Int = 2, val scale: Float = 1f,
    val si: List<String> = listOf("නිශ්. දිනය", "ක.ඉ.දිනය", "කාණ්ඩ අං.", "මිල රු."),
    val en: List<String> = listOf("Mfd Date", "Exp Date", "Batch No", "Price Rs"),
) {
    fun values() = listOf(mfd, exp, batch, price)

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("mfd", mfd).put("exp", exp).put("batch", batch).put("price", price)
        .put("w", w).put("h", h).put("gap", gap).put("scale", scale.toDouble())
        .put("si", JSONArray(si)).put("en", JSONArray(en))

    companion object {
        fun from(o: JSONObject): Tpl {
            val d = Tpl()
            fun l(k: String, def: List<String>) =
                o.optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } } ?: def
            return Tpl(
                o.optString("name"), o.optString("mfd", d.mfd), o.optString("exp", d.exp),
                o.optString("batch"), o.optString("price"),
                o.optInt("w", 30), o.optInt("h", 20), o.optInt("gap", 2),
                o.optDouble("scale", 1.0).toFloat(), l("si", d.si), l("en", d.en)
            )
        }
    }
}

data class Cfg(
    val mode: String = "bt", val bt: String = "", val ip: String = "192.168.",
    val port: Int = 9100, val rot: Boolean = false,
)

class Store(c: Context) {
    private val sp = c.getSharedPreferences("lp", Context.MODE_PRIVATE)

    fun templates(): List<Tpl> = runCatching {
        JSONArray(sp.getString("t", "[]")).let { a -> List(a.length()) { Tpl.from(a.getJSONObject(it)) } }
    }.getOrDefault(emptyList())

    fun saveTemplates(l: List<Tpl>) {
        sp.edit().putString("t", JSONArray(l.map { it.toJson() }).toString()).apply()
    }

    var cfg: Cfg
        get() = Cfg(
            sp.getString("mode", "bt")!!, sp.getString("bt", "")!!, sp.getString("ip", "192.168.")!!,
            sp.getInt("port", 9100), sp.getBoolean("rot", false)
        )
        set(v) {
            sp.edit().putString("mode", v.mode).putString("bt", v.bt).putString("ip", v.ip)
                .putInt("port", v.port).putBoolean("rot", v.rot).apply()
        }
}
