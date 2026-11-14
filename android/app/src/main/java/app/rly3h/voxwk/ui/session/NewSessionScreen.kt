package app.rly3h.voxwk.ui.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rly3h.voxwk.R
import app.rly3h.voxwk.data.SessionMode
import app.rly3h.voxwk.lang.Language
import app.rly3h.voxwk.lang.OnlineProvider
import app.rly3h.voxwk.lang.onlineCode
import app.rly3h.voxwk.translate.EngineStatus
import app.rly3h.voxwk.translate.LiveTurn
import app.rly3h.voxwk.translate.Speaker

@Composable
fun NewSessionScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  // Scoped to the Activity, not to this nav entry: the session (and its running
  // engine) has to survive a trip through the drawer into Settings and back.
  val activity = LocalActivity.current as ComponentActivity
  val vm: NewSessionViewModel = viewModel(viewModelStoreOwner = activity)
  val settings by vm.settings.collectAsStateWithLifecycle()
  val hasKey by vm.hasKey.collectAsStateWithLifecycle()

  var hasPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  // POST_NOTIFICATIONS rides along on 33+: the recording service runs without
  // it, but its notification (and its Stop button) would be invisible. Only
  // RECORD_AUDIO gates recording.
  val requested = remember {
    buildList {
      add(Manifest.permission.RECORD_AUDIO)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
      }
    }.toTypedArray()
  }
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
      hasPermission = granted[Manifest.permission.RECORD_AUDIO] == true
    }

  val listState = rememberLazyListState()

  val networkUp by rememberNetworkAvailable()
  val provider = settings.onlineProvider
  val mine = settings.myLanguage
  val conversation = settings.sessionMode == SessionMode.CONVERSATION
  // Online needs a key for the selected provider, a network link, and a provider that can
  // output every target of the session. Derived rather than stored: losing the network or the
  // key shows Offline without rewriting the saved preference.
  fun onlineFor(targets: List<Language>) =
    settings.onlineEnabled && onlineAvailable(networkUp, hasKey, provider, targets)
  val targets = if (conversation) listOf(mine, settings.conversationPartner) else listOf(mine)
  val spoken = if (conversation) targets else listOfNotNull(settings.listenSource)
  val online = onlineFor(targets)

  // USAGE_VOICE_COMMUNICATION playback follows the call volume, so the hardware
  // volume keys must adjust that stream while a Conversation session records.
  val conversationActive = conversation && vm.recording
  DisposableEffect(conversationActive) {
    activity.volumeControlStream =
      if (conversationActive) AudioManager.STREAM_VOICE_CALL else AudioManager.USE_DEFAULT_STREAM_TYPE
    onDispose { activity.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE }
  }

  // Lives here so a latched Speaking cannot carry over into the next session.
  val latch = remember { PttLatch() }
  LaunchedEffect(vm.recording) { if (!vm.recording) latch.reset() }

  // Keep the newest content in view as turns (and the live partial) stream in.
  LaunchedEffect(vm.turns.size, vm.partial) {
    val last = vm.turns.size - 1 + if (vm.partial.isNotBlank()) 1 else 0
    if (last >= 0) listState.animateScrollToItem(last)
  }

  fun start() {
    if (!hasPermission) {
      permissionLauncher.launch(requested)
      return
    }
    vm.start(networkUp)
  }

  Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    ModeSelector(mode = settings.sessionMode, enabled = !vm.recording, onChange = vm::onModeChange)

    // Top bar: the partner-language control (the only language you pick here) on
    // the left; new-session + engine toggles on the right. Your own language is
    // fixed in Settings and shown only as a quiet caption below.
    Row(
      Modifier.fillMaxWidth().padding(top = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (conversation) {
        ConversationPartnerPicker(
          mine = mine,
          partner = settings.conversationPartner,
          optionEnabled = { opt -> !requiresOnline(listOf(mine, opt)) || onlineFor(listOf(mine, opt)) },
          enabled = !vm.recording,
          onChange = vm::onConversationPartnerChange,
        )
      } else {
        ListenSourcePicker(
          mine = mine,
          source = settings.listenSource,
          optionEnabled = { opt -> !requiresOnline(listOfNotNull(opt)) || onlineFor(listOf(mine)) },
          enabled = !vm.recording,
          onChange = vm::onListenSourceChange,
        )
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        // The session outlives navigation, so starting a fresh one is an
        // explicit act rather than a side effect of leaving the screen.
        IconButton(onClick = vm::reset, enabled = !vm.recording && vm.turns.isNotEmpty()) {
          Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.session_new))
        }
        // Online/Offline engine toggle. Enabled only when online can run, and
        // never mid-session (the engine is captured at Start). The speak toggle
        // lives down by the mic, next to the control it affects.
        FilledTonalIconToggleButton(
          checked = online,
          enabled = onlineAvailable(networkUp, hasKey, provider, targets) && !vm.recording,
          onCheckedChange = vm::onOnlineChange,
        ) {
          Icon(
            painterResource(if (online) R.drawable.ic_cloud else R.drawable.ic_cloud_off),
            contentDescription = stringResource(if (online) R.string.session_online_on else R.string.session_online_off),
          )
        }
      }
    }

    // Which language is "yours" — fixed; changed in Settings, not mid-conversation.
    Text(
      stringResource(R.string.session_my_language, stringResource(mine.label)),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.outline,
    )

    if (!vm.recording) {
      // Why the Online toggle is unavailable.
      val reason = when {
        onlineAvailable(networkUp, hasKey, provider, targets) -> null
        !hasKey -> stringResource(R.string.session_online_needs_key, providerName(provider))
        !networkUp -> stringResource(R.string.session_online_needs_network)
        else -> stringResource(
          R.string.session_online_unsupported,
          providerName(provider),
          stringResource(targets.first { onlineCode(provider, it) == null }.label),
        )
      }
      reason?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
      }
      // A language no offline recognizer covers: say so before the user hits Start.
      if (!online && requiresOnline(spoken)) {
        Text(
          stringResource(R.string.status_needs_online, stringResource(spoken.first { !it.offlineInput }.label)),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }

    // Conversation log: oldest at top, newest at the bottom (auto-scrolled). The
    // live partial transcript rides along as a trailing entry so it never
    // overlaps the controls below.
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      if (vm.turns.isEmpty() && vm.partial.isBlank()) {
        Text(
          stringResource(R.string.session_empty),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.outline,
        )
      } else {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(vm.turns, key = { it.id }) { t -> TurnCard(t) }
          if (vm.partial.isNotBlank()) {
            item(key = "partial") { PartialTurn(vm.partial) }
          }
        }
      }
    }

    // Status + the mic/stop control, anchored at the bottom.
    Column(
      Modifier.fillMaxWidth().padding(bottom = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Reported once at session start, so it has its own line instead of sharing the status.
      if (vm.recording && vm.noEarphones) {
        Text(
          stringResource(R.string.notice_no_earphones),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.tertiary,
        )
      }
      Text(
        vm.status?.let { statusText(it) }
          ?: stringResource(if (conversation) R.string.idle_conversation else R.string.idle_listen),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
      if (conversationActive) {
        SpeakingButton(
          label = stringResource(
            R.string.session_speaking_button,
            mine.badge,
            settings.conversationPartner.badge,
          ),
          on = vm.speaking,
          latch = latch,
          onChange = vm::onSpeakingChange,
        )
      }
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Mirror the speak toggle's footprint on the left so the mic stays centered.
        Spacer(Modifier.size(48.dp))
        Spacer(Modifier.weight(1f))
        Surface(
          onClick = { if (vm.recording) vm.stop() else start() },
          shape = CircleShape,
          color = if (vm.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(72.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              painterResource(if (vm.recording) R.drawable.ic_stop else R.drawable.ic_mic),
              contentDescription = stringResource(if (vm.recording) R.string.session_stop else R.string.session_start),
              modifier = Modifier.size(32.dp),
              tint = if (vm.recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
            )
          }
        }
        Spacer(Modifier.weight(1f))
        // Speak-aloud toggle, beside the mic it relates to.
        IconToggleButton(checked = settings.autoSpeak, onCheckedChange = vm::onAutoSpeakChange) {
          Icon(
            painterResource(if (settings.autoSpeak) R.drawable.ic_volume_up else R.drawable.ic_volume_off),
            contentDescription = stringResource(if (settings.autoSpeak) R.string.session_speak_on else R.string.session_speak_off),
          )
        }
      }
    }
  }
}

@Composable
private fun providerName(provider: OnlineProvider): String = stringResource(
  when (provider) {
    OnlineProvider.OPENAI -> R.string.provider_openai
    OnlineProvider.GEMINI -> R.string.provider_gemini
  },
)

@Composable
private fun statusText(status: SessionStatus): String = when (status) {
  is SessionStatus.Message -> stringResource(status.res, *status.args.map { argText(it) }.toTypedArray())
  is SessionStatus.Engine -> engineStatusText(status.status)
  is SessionStatus.Stopped -> pluralStringResource(R.plurals.status_stopped, status.turns, status.turns)
  is SessionStatus.Error -> stringResource(R.string.status_error, status.detail)
}

@Composable
private fun engineStatusText(status: EngineStatus): String =
  stringResource(statusRes(status), *statusArgs(status).map { argText(it) }.toTypedArray())

// The arguments of statusRes and SessionStatus.Message: names are resolved here so the rules stay free of Compose.
@Composable
private fun argText(arg: Any?): String = when (arg) {
  null -> stringResource(R.string.status_unknown_detail)
  is Language -> stringResource(arg.label)
  is Speaker -> stringResource(if (arg == Speaker.ME) R.string.speaker_me else R.string.speaker_partner)
  is EngineStatus -> engineStatusText(arg)
  else -> arg.toString()
}

// Tracks whether the device currently has a validated internet connection, used
// to gate the Online toggle. Seeded synchronously, then updated via callbacks.
@Composable
private fun rememberNetworkAvailable(): State<Boolean> {
  val context = LocalContext.current
  val state = remember { mutableStateOf(hasInternet(context)) }
  DisposableEffect(Unit) {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) { state.value = true }
      override fun onLost(network: Network) { state.value = hasInternet(context) }
      override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        state.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      }
    }
    cm.registerDefaultNetworkCallback(cb)
    onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
  }
  return state
}

private fun hasInternet(context: Context): Boolean {
  val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
  val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
  return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

// Listen / Conversation.
@Composable
private fun ModeSelector(mode: SessionMode, enabled: Boolean, onChange: (SessionMode) -> Unit) {
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
    SegmentedButton(
      selected = mode == SessionMode.LISTEN,
      onClick = { onChange(SessionMode.LISTEN) },
      shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
      enabled = enabled,
    ) { Text(stringResource(R.string.session_mode_listen)) }
    SegmentedButton(
      selected = mode == SessionMode.CONVERSATION,
      onClick = { onChange(SessionMode.CONVERSATION) },
      shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
      enabled = enabled,
    ) { Text(stringResource(R.string.session_mode_conversation)) }
  }
}

// Conversation partner: always a concrete language (no auto-detect, the direction
// comes from the Speaking button). Partners no offline recognizer covers are
// marked, and disabled while Offline is the engine that will run ([optionEnabled]).
@Composable
private fun ConversationPartnerPicker(
  mine: Language,
  partner: Language,
  optionEnabled: (Language) -> Boolean,
  enabled: Boolean,
  onChange: (Language) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    AssistChip(
      onClick = { open = true },
      enabled = enabled,
      label = { Text(stringResource(R.string.session_partner_chip, stringResource(partner.label))) },
      trailingIcon = {
        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
      },
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      conversationPartnerOptions(mine).forEach { opt ->
        DropdownMenuItem(
          text = { Text(optionLabel(opt, onlineOnly = requiresOnline(listOf(mine, opt)))) },
          enabled = optionEnabled(opt),
          onClick = { open = false; onChange(opt) },
        )
      }
    }
  }
}

// The Listen source: "Auto" (detect it) or pinned to a specific language.
// The only language control on this screen — the user's own side is set in Settings.
@Composable
private fun ListenSourcePicker(
  mine: Language,
  source: Language?,
  optionEnabled: (Language?) -> Boolean,
  enabled: Boolean,
  onChange: (Language?) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    AssistChip(
      onClick = { open = true },
      enabled = enabled,
      label = {
        Text(
          if (source == null) stringResource(R.string.session_partner_auto_chip)
          else stringResource(R.string.session_partner_chip, stringResource(source.label)),
        )
      },
      trailingIcon = {
        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
      },
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      listenSourceOptions(mine).forEach { opt ->
        DropdownMenuItem(
          text = {
            Text(
              if (opt == null) stringResource(R.string.session_partner_auto_option)
              else optionLabel(opt, onlineOnly = requiresOnline(listOf(opt))),
            )
          },
          enabled = optionEnabled(opt),
          onClick = { open = false; onChange(opt) },
        )
      }
    }
  }
}

@Composable
private fun optionLabel(language: Language, onlineOnly: Boolean): String =
  if (onlineOnly) stringResource(R.string.session_online_only_option, stringResource(language.label))
  else stringResource(language.label)

// Push-to-talk. A pointer-level gesture rather than onClick because the latch needs
// both the press and the release: a tap latches it, a long hold is momentary.
@Composable
private fun SpeakingButton(label: String, on: Boolean, latch: PttLatch, onChange: (Boolean) -> Unit) {
  val currentOnChange by rememberUpdatedState(onChange)
  Surface(
    shape = MaterialTheme.shapes.large,
    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier
      .fillMaxWidth()
      .height(64.dp)
      .semantics { role = Role.Button }
      .pointerInput(latch) {
        detectTapGestures(
          onPress = {
            latch.press(SystemClock.uptimeMillis())
            currentOnChange(latch.on)
            tryAwaitRelease() // false on cancel; treated as a release either way
            latch.release(SystemClock.uptimeMillis())
            currentOnChange(latch.on)
          },
        )
      },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(label, style = MaterialTheme.typography.titleMedium)
    }
  }
}

// A finished (or in-flight) turn. No card background — turns are separated by the
// list spacing and the src→tgt header alone, per the flat look we want.
@Composable
internal fun TurnCard(turn: LiveTurn) {
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    val direction = stringResource(
      R.string.turn_direction,
      turn.src?.badge ?: stringResource(R.string.language_auto),
      turn.tgt.badge,
    )
    Text(
      when (turn.speaker) {
        null -> direction
        Speaker.ME -> stringResource(R.string.turn_direction_me, direction)
        Speaker.PARTNER -> stringResource(R.string.turn_direction_partner, direction)
      },
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.primary,
    )
    Text(
      turn.transcript,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      turn.translation ?: "…", // language-neutral while the translation streams in
      style = MaterialTheme.typography.titleMedium,
    )
  }
}

// The streaming recognizer's live, not-yet-finalized transcript, shown at the
// tail of the log in the accent color to set it apart from committed turns.
@Composable
private fun PartialTurn(text: String) {
  Text(
    text,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
    style = MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.primary,
  )
}
