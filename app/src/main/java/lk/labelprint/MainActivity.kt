package lk.labelprint

import android.Manifest
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF3D5AFE), onPrimary = Color.White,
                    background = Color(0xFFF4F6FB), surface = Color.White,
                    surfaceVariant = Color(0xFFE8ECF8)
                )
            ) { Root(this) }
        }
    }
}

@Composable
fun DateField(label: String, value: String, onClick: () -> Unit) {
    Box {
        OutlinedTextField(
            value, {}, readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { Icon(Icons.Default.DateRange, null) }, modifier = Modifier.fillMaxWidth()
        )
        Box(Modifier.matchParentSize().clickable(onClick = onClick))
    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(ctx: Context) {
    val store = remember { Store(ctx) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var list by remember { mutableStateOf(store.templates()) }
    var t by remember { mutableStateOf(Tpl()) }
    var copies by remember { mutableStateOf("1") }
    var cfg by remember { mutableStateOf(store.cfg) }
    var dlg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val bmp = remember(t) { renderLabel(t).asImageBitmap() }

    var perm by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 31 ||
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perm = it }
    LaunchedEffect(Unit) { if (!perm) launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }

    fun doPrint() {
        val n = (copies.toIntOrNull() ?: 1).coerceIn(1, 9999)
        busy = true
        scope.launch {
            val msg = try {
                sendToPrinter(ctx, cfg, buildTspl(t, n, cfg.rot))
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
                actions = { IconButton({ dlg = "settings" }) { Icon(Icons.Default.Settings, "Settings") } }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 12.dp, color = Color.White) {
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
                        { doPrint() }, Modifier.weight(1f).height(52.dp), enabled = !busy,
                        shape = RoundedCornerShape(16.dp)
                    ) { Text(if (busy) "යවනවා…" else "ප්‍රින්ට් · Print", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = t.name == "", onClick = { t = Tpl() }, label = { Text("+ අලුත්") })
                }
                items(list) { x ->
                    FilterChip(selected = x.name == t.name, onClick = { t = x }, label = { Text(x.name) })
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bmp, null,
                        Modifier.fillMaxWidth().aspectRatio(t.w.toFloat() / t.h)
                            .border(1.dp, Color(0xFFCFD5E6), RoundedCornerShape(8.dp))
                    )
                    Text("Preview · ${t.w}×${t.h} mm", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DateField("නිශ්. දිනය · Mfd Date", t.mfd) { dlg = "mfd" }
                    DateField("ක.ඉ.දිනය · Exp Date", t.exp) { dlg = "exp" }
                    OutlinedTextField(
                        t.batch, { t = t.copy(batch = it) }, Modifier.fillMaxWidth(),
                        label = { Text("කාණ්ඩ අංකය · Batch No") }, singleLine = true
                    )
                    OutlinedTextField(
                        t.price, { t = t.copy(price = it) }, Modifier.fillMaxWidth(),
                        label = { Text("මිල රු. · Price Rs") }, singleLine = true
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
                        t = if (dlg == "mfd") t.copy(mfd = s) else t.copy(exp = s)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumField("Width mm", t.w, 10, 110, Modifier.weight(1f)) { t = t.copy(w = it) }
                        NumField("Height mm", t.h, 10, 100, Modifier.weight(1f)) { t = t.copy(h = it) }
                        NumField("Gap mm", t.gap, 0, 10, Modifier.weight(1f)) { t = t.copy(gap = it) }
                    }
                    Text("අකුරු ප්‍රමාණය ${(t.scale * 100).toInt()}%")
                    Slider(t.scale, { t = t.copy(scale = it) }, valueRange = 0.7f..1.3f)
                    for (i in 0..3) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            t.si[i], { v -> t = t.copy(si = t.si.toMutableList().also { it[i] = v }) },
                            Modifier.weight(1f), label = { Text("සිංහල ${i + 1}") }, singleLine = true
                        )
                        OutlinedTextField(
                            t.en[i], { v -> t = t.copy(en = t.en.toMutableList().also { it[i] = v }) },
                            Modifier.weight(1f), label = { Text("English ${i + 1}") }, singleLine = true
                        )
                    }
                    Text("(:) ඉබේම වැටෙනවා. වෙනස්කම් තියාගන්න පස්සේ 'සේව්' ඔබන්න.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton({ dlg = "" }) { Text("OK") } }
        )
    }

    if (dlg == "settings") {
        var c by remember { mutableStateOf(cfg) }
        var ps by remember { mutableStateOf(cfg.port.toString()) }
        val devs = remember(perm) { pairedPrinters(ctx) }
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Printer Settings") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                }
            },
            confirmButton = {
                TextButton({
                    c = c.copy(port = ps.toIntOrNull() ?: 9100)
                    cfg = c; store.cfg = c; dlg = ""
                }) { Text("සේව්") }
            },
            dismissButton = { TextButton({ dlg = "" }) { Text("Cancel") } }
        )
    }
}
