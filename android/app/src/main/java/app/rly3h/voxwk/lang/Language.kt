package app.rly3h.voxwk.lang

import androidx.annotation.StringRes
import app.rly3h.voxwk.R
import java.util.Locale

enum class OnlineProvider { OPENAI, GEMINI }

/**
 * A language the app can translate to or from. The single source of truth for everything
 * language-specific; supporting one more language is one more row.
 *
 * [code] is the canonical id: the PFML3 `lang` value, also stored in settings and session logs.
 *
 * @param label  full name shown in pickers
 * @param badge  short tag shown on a turn card
 * @param locale OS TTS locale used to read the translation aloud
 * @param openAiCode OpenAI Realtime output language; null = not an OpenAI output language
 * @param geminiCode Gemini Live translation language
 * @param senseVoiceTag substring of the SenseVoice tag ("<|en|>" contains "en"); null = SenseVoice cannot recognise it
 * @param offlineInput Nemotron 3.5 recognises it (false only for ZH_HANT: Nemotron lists zh-CN)
 */
enum class Language(
  val code: String,
  @StringRes val label: Int,
  val badge: String,
  val locale: Locale,
  val openAiCode: String?,
  val geminiCode: String,
  val senseVoiceTag: String?,
  val offlineInput: Boolean,
) {
  JA("ja", R.string.lang_ja, "JA", Locale.JAPANESE, "ja", "ja", "ja", true),
  EN("en", R.string.lang_en, "EN", Locale.ENGLISH, "en", "en", "en", true),
  FR("fr", R.string.lang_fr, "FR", Locale.FRENCH, "fr", "fr", null, true),
  DE("de", R.string.lang_de, "DE", Locale.GERMAN, "de", "de", null, true),
  IT("it", R.string.lang_it, "IT", Locale.ITALIAN, "it", "it", null, true),
  ES("es", R.string.lang_es, "ES", Locale.forLanguageTag("es"), "es", "es", null, true),
  RU("ru", R.string.lang_ru, "RU", Locale.forLanguageTag("ru"), "ru", "ru", null, true),
  ZH_HANS("zh-hans", R.string.lang_zh_hans, "ZH-S", Locale.SIMPLIFIED_CHINESE, "zh", "zh-Hans", "zh", true),
  ZH_HANT("zh-hant", R.string.lang_zh_hant, "ZH-T", Locale.TRADITIONAL_CHINESE, null, "zh-Hant", "zh", false),
}

/** Languages the user can pick as "my language". JA only for now; adding a row enables it everywhere. */
val MY_LANGUAGES: List<Language> = listOf(Language.JA)

/** Null for an unknown code; callers apply their own default. */
fun languageByCode(code: String?): Language? = Language.entries.firstOrNull { it.code == code }

fun onlineCode(provider: OnlineProvider, target: Language): String? = when (provider) {
  OnlineProvider.OPENAI -> target.openAiCode
  OnlineProvider.GEMINI -> target.geminiCode
}

/** Listen online needs the target; Conversation online needs both directions' targets. */
fun onlineSupported(provider: OnlineProvider, targets: Collection<Language>): Boolean =
  targets.all { onlineCode(provider, it) != null }

/** Every spoken language of the session (Listen: the source, or none for Auto; Conversation: mine and partner). */
fun offlineCapable(spoken: Collection<Language>): Boolean = spoken.all { it.offlineInput }

/** Kana only: Han characters alone are not Japanese (Chinese partners). */
fun containsKana(text: String): Boolean = text.any { it in '぀'..'ヿ' }

/** Listen skips an utterance in the user's own language: SenseVoice tag when present, else kana when the target is JA. */
fun listenSkips(target: Language, senseVoiceTag: String, transcript: String): Boolean {
  if (senseVoiceTag.isNotBlank()) {
    val tag = target.senseVoiceTag ?: return false
    return senseVoiceTag.contains(tag)
  }
  return target == Language.JA && containsKana(transcript)
}

enum class AsrEngine { NEMOTRON, SENSEVOICE }

/** The setting for the Japanese recognizer; NEMOTRON is the default. */
enum class JaRecognizer { NEMOTRON, SENSEVOICE }

/** SENSEVOICE only when the speaker's language is known to be JA and the option is on; Auto (null) uses NEMOTRON. */
fun asrEngineFor(speaker: Language?, ja: JaRecognizer): AsrEngine =
  if (speaker == Language.JA && ja == JaRecognizer.SENSEVOICE) AsrEngine.SENSEVOICE else AsrEngine.NEMOTRON
