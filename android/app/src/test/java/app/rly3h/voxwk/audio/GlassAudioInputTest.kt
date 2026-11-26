package app.rly3h.voxwk.audio

import app.rly3h.voxwk.glass.GlassLink
import app.rly3h.voxwk.glass.gm.Commands
import app.rly3h.voxwk.glass.gm.Gm
import app.rly3h.voxwk.glass.gm.GmEvent
import app.rly3h.voxwk.glass.gm.GmStreamParser
import app.rly3h.voxwk.glass.gm.Tlv
import app.rly3h.voxwk.glass.gm.hex
import app.rly3h.voxwk.glass.gm.unhex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GlassAudioInputTest {
  private fun pcm(fill: Int, size: Int) = ByteArray(size) { fill.toByte() }

  @Test
  fun rechunkerGroupsFivePacketsIntoOneChunk() {
    val out = mutableListOf<ByteArray>()
    val r = PcmRechunker(3200)
    repeat(5) { r.add(pcm(it, 640), out::add) }
    assertEquals(listOf(3200), out.map { it.size })
    assertArrayEquals(pcm(0, 640) + pcm(1, 640) + pcm(2, 640) + pcm(3, 640) + pcm(4, 640), out[0])
  }

  @Test
  fun rechunkerCarriesTheRemainderIntoTheNextChunk() {
    val out = mutableListOf<ByteArray>()
    val r = PcmRechunker(1280)
    repeat(5) { r.add(pcm(it, 640), out::add) }
    assertEquals(listOf(1280, 1280), out.map { it.size })
    r.add(pcm(5, 640), out::add)
    assertEquals(3, out.size)
    assertArrayEquals(pcm(4, 640) + pcm(5, 640), out[2])
  }

  @Test
  fun micStreams() {
    assertTrue(isMicStream(0x92))
    assertTrue(isMicStream(0x93))
    listOf(0x90, 0x91, 0x94).forEach { assertFalse(isMicStream(it)) }
  }

  /**
   * The Glass side of a [GlassLink]: answers every GM request with STATUS [gmStatus], and answers the recording
   * commands like the firmware does. [onStart] returns the bytes sent after the n-th `52 01 00` (1-based).
   */
  private class FakeGlass(
    private val gmStatus: Int = 0,
    private val closeRecAfterStart: Boolean = false,
    private val onStart: (Int) -> ByteArray = { ByteArray(0) },
  ) {
    private val cmdOut = PipedOutputStream()
    private val cmdGlassIn = PipedInputStream(cmdOut, 1 shl 16)
    private val cmdGlassOut = PipedOutputStream()
    private val cmdIn = PipedInputStream(cmdGlassOut, 1 shl 16)
    private val recOut = PipedOutputStream()
    private val recGlassIn = PipedInputStream(recOut, 1 shl 16)
    private val recGlassOut = PipedOutputStream()
    private val recIn = PipedInputStream(recGlassOut, 1 shl 16)

    /** Bodies of the received `01/2F` requests. */
    val audioProcessing = LinkedBlockingQueue<ByteArray>()

    /** Hex of every recording command received. */
    val recLog = LinkedBlockingQueue<String>()

    init {
      Thread {
        val parser = GmStreamParser()
        val buf = ByteArray(1024)
        while (true) {
          val n = runCatching { cmdGlassIn.read(buf) }.getOrDefault(-1)
          if (n < 0) break
          parser.feed(buf, n).filterIsInstance<GmEvent.Message>().forEach { ev ->
            val m = ev.message
            if (m.service == Commands.SYSTEM && m.command == Commands.AUDIO_PROCESSING) audioProcessing.add(m.body)
            cmdGlassOut.write(Gm.encodeJoined(m.event, m.service, m.command, Tlv.encode(Tlv.STATUS, byteArrayOf(gmStatus.toByte()))))
            cmdGlassOut.flush()
          }
        }
      }.apply { isDaemon = true; start() }

      Thread {
        var starts = 0
        val cmd = ByteArray(3)
        while (true) {
          var got = 0
          while (got < 3) {
            val n = runCatching { recGlassIn.read(cmd, got, 3 - got) }.getOrDefault(-1)
            if (n < 0) return@Thread
            got += n
          }
          recLog.add(cmd.hex())
          when (cmd[1].toInt()) {
            0x12 -> recGlassOut.write(byteArrayOf(0x52, 0x13, cmd[2], 0x31, 0x28, 0x00))
            0x01 -> {
              recGlassOut.write("52 11 00 01 02 03".unhex())
              recGlassOut.write(onStart(++starts))
            }
          }
          recGlassOut.flush()
          if (closeRecAfterStart && cmd[1].toInt() == 0x01) recGlassOut.close()
        }
      }.apply { isDaemon = true; start() }
    }

    val link = GlassLink(cmdIn, cmdOut, recIn, recOut)

    fun nextRecCommand(): String = recLog.poll(TIMEOUT_S, TimeUnit.SECONDS) ?: error("no recording command in ${TIMEOUT_S}s")
  }

  private class Capture(input: AudioInput, format: CaptureFormat) {
    val running = AtomicBoolean(true)
    val chunks = LinkedBlockingQueue<ByteArray>()
    val done = CompletableFuture<Throwable?>()

    init {
      Thread { done.complete(runCatching { input.capture(format, running, chunks::add) }.exceptionOrNull()) }
        .apply { isDaemon = true; start() }
    }

    fun nextChunk(): ByteArray = chunks.poll(TIMEOUT_S, TimeUnit.SECONDS) ?: error("no chunk in ${TIMEOUT_S}s")
  }

  /** One recording event: [count] packets of 40 bytes, all filled with [fill], on [stream]. */
  private fun audio(stream: Int, count: Int, fill: Int) =
    "52 ${"%02X".format(stream)} 00 00 09 FF ${"%02X".format(count)} 00 00 00 00 FF".unhex() +
      ByteArray(count * 40) { fill.toByte() }

  // Each decoded packet is 640 bytes filled with the packet's first byte, so a chunk shows which packets it holds.
  private fun fakeDecode(packet: ByteArray) = ByteArray(640) { packet[0] }

  private val format100 = CaptureFormat(16000, 100, CapturePurpose.RECOGNITION)

  @Test
  fun captureRestartsAfterScoAndStopsOnRequest() {
    val glass = FakeGlass(onStart = { n ->
      when (n) {
        1 -> audio(0x92, 5, 0x10) + audio(0x91, 3, 0x20) + audio(0x93, 5, 0x30)
        2 -> audio(0x92, 5, 0x40)
        else -> ByteArray(0)
      }
    })
    val input = GlassAudioInput(glass.link, ::fakeDecode, pickupMode = 1, restartAfterMs = 200)
    val capture = Capture(input, format100)
    try {
      // 0x92 and 0x93 are the mic; the 0x91 packets sit between them and must not appear.
      assertArrayEquals(pcm(0x10, 3200), capture.nextChunk())
      assertArrayEquals(pcm(0x30, 3200), capture.nextChunk())
      val silenceStartNs = System.nanoTime()
      assertArrayEquals(Commands.audioProcessing(0, 1), glass.audioProcessing.poll(TIMEOUT_S, TimeUnit.SECONDS))

      // Silence follows, so the capture negotiates and starts again, and the peer's answer yields a third chunk.
      assertEquals(listOf("52 12 01", "52 01 00"), List(2) { glass.nextRecCommand() })
      assertEquals("52 12 01", glass.nextRecCommand())
      val restartMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - silenceStartNs)
      assertTrue("restart took $restartMs ms", restartMs < 1000)
      assertEquals("52 01 00", glass.nextRecCommand())
      assertArrayEquals(pcm(0x40, 3200), capture.nextChunk())

      capture.running.set(false)
      // The silence goes on, so more restarts may come before the stop.
      generateSequence { glass.nextRecCommand() }.first { it == "52 00 00" }
      assertNull(capture.done.get(TIMEOUT_S, TimeUnit.SECONDS))
      assertNull(glass.link.onAudio)
    } finally {
      capture.running.set(false)
      glass.link.close()
    }
  }

  @Test
  fun captureThrowsWhenTheGlassStaysSilentThroughTheRestartLimit() {
    val glass = FakeGlass()
    val input = GlassAudioInput(glass.link, ::fakeDecode, pickupMode = 0, restartAfterMs = 50)
    val capture = Capture(input, format100)
    try {
      val error = capture.done.get(TIMEOUT_S, TimeUnit.SECONDS)
      assertTrue(error.toString(), error is IOException)
      assertEquals("Glass stopped sending audio", error!!.message)
      // The first start and 10 restarts; the 11th stall ends the capture instead of starting again.
      assertEquals(11, glass.recLog.count { it == "52 01 00" })
    } finally {
      glass.link.close()
    }
  }

  @Test
  fun captureThrowsWhenTheRecordingLinkCloses() {
    val glass = FakeGlass(closeRecAfterStart = true)
    val input = GlassAudioInput(glass.link, ::fakeDecode, pickupMode = 0, restartAfterMs = 200)
    val capture = Capture(input, format100)
    try {
      val error = capture.done.get(TIMEOUT_S, TimeUnit.SECONDS)
      assertTrue(error.toString(), error is IOException)
      assertEquals("Glass link closed", error!!.message)
    } finally {
      glass.link.close()
    }
  }

  @Test
  fun captureThrowsWhenTheGlassRejectsTheAudioSettings() {
    val glass = FakeGlass(gmStatus = 1)
    val input = GlassAudioInput(glass.link, ::fakeDecode, pickupMode = 0)
    val capture = Capture(input, format100)
    try {
      assertTrue(capture.done.get(TIMEOUT_S, TimeUnit.SECONDS) is IOException)
      assertTrue(glass.recLog.isEmpty())
    } finally {
      glass.link.close()
    }
  }

  @Test
  fun chunksAreResampledTo24kHz() {
    val glass = FakeGlass(onStart = { n -> if (n == 1) audio(0x92, 5, 0x10) else ByteArray(0) })
    val input = GlassAudioInput(glass.link, ::fakeDecode, pickupMode = 0, restartAfterMs = 60_000)
    val capture = Capture(input, CaptureFormat(24000, 40, CapturePurpose.COMMUNICATION))
    try {
      // 5 x 640 bytes make two 1280-byte 16 kHz chunks, 1920 bytes each at 24 kHz.
      repeat(2) { assertEquals(1920, capture.nextChunk().size) }
    } finally {
      capture.running.set(false)
      glass.link.close()
    }
  }

  private companion object {
    const val TIMEOUT_S = 10L
  }
}
