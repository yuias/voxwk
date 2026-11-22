package app.rly3h.voxwk.data

import app.rly3h.voxwk.glass.GlassDisplayMode
import app.rly3h.voxwk.lang.JaRecognizer
import app.rly3h.voxwk.lang.Language
import app.rly3h.voxwk.lang.OnlineProvider
import app.rly3h.voxwk.translate.Audience
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

  @Test
  fun emptyPrefsGiveGlassDefaults() {
    val s = parseSettings(emptyMap())
    assertEquals(false, s.glassEnabled)
    assertNull(s.glassAddress)
    assertEquals(GlassMic.FRONT, s.glassMic)
    assertEquals(GlassDisplayMode.SOURCE_AND_TRANSLATION, s.glassDisplay)
  }

  @Test
  fun storedGlassValuesAreKept() {
    val s = parseSettings(
      mapOf(
        "glassEnabled" to true,
        "glassAddress" to "00:11:22:AA:BB:CC",
        "glassMic" to "ALL_AROUND",
        "glassDisplay" to "TRANSLATION_ONLY",
      ),
    )
    assertEquals(true, s.glassEnabled)
    assertEquals("00:11:22:AA:BB:CC", s.glassAddress)
    assertEquals(GlassMic.ALL_AROUND, s.glassMic)
    assertEquals(GlassDisplayMode.TRANSLATION_ONLY, s.glassDisplay)
  }

  @Test
  fun unknownGlassEnumStringGivesItsDefault() {
    val s = parseSettings(mapOf("glassMic" to "bogus", "glassDisplay" to "bogus"))
    assertEquals(GlassMic.FRONT, s.glassMic)
    assertEquals(GlassDisplayMode.SOURCE_AND_TRANSLATION, s.glassDisplay)
  }

  @Test
  fun malformedGlassAddressGivesNull() {
    assertNull(parseSettings(mapOf("glassAddress" to "aa:bb")).glassAddress)
    assertNull(parseSettings(mapOf("glassAddress" to "00:11:22:33:44:5G")).glassAddress)
    assertNull(parseSettings(mapOf("glassAddress" to "00:11:22:aa:bb:cc")).glassAddress)
  }

  @Test
  fun glassMicPickupModes() {
    assertEquals(0, GlassMic.FRONT.pickupMode)
    assertEquals(1, GlassMic.ALL_AROUND.pickupMode)
  }

  @Test
  fun autoSpeakPartnerDefaultsToTrue() {
    assertEquals(true, parseSettings(emptyMap()).autoSpeakPartner)
  }

  @Test
  fun storedAutoSpeakPartnerIsKept() {
    assertEquals(false, parseSettings(mapOf("autoSpeakPartner" to false)).autoSpeakPartner)
  }

  @Test
  fun mistypedAutoSpeakPartnerGivesTrue() {
    assertEquals(true, parseSettings(mapOf("autoSpeakPartner" to "no")).autoSpeakPartner)
  }

  @Test
  fun speaksFollowsTheAudience() {
    val s = AppSettings(autoSpeak = false, autoSpeakPartner = true)
    assertEquals(false, s.speaks(Audience.ME))
    assertEquals(true, s.speaks(Audience.PARTNER))
  }
}
