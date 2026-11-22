package app.rly3h.voxwk.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rly3h.voxwk.R
import app.rly3h.voxwk.data.MtModel
import app.rly3h.voxwk.lang.JaRecognizer

@Composable
internal fun RecognitionPage(modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
  val s by vm.settings.collectAsStateWithLifecycle()
  val recording = vm.recording
  val mtInfo = vm.mtInfo

  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    SectionHeader(stringResource(R.string.settings_ja_recognition))
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

    SectionHeader(stringResource(R.string.settings_translation))
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
    SettingNote(stringResource(R.string.settings_built_with_plamo))
    if (mtInfo?.supported == false) SettingNote(stringResource(R.string.settings_mt_unsupported), error = true)
    if (recording) SettingNote(stringResource(R.string.settings_locked_while_recording))
  }
}
