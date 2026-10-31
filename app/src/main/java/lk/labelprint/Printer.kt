package lk.labelprint

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

const val DPM = 8 // dots per mm (203 dpi)
val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/** Label bitmap (pure black/white). Sinhala is drawn as an image so any printer can print it. */
fun renderLabel(t: Tpl): Bitmap {
    val w = t.w * DPM
    val h = t.h * DPM
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(Color.WHITE)
    fun paint(bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
    val si = paint(true)
    val en = paint(false)
    val v = paint(false)
    val mx = 8f
    val my = 6f
    val rh = (h - 2 * my) / 4f
    si.textSize = rh * 0.46f * t.scale
    en.textSize = rh * 0.28f * t.scale
    v.textSize = rh * 0.52f * t.scale

    // make the two label lines fit inside one row
    val fit = (si.fontMetrics.descent - si.fontMetrics.ascent) + (en.fontMetrics.descent - en.fontMetrics.ascent)
    if (fit > rh) { val f = rh / fit; si.textSize *= f; en.textSize *= f }

    // aligned columns: labels | colon | values
    val leftW = (0..3).maxOf { maxOf(si.measureText(t.si[it]), en.measureText(t.en[it])) }
    val colonX = mx + leftW + 4f
    val vx = colonX + si.measureText(":") + 6f
    val vals = t.values()
    val vw = vals.maxOf { v.measureText(it) }
    val avail = w - vx - mx
    if (vw > avail && vw > 0f) v.textSize *= maxOf(avail / vw, 0.5f)

    val sf = si.fontMetrics
    val ef = en.fontMetrics
    val vf = v.fontMetrics
    val bh = (sf.descent - sf.ascent) + (ef.descent - ef.ascent)
    for (i in 0..3) {
        val y0 = my + i * rh
        val top = y0 + (rh - bh) / 2f
        val sBase = top - sf.ascent
        val eBase = top + (sf.descent - sf.ascent) - ef.ascent
        c.drawText(t.si[i], mx, sBase, si); c.drawText(":", colonX, sBase, si)
        c.drawText(t.en[i], mx, eBase, en); c.drawText(":", colonX, eBase, en)
        c.drawText(vals[i], vx, y0 + rh / 2f - (vf.ascent + vf.descent) / 2f, v)
    }

    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    for (i in px.indices) px[i] = if (Color.red(px[i]) < 160) Color.BLACK else Color.WHITE
    bmp.setPixels(px, 0, w, 0, 0, w, h)
    return bmp
}

/** TSPL job: label size + bitmap + N copies. */
fun buildTspl(t: Tpl, copies: Int, rotate: Boolean): ByteArray {
    val b = renderLabel(t)
    val w = b.width
    val h = b.height
    val px = IntArray(w * h)
    b.getPixels(px, 0, w, 0, 0, w, h)
    val wb = w / 8
    val data = ByteArray(wb * h)
    for (y in 0 until h) for (xb in 0 until wb) {
        var v = 0
        for (k in 0..7) {
            val i = y * w + xb * 8 + k
            val p = px[if (rotate) w * h - 1 - i else i]
            v = (v shl 1) or (if ((p and 0xFF) > 128) 1 else 0) // TSPL: 1 = white, 0 = black
        }
        data[y * wb + xb] = v.toByte()
    }
    val head = "SIZE ${t.w} mm,${t.h} mm\r\nGAP ${t.gap} mm,0 mm\r\nDIRECTION 1\r\nCLS\r\nBITMAP 0,0,$wb,$h,0,"
    val tail = "\r\nPRINT 1,$copies\r\n"
    return head.toByteArray(Charsets.US_ASCII) + data + tail.toByteArray(Charsets.US_ASCII)
}

@SuppressLint("MissingPermission")
fun pairedPrinters(ctx: Context): List<Pair<String, String>> = runCatching {
    ctx.getSystemService(BluetoothManager::class.java).adapter.bondedDevices
        .map { (it.name ?: "Unknown") to it.address }
}.getOrDefault(emptyList())

@SuppressLint("MissingPermission")
suspend fun sendToPrinter(ctx: Context, cfg: Cfg, data: ByteArray) = withContext(Dispatchers.IO) {
    if (cfg.mode == "wifi") {
        Socket().use { s ->
            s.connect(InetSocketAddress(cfg.ip.trim(), cfg.port), 5000)
            s.getOutputStream().apply { write(data); flush() }
            Thread.sleep(600)
        }
    } else {
        require(cfg.bt.isNotBlank()) { "Printer එක තෝරලා නෑ (⚙ Settings)" }
        val ad = ctx.getSystemService(BluetoothManager::class.java).adapter
        require(ad != null && ad.isEnabled) { "Bluetooth ON කරන්න" }
        ad.cancelDiscovery()
        val sock = ad.getRemoteDevice(cfg.bt).createRfcommSocketToServiceRecord(SPP)
        try {
            sock.connect()
            val o = sock.outputStream
            var i = 0
            while (i < data.size) {
                val n = minOf(512, data.size - i)
                o.write(data, i, n); i += n
                Thread.sleep(15)
            }
            o.flush()
            Thread.sleep(1200)
        } finally {
            runCatching { sock.close() }
        }
    }
}
