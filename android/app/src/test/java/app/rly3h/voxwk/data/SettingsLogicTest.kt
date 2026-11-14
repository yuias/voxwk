package app.rly3h.voxwk.data

import app.rly3h.voxwk.lang.JaRecognizer
import app.rly3h.voxwk.lang.Language
import app.rly3h.voxwk.lang.OnlineProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsLogicTest {
  @Test
  fun emptyPrefsGiveDefaults() {
    val s = parseSettings(emptyMap())
    assertEquals(AppSettings(), s)
    assertEquals(Language.JA, s.myLanguage)
    assertNull(s.listenSource)
    assertEquals(Language.EN, s.conversationPartner)
    assertEquals(SessionMode.LISTEN, s.sessionMode)
    assertEquals(JaRecognizer.NEMOTRON, s.jaRecognizer)
    assertEquals(MtModel.PLAMO3_0_8B, s.mtModel)
    assertEquals(OnlineProvider.OPENAI, s.onlineProvider)
  }

  @Test
  fun storedValuesAreKept() {
    val s = parseSettings(
      mapOf(
        "listenSource" to "fr",
        "conversationPartner" to "zh-hant",
        "sessionMode" to "CONVERSATION",
        "jaRecognizer" to "SENSEVOICE",
        "mtModel" to "PLAMO3_2B",
        "autoSpeak" to false,
        "speechRate" to 1.5f,
        "onlineEnabled" to true,
        "onlineProvider" to "GEMINI",
        "onlineIdleGapMs" to 2000,
        "vadProbThreshold" to 0.3f,
        "endpointRule2" to 2.0f,
      ),
    )
    assertEquals(Language.FR, s.listenSource)
    assertEquals(Language.ZH_HANT, s.conversationPartner)
    assertEquals(SessionMode.CONVERSATION, s.sessionMode)
    assertEquals(JaRecognizer.SENSEVOICE, s.jaRecognizer)
    assertEquals(MtModel.PLAMO3_2B, s.mtModel)
    assertEquals(false, s.autoSpeak)
    assertEquals(1.5f, s.speechRate)
    assertEquals(true, s.onlineEnabled)
    assertEquals(OnlineProvider.GEMINI, s.onlineProvider)
    assertEquals(2000, s.onlineIdleGapMs)
    assertEquals(VadParams(threshold = 0.3f), s.vad)
    assertEquals(EndpointParams(rule2 = 2.0f), s.endpoint)
  }

  @Test
  fun listenSourceEqualToMyLanguageIsAuto() {
    assertNull(parseSettings(mapOf("myLanguage" to "ja", "listenSource" to "ja")).listenSource)
  }

  @Test
  fun conversationPartnerEqualToMineFallsBackToDefault() {
    assertEquals(Language.EN, parseSettings(mapOf("conversationPartner" to "ja")).conversationPartner)
  }

  @Test
  fun unknownEnumStringGivesItsDefault() {
    val s = parseSettings(
      mapOf(
        "sessionMode" to "bogus",
        "jaRecognizer" to "bogus",
        "mtModel" to "PLAMO2_1B",
        "onlineProvider" to "bogus",
      ),
    )
    assertEquals(SessionMode.LISTEN, s.sessionMode)
    assertEquals(JaRecognizer.NEMOTRON, s.jaRecognizer)
    assertEquals(MtModel.PLAMO3_0_8B, s.mtModel)
    assertEquals(OnlineProvider.OPENAI, s.onlineProvider)
  }

  @Test
  fun unknownLanguageCodeGivesItsDefault() {
    val s = parseSettings(
      mapOf("myLanguage" to "eng_Latn", "listenSource" to "xx", "conversationPartner" to "jpn_Jpan"),
    )
    assertEquals(Language.JA, s.myLanguage)
    assertNull(s.listenSource)
    assertEquals(Language.EN, s.conversationPartner)
  }

  @Test
  fun myLanguageOutsideTheOfferedListGivesDefault() {
    // EN is a valid language but not (yet) one of MY_LANGUAGES.
    assertEquals(Language.JA, parseSettings(mapOf("myLanguage" to "en")).myLanguage)
  }

  @Test
  fun mistypedValuesGiveDefaults() {
    val s = parseSettings(mapOf("autoSpeak" to "yes", "speechRate" to 2, "onlineIdleGapMs" to 1.5f))
    assertEquals(true, s.autoSpeak)
    assertEquals(1.0f, s.speechRate)
    assertEquals(DEFAULT_ONLINE_IDLE_GAP_MS, s.onlineIdleGapMs)
  }
}
