package app.rly3h.voxwk.ui.session

import app.rly3h.voxwk.R
import app.rly3h.voxwk.lang.Language.EN
import app.rly3h.voxwk.lang.Language.JA
import app.rly3h.voxwk.lang.Language.ZH_HANS
import app.rly3h.voxwk.lang.Language.ZH_HANT
import app.rly3h.voxwk.lang.OnlineProvider.GEMINI
import app.rly3h.voxwk.lang.OnlineProvider.OPENAI
import app.rly3h.voxwk.translate.EngineStatus
import app.rly3h.voxwk.translate.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SessionRulesTest {
  @Test
  fun listenSourcesStartWithAuto() {
    assertNull(listenSourceOptions(JA).first())
    assertEquals(9, listenSourceOptions(JA).size)
    assertFalse(JA in listenSourceOptions(JA))
  }

  @Test
  fun conversationPartnersExcludeMine() {
    assertEquals(8, conversationPartnerOptions(JA).size)
    assertFalse(JA in conversationPartnerOptions(JA))
  }

  @Test
  fun onlineNeedsNetworkKeyAndProviderSupport() {
    assertFalse(onlineAvailable(true, true, OPENAI, listOf(JA, ZH_HANT)))
    assertTrue(onlineAvailable(true, true, GEMINI, listOf(JA, ZH_HANT)))
    assertFalse(onlineAvailable(false, true, GEMINI, listOf(JA)))
    assertFalse(onlineAvailable(true, false, GEMINI, listOf(JA)))
  }

  @Test
  fun onlyZhHantNeedsOnline() {
    assertTrue(requiresOnline(listOf(JA, ZH_HANT)))
    assertFalse(requiresOnline(listOf(JA, ZH_HANS)))
    assertFalse(requiresOnline(emptyList()))
  }

  @Test
  fun everyStatusMapsToAResource() {
    val simple = listOf(
      EngineStatus.Listening,
      EngineStatus.Connecting,
      EngineStatus.NoEarphones,
      EngineStatus.NoTtsVoice(EN),
      EngineStatus.Directional(Speaker.ME, EngineStatus.Listening),
    ) + EngineStatus.Stage.entries.map { EngineStatus.Failed(it, "detail") }
    for (status in simple) assertTrue("$status", statusRes(status) != 0)
    assertEquals(listOf<Any?>(null), statusArgs(EngineStatus.Failed(EngineStatus.Stage.MIC, null)))
    assertEquals(
      listOf<Any?>(Speaker.PARTNER, EngineStatus.Connecting),
      statusArgs(EngineStatus.Directional(Speaker.PARTNER, EngineStatus.Connecting)),
    )
  }

  @Test
  fun glassConnectFailureNeedsPermissionOnlyForSecurityException() {
    assertEquals(R.string.status_glass_permission, glassConnectFailureRes(SecurityException("denied")))
  }

  @Test
  fun glassConnectFailureOtherwiseMeansUnavailable() {
    assertEquals(R.string.status_glass_unavailable, glassConnectFailureRes(IOException("connect failed")))
  }
}
