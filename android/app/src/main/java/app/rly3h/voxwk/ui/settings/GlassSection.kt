package app.rly3h.voxwk.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rly3h.voxwk.R
import app.rly3h.voxwk.data.GlassMic
import app.rly3h.voxwk.glass.GlassDisplayMode

/** Glass choice and options. There is no connection state: the link exists only during a session. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlassSection(vm: SettingsViewModel = viewModel()) {
  val s by vm.settings.collectAsStateWithLifecycle()
  val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    vm.refreshGlassDevices()
  }

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(stringResource(R.string.settings_glass), style = MaterialTheme.typography.titleMedium)

    if (!vm.glassPermitted) {
      Button(onClick = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
        Text(stringResource(R.string.settings_glass_grant))
      }
    } else if (vm.glassDevices.isEmpty()) {
      Text(
        stringResource(R.string.settings_glass_no_devices),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
    } else {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.glassDevices.forEach { device ->
          FilterChip(
            selected = s.glassAddress == device.address,
            onClick = { vm.onGlassAddressChange(device.address) },
            label = { Text(device.label) },
          )
        }
      }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.settings_glass_enabled), style = MaterialTheme.typography.bodyMedium)
      Switch(checked = s.glassEnabled, enabled = s.glassAddress != null, onCheckedChange = vm::onGlassEnabledChange)
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      GlassMic.entries.forEach { mic ->
        FilterChip(
          selected = s.glassMic == mic,
          onClick = { vm.onGlassMicChange(mic) },
          label = {
            Text(
              stringResource(
                when (mic) {
                  GlassMic.FRONT -> R.string.settings_glass_mic_front
                  GlassMic.ALL_AROUND -> R.string.settings_glass_mic_all_around
                },
              ),
            )
          },
        )
      }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      GlassDisplayMode.entries.forEach { mode ->
        FilterChip(
          selected = s.glassDisplay == mode,
          onClick = { vm.onGlassDisplayChange(mode) },
          label = {
            Text(
              stringResource(
                when (mode) {
                  GlassDisplayMode.TRANSLATION_ONLY -> R.string.settings_glass_display_translation
                  GlassDisplayMode.SOURCE_AND_TRANSLATION -> R.string.settings_glass_display_both
                },
              ),
            )
          },
        )
      }
    }
  }
}
