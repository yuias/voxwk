package app.rly3h.voxwk.translate

import app.rly3h.voxwk.lang.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationInputTest {
  private val ja = Language.JA
  private val fr = Language.FR

  @Test
  fun gateSequenceFollowsSpeakingAndEchoRules() {
    val gate = ConversationInputGate()
    assertEquals(InputDecision(Speaker.PARTNER, muted = false, switchedFrom = null), gate.decide(false, false))
    assertEquals(InputDecision(Speaker.ME, muted = false, switchedFrom = Speaker.PARTNER), gate.decide(true, false))
    // Speaking is never muted, even while the speaker plays.
    assertEquals(InputDecision(Speaker.ME, muted = false, switchedFrom = null), gate.decide(true, true))
    assertEquals(InputDecision(Speaker.PARTNER, muted = true, switchedFrom = Speaker.ME), gate.decide(false, true))
  }

  @Test
  fun switchTailIsDerivedFromTheTargetRate() {
    // 16-bit mono: 800 ms is 25600 bytes at 16 kHz and 38400 at 24 kHz, in whole 40 ms chunks.
    assertEquals(25_600, switchTailBytes(16_000))
    assertEquals(38_400, switchTailBytes(24_000))
    assertEquals(1_280, onlineChunkBytes(16_000))
    assertEquals(1_920, onlineChunkBytes(24_000))
    assertEquals(0, switchTailBytes(24_000) % onlineChunkBytes(24_000))
  }

  @Test
  fun directionFollowsSpeaker() {
    assertEquals(ja to fr, conversationDirection(Speaker.ME, ja, fr))
    assertEquals(fr to ja, conversationDirection(Speaker.PARTNER, ja, fr))
  }

  @Test
  fun translationIsHeardByTheOtherParty() {
    assertEquals(Audience.PARTNER, audienceFor(Speaker.ME))
    assertEquals(Audience.ME, audienceFor(Speaker.PARTNER))
  }

  private class Recorder : TranslatorCallbacks {
    val starts = mutableListOf<LiveTurn>()
    val updates = mutableListOf<Triple<Long, String?, String?>>()
    val completes = mutableListOf<Long>()
    val partials = mutableListOf<String>()
    val statuses = mutableListOf<EngineStatus>()
    val spoken = mutableListOf<Pair<String, Language>>()
    var persists = 0
    val finished = mutableListOf<String?>()

    override fun onTurnStart(turn: LiveTurn) { starts += turn }
    override fun onTurnUpdate(id: Long, transcript: String?, translation: String?) {
      updates += Triple(id, transcript, translation)
    }
    override fun onTurnComplete(id: Long) { completes += id }
    override fun onPartial(text: String) { partials += text }
    override fun onStatus(status: EngineStatus) { statuses += status }
    override fun onSpeak(text: String, tgt: Language) { spoken += text to tgt }
    override fun onPersist() { persists++ }
    override fun onFinished(error: String?) { finished += error }
  }

  private fun turn(id: Long) = LiveTurn(id, "t", ja, fr, "", at = 0L)

  @Test
  fun turnIdsAreRemappedAndTaggedPerSpeaker() {
    val outer = Recorder()
    val me = DirectionalCallbacks(Speaker.ME, outer, { _, _ -> }, { _, _ -> })
    val partner = DirectionalCallbacks(Speaker.PARTNER, outer, { _, _ -> }, { _, _ -> })

    me.onTurnStart(turn(0))
    partner.onTurnStart(turn(0))
    me.onTurnUpdate(0, "a", "b")
    partner.onTurnUpdate(3, translation = "c")
    me.onTurnComplete(0)
    partner.onTurnComplete(3)

    assertEquals(listOf(1L to Speaker.ME, 0L to Speaker.PARTNER), outer.starts.map { it.id to it.speaker })
    assertEquals(listOf(Triple(1L, "a", "b"), Triple(6L, null, "c")), outer.updates)
    assertEquals(listOf(1L, 6L), outer.completes)
  }

  @Test
  fun statusIsWrappedAsDirectionalAndFinishIsInterceptedNotForwarded() {
    val outer = Recorder()
    val statuses = mutableListOf<Pair<Speaker, EngineStatus>>()
    val finishes = mutableListOf<Pair<Speaker, String?>>()
    val me = DirectionalCallbacks(Speaker.ME, outer, { s, t -> statuses += s to t }, { s, e -> finishes += s to e })

    me.onStatus(EngineStatus.Listening)
    me.onFinished("boom")

    assertEquals(
      listOf(Speaker.ME to EngineStatus.Directional(Speaker.ME, EngineStatus.Listening)),
      statuses,
    )
    assertEquals(listOf(Speaker.ME to "boom"), finishes)
    assertEquals(emptyList<EngineStatus>(), outer.statuses)
    assertEquals(emptyList<String?>(), outer.finished)
  }

  @Test
  fun otherCallbacksPassThrough() {
    val outer = Recorder()
    val cb = DirectionalCallbacks(Speaker.PARTNER, outer, { _, _ -> }, { _, _ -> })

    cb.onPartial("p")
    cb.onSpeak("hello", Language.EN)
    cb.onPersist()

    assertEquals(listOf("p"), outer.partials)
    assertEquals(listOf("hello" to Language.EN), outer.spoken)
    assertEquals(1, outer.persists)
    assertNull(outer.starts.firstOrNull())
  }
}
