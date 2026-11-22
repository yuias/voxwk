package app.rly3h.voxwk.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rly3h.voxwk.R
import app.rly3h.voxwk.data.Models

/** The model list: size, in-use mark, and download / cancel / delete with the worker's progress. */
@Composable
internal fun ModelsSection(modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
  val state by vm.models.collectAsStateWithLifecycle()
  // Not Compose state: read fresh on every composition.
  val recording = vm.recording

  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    Button(
      enabled = !vm.deleting && state.missingRequired.isNotEmpty(),
      onClick = vm::downloadRequiredModels,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) { Text(stringResource(R.string.models_download_required)) }
    if (state.allRequiredPresent) SettingNote(stringResource(R.string.models_all_required_present))
    state.rows.forEach { row ->
      ModelRow(
        row = row,
        // Enabled on bytes > 0, not only present, so leftovers of a failed download can be cleared too.
        deleteEnabled = !vm.deleting && !recording && !row.active && row.bytes > 0,
        downloadEnabled = !vm.deleting && !row.present && !row.active,
        onDownload = { vm.downloadModel(row.id) },
        onCancel = { vm.cancelDownload(row.id) },
        onDelete = { vm.requestDelete(row.id) },
      )
    }
    if (recording) SettingNote(stringResource(R.string.models_delete_locked))
    vm.modelMessage?.let {
      Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
  }

  vm.pendingDelete?.let { id ->
    val row = state.rows.firstOrNull { it.id == id }
    if (row != null) {
      AlertDialog(
        onDismissRequest = vm::dismissDelete,
        title = { Text(stringResource(R.string.models_delete_title, row.name)) },
        text = { Text(stringResource(R.string.models_delete_text, formatMb(row.bytes))) },
        confirmButton = { TextButton(onClick = vm::confirmDelete) { Text(stringResource(R.string.models_delete)) } },
        dismissButton = { TextButton(onClick = vm::dismissDelete) { Text(stringResource(R.string.models_cancel)) } },
      )
    }
  }
}

@Composable
private fun ModelRow(
  row: ModelRowState,
  deleteEnabled: Boolean,
  downloadEnabled: Boolean,
  onDownload: () -> Unit,
  onCancel: () -> Unit,
  onDelete: () -> Unit,
) {
  Column {
    ListItem(
      headlineContent = { Text(if (row.inUse) stringResource(R.string.models_in_use, row.name) else row.name) },
      supportingContent = { Text(statusText(row)) },
      trailingContent = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (row.active) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.models_cancel)) }
          } else {
            TextButton(enabled = downloadEnabled, onClick = onDownload) { Text(stringResource(R.string.models_download)) }
            TextButton(enabled = deleteEnabled, onClick = onDelete) { Text(stringResource(R.string.models_delete)) }
          }
        }
      },
    )
    val progressModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    when (val d = row.download) {
      ModelDownload.Waiting -> LinearProgressIndicator(progressModifier)
      is ModelDownload.Running ->
        // The size is unknown while unpacking, and when the server omits a length.
        if (d.phase == Models.PHASE_DOWNLOAD && d.total > 0) {
          LinearProgressIndicator(progress = { d.bytes.toFloat() / d.total }, modifier = progressModifier)
        } else {
          LinearProgressIndicator(progressModifier)
        }
      else -> Unit
    }
  }
}

@Composable
private fun statusText(row: ModelRowState): String = when (val d = row.download) {
  ModelDownload.Waiting -> stringResource(R.string.models_status_waiting)
  is ModelDownload.Running ->
    if (d.phase == Models.PHASE_EXTRACT) {
      stringResource(R.string.models_status_unpacking, formatMb(d.bytes))
    } else if (d.total > 0) {
      stringResource(R.string.models_status_downloading_of, formatMb(d.bytes), formatMb(d.total))
    } else {
      stringResource(R.string.models_status_downloading, formatMb(d.bytes))
    }
  is ModelDownload.Failed -> stringResource(R.string.models_status_failed, d.detail)
  ModelDownload.Idle -> when {
    row.present -> stringResource(R.string.models_status_downloaded, formatMb(row.bytes))
    row.bytes > 0 -> stringResource(R.string.models_status_incomplete, formatMb(row.bytes))
    row.approxMb != null -> stringResource(R.string.models_status_missing_approx, row.approxMb)
    else -> stringResource(R.string.models_status_missing)
  }
}

@Composable
private fun formatMb(bytes: Long): String =
  if (bytes < 1_000_000) stringResource(R.string.models_size_under_1_mb) else stringResource(R.string.models_size_mb, bytes / 1_000_000)
