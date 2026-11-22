package app.rly3h.voxwk.data

import app.rly3h.voxwk.glass.GlassDisplayMode
import app.rly3h.voxwk.lang.JaRecognizer
import app.rly3h.voxwk.lang.Language
import app.rly3h.voxwk.lang.MY_LANGUAGES
import app.rly3h.voxwk.lang.OnlineProvider
import app.rly3h.voxwk.lang.languageByCode
import app.rly3h.voxwk.translate.Audience

/** New Session screen mode: one-way Listen or two-way push-to-talk Conversation. */
enum class SessionMode { LISTEN, CONVERSATION }

/**
 * User-tunable Silero VAD knobs (persisted in settings). `threshold` is the
 * speech-probability cutoff (0..1; lower = more sensitive); the durations are in
 * milliseconds here for the sliders and converted to seconds at the FFI boundary.
 */
data class VadParams(
  val threshold: Float = 0.5f, // speech probability; above = voiced
  val minSilenceMs: Int = 500, // trailing silence that ends a segment
  val minSpeechMs: Int = 250, // ignore blips shorter than this
  val maxSpeechMs: Int = 15000, // force-cut very long utterances
)

// Sensible, processing-appropriate bounds for the settings sliders.
object VadBounds {
  val threshold = 0.1f..0.9f
  val minSilenceMs = 100f..1500f
  val minSpeechMs = 50f..1000f
  val maxSpeechMs = 5000f..30000f
}

/**
 * Endpoint rules for the streaming recognizer (seconds). These map to sherpa's
 * rule1/2/3 and are baked into the recognizer at creation. `rule2` (trailing
 * silence after speech) is the main "end of turn" knob.
 */
data class EndpointParams(
  val rule1: Float = 2.4f, // trailing silence before any speech is decoded
  val rule2: Float = 1.2f, // trailing silence after speech -> ends a turn
  val rule3: Float = 20.0f, // max utterance length, force-cut
)

object EndpointBounds {
  val rule1 = 0.5f..5.0f
  val rule2 = 0.3f..3.0f
  val rule3 = 5.0f..30.0f
}

/** Slider bounds for the online engines' idle-gap turn splitter (milliseconds). */
object OnlineBounds {
  val idleGapMs = 500f..3000f
}

const val DEFAULT_ONLINE_IDLE_GAP_MS = 1200

/** Glass microphone pickup; [pickupMode] is the value the Glass takes on the wire. */
enum class GlassMic(val pickupMode: Int) {
  FRONT(0), // the only mode measured on the device
  ALL_AROUND(1), // the documented 3-mic conference mode
}

// The form BluetoothDevice.getAddress returns; BluetoothAdapter.checkBluetoothAddress is not callable in JVM tests.
private val GLASS_ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

/** Engines take a snapshot of this at session start, not a live object. */
data class AppSettings(
  val myLanguage: Language = Language.JA, // one of MY_LANGUAGES
  val listenSource: Language? = null, // null = Auto; a value equal to myLanguage parses as null
  val conversationPartner: Language = DEFAULT_PARTNER, // never == myLanguage
  val sessionMode: SessionMode = SessionMode.LISTEN,
  val jaRecognizer: JaRecognizer = JaRecognizer.NEMOTRON,
  val mtModel: MtModel = MtModel.PLAMO3_0_8B,
  /** Speak translations into my language (Audience.ME, what I hear). The name and stored key predate the per-direction split. */
  val autoSpeak: Boolean = true,
  /** Speak translations into the partner's language (Audience.PARTNER, what the partner hears). */
  val autoSpeakPartner: Boolean = true,
  /** Applies only to what I hear; what the partner hears always plays at 1.0x. */
  val speechRate: Float = 1.0f,
  /**
   * Last-used engine toggle (mic-side switch). Offline by default; only honored
   * when an API key is set and the network is up (checked at the call site).
   */
  val onlineEnabled: Boolean = false,
  val onlineProvider: OnlineProvider = OnlineProvider.OPENAI,
  /**
   * Silence (ms) after the last streamed delta that closes an open online turn.
   * Shared by both online engines; the main fallback splitter for Gemini (which
   * sends no end-of-turn signal) and the primary one for any stretch without
   * sentence punctuation.
   */
  val onlineIdleGapMs: Int = DEFAULT_ONLINE_IDLE_GAP_MS,
  val vad: VadParams = VadParams(),
  val endpoint: EndpointParams = EndpointParams(),
  val glassEnabled: Boolean = false,
  val glassAddress: String? = null, // a session uses the Glass when glassEnabled && glassAddress != null
  val glassMic: GlassMic = GlassMic.FRONT,
  val glassDisplay: GlassDisplayMode = GlassDisplayMode.SOURCE_AND_TRANSLATION,
  /** Conversation: show my own speech (Speaker.ME turns and partials) on the Glass. False shows only the partner's. */
  val glassShowMine: Boolean = true,
)

fun AppSettings.speaks(audience: Audience): Boolean = if (audience == Audience.ME) autoSpeak else autoSpeakPartner

private val DEFAULT_PARTNER = Language.EN

/** Stored preference names, shared by [parseSettings] and the repository. */
internal object SettingsKeys {
  const val MY_LANGUAGE = "myLanguage"
  const val LISTEN_SOURCE = "listenSource"
  const val CONVERSATION_PARTNER = "conversationPartner"
  const val SESSION_MODE = "sessionMode"
  const val JA_RECOGNIZER = "jaRecognizer"
  const val MT_MODEL = "mtModel"
  const val AUTO_SPEAK = "autoSpeak"
  const val AUTO_SPEAK_PARTNER = "autoSpeakPartner"
  const val SPEECH_RATE = "speechRate"
  const val ONLINE_ENABLED = "onlineEnabled"
  const val ONLINE_PROVIDER = "onlineProvider"
  const val ONLINE_IDLE_GAP = "onlineIdleGapMs"
  const val VAD_THRESHOLD = "vadProbThreshold"
  const val VAD_SILENCE = "vadMinSilenceMs"
  const val VAD_MIN_SPEECH = "vadMinSpeechMs"
  const val VAD_MAX_SPEECH = "vadMaxSpeechMs"
  const val EP_RULE1 = "endpointRule1"
  const val EP_RULE2 = "endpointRule2"
  const val EP_RULE3 = "endpointRule3"
  const val GLASS_ENABLED = "glassEnabled"
  const val GLASS_ADDRESS = "glassAddress"
  const val GLASS_MIC = "glassMic"
  const val GLASS_DISPLAY = "glassDisplay"
  const val GLASS_SHOW_MINE = "glassShowMine"
}

private inline fun <reified E : Enum<E>> Map<String, Any?>.enumOr(key: String, default: E): E =
  enumValues<E>().firstOrNull { it.name == this[key] } ?: default

/**
 * Builds the settings from stored preference values (name -> value). Unknown or
 * mistyped values fall back to their defaults: this is the one place stored data is validated.
 */
fun parseSettings(prefs: Map<String, Any?>): AppSettings {
  val mine = (languageByCode(prefs[SettingsKeys.MY_LANGUAGE] as? String)?.takeIf { it in MY_LANGUAGES })
    ?: MY_LANGUAGES.first()
  val vad = VadParams()
  val ep = EndpointParams()
  return AppSettings(
    myLanguage = mine,
    // A source equal to my language would collapse the pair, so it means Auto.
    listenSource = languageByCode(prefs[SettingsKeys.LISTEN_SOURCE] as? String)?.takeIf { it != mine },
    // Not languageByCode's caller default: a fallback of EN would collide with mine when mine is EN.
    conversationPartner = languageByCode(prefs[SettingsKeys.CONVERSATION_PARTNER] as? String)?.takeIf { it != mine }
      ?: listOf(DEFAULT_PARTNER, Language.JA).first { it != mine },
    sessionMode = prefs.enumOr(SettingsKeys.SESSION_MODE, SessionMode.LISTEN),
    jaRecognizer = prefs.enumOr(SettingsKeys.JA_RECOGNIZER, JaRecognizer.NEMOTRON),
    mtModel = prefs.enumOr(SettingsKeys.MT_MODEL, MtModel.PLAMO3_0_8B),
    autoSpeak = prefs[SettingsKeys.AUTO_SPEAK] as? Boolean ?: true,
    autoSpeakPartner = prefs[SettingsKeys.AUTO_SPEAK_PARTNER] as? Boolean ?: true,
    speechRate = prefs[SettingsKeys.SPEECH_RATE] as? Float ?: 1.0f,
    onlineEnabled = prefs[SettingsKeys.ONLINE_ENABLED] as? Boolean ?: false,
    onlineProvider = prefs.enumOr(SettingsKeys.ONLINE_PROVIDER, OnlineProvider.OPENAI),
    onlineIdleGapMs = prefs[SettingsKeys.ONLINE_IDLE_GAP] as? Int ?: DEFAULT_ONLINE_IDLE_GAP_MS,
    vad = VadParams(
      threshold = prefs[SettingsKeys.VAD_THRESHOLD] as? Float ?: vad.threshold,
      minSilenceMs = prefs[SettingsKeys.VAD_SILENCE] as? Int ?: vad.minSilenceMs,
      minSpeechMs = prefs[SettingsKeys.VAD_MIN_SPEECH] as? Int ?: vad.minSpeechMs,
      maxSpeechMs = prefs[SettingsKeys.VAD_MAX_SPEECH] as? Int ?: vad.maxSpeechMs,
    ),
    endpoint = EndpointParams(
      rule1 = prefs[SettingsKeys.EP_RULE1] as? Float ?: ep.rule1,
      rule2 = prefs[SettingsKeys.EP_RULE2] as? Float ?: ep.rule2,
      rule3 = prefs[SettingsKeys.EP_RULE3] as? Float ?: ep.rule3,
    ),
    glassEnabled = prefs[SettingsKeys.GLASS_ENABLED] as? Boolean ?: false,
    glassAddress = (prefs[SettingsKeys.GLASS_ADDRESS] as? String)?.takeIf { GLASS_ADDRESS.matches(it) },
    glassMic = prefs.enumOr(SettingsKeys.GLASS_MIC, GlassMic.FRONT),
    glassDisplay = prefs.enumOr(SettingsKeys.GLASS_DISPLAY, GlassDisplayMode.SOURCE_AND_TRANSLATION),
    glassShowMine = prefs[SettingsKeys.GLASS_SHOW_MINE] as? Boolean ?: true,
  )
}
