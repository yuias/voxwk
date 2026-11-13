package app.rly3h.voxwk.poc.glass

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rly3h.voxwk.glass.gm.Commands
import java.io.File
import java.util.concurrent.Executors

@SuppressLint("MissingPermission") // Every Bluetooth call is gated on hasPermission.
class MainActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val logLines = mutableStateListOf<String>()
    private val devices = mutableStateListOf<BluetoothDevice>()
    private var hasPermission by mutableStateOf(false)
    private lateinit var link: GlassLink

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) loadDevices() else log("BLUETOOTH_CONNECT denied")
    }

    private val sdpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // The typed getters need API 33; minSdk is 31.
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            @Suppress("DEPRECATION")
            val uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
            log("SDP ${device?.address}: ${uuids?.joinToString() ?: "none"}")
        }
    }

    private fun log(line: String) = main.post {
        logLines += line
        if (logLines.size > 500) logLines.removeRange(0, logLines.size - 500)
    }

    private fun bg(block: () -> Unit) = io.execute { runCatching(block).onFailure { log("error: $it") } }

    private fun loadDevices() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        devices.clear()
        devices += adapter?.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }
        log("${devices.size} bonded devices. Pair the glasses in system settings if missing.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(getExternalFilesDir(null), "glass-poc").apply { mkdirs() }
        link = GlassLink(dir) { log(it) }
        log("captures: ${dir.path}")
        log("wire log: ${link.wireLogPath}")
        registerReceiver(sdpReceiver, IntentFilter(BluetoothDevice.ACTION_UUID), RECEIVER_EXPORTED)
        hasPermission = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) loadDevices() else requestPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(sdpReceiver)
        io.execute { link.close() }
        io.shutdown()
        super.onDestroy()
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Screen() {
        var selected by remember { mutableStateOf<BluetoothDevice?>(null) }
        var noise by remember { mutableStateOf(0) }
        var pickup by remember { mutableStateOf<Int?>(null) }
        var compact by remember { mutableStateOf(true) }
        var conversation by remember { mutableStateOf(false) }
        var asciiOnly by remember { mutableStateOf(false) }
        var title by remember { mutableStateOf("voxwk") }
        var source by remember { mutableStateOf("Bonjour, je voudrais un café, s'il vous plaît.") }
        var translation by remember { mutableStateOf("こんにちは、コーヒーを一杯お願いします。") }
        var burstHz by remember { mutableStateOf("5") }

        val provisionalCmd = if (conversation) Commands.CONV_PROVISIONAL else Commands.TR_PROVISIONAL
        val finalCmd = if (conversation) Commands.CONV_FINAL else Commands.TR_FINAL

        Column(Modifier.safeDrawingPadding().padding(8.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    devices.forEach { d ->
                        FilterChip(selected = d == selected, onClick = { selected = d }, label = { Text(d.name ?: d.address) })
                    }
                    if (!hasPermission) Button({ requestPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Permission") }
                }
                val device = selected
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button({ device?.let { d -> bg { link.describe(d); d.fetchUuidsWithSdp() } } }, enabled = device != null) { Text("SDP") }
                    Button({ device?.let { d -> bg { link.connectCommand(d) {} } } }, enabled = device != null) { Text("Connect cmd") }
                    Button({ device?.let { d -> bg { link.connectRecording(d) } } }, enabled = device != null) { Text("Connect rec") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button({ bg { link.request("01/01 heartbeat", Commands.SYSTEM, Commands.HEARTBEAT) } }) { Text("Heartbeat") }
                    Button({ bg { link.request("01/02 info", Commands.SYSTEM, Commands.DEVICE_INFO) } }) { Text("Info") }
                    Button({ bg { link.request("01/06 status", Commands.SYSTEM, Commands.DEVICE_STATUS) } }) { Text("Status") }
                }

                HorizontalDivider()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0 to "ENC", 1 to "raw").forEach { (v, l) -> FilterChip(noise == v, { noise = v }, { Text(l) }) }
                    listOf<Int?>(null, 0, 1, 2, 3, 4).forEach { v ->
                        FilterChip(pickup == v, { pickup = v }, { Text(v?.let { "pickup $it" } ?: "omit") })
                    }
                    Button({ bg { link.setAudio(noise, pickup) } }) { Text("Set 01/2F") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(compact, { compact = !compact }, { Text(if (compact) "compact" else "legacy") })
                    Button({ bg { link.startRecording(compact) } }) { Text("Rec start") }
                    Button({ bg { link.stopRecording() } }) { Text("Rec stop") }
                }

                HorizontalDivider()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(conversation, { conversation = !conversation }, { Text(if (conversation) "conversation 05-08" else "simultaneous 01-04") })
                    FilterChip(asciiOnly, { asciiOnly = !asciiOnly }, { Text(if (asciiOnly) "JSON \\u escapes" else "JSON raw UTF-8") })
                }
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("title") }, singleLine = true)
                OutlinedTextField(source, { source = it }, Modifier.fillMaxWidth(), label = { Text("source (may be empty)") })
                OutlinedTextField(translation, { translation = it }, Modifier.fillMaxWidth(), label = { Text("translation (may be empty)") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button({
                        bg {
                            if (conversation) link.request("03/05 conv start", Commands.TRANSLATION, Commands.CONV_START, Commands.title(title))
                            else link.request("03/01 start", Commands.TRANSLATION, Commands.TR_START, Commands.title(title))
                        }
                    }) { Text("Start") }
                    Button({
                        bg { link.request("03/%02X provisional".format(provisionalCmd), Commands.TRANSLATION, provisionalCmd, Commands.result(translation, source, asciiOnly)) }
                    }) { Text("Provisional") }
                    Button({
                        bg { link.request("03/%02X final".format(finalCmd), Commands.TRANSLATION, finalCmd, Commands.result(translation, source, asciiOnly)) }
                    }) { Text("Final") }
                    Button({
                        bg {
                            if (conversation) link.request("03/06 conv stop", Commands.TRANSLATION, Commands.CONV_STOP, Commands.conversationStop(0))
                            else link.request("03/02 stop", Commands.TRANSLATION, Commands.TR_STOP)
                        }
                    }) { Text("Stop") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0 to 0, 0 to 1, 1 to 1).forEach { (src, dir) ->
                        Button({
                            bg { link.request("03/0C source=$src dir=$dir", Commands.TRANSLATION, Commands.TR_RECORDING_SOURCE, Commands.recordingSource(src, dir)) }
                        }) { Text("03/0C ${if (src == 0) "glasses" else "phone"} ${if (dir == 0) "front" else "omni"}") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(burstHz, { burstHz = it }, Modifier.weight(1f), label = { Text("burst Hz") }, singleLine = true)
                    Button({
                        val hz = burstHz.toIntOrNull()?.coerceIn(1, 100) ?: 5
                        bg {
                            // Grows the source text one character at a time, like streaming ASR, without waiting for ACKs.
                            val period = 1000L / hz
                            for (i in 1..minOf(source.length, hz * 10)) {
                                link.post(Commands.TRANSLATION, provisionalCmd, Commands.result("", source.take(i), asciiOnly))
                                Thread.sleep(period)
                            }
                            link.request("burst end final", Commands.TRANSLATION, finalCmd, Commands.result(translation, "", asciiOnly))
                        }
                    }) { Text("Burst provisional") }
                }
            }
            HorizontalDivider()
            LogView(Modifier.weight(1f))
        }
    }

    @Composable
    private fun LogView(modifier: Modifier) {
        val state = rememberLazyListState()
        LaunchedEffect(logLines.size) { if (logLines.isNotEmpty()) state.scrollToItem(logLines.size - 1) }
        LazyColumn(modifier.fillMaxWidth(), state) {
            items(logLines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}
