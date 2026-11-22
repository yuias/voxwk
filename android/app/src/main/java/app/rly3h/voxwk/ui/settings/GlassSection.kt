package app.rly3h.voxwk.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rly3h.voxwk.R
import app.rly3h.voxwk.data.GlassMic
import app.rly3h.voxwk.glass.GlassDisplayMode

/** Glass choice and options. There is no connection state: the link exists only during a session. */
@Composable
internal fun GlassSection(modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
  val s by vm.settings.collectAsStateWithLifecycle()
  var showOthers by rememberSaveable { mutableStateOf(false) }
  val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    vm.refreshGlassDevices()
  }

  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    if (!vm.glassPermitted) {
      Button(
        onClick = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
        modifier = Modifier.padding(16.dp),
      ) {
        Text(stringResource(R.string.settings_glass_grant))
      }
    }

    SwitchRow(
      stringResource(R.string.settings_glass_enabled),
      s.glassEnabled,
      vm::onGlassEnabledChange,
      enabled = s.glassAddress != null,
    )

    if (vm.glassPermitted) {
      SectionHeader(stringResource(R.string.settings_glass_device))
      if (vm.glassDevices.isEmpty()) {
        SettingNote(stringResource(R.string.settings_glass_no_devices))
      } else {
        val (glasses, others) = splitGlassDevices(vm.glassDevices, s.glassAddress)
        glasses.forEach { device -> DeviceRow(device, s.glassAddress, vm::onGlassAddressChange) }
        if (others.isNotEmpty()) {
          ListItem(
            headlineContent = { Text(stringResource(R.string.settings_glass_other_devices)) },
            trailingContent = {
              val arrow = if (showOthers) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown
              Icon(arrow, contentDescription = null)
            },
            modifier = Modifier.clickable { showOthers = !showOthers },
          )
          if (showOthers) others.forEach { device -> DeviceRow(device, s.glassAddress, vm::onGlassAddressChange) }
        }
      }
    }

    SectionHeader(stringResource(R.string.settings_glass_mic))
    GlassMic.entries.forEach { mic ->
      ChoiceRow(
        selected = s.glassMic == mic,
        enabled = true,
        title = stringResource(
          when (mic) {
            GlassMic.FRONT -> R.string.settings_glass_mic_front
            GlassMic.ALL_AROUND -> R.string.settings_glass_mic_all_around
          },
        ),
        onSelect = { vm.onGlassMicChange(mic) },
      )
    }

    SectionHeader(stringResource(R.string.settings_glass_display))
    GlassDisplayMode.entries.forEach { mode ->
      ChoiceRow(
        selected = s.glassDisplay == mode,
        enabled = true,
        title = stringResource(
          when (mode) {
            GlassDisplayMode.TRANSLATION_ONLY -> R.string.settings_glass_display_translation
            GlassDisplayMode.SOURCE_AND_TRANSLATION -> R.string.settings_glass_display_both
          },
        ),
        onSelect = { vm.onGlassDisplayChange(mode) },
      )
    }

    SwitchRow(stringResource(R.string.settings_glass_show_mine), s.glassShowMine, vm::onGlassShowMineChange)
  }
}

@Composable
private fun DeviceRow(device: GlassDevice, selected: String?, onSelect: (String) -> Unit) {
  ChoiceRow(
    selected = selected == device.address,
    enabled = true,
    title = device.label,
    onSelect = { onSelect(device.address) },
  )
}
