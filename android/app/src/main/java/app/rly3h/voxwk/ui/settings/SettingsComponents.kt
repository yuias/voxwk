package app.rly3h.voxwk.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ListItem has no `enabled` parameter, so a disabled row dims its texts with the Material disabled alpha itself.
@Composable
private fun rowColors(enabled: Boolean): ListItemColors {
  if (enabled) return ListItemDefaults.colors()
  val disabled = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
  return ListItemDefaults.colors(headlineColor = disabled, supportingColor = disabled)
}

/** A Settings list row: title and the current value; opens its page. */
@Composable
internal fun CategoryRow(title: String, summary: String?, onClick: () -> Unit) {
  ListItem(
    headlineContent = { Text(title) },
    supportingContent = summary?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
    modifier = Modifier.clickable(onClick = onClick),
  )
}

/** A switch setting; the whole row is the touch target, so the Switch itself is not clickable. */
@Composable
internal fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
  ListItem(
    headlineContent = { Text(title) },
    trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    colors = rowColors(enabled),
    modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
  )
}

/** One option of an exclusive choice; the whole row is the touch target, so the RadioButton itself is not clickable. */
@Composable
internal fun ChoiceRow(selected: Boolean, enabled: Boolean, title: String, description: String? = null, onSelect: () -> Unit) {
  ListItem(
    headlineContent = { Text(title) },
    supportingContent = description?.let { { Text(it) } },
    leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
    colors = rowColors(enabled),
    modifier = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
  )
}

// Label + current value + a bounded slider. The drag position is local so the stored
// setting is written once, when the drag finishes, not on every tick.
@Composable
internal fun SettingSlider(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  format: (Float) -> String,
  onCommit: (Float) -> Unit,
) {
  var drag by remember(value) { mutableFloatStateOf(value) }
  Column(Modifier.padding(horizontal = 16.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label, style = MaterialTheme.typography.bodyMedium)
      Text(format(drag), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = drag, onValueChange = { drag = it }, onValueChangeFinished = { onCommit(drag) }, valueRange = range)
  }
}

/** A group label inside a page. */
@Composable
internal fun SectionHeader(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
  )
}

/** A state notice under a group. */
@Composable
internal fun SettingNote(text: String, error: Boolean = false) {
  Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
  )
}
