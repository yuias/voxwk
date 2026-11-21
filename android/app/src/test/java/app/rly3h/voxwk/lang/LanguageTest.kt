package app.rly3h.voxwk.lang

import app.rly3h.voxwk.lang.Language.DE
import app.rly3h.voxwk.lang.Language.EN
import app.rly3h.voxwk.lang.Language.ES
import app.rly3h.voxwk.lang.Language.FR
import app.rly3h.voxwk.lang.Language.IT
import app.rly3h.voxwk.lang.Language.JA
import app.rly3h.voxwk.lang.Language.RU
import app.rly3h.voxwk.lang.Language.ZH_HANS
import app.rly3h.voxwk.lang.Language.ZH_HANT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageTest {
  @Test
  fun allNineCodesRoundTrip() {
    assertEquals(
      listOf("ja", "en", "fr", "de", "it", "es", "ru", "zh-hans", "zh-hant"),
      Language.entries.map { it.code },
    )
    for (l in Language.entries) assertEquals(l, languageByCode(l.code))
  }

  @Test
  fun unknownCodesAreNull() {
    assertNull(languageByCode("eng_Latn"))
    assertNull(languageByCode("zh"))
    assertNull(languageByCode(null))
  }

  @Test
  fun nemotronCodesAreDictionaryKeys() {
    // Keys from the model's prompt_dictionary; a bare "ja" is not one and silently decodes as auto.
    assertEquals(
      listOf("ja-JP", "en-US", "fr-FR", "de-DE", "it-IT", "es-ES", "ru-RU", "zh-CN", null),
      listOf(JA, EN, FR, DE, IT, ES, RU, ZH_HANS, ZH_HANT).map { it.nemotronCode },
    )
    assertFalse(ZH_HANT.offlineInput)
  }

  @Test
  fun onlineCodes() {
    assertEquals("zh", onlineCode(OnlineProvider.OPENAI, ZH_HANS))
    assertNull(onlineCode(OnlineProvider.OPENAI, ZH_HANT))
    assertEquals("zh-Hant", onlineCode(OnlineProvider.GEMINI, ZH_HANT))
    assertEquals("zh-Hans", onlineCode(OnlineProvider.GEMINI, ZH_HANS))
  }

  @Test
  fun onlineSupportNeedsEveryTarget() {
    assertFalse(onlineSupported(OnlineProvider.OPENAI, listOf(JA, ZH_HANT)))
    assertTrue(onlineSupported(OnlineProvider.GEMINI, listOf(JA, ZH_HANT)))
    assertTrue(onlineSupported(OnlineProvider.OPENAI, listOf(JA, EN, FR, DE, IT, ES, RU, ZH_HANS)))
  }

  @Test
  fun kanaOnlyIsJapanese() {
    assertTrue(containsKana("こんにちは"))
    assertTrue(containsKana("カタカナ"))
    assertFalse(containsKana("你好"))
    assertFalse(containsKana("hello"))
  }

  @Test
  fun listenSkipsOwnLanguage() {
    assertTrue(listenSkips(JA, "", "こんにちは"))
    assertFalse(listenSkips(JA, "", "你好"))
    assertFalse(listenSkips(JA, "", "hello"))
    assertTrue(listenSkips(EN, "<|en|>", "hello"))
    assertFalse(listenSkips(JA, "<|en|>", "hello"))
    assertFalse(listenSkips(FR, "<|en|>", "hello"))
  }

  @Test
  fun recognizerChoice() {
    assertEquals(AsrEngine.SENSEVOICE, asrEngineFor(JA, JaRecognizer.SENSEVOICE))
    assertEquals(AsrEngine.NEMOTRON, asrEngineFor(JA, JaRecognizer.NEMOTRON))
    assertEquals(AsrEngine.NEMOTRON, asrEngineFor(null, JaRecognizer.SENSEVOICE))
    assertEquals(AsrEngine.NEMOTRON, asrEngineFor(FR, JaRecognizer.SENSEVOICE))
  }

  @Test
  fun offlineCapability() {
    assertTrue(offlineCapable(listOf(JA, ZH_HANS)))
    assertFalse(offlineCapable(listOf(JA, ZH_HANT)))
    assertTrue(offlineCapable(emptyList()))
  }

  @Test
  fun myLanguageIsJapaneseOnly() {
    assertEquals(listOf(JA), MY_LANGUAGES)
  }
}
