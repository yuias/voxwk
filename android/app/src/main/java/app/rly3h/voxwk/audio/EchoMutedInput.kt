package app.rly3h.voxwk.audio

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps [inner] so it delivers silence while [busy] is true. Zeros instead of dropped chunks keep the recognizer's
 * time axis, as in Conversation. Used for Listen when its translation plays where the mic hears it: the Glass mic
 * hears its own playback far above speech.
 */
class EchoMutedInput(private val inner: AudioInput, private val busy: () -> Boolean) : AudioInput {
  override val kind: InputKind get() = inner.kind

  override fun capture(format: CaptureFormat, running: AtomicBoolean, emit: (ByteArray) -> Unit) =
    inner.capture(format, running) { chunk -> emit(if (busy()) ByteArray(chunk.size) else chunk) }
}
