package app.rly3h.voxwk.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rly3h.voxwk.R

private val RATES = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaybackPage(modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
  val s by vm.settings.collectAsStateWithLifecycle()

  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    SwitchRow(stringResource(R.string.settings_speak_for_me), s.autoSpeak, vm::onAutoSpeakChange)
    SwitchRow(stringResource(R.string.settings_speak_for_partner), s.autoSpeakPartner, vm::onAutoSpeakPartnerChange)
    SectionHeader(stringResource(R.string.settings_speech_rate))
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      RATES.forEach { r ->
        FilterChip(
          selected = s.speechRate == r,
          onClick = { vm.onSpeechRateChange(r) },
          label = { Text(stringResource(R.string.settings_speech_rate_value, r)) },
        )
      }
    }
  }
}
