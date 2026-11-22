package app.rly3h.voxwk.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rly3h.voxwk.R
import app.rly3h.voxwk.data.EndpointBounds
import app.rly3h.voxwk.data.MtModel
import app.rly3h.voxwk.data.OnlineBounds
import app.rly3h.voxwk.data.VadBounds
import app.rly3h.voxwk.lang.JaRecognizer
import app.rly3h.voxwk.lang.MY_LANGUAGES
import app.rly3h.voxwk.lang.OnlineProvider

private val RATES = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
  val s by vm.settings.collectAsStateWithLifecycle()
  val hasKey by vm.hasKey.collectAsStateWithLifecycle()
  val res = LocalResources.current
  val recording = vm.recording
  val mtInfo = vm.mtInfo

  // A download can finish, and a device can be paired, while another screen is showing.
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    vm.refreshModels()
    vm.refreshGlassDevices()
  }

  Column(
    modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (MY_LANGUAGES.size > 1) {
      SectionTitle(stringResource(R.string.settings_my_language))
      Text(
        stringResource(R.string.settings_my_language_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MY_LANGUAGES.forEach { lang ->
          FilterChip(
            selected = s.myLanguage == lang,
            enabled = !recording,
            onClick = { vm.onMyLanguageChange(lang) },
            label = { Text(stringResource(lang.label)) },
          )
        }
      }

      HorizontalDivider()
    }

    SectionTitle(stringResource(R.string.settings_playback))
    Text(stringResource(R.string.settings_speech_rate), style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      RATES.forEach { r ->
        FilterChip(
          selected = s.speechRate == r,
          onClick = { vm.onSpeechRateChange(r) },
          label = { Text(stringResource(R.string.settings_speech_rate_value, r)) },
        )
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.settings_speak_for_me), style = MaterialTheme.typography.bodyMedium)
      Switch(checked = s.autoSpeak, onCheckedChange = vm::onAutoSpeakChange)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.settings_speak_for_partner), style = MaterialTheme.typography.bodyMedium)
      Switch(checked = s.autoSpeakPartner, onCheckedChange = vm::onAutoSpeakPartnerChange)
    }

    HorizontalDivider()

    SectionTitle(stringResource(R.string.settings_ja_recognition))
    ChoiceRow(
      selected = s.jaRecognizer == JaRecognizer.NEMOTRON,
      enabled = !recording,
      title = stringResource(R.string.settings_ja_nemotron),
      description = stringResource(R.string.settings_ja_nemotron_desc),
      onSelect = { vm.onJaRecognizerChange(JaRecognizer.NEMOTRON) },
    )
    ChoiceRow(
      selected = s.jaRecognizer == JaRecognizer.SENSEVOICE,
      enabled = !recording,
      title = stringResource(R.string.settings_ja_sensevoice),
      description = stringResource(R.string.settings_ja_sensevoice_desc),
      onSelect = { vm.onJaRecognizerChange(JaRecognizer.SENSEVOICE) },
    )
    Text(
      stringResource(R.string.settings_endpoint_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
    )
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
    TextButton(onClick = vm::resetEndpoint) { Text(stringResource(R.string.settings_reset_defaults)) }

    HorizontalDivider()

    SectionTitle(stringResource(R.string.settings_translation))
    ChoiceRow(
      selected = s.mtModel == MtModel.PLAMO3_0_8B,
      enabled = !recording,
      title = stringResource(R.string.settings_mt_08b),
      description = stringResource(R.string.settings_mt_08b_desc),
      onSelect = { vm.onMtModelChange(MtModel.PLAMO3_0_8B) },
    )
    ChoiceRow(
      selected = s.mtModel == MtModel.PLAMO3_2B,
      enabled = !recording && (vm.mt2bPresent || s.mtModel == MtModel.PLAMO3_2B),
      title = stringResource(R.string.settings_mt_2b),
      description = stringResource(if (vm.mt2bPresent) R.string.settings_mt_2b_desc else R.string.settings_mt_2b_desc_missing),
      onSelect = { vm.onMtModelChange(MtModel.PLAMO3_2B) },
    )
    // Attribution required by the PLaMo Community License.
    Text(stringResource(R.string.settings_built_with_plamo), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    if (mtInfo?.supported == false) {
      Text(
        stringResource(R.string.settings_mt_unsupported),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
    if (recording) {
      Text(
        stringResource(R.string.settings_locked_while_recording),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
    }

    HorizontalDivider()

    ModelsSection()

    HorizontalDivider()

    if (s.jaRecognizer == JaRecognizer.SENSEVOICE) {
      SectionTitle(stringResource(R.string.settings_vad))
      Text(
        stringResource(R.string.settings_vad_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
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
      TextButton(onClick = vm::resetVad) { Text(stringResource(R.string.settings_reset_defaults)) }

      HorizontalDivider()
    }

    SectionTitle(stringResource(R.string.settings_online))
    val providerLabel = stringResource(
      when (s.onlineProvider) {
        OnlineProvider.OPENAI -> R.string.provider_openai
        OnlineProvider.GEMINI -> R.string.provider_gemini
      },
    )
    Text(
      stringResource(R.string.settings_online_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
    )
    // The engine used when Online is toggled on. Switching here swaps which provider's key the field below edits.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OnlineProvider.entries.forEach { p ->
        FilterChip(
          selected = s.onlineProvider == p,
          onClick = { vm.onProviderChange(p) },
          label = {
            Text(
              stringResource(
                when (p) {
                  OnlineProvider.OPENAI -> R.string.settings_provider_openai
                  OnlineProvider.GEMINI -> R.string.settings_provider_gemini
                },
              ),
            )
          },
        )
      }
    }
    OutlinedTextField(
      value = vm.apiKeyInput,
      onValueChange = vm::onApiKeyInputChange,
      label = { Text(stringResource(R.string.settings_api_key, providerLabel)) },
      singleLine = true,
      visualTransformation = if (vm.keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
      trailingIcon = {
        TextButton(onClick = vm::toggleKeyVisible) {
          Text(stringResource(if (vm.keyVisible) R.string.settings_key_hide else R.string.settings_key_show))
        }
      },
      modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(enabled = vm.apiKeyInput.isNotBlank(), onClick = vm::saveKey) {
        Text(stringResource(R.string.settings_key_save))
      }
      TextButton(onClick = vm::clearKey) { Text(stringResource(R.string.settings_key_clear)) }
      TextButton(enabled = hasKey && vm.keyTest != KeyTest.Running, onClick = vm::testConnection) {
        Text(stringResource(R.string.settings_key_test))
      }
    }
    Text(
      when (val test = vm.keyTest) {
        KeyTest.Running -> stringResource(R.string.settings_test_running)
        KeyTest.Ok -> stringResource(R.string.settings_test_ok)
        is KeyTest.Failed -> stringResource(R.string.settings_test_failed, test.detail)
        null -> stringResource(if (hasKey) R.string.settings_key_saved else R.string.settings_key_none)
      },
      style = MaterialTheme.typography.bodySmall,
    )

    Text(
      stringResource(R.string.settings_idle_gap_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
    )
    SettingSlider(
      stringResource(R.string.settings_idle_gap),
      s.onlineIdleGapMs.toFloat(),
      OnlineBounds.idleGapMs,
      { res.getString(R.string.settings_unit_ms, it.toInt()) },
    ) { vm.onIdleGapChange(it.toInt()) }

    HorizontalDivider()

    GlassSection()

    HorizontalDivider()

    SectionTitle(stringResource(R.string.settings_about))
    // Tight inner spacing keeps the version block together; the parent Column's
    // 12.dp gap would otherwise scatter these one-line entries.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        stringResource(R.string.settings_about_version, stringResource(R.string.app_name), vm.versionName, vm.gitHash),
        style = MaterialTheme.typography.bodySmall,
      )
      mtInfo?.let { Text(it.systemInfo, style = MaterialTheme.typography.bodySmall) }
    }
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(text, style = MaterialTheme.typography.titleMedium)
}
