package app.rly3h.voxwk.glass

import app.rly3h.voxwk.lang.Language
import app.rly3h.voxwk.translate.EngineStatus
import app.rly3h.voxwk.translate.LiveTurn
import app.rly3h.voxwk.translate.Speaker
import app.rly3h.voxwk.translate.TranslatorCallbacks
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassDisplayCallbacksTest {
  private data class Shown(val source: String, val translation: String, val final: Boolean)

  private class FakeDisplay : GlassDisplay {
    val shown = mutableListOf<Shown>()

    override fun begin(conversation: Boolean) = Unit

    override fun show(source: String, translation: String, final: Boolean) {
      shown += Shown(source, translation, final)
    }

    override fun end() = Unit
  }

  private class RecordingInner : TranslatorCallbacks {
    val calls = mutableListOf<String>()

    override fun onTurnStart(turn: LiveTurn) { calls += "start:${turn.id}" }

    override fun onTurnUpdate(id: Long, transcript: String?, translation: String?) {
      calls += "update:$id:$transcript:$translation"
    }

    override fun onTurnComplete(id: Long) { calls += "complete:$id" }

    override fun onPartial(text: String) { calls += "partial:$text" }

    override fun onStatus(status: EngineStatus) { calls += "status:$status" }

    override fun onSpeak(text: String, tgt: Language) { calls += "speak:$text:$tgt" }

    override fun onPersist() { calls += "persist" }

    override fun onFinished(error: String?) { calls += "finished:$error" }
  }

  private var now = 0L
  private val inner = RecordingInner()
  private val display = FakeDisplay()

  private fun callbacks(
    mode: GlassDisplayMode = GlassDisplayMode.SOURCE_AND_TRANSLATION,
    showMine: Boolean = true,
  ) = GlassDisplayCallbacks(inner, display, mode, showMine, minProvisionalIntervalMs = 250, clock = { now })

  private fun turn(id: Long, transcript: String = "", speaker: Speaker? = null) =
    LiveTurn(
      id = id, transcript = transcript, src = Language.EN, tgt = Language.JA, detected = "", at = 0,
      speaker = speaker,
    )

  private fun playTurn(cb: GlassDisplayCallbacks, speaker: Speaker?) {
    cb.onTurnStart(turn(1, "good morning", speaker))
    now += 300
    cb.onTurnUpdate(1, translation = "おはよう")
    now += 300
    cb.onTurnComplete(1)
  }

  @Test
  fun delegatesEveryCallbackToInner() {
    val cb = callbacks()
    cb.onTurnStart(turn(1, "hello"))
    cb.onTurnUpdate(1, transcript = "hello there", translation = "こんにちは")
    cb.onTurnComplete(1)
    cb.onPartial("next")
    cb.onStatus(EngineStatus.Listening)
    cb.onSpeak("こんにちは", Language.JA)
    cb.onPersist()
    cb.onFinished(null)
    assertEquals(
      listOf(
        "start:1",
        "update:1:hello there:こんにちは",
        "complete:1",
        "partial:next",
        "status:Listening",
        "speak:こんにちは:JA",
        "persist",
        "finished:null",
      ),
      inner.calls,
    )
  }

  @Test
  fun partialGivesProvisionalWithEmptyTranslation() {
    val cb = callbacks()
    cb.onPartial("good mor")
    assertEquals(listOf(Shown("good mor", "", false)), display.shown)
  }

  @Test
  fun emptyPartialIsNotShown() {
    callbacks().onPartial("")
    assertEquals(emptyList<Shown>(), display.shown)
  }

  @Test
  fun turnStartGivesProvisionalWithEmptyTranslation() {
    callbacks().onTurnStart(turn(1, "good morning"))
    assertEquals(listOf(Shown("good morning", "", false)), display.shown)
  }

  @Test
  fun completeGivesFinalWithSource() {
    val cb = callbacks()
    cb.onTurnStart(turn(1, "good morning"))
    cb.onTurnUpdate(1, translation = "おはよう")
    cb.onTurnComplete(1)
    assertEquals(Shown("good morning", "おはよう", true), display.shown.last())
  }

  @Test
  fun completeHasEmptySourceInTranslationOnly() {
    val cb = callbacks(GlassDisplayMode.TRANSLATION_ONLY)
    cb.onTurnStart(turn(1, "good morning"))
    cb.onTurnUpdate(1, translation = "おはよう")
    cb.onTurnComplete(1)
    assertEquals(Shown("", "おはよう", true), display.shown.last())
  }

  @Test
  fun updateKeepsTheLatestSourceOfTheTurn() {
    val cb = callbacks()
    cb.onTurnStart(turn(1, "good"))
    now = 300
    cb.onTurnUpdate(1, transcript = "good morning", translation = "おはよう")
    assertEquals(Shown("good morning", "おはよう", false), display.shown.last())
  }

  @Test
  fun provisionalsWithinTheIntervalGiveOneShowWithTheLatestText() {
    val cb = callbacks()
    cb.onPartial("a")
    now = 100
    cb.onPartial("a b")
    now = 200
    cb.onPartial("a b c")
    assertEquals(listOf(Shown("a", "", false)), display.shown)
    now = 300
    cb.onPartial("a b c d")
    assertEquals(listOf(Shown("a", "", false), Shown("a b c d", "", false)), display.shown)
  }

  @Test
  fun finalIsNeverDelayed() {
    val cb = callbacks()
    cb.onTurnStart(turn(1, "hi"))
    now = 10
    cb.onTurnUpdate(1, translation = "やあ")
    now = 20
    cb.onTurnComplete(1)
    assertEquals(
      listOf(Shown("hi", "", false), Shown("hi", "やあ", true)),
      display.shown,
    )
  }

  @Test
  fun hiddenTaggedPartialIsDelegatedAndNotShown() {
    callbacks(showMine = false).onPartial("こんに", Speaker.ME)
    assertEquals(emptyList<Shown>(), display.shown)
    assertEquals(listOf("partial:こんに"), inner.calls)
  }

  @Test
  fun partnerPartialIsShownWhenMineIsHidden() {
    callbacks(showMine = false).onPartial("hel", Speaker.PARTNER)
    assertEquals(listOf(Shown("hel", "", false)), display.shown)
  }

  @Test
  fun hiddenTurnIsDelegatedAndNotShown() {
    playTurn(callbacks(showMine = false), Speaker.ME)
    assertEquals(emptyList<Shown>(), display.shown)
    assertEquals(listOf("start:1", "update:1:null:おはよう", "complete:1"), inner.calls)
  }

  @Test
  fun partnerTurnIsShownAsWhenMineIsShown() {
    playTurn(callbacks(showMine = false), Speaker.PARTNER)
    val hidingMine = display.shown.toList()
    display.shown.clear()
    now = 0
    playTurn(callbacks(showMine = true), Speaker.PARTNER)
    assertEquals(display.shown, hidingMine)
  }

  @Test
  fun listenTurnIsShownWhenMineIsHidden() {
    playTurn(callbacks(showMine = false), null)
    assertEquals(Shown("good morning", "おはよう", true), display.shown.last())
  }

  @Test
  fun hiddenEventsLeaveTheProvisionalIntervalAlone() {
    val cb = callbacks(showMine = false)
    now = 0
    cb.onPartial("a", Speaker.ME)
    now = 100
    cb.onPartial("b", Speaker.PARTNER)
    assertEquals(listOf(Shown("b", "", false)), display.shown)
  }

  @Test
  fun partnerFinalIsTranslationOnlyWhenMineIsHidden() {
    playTurn(callbacks(GlassDisplayMode.TRANSLATION_ONLY, showMine = false), Speaker.PARTNER)
    assertEquals(Shown("", "おはよう", true), display.shown.last())
  }

  @Test
  fun mineIsShownByDefault() {
    val cb = callbacks()
    cb.onPartial("x", Speaker.ME)
    assertEquals(listOf(Shown("x", "", false)), display.shown)
    display.shown.clear()
    playTurn(cb, Speaker.ME)
    assertEquals(Shown("good morning", "おはよう", true), display.shown.last())
  }
}
