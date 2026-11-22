package app.rly3h.voxwk.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackLogicTest {
  @Test
  fun emptyTextGetsTheBaseTimeout() {
    assertEquals(2000L, utteranceTimeoutMs(""))
  }

  @Test
  fun timeoutGrowsWithTextLength() {
    assertEquals(3500L, utteranceTimeoutMs("a".repeat(10)))
  }

  @Test
  fun timeoutIsCapped() {
    assertEquals(60000L, utteranceTimeoutMs("a".repeat(1000)))
  }

  @Test
  fun onlySpeakerAndGlassAreBusy() {
    assertTrue(isEchoBusy(ResolvedOutput.SPEAKER))
    assertTrue(isEchoBusy(ResolvedOutput.GLASS))
    assertFalse(isEchoBusy(ResolvedOutput.EARPHONE))
    assertFalse(isEchoBusy(ResolvedOutput.NONE))
  }
}
