package app.rly3h.voxwk.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import app.rly3h.voxwk.translate.Audience
import app.rly3h.voxwk.translate.EngineStatus

/**
 * Where audio actually went. SPEAKER and GLASS mean the mic will hear it (echo gate input);
 * EARPHONE is not audible to the mic; NONE means nothing was played. GLASS is the paired Glass
 * on Bluetooth SCO: its own mic stream hears that playback about 30 dB above speech.
 */
enum class ResolvedOutput { EARPHONE, SPEAKER, GLASS, NONE }

/** Read by the capture thread: true while (or shortly after) audio plays where the mic hears it (speaker or Glass). */
interface EchoGate {
  val speakerBusy: Boolean
}

/** Reverb allowance after the last speaker or Glass sample, so the mic does not hear the tail as speech. */
internal const val ECHO_TAIL_MS = 300L

/** Whether the mic can hear [output]: the speaker and the Glass are audible to it; earphones are not. */
internal fun isEchoBusy(output: ResolvedOutput): Boolean =
  output == ResolvedOutput.SPEAKER || output == ResolvedOutput.GLASS

/**
 * Output routing for Conversation and for Listen with Glass output. Kept behind an interface because the
 * routing assumptions are unverified on real hardware and the implementation
 * may need to be swapped without touching the engines.
 */
interface AudioRouter {
  /** Enter the routing mode for the session. Idempotent. Returns [EngineStatus.NoEarphones] when no earphone or Glass is connected. */
  fun begin(): EngineStatus?

  /**
   * Route the next utterance; blocks for the device-switch settle time. Re-reads devices each call.
   * Returns [ResolvedOutput.NONE] for ME without earphones or Glass: the caller skips playback.
   */
  fun routeTo(audience: Audience): ResolvedOutput

  /** Attributes every playback (AudioTrack and TTS) of the session must use so it follows routing. */
  val playbackAttributes: AudioAttributes

  /** Built-in mic to pin capture to, or null. */
  fun builtinMic(): AudioDeviceInfo?

  /** Restore MODE_NORMAL and clear the communication device. Idempotent; safe from any thread. */
  fun end()
}

/**
 * Tracks the one live router. Each registration bumps a generation so a late
 * safety-net release can tell whether the router is still the one it was
 * started for.
 */
internal class RouterRegistry<T : Any> {
  private var active: T? = null
  private var generation = 0L

  /** Registers [router] as active and returns the router it replaced, if any. */
  @Synchronized
  fun register(router: T): T? = active.also {
    active = router
    generation++
  }

  @Synchronized
  fun generation(): Long = generation

  /** Compare-and-clear: a router that was already replaced must not unregister its successor. */
  @Synchronized
  fun clear(router: T) {
    if (active === router) active = null
  }

  /** Takes the active router only if it was registered at [owner]'s generation. */
  @Synchronized
  fun takeIfGeneration(owner: Long): T? =
    if (generation == owner) active.also { active = null } else null
}

object AudioRouters {
  private val registry = RouterRegistry<AudioRouter>()

  /**
   * Ends any still-registered router first (a previous session whose cleanup
   * failed), then registers the new one. A router deregisters itself in end().
   */
  fun create(context: Context, glassAddress: String? = null): AudioRouter {
    val router = CommunicationDeviceRouter(context.applicationContext, glassAddress, onEnded = registry::clear)
    registry.register(router)?.end()
    return router
  }

  /** Generation of the latest router; a service captures it to scope its own safety net. */
  fun generation(): Long = registry.generation()

  /**
   * Safety net for when only the service is stopped: ends the active router
   * only if it is still the one registered at [owner], never a newer session's.
   */
  fun releaseActive(owner: Long) {
    registry.takeIfGeneration(owner)?.end()
  }
}

// Order matters: a wired or USB headset is the least lossy path, Bluetooth SCO the narrowest.
private val EARPHONE_PRIORITY = listOf(
  AudioDeviceInfo.TYPE_WIRED_HEADSET,
  AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
  AudioDeviceInfo.TYPE_USB_HEADSET,
  AudioDeviceInfo.TYPE_BLE_HEADSET,
  AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
  AudioDeviceInfo.TYPE_HEARING_AID,
)

/** Preferred earphone among communication-device types, by priority; null if none. */
internal fun pickEarphoneType(types: List<Int>): Int? =
  EARPHONE_PRIORITY.firstOrNull { it in types }

internal data class MeDevice(val index: Int, val output: ResolvedOutput)

/**
 * ME's output among the communication devices, given as (type, address) pairs: the SCO device whose
 * address equals [glassAddress] (ignoring case) is the Glass; otherwise the earphone order applies.
 * Returns the index into [devices]; null when there is none.
 */
internal fun pickMeDevice(devices: List<Pair<Int, String>>, glassAddress: String?): MeDevice? {
  if (glassAddress != null) {
    val glass = devices.indexOfFirst { (type, address) ->
      type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && address.equals(glassAddress, ignoreCase = true)
    }
    if (glass >= 0) return MeDevice(glass, ResolvedOutput.GLASS)
  }
  val type = pickEarphoneType(devices.map { it.first }) ?: return null
  return MeDevice(devices.indexOfFirst { it.first == type }, ResolvedOutput.EARPHONE)
}

private val PRIVATE_MEDIA_TYPES = setOf(
  AudioDeviceInfo.TYPE_WIRED_HEADSET,
  AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
  AudioDeviceInfo.TYPE_USB_HEADSET,
  AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
  AudioDeviceInfo.TYPE_BLE_HEADSET,
  AudioDeviceInfo.TYPE_HEARING_AID,
)

/**
 * Listen and online Listen speak only if a private output exists. [outputTypes] are the media
 * output device types (not communication devices): A2DP counts, the built-in speaker does not.
 */
fun listenHasPrivateOutput(outputTypes: List<Int>): Boolean = outputTypes.any { it in PRIVATE_MEDIA_TYPES }
