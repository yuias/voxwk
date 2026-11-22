package app.rly3h.voxwk.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rly3h.voxwk.R
import app.rly3h.voxwk.data.EndpointBounds
import app.rly3h.voxwk.data.OnlineBounds
import app.rly3h.voxwk.data.VadBounds
import app.rly3h.voxwk.lang.JaRecognizer

@Composable
internal fun TurnDetectionPage(modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
  val s by vm.settings.collectAsStateWithLifecycle()
  val res = LocalResources.current

  // The button's own 8 dp inset puts its label on the 16 dp content edge.
  val resetModifier = Modifier.padding(horizontal = 8.dp)

  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    SectionHeader(stringResource(R.string.settings_endpoint))
    val ep = s.endpoint
    SettingSlider(stringResource(R.string.settings_endpoint_end_of_turn), ep.rule2, EndpointBounds.rule2, { res.getString(R.string.settings_unit_seconds_1, it) }) {
      vm.onEndpointChange { p -> p.copy(rule2 = it) }
    }
    SettingSlider(stringResource(R.string.settings_endpoint_before_speech), ep.rule1, EndpointBounds.rule1, { res.getString(R.string.settings_unit_seconds_1, it) }) {
      vm.onEndpointChange { p -> p.copy(rule1 = it) }
    }
    SettingSlider(stringResource(R.string.settings_endpoint_max_length), ep.rule3, EndpointBounds.rule3, { res.getString(R.string.settings_unit_seconds, it.toInt()) }) {
      vm.onEndpointChange { p -> p.copy(rule3 = it) }
    }
    TextButton(onClick = vm::resetEndpoint, modifier = resetModifier) { Text(stringResource(R.string.settings_reset_defaults)) }

    if (s.jaRecognizer == JaRecognizer.SENSEVOICE) {
      SectionHeader(stringResource(R.string.settings_vad))
      val vad = s.vad
      SettingSlider(stringResource(R.string.settings_vad_silence), vad.minSilenceMs.toFloat(), VadBounds.minSilenceMs, { res.getString(R.string.settings_unit_ms, it.toInt()) }) {
        vm.onVadChange { p -> p.copy(minSilenceMs = it.toInt()) }
      }
      SettingSlider(stringResource(R.string.settings_vad_sensitivity), vad.threshold, VadBounds.threshold, { res.getString(R.string.settings_unit_ratio, it) }) {
        vm.onVadChange { p -> p.copy(threshold = it) }
      }
      SettingSlider(stringResource(R.string.settings_vad_min_speech), vad.minSpeechMs.toFloat(), VadBounds.minSpeechMs, { res.getString(R.string.settings_unit_ms, it.toInt()) }) {
        vm.onVadChange { p -> p.copy(minSpeechMs = it.toInt()) }
      }
      SettingSlider(stringResource(R.string.settings_vad_max_segment), vad.maxSpeechMs.toFloat(), VadBounds.maxSpeechMs, { res.getString(R.string.settings_unit_seconds, (it / 1000).toInt()) }) {
        vm.onVadChange { p -> p.copy(maxSpeechMs = it.toInt()) }
      }
      TextButton(onClick = vm::resetVad, modifier = resetModifier) { Text(stringResource(R.string.settings_reset_defaults)) }
    }

    SectionHeader(stringResource(R.string.settings_online))
    SettingSlider(
      stringResource(R.string.settings_idle_gap),
      s.onlineIdleGapMs.toFloat(),
      OnlineBounds.idleGapMs,
      { res.getString(R.string.settings_unit_ms, it.toInt()) },
    ) { vm.onIdleGapChange(it.toInt()) }
  }
}
