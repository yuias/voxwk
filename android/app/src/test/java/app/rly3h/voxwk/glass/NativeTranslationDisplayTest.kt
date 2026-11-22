package app.rly3h.voxwk.glass

import app.rly3h.voxwk.glass.gm.Commands
import app.rly3h.voxwk.glass.gm.GmEvent
import app.rly3h.voxwk.glass.gm.GmMessage
import app.rly3h.voxwk.glass.gm.GmSession
import app.rly3h.voxwk.glass.gm.GmStreamParser
import app.rly3h.voxwk.glass.gm.Tlv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** [NativeTranslationDisplay] on a [GmSession] against a fake glasses peer over piped streams. */
class NativeTranslationDisplayTest {
  private val toGlasses = PipedOutputStream()
  private val glassesIn = PipedInputStream(toGlasses, 1 shl 16)
  private val toPhone = PipedOutputStream()
  private val phoneIn = PipedInputStream(toPhone, 1 shl 16)
  private val received = CopyOnWriteArrayList<GmMessage>()

  // The peer only records frames and never replies.
  private val peer = Thread {
    val parser = GmStreamParser()
    val buf = ByteArray(1024)
    while (true) {
      val n = runCatching { glassesIn.read(buf) }.getOrDefault(-1)
      if (n < 0) break
      parser.feed(buf, n).filterIsInstance<GmEvent.Message>().forEach { received += it.message }
    }
  }.apply { isDaemon = true; start() }

  private val session = GmSession(phoneIn, toGlasses)
  private val display = NativeTranslationDisplay(session, "voxwk")

  @After
  fun tearDown() {
    display.close()
    session.close()
    runCatching { toPhone.close() }
  }

  private fun awaitFrames(count: Int) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (received.size < count && System.nanoTime() < deadline) Thread.sleep(10)
    assertEquals(count, received.size)
  }

  @Test
  fun sendsTheFourFramesInOrder() {
    display.begin(false)
    display.show("Bonjour", "", false)
    display.show("Bonjour", "こんにちは", true)
    display.end()
    display.close()
    awaitFrames(4)

    assertEquals(
      listOf(Commands.TR_START, Commands.TR_PROVISIONAL, Commands.TR_FINAL, Commands.TR_STOP),
      received.map { it.command },
    )
    assertTrue(received.all { it.service == Commands.TRANSLATION })
    val title = received[0].tlvs.single()
    assertEquals(Tlv.STRING, title.type)
    assertEquals("voxwk", title.text)
    assertEquals(0, received[3].body.size)
  }

  @Test
  fun finalResultIsRawUtf8TranslationThenSource() {
    display.show("Bonjour", "こんにちは", true)
    display.close()
    awaitFrames(1)

    val json = received.single().tlvs.single()
    assertEquals(Tlv.JSON, json.type)
    assertEquals("[\"こんにちは\",\"Bonjour\"]", json.value.toString(Charsets.UTF_8))
  }

  @Test
  fun callsReturnWithoutWaitingForAReply() {
    val t0 = System.nanoTime()
    display.begin(false)
    display.show("Bonjour", "", false)
    display.show("Bonjour", "こんにちは", true)
    display.end()
    val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
    assertTrue("took $ms ms", ms < 100)
    display.close()
  }

  @Test
  fun doesNotThrowAfterThePeerClosesItsStreams() {
    glassesIn.close()
    toPhone.close()
    peer.join(10_000)
    repeat(3) { display.show("Bonjour", "こんにちは", it == 2) }
    display.close()
  }

  @Test
  fun dropsCallsAfterClose() {
    display.begin(false)
    display.close()
    awaitFrames(1)

    display.show("Bonjour", "こんにちは", true)
    display.end()
    display.close()
    Thread.sleep(200)
    assertEquals(1, received.size)
  }

  // The idle timeout is shortened to keep the real-time wait small; the 15 s default is not exercised.
  private fun idleDisplay(ms: Long) = NativeTranslationDisplay(session, "voxwk", ms)

  private fun commands() = received.map { it.command }

  @Test
  fun closesTheUiAfterTheIdleTimeout() {
    idleDisplay(200).use {
      it.begin(false)
      it.show("Bonjour", "", false)
      awaitFrames(3)
      assertEquals(listOf(Commands.TR_START, Commands.TR_PROVISIONAL, Commands.TR_STOP), commands())
    }
  }

  @Test
  fun nextUpdateAfterIdleReopensTheUiFirst() {
    idleDisplay(200).use {
      it.begin(false)
      it.show("Bonjour", "", false)
      awaitFrames(3)
      it.show("Bonjour", "こんにちは", true)
      awaitFrames(5)
      assertEquals(
        listOf(Commands.TR_START, Commands.TR_PROVISIONAL, Commands.TR_STOP, Commands.TR_START, Commands.TR_FINAL),
        commands(),
      )
      assertEquals("voxwk", received[3].tlvs.single().text)
    }
  }

  @Test
  fun updatesWithinTheTimeoutKeepTheUiOpen() {
    idleDisplay(500).use {
      it.begin(false)
      // Five updates 200 ms apart span 1 s, so the timeout must restart on each one to stay open.
      repeat(5) { i ->
        Thread.sleep(200)
        it.show("Bonjour", "", i == 4)
      }
      awaitFrames(6)
      assertTrue(Commands.TR_STOP !in commands())
    }
  }

  @Test
  fun endAfterIdleCloseSendsNothingMore() {
    idleDisplay(100).use {
      it.begin(false)
      awaitFrames(2)
      it.end()
      Thread.sleep(200)
      assertEquals(listOf(Commands.TR_START, Commands.TR_STOP), commands())
    }
  }

  @Test
  fun sendsNothingAfterCloseEvenWhenTheIdleTimeoutPasses() {
    val d = idleDisplay(200)
    d.begin(false)
    d.close()
    awaitFrames(1)
    Thread.sleep(500)
    d.show("Bonjour", "こんにちは", true)
    assertEquals(listOf(Commands.TR_START), commands())
    Thread.sleep(100)
    assertEquals(1, received.size)
  }
}
