package lk.labelprint

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        setContent {
            var cfg by remember { mutableStateOf(store.cfg) }
            val dark = when (cfg.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            val cs = if (dark) darkColorScheme(
                primary = Color(0xFF8C9EFF), onPrimary = Color(0xFF0B1033),
                background = Color(0xFF0F1320), surface = Color(0xFF1A2030),
                onBackground = Color(0xFFE6E9F5), onSurface = Color(0xFFE6E9F5)
            ) else lightColorScheme(
                primary = Color(0xFF3D5AFE), onPrimary = Color.White,
                background = Color(0xFFF4F6FB), surface = Color.White
            )
            MaterialTheme(colorScheme = cs) {
                val view = LocalView.current
                val top = cs.background.toArgb()
                val bottom = cs.surface.toArgb()
                SideEffect {
                    val w = (view.context as? Activity)?.window ?: return@SideEffect
                    w.statusBarColor = top
                    w.navigationBarColor = bottom
                    WindowCompat.getInsetsController(w, view).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                Root(this@MainActivity, store, cfg) { cfg = it; store.cfg = it }
            }
        }
    }
}

/** Type digits (auto "yyyy MM dd") or tap 📅 to pick. */
fun fmtDigits(s: String): String {
    val d = s.filter(Char::isDigit).take(8)
    return buildString { d.forEachIndexed { i, ch -> if (i == 4 || i == 6) append(' '); append(ch) } }
}

@Composable
fun DateInput(label: String, value: String, onChange: (String) -> Unit, onPick: () -> Unit) {
    OutlinedTextField(
        value, { onChange(fmtDigits(it)) }, Modifier.fillMaxWidth(), label = { Text(label) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        trailingIcon = { IconButton(onPick) { Text("📅") } }
    )
}

@Composable
fun NumField(label: String, v: Int, lo: Int, hi: Int, mod: Modifier, on: (Int) -> Unit) {
    var s by remember { mutableStateOf(v.toString()) }
    OutlinedTextField(
        s, {
            s = it.filter(Char::isDigit).take(3)
            s.toIntOrNull()?.takeIf { n -> n in lo..hi }?.let(on)
        },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = mod
    )
}

@Composable
fun MarginSlider(label: String, v: Float, hi: Float = 6f, on: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(54.dp), fontSize = 13.sp)
        Slider(v, { on((it * 2).roundToInt() / 2f) }, Modifier.weight(1f), valueRange = 0f..hi, steps = (hi * 2).toInt() - 1)
        Text("$v mm", Modifier.width(54.dp), fontSize = 13.sp, textAlign = TextAlign.End)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(ctx: Context, store: Store, cfg: Cfg, setCfg: (Cfg) -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var list by remember { mutableStateOf(store.templates()) }
    var hist by remember { mutableStateOf(store.history()) }
    var t by remember { mutableStateOf(Tpl()) }
    var copies by remember { mutableStateOf("1") }
    var dlg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val bmp = remember(t) { renderLabel(t).asImageBitmap() }
    val line = MaterialTheme.colorScheme.outline
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val cardShape = RoundedCornerShape(20.dp)

    var perm by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 31 ||
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perm = it }
    LaunchedEffect(Unit) { if (!perm && cfg.mode == "bt") launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }

    fun doPrint(x: Tpl, n: Int) {
        busy = true
        scope.launch {
            val msg = try {
                sendToPrinter(ctx, cfg, buildTspl(x, n, cfg.rot))
                hist = store.addHistory(Hist(System.currentTimeMillis(), n, x))
                "✓ ලේබල් $n ක් යැව්වා"
            } catch (e: Exception) {
                "✗ " + (e.message ?: "Print අසාර්ථකයි")
            }
            busy = false
            snack.showSnackbar(msg)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text("ලේබල් ප්‍රින්ටර්", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton({ dlg = "history" }) { Text("🕘", fontSize = 20.sp) }
                    IconButton({ dlg = "settings" }) { Text("⚙️", fontSize = 20.sp) }
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 12.dp, tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        { copies = ((copies.toIntOrNull() ?: 1) - 1).coerceAtLeast(1).toString() },
                        Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)
                    ) { Text("−", fontSize = 22.sp) }
                    OutlinedTextField(
                        copies, { copies = it.filter(Char::isDigit).take(4) },
                        Modifier.width(80.dp), singleLine = true,
                        textStyle = TextStyle(textAlign = TextAlign.Center, fontSize = 18.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedButton(
                        { copies = ((copies.toIntOrNull() ?: 0) + 1).coerceAtMost(9999).toString() },
                        Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)
                    ) { Text("+", fontSize = 22.sp) }
                    Button(
                        { doPrint(t, (copies.toIntOrNull() ?: 1).coerceIn(1, 9999)) },
                        Modifier.weight(1f).height(52.dp), enabled = !busy, shape = RoundedCornerShape(16.dp)
                    ) { Text(if (busy) "යවනවා…" else "ප්‍රින්ට් · Print", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ dlg = "tpls" }, Modifier.weight(1f)) {
                    Text("🔍 " + t.name.ifBlank { "Template සොයන්න (${list.size})" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalButton({ t = Tpl() }) { Text("+ අලුත්") }
            }

            Card(colors = cardColors, shape = cardShape) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bmp, null,
                        Modifier.fillMaxWidth().aspectRatio(t.w.toFloat() / t.h).border(1.dp, line, RoundedCornerShape(8.dp))
                    )
                    Text("Preview · ${t.w}×${t.h} mm", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                }
            }

            Card(colors = cardColors, shape = cardShape) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DateInput("නි.පා.දි · Mfd Date", t.mfd, { s -> t = t.setMfd(s) }) { dlg = "mfd" }
                    DateInput("ක.ඉ.දිනය · Exp Date", t.exp, { s -> t = t.copy(exp = s) }) { dlg = "exp" }
                    Text("Exp ඉබේම = Mfd + 1 වසර − 1 දවස. අවශ්‍ය නම් type කරලා හෝ 📅 එකෙන් වෙනස් කරන්න.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        t.batch, { t = t.copy(batch = it) }, Modifier.fillMaxWidth(),
                        label = { Text("කාණ්ඩ අංකය · Batch No") }, singleLine = true
                    )
                    OutlinedTextField(
                        t.price, { t = t.copy(price = it) }, Modifier.fillMaxWidth(),
                        label = { Text("මිල රු. · Price Rs") }, singleLine = true
                    )
                    Row(Modifier.fillMaxWidth().clickable { t = t.copy(wOn = !t.wOn) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(t.wOn, { t = t.copy(wOn = it) })
                        Text("බර · Weight (ලේබලයේ වම් පැත්තෙන් හරවලා print වෙනවා)", fontSize = 14.sp)
                    }
                    if (t.wOn) OutlinedTextField(
                        t.wt, { t = t.copy(wt = it) }, Modifier.fillMaxWidth(),
                        label = { Text("බර (උදා: 50g)") }, singleLine = true
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ dlg = "save" }, Modifier.weight(1f)) { Text("සේව්") }
                OutlinedButton({ dlg = "design" }, Modifier.weight(1f)) { Text("Design") }
                OutlinedButton(
                    {
                        list = list.filter { it.name != t.name }
                        store.saveTemplates(list); t = Tpl()
                    },
                    Modifier.weight(1f), enabled = t.name != "" && list.any { it.name == t.name }
                ) { Text("මකන්න") }
            }
        }
    }

    // ---- Dialogs ----
    if (dlg == "mfd" || dlg == "exp") {
        val cur = runCatching { LocalDate.parse(if (dlg == "mfd") t.mfd else t.exp, DF) }.getOrDefault(LocalDate.now())
        val st = rememberDatePickerState(initialSelectedDateMillis = cur.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { dlg = "" },
            confirmButton = {
                TextButton({
                    st.selectedDateMillis?.let { ms ->
                        val s = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().format(DF)
                        t = if (dlg == "mfd") t.setMfd(s) else t.copy(exp = s)
                    }
                    dlg = ""
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ dlg = "" }) { Text("Cancel") } }
        ) { DatePicker(st) }
    }

    if (dlg == "save") {
        var nm by remember { mutableStateOf(t.name) }
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Template සේව් කරන්න") },
            text = {
                Column {
                    OutlinedTextField(nm, { nm = it }, label = { Text("නම · Name") }, singleLine = true)
                    Text("කලින් තියෙන නමම දුන්නොත් ඒ template එක update වෙනවා.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = nm.isNotBlank(), onClick = {
                    val n = nm.trim()
                    val x = t.copy(name = n)
                    list = if (list.any { it.name == n }) list.map { if (it.name == n) x else it } else list + x
                    store.saveTemplates(list); t = x; dlg = ""
                }) { Text("සේව්") }
            },
            dismissButton = { TextButton({ dlg = "" }) { Text("Cancel") } }
        )
    }

    if (dlg == "design") {
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("ලේබල් Design") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(
                        bmp, null,
                        Modifier.fillMaxWidth().aspectRatio(t.w.toFloat() / t.h).border(1.dp, line, RoundedCornerShape(8.dp))
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumField("Width mm", t.w, 10, 110, Modifier.weight(1f)) { t = t.copy(w = it) }
                        NumField("Height mm", t.h, 10, 100, Modifier.weight(1f)) { t = t.copy(h = it) }
                        NumField("Gap mm", t.gap, 0, 10, Modifier.weight(1f)) { t = t.copy(gap = it) }
                    }
                    Text("අකුරු ප්‍රමාණය ${(t.scale * 100).toInt()}%", fontSize = 13.sp)
                    Slider(t.scale, { t = t.copy(scale = it) }, valueRange = 0.7f..1.3f)
                    Text("Margins & section space (mm)", fontWeight = FontWeight.Bold)
                    MarginSlider("Left", t.ml) { t = t.copy(ml = it) }
                    MarginSlider("Right", t.mr) { t = t.copy(mr = it) }
                    MarginSlider("Top", t.mt) { t = t.copy(mt = it) }
                    MarginSlider("Bottom", t.mb) { t = t.copy(mb = it) }
                    MarginSlider("Space", t.sp, 2f) { t = t.copy(sp = it) }
                    Text("Label text (සිංහල · English · தமிழ்)", fontWeight = FontWeight.Bold)
                    val small = TextStyle(fontSize = 13.sp)
                    for (i in 0..3) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(t.si[i], { v -> t = t.copy(si = t.si.toMutableList().also { it[i] = v }) }, Modifier.weight(1f), textStyle = small, singleLine = true)
                        OutlinedTextField(t.en[i], { v -> t = t.copy(en = t.en.toMutableList().also { it[i] = v }) }, Modifier.weight(1f), textStyle = small, singleLine = true)
                        OutlinedTextField(t.ta[i], { v -> t = t.copy(ta = t.ta.toMutableList().also { it[i] = v }) }, Modifier.weight(1f), textStyle = small, singleLine = true)
                    }
                    OutlinedTextField(
                        t.wPre, { t = t.copy(wPre = it) }, Modifier.fillMaxWidth(),
                        label = { Text("බර ලේබලය (පැත්තෙන් එන අකුරු)") }, singleLine = true
                    )
                    Text("(:) ඉබේම වැටෙනවා. වෙනස්කම් තියාගන්න පස්සේ 'සේව්' ඔබන්න.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton({ dlg = "" }) { Text("OK") } }
        )
    }

    if (dlg == "tpls") {
        var q by remember { mutableStateOf("") }
        val shown = list.filter { it.name.contains(q.trim(), ignoreCase = true) }.sortedBy { it.name.lowercase() }
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Templates (${list.size})") },
            text = {
                Column {
                    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("🔍 සොයන්න · Search") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    if (shown.isEmpty()) Text("කිසිවක් හමු වුණේ නෑ.")
                    else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(shown) { x ->
                            Column(Modifier.fillMaxWidth().clickable { t = x; dlg = "" }.padding(vertical = 10.dp)) {
                                Text(x.name, fontWeight = FontWeight.Bold)
                                Text("${x.batch.ifBlank { "—" }} · Rs ${x.price.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ dlg = "" }) { Text("Close") } }
        )
    }

    if (dlg == "history") {
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Print history") },
            text = {
                if (hist.isEmpty()) Text("තාම print කරලා නෑ.")
                else LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(hist) { h ->
                        Row(
                            Modifier.fillMaxWidth().clickable { t = h.t; copies = h.n.toString(); dlg = "" },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${h.t.batch.ifBlank { "—" }} · Rs ${h.t.price.ifBlank { "—" }} · ×${h.n}", fontWeight = FontWeight.Bold)
                                Text("${h.t.mfd} → ${h.t.exp}\n${fmtTime(h.ts)}", style = MaterialTheme.typography.bodySmall)
                            }
                            FilledTonalButton({ dlg = ""; doPrint(h.t, h.n) }, enabled = !busy) { Text("Reprint") }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ dlg = "" }) { Text("Close") } },
            dismissButton = { TextButton({ hist = emptyList(); store.clearHistory() }) { Text("Clear") } }
        )
    }

    if (dlg == "settings") {
        var c by remember { mutableStateOf(cfg) }
        var ps by remember { mutableStateOf(cfg.port.toString()) }
        val devs = remember(perm) { pairedPrinters(ctx) }
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Settings") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Printer", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = c.mode == "bt", onClick = { c = c.copy(mode = "bt") }, label = { Text("Bluetooth") })
                        FilterChip(selected = c.mode == "wifi", onClick = { c = c.copy(mode = "wifi") }, label = { Text("WiFi") })
                    }
                    if (c.mode == "bt") {
                        if (!perm) TextButton({ launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Bluetooth permission දෙන්න") }
                        if (devs.isEmpty()) Text("Phone Settings → Bluetooth වල printer එක Pair කරන්න (PIN: 0000 හෝ 1234). ඊට පස්සේ මෙතන පෙන්නනවා.", style = MaterialTheme.typography.bodySmall)
                        devs.forEach { (n, a) ->
                            Row(Modifier.fillMaxWidth().clickable { c = c.copy(bt = a) }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(c.bt == a, { c = c.copy(bt = a) })
                                Text("$n\n$a", fontSize = 14.sp)
                            }
                        }
                    } else {
                        OutlinedTextField(c.ip, { c = c.copy(ip = it) }, label = { Text("Printer IP") }, singleLine = true)
                        OutlinedTextField(ps, { ps = it.filter(Char::isDigit).take(5) }, label = { Text("Port (සාමාන්‍යයෙන් 9100)") }, singleLine = true)
                        Text("Phone එක printer එකේ WiFi එකට connect වෙලා ඉන්න ඕන.", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(c.rot, { c = c.copy(rot = it) })
                        Spacer(Modifier.width(8.dp))
                        Text("ලේබලය 180° හරවන්න\n(උඩු යටිකුරු එනවා නම්)", fontSize = 14.sp)
                    }
                    Text("Theme", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = c.theme == "auto", onClick = { c = c.copy(theme = "auto") }, label = { Text("Auto") })
                        FilterChip(selected = c.theme == "light", onClick = { c = c.copy(theme = "light") }, label = { Text("Light") })
                        FilterChip(selected = c.theme == "dark", onClick = { c = c.copy(theme = "dark") }, label = { Text("Dark") })
                    }
                }
            },
            confirmButton = {
                TextButton({ setCfg(c.copy(port = ps.toIntOrNull() ?: 9100)); dlg = "" }) { Text("සේව්") }
            },
            dismissButton = { TextButton({ dlg = "" }) { Text("Cancel") } }
        )
    }
}
