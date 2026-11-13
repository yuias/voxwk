package app.rly3h.voxwk.poc.glass

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import app.rly3h.voxwk.glass.gm.Commands
import app.rly3h.voxwk.glass.gm.GmMessage
import app.rly3h.voxwk.glass.gm.GmSession
import app.rly3h.voxwk.glass.gm.RecordingSession
import app.rly3h.voxwk.glass.gm.WireTap
import app.rly3h.voxwk.glass.gm.hex
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the two Classic SPP sockets and writes every byte that crosses them to `wire-*.log`. All methods block and
 * must run off the main thread.
 */
@SuppressLint("MissingPermission") // MainActivity requests BLUETOOTH_CONNECT before any call reaches here.
class GlassLink(private val dir: File, private val log: (String) -> Unit) {
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    private val wire = PrintWriter(FileOutputStream(File(dir, "wire-$stamp.log")), true)
    private val t0 = System.nanoTime()

    private var cmdSocket: BluetoothSocket? = null
    private var recSocket: BluetoothSocket? = null
    var gm: GmSession? = null
        private set
    private var rec: RecordingSession? = null
    // Written by the recording reader thread, swapped by start/stop.
    @Volatile
    private var recFile: FileOutputStream? = null
    private val audioPackets = AtomicInteger()

    val wireLogPath: String get() = File(dir, "wire-$stamp.log").path

    private fun tap(link: String) = WireTap { out, bytes ->
        synchronized(wire) {
            wire.println("%10.3f %s %s %s".format((System.nanoTime() - t0) / 1e9, link, if (out) ">" else "<", bytes.hex()))
        }
    }

    private fun note(text: String) {
        synchronized(wire) { wire.println("%10.3f # %s".format((System.nanoTime() - t0) / 1e9, text)) }
        log(text)
    }

    fun describe(device: BluetoothDevice) {
        note("device ${device.name} ${device.address} type=${device.type} bond=${device.bondState}")
        note("cached SDP UUIDs: ${device.uuids?.joinToString() ?: "none"}")
    }

    /** Tries each service UUID in order, secure then insecure RFCOMM, and logs every failure verbatim. */
    private fun open(device: BluetoothDevice, label: String, uuids: List<UUID>): BluetoothSocket? {
        for (uuid in uuids) for (secure in listOf(true, false)) {
            val kind = if (secure) "secure" else "insecure"
            val socket = runCatching {
                if (secure) device.createRfcommSocketToServiceRecord(uuid) else device.createInsecureRfcommSocketToServiceRecord(uuid)
            }.getOrElse { note("$label $uuid $kind: create failed: $it"); continue }
            val start = System.nanoTime()
            try {
                socket.connect()
                note("$label connected via $uuid ($kind) in %d ms".format((System.nanoTime() - start) / 1_000_000))
                return socket
            } catch (e: Exception) {
                note("$label $uuid $kind: connect failed after %d ms: $e".format((System.nanoTime() - start) / 1_000_000))
                runCatching { socket.close() }
            }
        }
        return null
    }

    fun connectCommand(device: BluetoothDevice, onUnsolicited: (GmMessage) -> Unit) {
        closeCommand()
        val socket = open(device, "cmd", listOf(CMD_UUID, SPP_FALLBACK_UUID)) ?: return
        cmdSocket = socket
        gm = GmSession(
            socket.inputStream, socket.outputStream, tap("cmd"),
            onUnsolicited = { note("cmd unsolicited: $it"); onUnsolicited(it) },
            onSkipped = { note("cmd skipped: $it") },
            onClosed = { note("cmd link closed: ${it ?: "eof"}") },
        )
    }

    fun connectRecording(device: BluetoothDevice) {
        closeRecording()
        val socket = open(device, "rec", listOf(REC_UUID)) ?: return
        recSocket = socket
        rec = RecordingSession(
            socket.inputStream, socket.outputStream,
            tap = { out, bytes ->
                // Audio during a recording goes to rec-*.bin; writes and bytes outside a recording go to the hex log.
                val f = recFile
                if (out || f == null) tap("rec").onBytes(out, bytes) else runCatching { f.write(bytes) }
            },
            onAudio = { stream, packets ->
                val n = audioPackets.addAndGet(packets.size)
                if (stream != 0x92) note("rec: stream %02X x%d (not 92)".format(stream, packets.size))
                if (n % 250 < packets.size) note("rec: $n Opus packets")
            },
            onSkipped = { note("rec skipped: $it") },
            onClosed = { note("rec link closed: ${it ?: "eof"}") },
        )
    }

    fun request(label: String, service: Int, command: Int, body: ByteArray = ByteArray(0)): GmMessage? {
        val session = gm ?: run { note("$label: command link not connected"); return null }
        val start = System.nanoTime()
        return runCatching { session.request(service, command, body) }
            .onSuccess { note("$label -> %s (%d ms)".format(it, (System.nanoTime() - start) / 1_000_000)) }
            .onFailure { note("$label failed: $it") }
            .getOrNull()
    }

    fun post(service: Int, command: Int, body: ByteArray) {
        gm?.post(service, command, body) ?: note("command link not connected")
    }

    fun setAudio(noise: Int, pickup: Int?) =
        request("01/2F noise=$noise pickup=${pickup ?: "omit"}", Commands.SYSTEM, Commands.AUDIO_PROCESSING, Commands.audioProcessing(noise, pickup))

    fun startRecording(compact: Boolean) {
        val r = rec ?: run { note("recording link not connected"); return }
        val mode = if (compact) "compact" else "legacy"
        // The decoder reads the effective format from the 52 13 reply inside the capture, so the name carries no mode.
        val file = File(dir, "rec-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.bin")
        recFile?.close()
        recFile = FileOutputStream(file)
        audioPackets.set(0)
        runCatching {
            val t = System.nanoTime()
            val neg = r.negotiate(compact)
            note("rec negotiate $mode -> ${neg?.toString() ?: "no 52 13 (legacy)"}")
            val started = r.start()
            note("rec start -> ${started?.toString() ?: "audio before 52 11"} after %d ms".format((System.nanoTime() - t) / 1_000_000))
            note("rec raw stream -> ${file.path}")
        }.onFailure { note("rec start failed: $it") }
    }

    fun stopRecording() {
        val r = rec ?: return
        runCatching { r.stop() }.onFailure { note("rec stop failed: $it") }
        recFile?.close()
        recFile = null
        note("rec stopped: ${audioPackets.get()} Opus packets")
    }

    private fun closeCommand() {
        gm?.close(); gm = null
        runCatching { cmdSocket?.close() }; cmdSocket = null
    }

    private fun closeRecording() {
        recFile?.close(); recFile = null
        rec?.close(); rec = null
        runCatching { recSocket?.close() }; recSocket = null
    }

    fun close() {
        closeRecording()
        closeCommand()
        wire.close()
    }

    companion object {
        val CMD_UUID: UUID = UUID.fromString("00007033-0000-1000-8000-00805f9b34fb")
        val REC_UUID: UUID = UUID.fromString("00002024-0000-1000-8000-00805f9b34fb")

        // The official App falls back to the standard Serial Port Profile UUID for commands.
        val SPP_FALLBACK_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
