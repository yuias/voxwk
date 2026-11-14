package app.rly3h.voxwk.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rly3h.voxwk.R
import app.rly3h.voxwk.audio.InputKind
import app.rly3h.voxwk.data.LoggedUtterance
import app.rly3h.voxwk.lang.languageByCode
import app.rly3h.voxwk.translate.Speaker

@Composable
fun HistoryDetailScreen(id: String, modifier: Modifier = Modifier, vm: HistoryViewModel = viewModel()) {
  LaunchedEffect(id) { vm.loadDetail(id) }

  val s = vm.detail
  if (s == null) {
    if (vm.detailLoaded) {
      Column(modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.history_not_found), color = MaterialTheme.colorScheme.error)
      }
    }
    return
  }

  LazyColumn(
    modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    item {
      Column(Modifier.padding(bottom = 4.dp)) {
        Text(formatTime(s.startedAt), style = MaterialTheme.typography.titleMedium)
        Text(
          stringResource(
            R.string.history_mode_input,
            modeLabel(s.mode),
            stringResource(
              if (s.input == InputKind.GLASSES.name) R.string.history_input_glasses else R.string.history_input_phone,
            ),
          ),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.outline,
        )
      }
    }
    items(s.utterances) { LoggedTurnCard(it) }
  }
}

@Composable
private fun LoggedTurnCard(u: LoggedUtterance) {
  val direction = stringResource(
    R.string.turn_direction,
    languageByCode(u.srcLang)?.badge ?: stringResource(R.string.language_auto),
    languageByCode(u.tgtLang)?.badge ?: u.tgtLang,
  )
  val heading = when (u.speaker) {
    null -> direction
    Speaker.ME.name -> stringResource(R.string.turn_direction_me, direction)
    else -> stringResource(R.string.turn_direction_partner, direction)
  }
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = if (u.speaker == Speaker.ME.name) {
        MaterialTheme.colorScheme.primaryContainer
      } else {
        MaterialTheme.colorScheme.surfaceVariant
      },
    ),
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        stringResource(R.string.history_turn_heading, heading, formatClock(u.at)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        u.transcript,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(u.translation, style = MaterialTheme.typography.titleMedium)
    }
  }
}
