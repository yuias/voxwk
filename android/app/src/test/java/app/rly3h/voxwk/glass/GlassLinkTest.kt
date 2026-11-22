package app.rly3h.voxwk.glass

import app.rly3h.voxwk.glass.gm.Commands
import app.rly3h.voxwk.glass.gm.Gm
import app.rly3h.voxwk.glass.gm.GmMessage
import app.rly3h.voxwk.glass.gm.Tlv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class GlassLinkTest {
  private val toPhone = PipedOutputStream()
  private val cmdIn = PipedInputStream(toPhone, 1 shl 16)
  private val recWriter = PipedOutputStream()
  private val link = GlassLink(cmdIn, PipedOutputStream(PipedInputStream(1 shl 16)), PipedInputStream(recWriter, 1 shl 16), PipedOutputStream(PipedInputStream(1 shl 16)))

  @After
  fun tearDown() {
    link.close()
    runCatching { toPhone.close() }
    runCatching { recWriter.close() }
  }

  private fun uploadFrame(index: Int) = Gm.encodeJoined(
    0x80, Commands.SYSTEM, Commands.SCREENSHOT_UPLOAD,
    Tlv.concat(Tlv.int16(index), Tlv.json("{}"), Tlv.bytes(ByteArray(0))),
  )

  private fun write(frame: ByteArray) {
    toPhone.write(frame)
    toPhone.flush()
  }

  @Test
  fun unmatchedMessageReachesTheSink() {
    val inbox = LinkedBlockingQueue<GmMessage>()
    link.onGmMessage = inbox::add
    write(uploadFrame(0xFFFE))
    val m = inbox.poll(1, TimeUnit.SECONDS)!!
    assertEquals(Commands.SYSTEM, m.service)
    assertEquals(Commands.SCREENSHOT_UPLOAD, m.command)
  }

  @Test
  fun messageWithoutSinkIsDropped() {
    write(uploadFrame(0xFFFE))
    val inbox = LinkedBlockingQueue<GmMessage>()
    // Gives the reader time to take the first frame while no sink is attached.
    Thread.sleep(200)
    link.onGmMessage = inbox::add
    write(uploadFrame(0xFFFD))
    val m = inbox.poll(1, TimeUnit.SECONDS)!!
    assertEquals(0xFFFD, m.tlvs.first().int.toInt())
    assertFalse(link.isClosed)
  }
}
