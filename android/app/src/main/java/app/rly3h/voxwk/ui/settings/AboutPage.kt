package app.rly3h.voxwk.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rly3h.voxwk.R

@Composable
internal fun AboutPage(modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    ListItem(
      headlineContent = {
        Text(stringResource(R.string.settings_about_version, stringResource(R.string.app_name), vm.versionName, vm.gitHash))
      },
      supportingContent = vm.mtInfo?.let { { Text(it.systemInfo) } },
    )
  }
}
