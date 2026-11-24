package lk.labelprint

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

val DF: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu MM dd").withResolverStyle(ResolverStyle.STRICT)
val TF: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
fun today(): String = LocalDate.now().format(DF)
fun plusYear(s: String): String = runCatching { LocalDate.parse(s, DF).plusYears(1).minusDays(1).format(DF) }.getOrDefault(s)
/** Old default label texts -> new short forms (so saved templates keep working). */
val MIG = mapOf(
    "නිශ්. දිනය" to "නි.පා.දි", "ක.ඉ.දිනය" to "ක.ඉ.දි", "කාණ්ඩ අං." to "කා.අං.",
    "Mfd Date" to "Mfd", "Exp Date" to "Exp", "Batch No" to "Batch", "Price Rs" to "Price",
    "உற்பத்தி திகதி" to "உ.தி.", "காலாவதி திகதி" to "கா.தி.", "தொகுதி இல." to "தொ.இல.", "விலை ரூ." to "விலை",
)
fun mig(x: String): String = MIG[x] ?: x
fun isDate(s: String): Boolean = runCatching { LocalDate.parse(s, DF) }.isSuccess
fun fmtTime(ts: Long): String = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(TF)

data class Tpl(
    val name: String = "",
    val mfd: String = today(),
    val exp: String = plusYear(today()),
    val batch: String = "",
    val price: String = "",
    val w: Int = 30, val h: Int = 20, val gap: Int = 2, val scale: Float = 1f,
    val ml: Float = 1.5f, val mr: Float = 1f, val mt: Float = 0.5f, val mb: Float = 0.5f, val sp: Float = 0.5f,
    val si: List<String> = listOf("නි.පා.දි", "ක.ඉ.දි", "කා.අං.", "මිල රු."),
    val en: List<String> = listOf("Mfd", "Exp", "Batch", "Price"),
    val ta: List<String> = listOf("உ.தி.", "கா.தி.", "தொ.இல.", "விலை"),
    val wOn: Boolean = false, val wt: String = "", val wPre: String = "ශුද්ධ බර",
) {
    fun values() = listOf(mfd, exp, batch, price)

    /** Set Mfd; when it is a complete valid date, Exp = Mfd + 1 year - 1 day. */
    fun setMfd(s: String): Tpl = if (isDate(s)) copy(mfd = s, exp = plusYear(s)) else copy(mfd = s)

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("mfd", mfd).put("exp", exp).put("batch", batch).put("price", price)
        .put("w", w).put("h", h).put("gap", gap).put("scale", scale.toDouble())
        .put("sp", sp.toDouble()).put("ml", ml.toDouble()).put("mr", mr.toDouble()).put("mt", mt.toDouble()).put("mb", mb.toDouble())
        .put("si", JSONArray(si)).put("en", JSONArray(en)).put("ta", JSONArray(ta))
        .put("wOn", wOn).put("wt", wt).put("wPre", wPre)

    companion object {
        fun from(o: JSONObject): Tpl {
            val d = Tpl()
            fun l(k: String, def: List<String>) =
                o.optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } }?.takeIf { it.size == 4 } ?: def
            fun f(k: String, def: Float) = o.optDouble(k, def.toDouble()).toFloat()
            return Tpl(
                o.optString("name"), o.optString("mfd", d.mfd), o.optString("exp", d.exp),
                o.optString("batch"), o.optString("price"),
                o.optInt("w", 30), o.optInt("h", 20), o.optInt("gap", 2), f("scale", 1f),
                f("ml", d.ml), f("mr", d.mr), f("mt", d.mt), f("mb", d.mb), f("sp", d.sp),
                l("si", d.si).map(::mig), l("en", d.en).map(::mig), l("ta", d.ta).map(::mig),
                o.optBoolean("wOn", false), o.optString("wt"), o.optString("wPre", d.wPre)
            )
        }
    }
}

data class Hist(val ts: Long, val n: Int, val t: Tpl)

data class Cfg(
    val mode: String = "bt", val bt: String = "", val ip: String = "192.168.",
    val port: Int = 9100, val rot: Boolean = false, val theme: String = "auto",
)

class Store(c: Context) {
    private val sp = c.getSharedPreferences("lp", Context.MODE_PRIVATE)

    fun templates(): List<Tpl> = runCatching {
        JSONArray(sp.getString("t", "[]")).let { a -> List(a.length()) { Tpl.from(a.getJSONObject(it)) } }
    }.getOrDefault(emptyList())

    fun saveTemplates(l: List<Tpl>) {
        sp.edit().putString("t", JSONArray(l.map { it.toJson() }).toString()).apply()
    }

    fun history(): List<Hist> = runCatching {
        JSONArray(sp.getString("h", "[]")).let { a ->
            List(a.length()) { val o = a.getJSONObject(it); Hist(o.getLong("ts"), o.getInt("n"), Tpl.from(o.getJSONObject("t"))) }
        }
    }.getOrDefault(emptyList())

    fun addHistory(h: Hist): List<Hist> {
        val l = (listOf(h) + history()).take(30)
        sp.edit().putString("h", JSONArray(l.map { JSONObject().put("ts", it.ts).put("n", it.n).put("t", it.t.toJson()) }).toString()).apply()
        return l
    }

    fun clearHistory() { sp.edit().remove("h").apply() }

    var cfg: Cfg
        get() = Cfg(
            sp.getString("mode", "bt")!!, sp.getString("bt", "")!!, sp.getString("ip", "192.168.")!!,
            sp.getInt("port", 9100), sp.getBoolean("rot", false), sp.getString("theme", "auto")!!
        )
        set(v) {
            sp.edit().putString("mode", v.mode).putString("bt", v.bt).putString("ip", v.ip)
                .putInt("port", v.port).putBoolean("rot", v.rot).putString("theme", v.theme).apply()
        }
}
