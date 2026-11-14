package app.rly3h.voxwk.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioInputTest {
  private fun pcm(vararg samples: Int): ByteArray {
    val out = ByteArray(samples.size * 2)
    samples.forEachIndexed { i, v ->
      out[i * 2] = (v and 0xFF).toByte()
      out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
    }
    return out
  }

  private fun samples(b: ByteArray): List<Int> =
    List(b.size / 2) { ((b[it * 2].toInt() and 0xFF) or (b[it * 2 + 1].toInt() shl 8)) }

  @Test
  fun resampleOutputIsThreeHalvesOfTheInput() {
    // A 40 ms online chunk: 640 samples at 16 kHz -> 960 at 24 kHz.
    assertEquals(960 * 2, linearResample16to24(ByteArray(640 * 2)).size)
    assertEquals(150 * 2, linearResample16to24(ByteArray(100 * 2)).size)
  }

  @Test
  fun resampleKeepsTheEndpoints() {
    val out = samples(linearResample16to24(pcm(-1000, 0, 500, 2000)))
    assertEquals(6, out.size)
    assertEquals(-1000, out.first())
    assertEquals(2000, out.last())
  }

  @Test
  fun resampleInterpolatesLinearly() {
    // 0..300 ramp: every output sample stays on the ramp, so the output is non-decreasing.
    val out = samples(linearResample16to24(pcm(0, 100, 200, 300)))
    assertEquals(out.sorted(), out)
  }

  @Test
  fun resampleOfEmptyInputIsEmpty() {
    assertArrayEquals(ByteArray(0), linearResample16to24(ByteArray(0)))
  }
}
