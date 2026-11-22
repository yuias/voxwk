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
import app.rly3h.voxwk.lang.MY_LANGUAGES

@Composable
internal fun LanguagePage(modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
  val s by vm.settings.collectAsStateWithLifecycle()

  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    MY_LANGUAGES.forEach { lang ->
      ChoiceRow(
        selected = s.myLanguage == lang,
        enabled = !vm.recording,
        title = stringResource(lang.label),
        onSelect = { vm.onMyLanguageChange(lang) },
      )
    }
  }
}
