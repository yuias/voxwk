package app.rly3h.voxwk.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rly3h.voxwk.R
import app.rly3h.voxwk.lang.OnlineProvider

@Composable
internal fun OnlinePage(modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
  val s by vm.settings.collectAsStateWithLifecycle()
  val hasKey by vm.hasKey.collectAsStateWithLifecycle()

  val providerLabel = stringResource(
    when (s.onlineProvider) {
      OnlineProvider.OPENAI -> R.string.provider_openai
      OnlineProvider.GEMINI -> R.string.provider_gemini
    },
  )

  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    SettingNote(stringResource(R.string.settings_online_notice))
    // The engine used when Online is toggled on. Switching here swaps which provider's key the field below edits.
    OnlineProvider.entries.forEach { p ->
      ChoiceRow(
        selected = s.onlineProvider == p,
        enabled = true,
        title = stringResource(
          when (p) {
            OnlineProvider.OPENAI -> R.string.settings_provider_openai
            OnlineProvider.GEMINI -> R.string.settings_provider_gemini
          },
        ),
        onSelect = { vm.onProviderChange(p) },
      )
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    }
  }
}
