package app.rly3h.voxwk.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rly3h.voxwk.R
import app.rly3h.voxwk.data.MtModel
import app.rly3h.voxwk.lang.JaRecognizer
import app.rly3h.voxwk.lang.MY_LANGUAGES
import app.rly3h.voxwk.lang.OnlineProvider

@Composable
fun SettingsScreen(onOpen: (SettingsPage) -> Unit, modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
  val s by vm.settings.collectAsStateWithLifecycle()
  val hasKey by vm.hasKey.collectAsStateWithLifecycle()
  val models by vm.models.collectAsStateWithLifecycle()

  // A download can finish, and a device can be paired, while another screen is showing.
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    vm.refreshModels()
    vm.refreshGlassDevices()
  }

  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    topLevelPages(MY_LANGUAGES.size).forEach { page ->
      val summary = when (page) {
        SettingsPage.LANGUAGE -> stringResource(s.myLanguage.label)
        SettingsPage.PLAYBACK -> playbackSummary(s.autoSpeak, s.autoSpeakPartner, s.speechRate).text()
        SettingsPage.RECOGNITION ->
          stringResource(
            R.string.settings_summary_join,
            stringResource(
              when (s.jaRecognizer) {
                JaRecognizer.NEMOTRON -> R.string.settings_ja_nemotron
                JaRecognizer.SENSEVOICE -> R.string.settings_ja_sensevoice
              },
            ),
            stringResource(
              when (s.mtModel) {
                MtModel.PLAMO3_0_8B -> R.string.settings_mt_08b
                MtModel.PLAMO3_2B -> R.string.settings_mt_2b
              },
            ),
          )
        SettingsPage.MODELS -> modelsSummary(models)?.text()
        SettingsPage.ONLINE ->
          stringResource(
            R.string.settings_summary_join,
            stringResource(
              when (s.onlineProvider) {
                OnlineProvider.OPENAI -> R.string.settings_provider_openai
                OnlineProvider.GEMINI -> R.string.settings_provider_gemini
              },
            ),
            stringResource(if (hasKey) R.string.settings_summary_key_saved else R.string.settings_summary_key_none),
          )
        SettingsPage.GLASS -> glassSummary(s, vm.glassDevices).text()
        SettingsPage.TURNS -> turnDetectionSummary(s).text()
        SettingsPage.ABOUT -> stringResource(R.string.settings_about_version, stringResource(R.string.app_name), vm.versionName, vm.gitHash)
      }
      CategoryRow(stringResource(page.title), summary, onClick = { onOpen(page) })
    }
  }
}

@Composable
private fun Summary.text(): String = stringResource(res, *args.toTypedArray())

/** One settings page; each is its own back-stack entry, so only the data it shows is refreshed on resume. */
@Composable
internal fun SettingsPageScreen(page: SettingsPage, modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    when (page) {
      SettingsPage.MODELS -> vm.refreshModels()
      SettingsPage.GLASS -> vm.refreshGlassDevices()
      else -> Unit
    }
  }

  when (page) {
    SettingsPage.LANGUAGE -> LanguagePage(modifier, vm)
    SettingsPage.PLAYBACK -> PlaybackPage(modifier, vm)
    SettingsPage.RECOGNITION -> RecognitionPage(modifier, vm)
    SettingsPage.MODELS -> ModelsSection(modifier, vm)
    SettingsPage.ONLINE -> OnlinePage(modifier, vm)
    SettingsPage.GLASS -> PaddedPage(modifier) { GlassSection(vm) }
    SettingsPage.TURNS -> TurnDetectionPage(modifier, vm)
    SettingsPage.ABOUT -> AboutPage(modifier, vm)
  }
}

// The layout of a page that has not moved to the shared rows yet.
@Composable
private fun PaddedPage(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
  Column(
    modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    content = content,
  )
}
