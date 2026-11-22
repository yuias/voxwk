package app.rly3h.voxwk.translate

import app.rly3h.voxwk.lang.Language

/**
 * What to do with one captured chunk in Conversation mode.
 *
 * @param active direction this chunk belongs to
 * @param muted replace the chunk with silence (echo guard)
 * @param switchedFrom non-null only on the first chunk after a Speaking toggle
 */
internal data class InputDecision(
  val active: Speaker,
  val muted: Boolean,
  val switchedFrom: Speaker?,
)

/** Not thread-safe; owned by the single capture thread. */
internal class ConversationInputGate {
  private var last: Speaker? = null

  /**
   * Speaking ON means the user talks (ME); otherwise the mic is treated as the
   * partner. Both directions are muted while the speaker or the Glass is busy: the
   * translation plays there, and the mic hears it (on the speaker, with Speaking still
   * latched, as a new ME turn; on the Glass, about 30 dB above speech in the Glass mic
   * stream). The platform AEC cannot be relied on (on a OnePlus CPH2655 it had no echo
   * reference), so the price is that nobody can talk over that playback. Earphone playback
   * is not audible to the mic and never mutes.
   */
  fun decide(speaking: Boolean, speakerBusy: Boolean): InputDecision {
    val active = if (speaking) Speaker.ME else Speaker.PARTNER
    val prev = last
    last = active
    return InputDecision(
      active = active,
      muted = speakerBusy,
      switchedFrom = if (prev != null && prev != active) prev else null,
    )
  }
}

/** (source, target) for a conversation turn: ME = mine -> partner, PARTNER = partner -> mine. */
internal fun conversationDirection(
  speaker: Speaker,
  mine: Language,
  partner: Language,
): Pair<Language, Language> =
  if (speaker == Speaker.ME) mine to partner else partner to mine

/** A translation is heard by the other party. */
internal fun audienceFor(speaker: Speaker): Audience =
  if (speaker == Speaker.ME) Audience.PARTNER else Audience.ME

/**
 * Turn id for the UI list. The two online sessions each number turns from 0, so
 * the id is interleaved (ME odd, PARTNER even) to keep the list keys unique.
 */
internal fun directionalTurnId(speaker: Speaker, id: Long): Long =
  id * 2 + if (speaker == Speaker.ME) 1 else 0

/**
 * Wraps the callbacks of one inner (single-direction) engine for a Conversation
 * session. It tags turns with [speaker] and remaps ids; status and finish are
 * intercepted because the outer session merges the two directions itself.
 *
 * @param status receives this direction's status instead of the outer onStatus,
 *   wrapped as [EngineStatus.Directional]
 * @param onInnerFinished called instead of the outer onFinished
 */
internal class DirectionalCallbacks(
  private val speaker: Speaker,
  private val outer: TranslatorCallbacks,
  private val status: (Speaker, EngineStatus) -> Unit,
  private val onInnerFinished: (Speaker, String?) -> Unit,
) : TranslatorCallbacks {
  override fun onTurnStart(turn: LiveTurn) =
    outer.onTurnStart(turn.copy(id = directionalTurnId(speaker, turn.id), speaker = speaker))

  override fun onTurnUpdate(id: Long, transcript: String?, translation: String?) =
    outer.onTurnUpdate(directionalTurnId(speaker, id), transcript, translation)

  override fun onTurnComplete(id: Long) = outer.onTurnComplete(directionalTurnId(speaker, id))

  override fun onPartial(text: String) = outer.onPartial(text)

  override fun onStatus(status: EngineStatus) =
    this.status(speaker, EngineStatus.Directional(speaker, status))

  override fun onSpeak(text: String, tgt: Language) = outer.onSpeak(text, tgt)

  override fun onPersist() = outer.onPersist()

  override fun onFinished(error: String?) = onInnerFinished(speaker, error)
}
