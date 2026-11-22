package app.rly3h.voxwk.ui.settings

import androidx.annotation.StringRes
import app.rly3h.voxwk.R
import app.rly3h.voxwk.data.AppSettings
import app.rly3h.voxwk.data.DEFAULT_ONLINE_IDLE_GAP_MS
import app.rly3h.voxwk.data.EndpointParams
import app.rly3h.voxwk.data.VadParams
import app.rly3h.voxwk.lang.JaRecognizer
import kotlinx.serialization.Serializable

/** One page of the Settings list; it is also the navigation key's payload, so it is serializable. */
@Serializable
enum class SettingsPage(@StringRes val title: Int) {
  LANGUAGE(R.string.settings_my_language),
  PLAYBACK(R.string.settings_playback),
  RECOGNITION(R.string.settings_recognition),
  MODELS(R.string.settings_models),
  ONLINE(R.string.settings_online),
  GLASS(R.string.settings_glass),
  TURNS(R.string.settings_turns),
  ABOUT(R.string.settings_about),
}

/** The Settings list in display order; LANGUAGE only when there is a choice. */
internal fun topLevelPages(myLanguageCount: Int): List<SettingsPage> = listOfNotNull(
  SettingsPage.LANGUAGE.takeIf { myLanguageCount > 1 },
  SettingsPage.PLAYBACK,
  SettingsPage.RECOGNITION,
  SettingsPage.MODELS,
  SettingsPage.ONLINE,
  SettingsPage.GLASS,
  SettingsPage.TURNS,
  SettingsPage.ABOUT,
)

/** One line under a row: a string resource and its format arguments (resolved by the caller, as glassConnectFailureRes is). */
internal data class Summary(@StringRes val res: Int, val args: List<Any> = emptyList())

/** Rate shown only when I hear translations: it applies to me only. */
internal fun playbackSummary(autoSpeak: Boolean, autoSpeakPartner: Boolean, speechRate: Float): Summary = when {
  autoSpeak && autoSpeakPartner -> Summary(R.string.settings_summary_speak_both, listOf(speechRate))
  autoSpeak -> Summary(R.string.settings_summary_speak_me, listOf(speechRate))
  autoSpeakPartner -> Summary(R.string.settings_summary_speak_partner)
  else -> Summary(R.string.settings_summary_off)
}

/** Null while the manifest is still loading (no rows), so the row shows no stale "Ready". */
internal fun modelsSummary(state: ModelsUiState): Summary? = when {
  state.rows.isEmpty() -> null
  state.rows.any { it.active } -> Summary(R.string.settings_summary_models_downloading)
  !state.allRequiredPresent -> Summary(R.string.settings_summary_models_missing)
  else -> Summary(R.string.settings_summary_models_ready)
}

internal fun glassSummary(s: AppSettings, devices: List<GlassDevice>): Summary {
  val address = s.glassAddress
  if (!s.glassEnabled || address == null) return Summary(R.string.settings_summary_off)
  val device = devices.firstOrNull { it.address == address }
  return if (device != null) Summary(R.string.settings_summary_on_device, listOf(device.label)) else Summary(R.string.settings_summary_on)
}

/** "Custom" when any knob shown on the Turns page differs from its default; VAD counts only while SenseVoice is selected. */
internal fun turnDetectionSummary(s: AppSettings): Summary {
  val isDefault = s.endpoint == EndpointParams() &&
    s.onlineIdleGapMs == DEFAULT_ONLINE_IDLE_GAP_MS &&
    (s.jaRecognizer != JaRecognizer.SENSEVOICE || s.vad == VadParams())
  return Summary(if (isDefault) R.string.settings_summary_default else R.string.settings_summary_custom)
}
