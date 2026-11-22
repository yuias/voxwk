package app.rly3h.voxwk.glass

import app.rly3h.voxwk.glass.gm.Commands
import app.rly3h.voxwk.glass.gm.GmSession
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Measured on firmware 1.0.4.83: Japanese sent as raw UTF-8 JSON rendered correctly; \u escapes are the
// alternative if a device ever shows garbled text.
private const val ASCII_ONLY = false

/**
 * Shows the translation in the Glass's own translation UI (service 03). Frames are posted from one thread,
 * in call order, without waiting for a reply: callers hold locks or run on Main, and a blocking request could stall them.
 */
class NativeTranslationDisplay(private val gm: GmSession, private val title: String) : GlassDisplay, AutoCloseable {
  private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
    Thread(r, "glass-display").apply { isDaemon = true }
  }
  private val lock = Any()
  private var closed = false

  // Conversation uses the same 03/01–03/04 frames: they rendered correctly on the device, while the conversation
  // variants (03/05–03/08) were never tried.
  override fun begin(conversation: Boolean) = post(Commands.TRANSLATION, Commands.TR_START, Commands.title(title))

  override fun show(source: String, translation: String, final: Boolean) = post(
    Commands.TRANSLATION,
    if (final) Commands.TR_FINAL else Commands.TR_PROVISIONAL,
    Commands.result(translation, source, ASCII_ONLY),
  )

  override fun end() = post(Commands.TRANSLATION, Commands.TR_STOP, ByteArray(0))

  /** Waits up to 1 s for queued frames to be written, so the link can be closed after it. */
  override fun close() {
    synchronized(lock) {
      if (closed) return
      closed = true
      executor.shutdown()
    }
    executor.awaitTermination(1, TimeUnit.SECONDS)
  }

  private fun post(service: Int, command: Int, body: ByteArray) {
    // Callbacks can arrive after teardown, and a shut-down executor would throw on submit.
    synchronized(lock) {
      if (closed) return
      executor.execute {
        // The link dropped; the audio input ends the session, and an uncaught exception here would crash the app.
        try {
          gm.post(service, command, body)
        } catch (_: IOException) {
        }
      }
    }
  }
}
