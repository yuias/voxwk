package app.rly3h.voxwk.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rly3h.voxwk.R
import app.rly3h.voxwk.data.SessionLog
import app.rly3h.voxwk.data.SessionMode

internal fun formatTime(millis: Long): String =
  java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    .format(java.util.Date(millis))

internal fun formatClock(millis: Long): String =
  java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    .format(java.util.Date(millis))

@Composable
internal fun modeLabel(mode: String): String = stringResource(
  if (mode == SessionMode.CONVERSATION.name) R.string.session_mode_conversation else R.string.session_mode_listen,
)

@Composable
fun SessionHistoryScreen(
  onOpen: (String) -> Unit,
  modifier: Modifier = Modifier,
  vm: HistoryViewModel = viewModel(),
) {
  LaunchedEffect(Unit) { vm.loadSessions() }

  val sessions = vm.sessions ?: return

  if (sessions.isEmpty()) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
      Text(
        stringResource(R.string.history_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
      )
    }
    return
  }

  LazyColumn(
    modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    items(sessions, key = { it.id }) { s -> SessionRow(s, onOpen = { onOpen(s.id) }, onDelete = { vm.delete(s.id) }) }
  }
}

@Composable
private fun SessionRow(session: SessionLog, onOpen: () -> Unit, onDelete: () -> Unit) {
  val preview = session.utterances.firstOrNull()?.transcript.orEmpty()
  Card(modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
    Row(
      Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(formatTime(session.startedAt), style = MaterialTheme.typography.titleSmall)
        Text(
          listOfNotNull(
            pluralStringResource(R.plurals.history_turns, session.utterances.size, session.utterances.size),
            modeLabel(session.mode),
            preview.ifBlank { null },
          ).joinToString(" · "),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.outline,
          maxLines = 1,
        )
      }
      IconButton(onClick = onDelete) {
        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.history_delete))
      }
    }
  }
}
