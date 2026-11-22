package app.rly3h.voxwk.audio

import app.rly3h.voxwk.translate.Audience
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

  @Test
  fun meFollowsTheSpeechRate() {
    assertEquals(1.5f, playbackRate(Audience.ME, 1.5f))
  }

  @Test
  fun partnerAlwaysPlaysAtNormalSpeed() {
    assertEquals(1.0f, playbackRate(Audience.PARTNER, 1.5f))
    assertEquals(1.0f, playbackRate(Audience.PARTNER, 0.75f))
  }
}
