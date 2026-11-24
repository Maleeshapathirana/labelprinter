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

/** Label bitmap (pure black/white): large Sinhala label, small "English/Tamil" line, aligned value column, optional rotated weight strip. */
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
    val lp = paint(true)   // Sinhala label (large)
    val ep = paint(true)   // English/Tamil (small)
    val v = paint(false)   // value
    val ml = t.ml * DPM
    val mr = t.mr * DPM
    val mt = t.mt * DPM
    val mb = t.mb * DPM
    val gap = t.sp * DPM
    val rh = (h - mt - mb - 3 * gap) / 4f
    lp.textSize = rh * 0.46f * t.scale
    ep.textSize = rh * 0.26f * t.scale
    v.textSize = rh * 0.57f * t.scale

    // optional weight text, rotated 90° (reads bottom -> top) on the left edge
    var cl = ml
    if (t.wOn && t.wt.isNotBlank()) {
        val txt = (t.wPre.trim() + " " + t.wt.trim()).trim()
        val wp = paint(false)
        wp.textAlign = Paint.Align.CENTER
        wp.textSize = rh * 0.4f * t.scale
        val len = h - mt - mb
        val tw = wp.measureText(txt)
        if (tw > len && tw > 0f) wp.textSize *= len / tw
        val fm = wp.fontMetrics
        val sw = minOf(fm.descent - fm.ascent, w * 0.2f)
        val cx = ml + sw / 2f
        val cy = mt + len / 2f
        c.save()
        c.rotate(-90f, cx, cy)
        c.drawText(txt, cx, cy - (fm.ascent + fm.descent) / 2f, wp)
        c.restore()
        cl = ml + sw + 3f
    }

    val small = (0..3).map { listOf(t.en[it], t.ta[it]).filter { s -> s.isNotBlank() }.joinToString("/") }
    var leftW = (0..3).maxOf { maxOf(lp.measureText(t.si[it]), ep.measureText(small[it])) }
    val cap = w * 0.6f - cl - 10f
    if (leftW > cap && cap > 10f) { val f = cap / leftW; lp.textSize *= f; ep.textSize *= f; leftW = cap }
    val colonX = cl + leftW + 3f
    val vx = colonX + lp.measureText(":") + 5f
    val vals = t.values()
    val vw = vals.maxOf { v.measureText(it) }
    val avail = w - mr - vx
    if (vw > avail && vw > 0f && avail > 10f) v.textSize *= maxOf(avail / vw, 0.5f)
    val vf = v.fontMetrics

    for (i in 0..3) {
        val y0 = mt + i * (rh + gap)
        val bs = y0 + lp.textSize
        c.drawText(t.si[i], cl, bs, lp)
        c.drawText(":", colonX, bs, lp)
        c.drawText(small[i], cl, y0 + rh * 0.86f, ep)
        c.drawText(vals[i], vx, y0 + rh / 2f - (vf.ascent + vf.descent) / 2f, v)
    }

    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    for (i in px.indices) px[i] = if (Color.red(px[i]) < 175) Color.BLACK else Color.WHITE
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
