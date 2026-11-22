package app.rly3h.voxwk.glass

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.RequiresPermission
import app.rly3h.voxwk.glass.gm.GmSession
import app.rly3h.voxwk.glass.gm.RecordingSession
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * The command and recording links to the Glass for one session. The official App and voxwk cannot share the
 * Glass, so a link is opened when a session starts and closed when it ends.
 */
class GlassLink internal constructor(
  cmdIn: InputStream,
  cmdOut: OutputStream,
  recIn: InputStream,
  recOut: OutputStream,
  private val sockets: List<Closeable> = emptyList(),
) : AutoCloseable {
  /** The attached capture's packet sink. Packets that arrive while none is attached are dropped. */
  @Volatile
  var onAudio: ((stream: Int, packets: List<ByteArray>) -> Unit)? = null

  /** True once either reader has ended (EOF or read error). */
  @Volatile
  var isClosed = false
    private set

  // Declared after the fields above: the reader threads start in the session constructors and may call back at once.
  val gm = GmSession(cmdIn, cmdOut, onClosed = { isClosed = true })
  val recording = RecordingSession(
    recIn, recOut,
    onAudio = { stream, packets -> onAudio?.invoke(stream, packets) },
    onClosed = { isClosed = true },
  )

  override fun close() {
    gm.close()
    recording.close()
    sockets.forEach { runCatching { it.close() } }
  }

  companion object {
    val CMD_UUID: UUID = UUID.fromString("00007033-0000-1000-8000-00805f9b34fb")
    val REC_UUID: UUID = UUID.fromString("00002024-0000-1000-8000-00805f9b34fb")

    /** Blocks; run on IO. SecurityException without BLUETOOTH_CONNECT; IOException when either socket fails. */
    fun open(context: Context, address: String): GlassLink {
      if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        throw SecurityException("BLUETOOTH_CONNECT is not granted")
      }
      val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
      val cmd = connect(device, CMD_UUID)
      val rec = try {
        connect(device, REC_UUID)
      } catch (e: IOException) {
        runCatching { cmd.close() }
        throw e
      }
      return GlassLink(cmd.inputStream, cmd.outputStream, rec.inputStream, rec.outputStream, listOf(cmd, rec))
    }

    // Secure RFCOMM only: the Glass accepts it on both services, and an insecure attempt would only delay the error.
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connect(device: BluetoothDevice, uuid: UUID): BluetoothSocket {
      val socket = device.createRfcommSocketToServiceRecord(uuid)
      try {
        socket.connect()
      } catch (e: IOException) {
        runCatching { socket.close() }
        throw e
      }
      return socket
    }
  }
}
