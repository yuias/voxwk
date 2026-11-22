package app.rly3h.voxwk.translate

import app.rly3h.voxwk.lang.Language
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicLong

/** Who spoke a turn in Conversation mode; null in Listen mode. */
enum class Speaker { ME, PARTNER }

/** Who hears a translation in Conversation mode. */
enum class Audience { ME, PARTNER }

/**
 * One conversation turn as shown in the session UI: the recognized source text
 * and (once ready) its translation. `translation == null` means "still in
 * flight" — the row renders the transcript immediately and fills the translation
 * in later. Shared by the offline and online engines so the screen is unchanged.
 *
 * @param src null = Auto (Listen); the language is not known up front
 * @param detected the raw ASR language tag, empty when the engine doesn't report one
 * @param speaker who spoke the turn in Conversation mode; null keeps Listen rows unchanged
 * @param at epoch ms when the turn started
 */
data class LiveTurn(
  val id: Long,
  val transcript: String,
  val src: Language?,
  val tgt: Language,
  val detected: String,
  val translation: String? = null,
  val speaker: Speaker? = null,
  val at: Long,
)

/**
 * What an engine reports about itself. Typed rather than text so the screen
 * (and a Glass display) can render it in the user's language.
 */
sealed interface EngineStatus {
  data object Listening : EngineStatus
  data object Connecting : EngineStatus

  /** Conversation: no earphones, so the ME translation is not spoken. */
  data object NoEarphones : EngineStatus

  /** The OS has no TTS voice for [language]; the translation is shown as text only. */
  data class NoTtsVoice(val language: Language) : EngineStatus

  /** [detail] is the native message, shown verbatim inside a resource template. */
  data class Failed(val stage: Stage, val detail: String?) : EngineStatus

  /** Online Conversation: the status of one direction's session. */
  data class Directional(val speaker: Speaker, val inner: EngineStatus) : EngineStatus

  enum class Stage { MIC, RECOGNITION, TRANSLATION, NETWORK, ENGINE }
}

/**
 * Source of [LiveTurn.id]s. Process-wide rather than per engine: the session list
 * keeps its turns across engine restarts (stop, then start again without a reset),
 * so a per-engine counter restarting at 0 would repeat ids and crash the keyed list.
 */
internal object TurnIds {
  private val next = AtomicLong()
  fun next(): Long = next.getAndIncrement()
}

/**
 * The seam between an engine and the session screen. Each method maps 1:1 onto a
 * single piece of screen state, so an engine drives the UI without knowing about
 * Compose. Implementations may invoke these from background threads; the screen's
 * adapter is responsible for thread-safe state updates. All engine output goes
 * through here, so a decorator can mirror it elsewhere (the Glass display).
 */
interface TranslatorCallbacks {
  /** A new turn began; append it (transcript shown, translation pending). */
  fun onTurnStart(turn: LiveTurn)

  /** Patch an existing turn in place (streaming transcript and/or translation). */
  fun onTurnUpdate(id: Long, transcript: String? = null, translation: String? = null)

  /** The turn's translation is final (also when it failed: translation ""). Called before [onPersist]. */
  fun onTurnComplete(id: Long)

  /** Live, not-yet-finalized transcript (streaming recognizers); "" clears it. */
  fun onPartial(text: String)

  /**
   * Conversation: [onPartial] for [speaker]'s direction, the direction of the chunk the text was decoded from. The
   * default drops the tag, so only a callback that needs it (the Glass display) overrides this.
   */
  fun onPartial(text: String, speaker: Speaker) = onPartial(text)

  fun onStatus(status: EngineStatus)

  /** Speak [text] in [tgt] via the OS TTS (offline path only). */
  fun onSpeak(text: String, tgt: Language)

  /** Persist the session after a turn completes. */
  fun onPersist()

  /** The engine stopped; [error] is non-null if it ended abnormally. */
  fun onFinished(error: String?)
}

/**
 * A pluggable speech-translation engine. `start` is non-blocking and spawns its
 * work on [scope]; `stop` requests a clean shutdown (idempotent).
 */
interface SpeechTranslator {
  fun start(scope: CoroutineScope, callbacks: TranslatorCallbacks)
  fun stop()
}

/** Conversation-mode engine: direction is set explicitly by the Speaking control. */
internal interface ConversationTranslator : SpeechTranslator {
  /** true = the user is speaking (mine -> partner). Safe to call from the main thread at any time. */
  fun setSpeaking(speaking: Boolean)
}

/** An online engine that can take audio from a shared capture instead of its own. */
internal interface FeedableTranslator : SpeechTranslator {
  /** Sample rate [feed] expects (OpenAI 24000, Gemini 16000). */
  val inputSampleRate: Int

  /** PCM16 LE mono at [inputSampleRate]; buffered until the session is ready, like the own capture. */
  fun feed(chunk: ByteArray)
}
