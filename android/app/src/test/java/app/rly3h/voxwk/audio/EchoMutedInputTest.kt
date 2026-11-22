package app.rly3h.voxwk.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class EchoMutedInputTest {
  private val format = CaptureFormat(SAMPLE_RATE_16K, OFFLINE_CHUNK_MS, CapturePurpose.RECOGNITION)

  private class FakeInput(val chunks: List<ByteArray>, override val kind: InputKind) : AudioInput {
    override fun capture(format: CaptureFormat, running: AtomicBoolean, emit: (ByteArray) -> Unit) {
      chunks.forEach(emit)
    }
  }

  private fun capture(input: AudioInput): List<ByteArray> {
    val out = mutableListOf<ByteArray>()
    input.capture(format, AtomicBoolean(true), out::add)
    return out
  }

  @Test
  fun chunksWhileBusyAreZerosOfTheSameLength() {
    val chunks = listOf(ByteArray(3200) { 7 }, ByteArray(1280) { 9 })
    val out = capture(EchoMutedInput(FakeInput(chunks, InputKind.GLASSES)) { true })
    assertEquals(listOf(3200, 1280), out.map { it.size })
    out.forEach { chunk -> assertArrayEquals(ByteArray(chunk.size), chunk) }
  }

  @Test
  fun chunksWhileNotBusyPassThroughUnchanged() {
    val chunks = listOf(ByteArray(3200) { 7 }, ByteArray(1280) { 9 })
    val out = capture(EchoMutedInput(FakeInput(chunks, InputKind.GLASSES)) { false })
    assertEquals(2, out.size)
    assertArrayEquals(chunks[0], out[0])
    assertArrayEquals(chunks[1], out[1])
  }

  @Test
  fun busyIsReadPerChunk() {
    var busy = false
    val chunks = listOf(ByteArray(4) { 1 }, ByteArray(4) { 2 }, ByteArray(4) { 3 })
    val inner = object : AudioInput {
      override val kind = InputKind.GLASSES
      override fun capture(format: CaptureFormat, running: AtomicBoolean, emit: (ByteArray) -> Unit) {
        chunks.forEachIndexed { i, c ->
          busy = i == 1
          emit(c)
        }
      }
    }
    val out = capture(EchoMutedInput(inner) { busy })
    assertArrayEquals(chunks[0], out[0])
    assertArrayEquals(ByteArray(4), out[1])
    assertArrayEquals(chunks[2], out[2])
  }

  @Test
  fun kindIsTheInnerInputsKind() {
    assertEquals(InputKind.GLASSES, EchoMutedInput(FakeInput(emptyList(), InputKind.GLASSES)) { false }.kind)
    assertEquals(InputKind.PHONE, EchoMutedInput(FakeInput(emptyList(), InputKind.PHONE)) { false }.kind)
  }
}
