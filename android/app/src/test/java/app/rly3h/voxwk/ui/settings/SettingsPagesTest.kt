package app.rly3h.voxwk.ui.settings

import app.rly3h.voxwk.R
import app.rly3h.voxwk.data.AppSettings
import app.rly3h.voxwk.data.VadParams
import app.rly3h.voxwk.lang.JaRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsPagesTest {
  private fun row(inUse: Boolean = true, present: Boolean = false, download: ModelDownload = ModelDownload.Idle) =
    ModelRowState("m", "Model", null, present, 0L, inUse, download)

  @Test
  fun topLevelPagesHidesLanguageForOneLanguage() {
    assertEquals(
      listOf(
        SettingsPage.PLAYBACK,
        SettingsPage.RECOGNITION,
        SettingsPage.MODELS,
        SettingsPage.ONLINE,
        SettingsPage.GLASS,
        SettingsPage.TURNS,
        SettingsPage.ABOUT,
      ),
      topLevelPages(1),
    )
  }

  @Test
  fun topLevelPagesStartsWithLanguageForSeveral() {
    val pages = topLevelPages(2)
    assertEquals(8, pages.size)
    assertEquals(SettingsPage.LANGUAGE, pages.first())
  }

  @Test
  fun playbackSummaryByAudience() {
    assertEquals(Summary(R.string.settings_summary_speak_both, listOf(1.25f)), playbackSummary(true, true, 1.25f))
    assertEquals(Summary(R.string.settings_summary_speak_me, listOf(1.0f)), playbackSummary(true, false, 1.0f))
    assertEquals(Summary(R.string.settings_summary_speak_partner), playbackSummary(false, true, 2.0f))
    assertEquals(Summary(R.string.settings_summary_off), playbackSummary(false, false, 1.0f))
  }

  @Test
  fun modelsSummaryHasNoValueWhileLoading() {
    assertNull(modelsSummary(ModelsUiState()))
  }

  @Test
  fun modelsSummaryReady() {
    assertEquals(Summary(R.string.settings_summary_models_ready), modelsSummary(ModelsUiState(listOf(row(present = true)))))
  }

  @Test
  fun modelsSummaryMissing() {
    assertEquals(Summary(R.string.settings_summary_models_missing), modelsSummary(ModelsUiState(listOf(row()))))
  }

  @Test
  fun modelsSummaryDownloading() {
    val running = row(inUse = false, download = ModelDownload.Running("download", 10, 100))
    assertEquals(Summary(R.string.settings_summary_models_downloading), modelsSummary(ModelsUiState(listOf(row(), running))))
  }

  @Test
  fun glassSummary() {
    val address = "AA:BB:CC:DD:EE:FF"
    val on = AppSettings(glassEnabled = true, glassAddress = address)
    assertEquals(Summary(R.string.settings_summary_off), glassSummary(AppSettings(), emptyList()))
    assertEquals(
      Summary(R.string.settings_summary_on_device, listOf("MemoMind One")),
      glassSummary(on, listOf(GlassDevice(address, "MemoMind One"))),
    )
    assertEquals(Summary(R.string.settings_summary_on), glassSummary(on, emptyList()))
    assertEquals(Summary(R.string.settings_summary_off), glassSummary(AppSettings(glassEnabled = true), emptyList()))
  }

  @Test
  fun turnDetectionSummaryDefault() {
    assertEquals(Summary(R.string.settings_summary_default), turnDetectionSummary(AppSettings()))
  }

  @Test
  fun turnDetectionSummaryCustomEndpointOrPause() {
    val custom = Summary(R.string.settings_summary_custom)
    val base = AppSettings()
    assertEquals(custom, turnDetectionSummary(base.copy(endpoint = base.endpoint.copy(rule2 = 0.8f))))
    assertEquals(custom, turnDetectionSummary(base.copy(onlineIdleGapMs = 900)))
  }

  @Test
  fun turnDetectionSummaryCountsVadOnlyWithSenseVoice() {
    val vad = VadParams(threshold = 0.3f)
    assertEquals(
      Summary(R.string.settings_summary_default),
      turnDetectionSummary(AppSettings(jaRecognizer = JaRecognizer.NEMOTRON, vad = vad)),
    )
    assertEquals(
      Summary(R.string.settings_summary_custom),
      turnDetectionSummary(AppSettings(jaRecognizer = JaRecognizer.SENSEVOICE, vad = vad)),
    )
  }
}
