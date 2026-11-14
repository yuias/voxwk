package app.rly3h.voxwk.glass

import app.rly3h.voxwk.translate.LiveTurn
import app.rly3h.voxwk.translate.TranslatorCallbacks

/** What the Glass HUD renders. The transport behind it is chosen separately. */
interface GlassDisplay {
  /** A session started; [conversation] is false for Listen. */
  fun begin(conversation: Boolean)

  /** Replace the HUD text. A provisional ([final] = false) update may be superseded; a final one stays up. */
  fun show(source: String, translation: String, final: Boolean)

  /** The session ended; clear the HUD. */
  fun end()
}

enum class GlassDisplayMode { TRANSLATION_ONLY, SOURCE_AND_TRANSLATION }

/**
 * Mirrors engine output to a [GlassDisplay] and delegates every callback to [inner], so the engines
 * stay unaware of Glass. Provisional updates closer together than [minProvisionalIntervalMs] are
 * dropped; the next one after the interval carries the latest text, and the turn's final always
 * follows, so nothing is lost. A final is sent at once. The Glass link rate-limits writes, hence
 * the interval.
 */
class GlassDisplayCallbacks(
  private val inner: TranslatorCallbacks,
  private val display: GlassDisplay,
  private val mode: GlassDisplayMode,
  private val minProvisionalIntervalMs: Long = 250,
  private val clock: () -> Long = System::currentTimeMillis,
) : TranslatorCallbacks by inner {
  private class Shown(var source: String, var translation: String)

  // Callbacks may arrive from several engine threads, so all state is guarded by this lock.
  private val lock = Any()
  private val turns = HashMap<Long, Shown>()
  private var lastSentAt: Long? = null

  override fun onTurnStart(turn: LiveTurn) {
    inner.onTurnStart(turn)
    synchronized(lock) {
      turns[turn.id] = Shown(turn.transcript, turn.translation.orEmpty())
      provisional(turn.transcript, turn.translation.orEmpty())
    }
  }

  override fun onTurnUpdate(id: Long, transcript: String?, translation: String?) {
    inner.onTurnUpdate(id, transcript, translation)
    synchronized(lock) {
      val turn = turns[id] ?: return
      transcript?.let { turn.source = it }
      translation?.let { turn.translation = it }
      provisional(turn.source, turn.translation)
    }
  }

  override fun onTurnComplete(id: Long) {
    inner.onTurnComplete(id)
    synchronized(lock) {
      val turn = turns.remove(id) ?: return
      lastSentAt = clock()
      display.show(if (mode == GlassDisplayMode.TRANSLATION_ONLY) "" else turn.source, turn.translation, true)
    }
  }

  override fun onPartial(text: String) {
    inner.onPartial(text)
    if (text.isEmpty()) return
    synchronized(lock) { provisional(text, "") }
  }

  private fun provisional(source: String, translation: String) {
    val now = clock()
    val last = lastSentAt
    if (last != null && now - last < minProvisionalIntervalMs) return
    lastSentAt = now
    display.show(source, translation, false)
  }
}
