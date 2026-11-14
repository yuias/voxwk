package app.rly3h.voxwk.translate

import app.rly3h.voxwk.lang.AsrEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionalAsrDispatcherTest {
  private val log = mutableListOf<String>()

  private fun pcm(tag: Int) = byteArrayOf(tag.toByte())

  // ME is on SenseVoice (segments), PARTNER on Nemotron (stream).
  private class Fakes(val log: MutableList<String>) {
    var segmentsOnAccept = listOf<ByteArray>()
    var segmentsOnFlush = listOf<ByteArray>()
    var step = StreamStep("", false)

    val segments = object : SegmentPath {
      override fun accept(pcm: ByteArray): List<ByteArray> {
        log += "sv.accept${pcm[0]}"
        return segmentsOnAccept
      }

      override fun flush(): List<ByteArray> {
        log += "sv.flush"
        return segmentsOnFlush
      }
    }
    val stream = object : StreamPath {
      override fun accept(pcm: ByteArray): StreamStep {
        log += "nm.accept${pcm[0]}"
        return step
      }

      override fun reset() {
        log += "nm.reset"
      }
    }
    val sink = object : DirectionalAsrSink {
      override suspend fun onSegment(pcm: ByteArray, speaker: Speaker) {
        log += "segment${pcm[0]}:$speaker"
      }

      override suspend fun onPartial(text: String) {
        log += "partial:$text"
      }

      override suspend fun onText(text: String, speaker: Speaker) {
        log += "text:$text:$speaker"
      }

      override suspend fun onError(e: Throwable) {
        log += "error"
      }
    }
  }

  private fun dispatcher(f: Fakes) = DirectionalAsrDispatcher(
    engineFor = { if (it == Speaker.ME) AsrEngine.SENSEVOICE else AsrEngine.NEMOTRON },
    segments = f.segments,
    stream = f.stream,
    sink = f.sink,
  )

  @Test
  fun chunksAreRoutedBySpeaker() = runTest {
    val f = Fakes(log)
    f.segmentsOnAccept = listOf(pcm(9))
    f.step = StreamStep("hello", true)
    val d = dispatcher(f)
    d.accept(pcm(1), Speaker.ME)
    assertEquals(listOf("sv.accept1", "segment9:ME"), log)
    log.clear()
    d.accept(pcm(2), Speaker.PARTNER)
    // Switching closes the SenseVoice side first; the endpoint then finishes the transcript.
    assertEquals(
      listOf("sv.flush", "nm.accept2", "partial:hello", "partial:", "text:hello:PARTNER"),
      log,
    )
  }

  @Test
  fun switchingFromSenseVoiceFlushesOnceBeforeTheFirstNemotronChunk() = runTest {
    val f = Fakes(log)
    f.segmentsOnFlush = listOf(pcm(7))
    val d = dispatcher(f)
    d.accept(pcm(1), Speaker.ME)
    d.accept(pcm(2), Speaker.ME)
    d.accept(pcm(3), Speaker.PARTNER)
    d.accept(pcm(4), Speaker.PARTNER)
    assertEquals(1, log.count { it == "sv.flush" })
    assertEquals(
      listOf("sv.accept1", "sv.accept2", "sv.flush", "segment7:ME", "nm.accept3", "partial:", "nm.accept4", "partial:"),
      log,
    )
  }

  @Test
  fun switchingFromNemotronEmitsThePartialAndResetsUnderTheOldSpeaker() = runTest {
    val f = Fakes(log)
    f.step = StreamStep(" so far ", false)
    val d = dispatcher(f)
    d.accept(pcm(1), Speaker.PARTNER)
    log.clear()
    d.accept(pcm(2), Speaker.ME)
    assertEquals(listOf("partial:", "text:so far:PARTNER", "nm.reset", "sv.accept2"), log)
  }

  @Test
  fun stopFlushesTheActivePath() = runTest {
    val f = Fakes(log)
    f.segmentsOnFlush = listOf(pcm(5))
    val d = dispatcher(f)
    d.accept(pcm(1), Speaker.ME)
    log.clear()
    d.finish()
    assertEquals(listOf("sv.flush", "segment5:ME"), log)

    log.clear()
    val g = Fakes(log)
    g.step = StreamStep("tail", false)
    val n = dispatcher(g)
    n.accept(pcm(1), Speaker.PARTNER)
    log.clear()
    n.finish()
    assertEquals(listOf("partial:", "text:tail:PARTNER", "nm.reset"), log)
  }

  @Test
  fun finishWithoutAudioDoesNothing() = runTest {
    dispatcher(Fakes(log)).finish()
    assertEquals(emptyList<String>(), log)
  }
}
