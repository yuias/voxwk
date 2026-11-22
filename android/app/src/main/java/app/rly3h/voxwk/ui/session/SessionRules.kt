package app.rly3h.voxwk.ui.session

import androidx.annotation.StringRes
import app.rly3h.voxwk.R
import app.rly3h.voxwk.lang.Language
import app.rly3h.voxwk.lang.OnlineProvider
import app.rly3h.voxwk.lang.offlineCapable
import app.rly3h.voxwk.lang.onlineSupported
import app.rly3h.voxwk.translate.EngineStatus

/** Listen sources: Auto (null) first, then every language except the user's own. */
fun listenSourceOptions(mine: Language): List<Language?> = listOf(null) + conversationPartnerOptions(mine)

fun conversationPartnerOptions(mine: Language): List<Language> = Language.entries.filter { it != mine }

/** Online toggle: network + key + [onlineSupported]; Listen targets = [mine], Conversation = [mine, partner]. */
fun onlineAvailable(network: Boolean, hasKey: Boolean, provider: OnlineProvider, targets: List<Language>): Boolean =
  network && hasKey && onlineSupported(provider, targets)

/** Offline unless [offlineCapable] is false; then the session starts only with the Online toggle on. */
fun requiresOnline(spoken: List<Language>): Boolean = !offlineCapable(spoken)

/**
 * String resource for [status]. The format arguments are [statusArgs]; a [Language] argument stands for its
 * label, a [Speaker][app.rly3h.voxwk.translate.Speaker] for "You" / "Partner", and a nested [EngineStatus] for its
 * own text.
 */
@StringRes
fun statusRes(status: EngineStatus): Int = when (status) {
  EngineStatus.Listening -> R.string.status_listening
  EngineStatus.Connecting -> R.string.status_connecting
  EngineStatus.NoEarphones -> R.string.notice_no_earphones
  is EngineStatus.NoTtsVoice -> R.string.status_no_tts_voice
  is EngineStatus.Failed -> when (status.stage) {
    EngineStatus.Stage.MIC -> R.string.status_failed_mic
    EngineStatus.Stage.RECOGNITION -> R.string.status_failed_recognition
    EngineStatus.Stage.TRANSLATION -> R.string.status_failed_translation
    EngineStatus.Stage.NETWORK -> R.string.status_failed_network
    EngineStatus.Stage.ENGINE -> R.string.status_failed_engine
  }
  is EngineStatus.Directional -> R.string.status_directional
}

/** Format arguments of [statusRes]; a null entry is an absent native message. */
fun statusArgs(status: EngineStatus): List<Any?> = when (status) {
  EngineStatus.Listening, EngineStatus.Connecting, EngineStatus.NoEarphones -> emptyList()
  is EngineStatus.NoTtsVoice -> listOf(status.language)
  is EngineStatus.Failed -> listOf(status.detail)
  is EngineStatus.Directional -> listOf(status.speaker, status.inner)
}

/** What the status line shows: a message of the screen itself, or what the running engine last reported. */
internal sealed interface SessionStatus {
  data class Message(@StringRes val res: Int, val args: List<Any> = emptyList()) : SessionStatus
  data class Engine(val status: EngineStatus) : SessionStatus
  data class Stopped(val turns: Int) : SessionStatus
  data class Error(val detail: String) : SessionStatus
}

/** SecurityException is a missing BLUETOOTH_CONNECT; any other failure means the Glass is held, off or out of range. */
@StringRes
fun glassConnectFailureRes(e: Throwable): Int =
  if (e is SecurityException) R.string.status_glass_permission else R.string.status_glass_unavailable
