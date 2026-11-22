package app.rly3h.voxwk.glass

import app.rly3h.voxwk.glass.gm.Commands
import app.rly3h.voxwk.glass.gm.GmSession
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

// Measured on firmware 1.0.4.83: Japanese sent as raw UTF-8 JSON rendered correctly; \u escapes are the
// alternative if a device ever shows garbled text.
private const val ASCII_ONLY = false

// Measured on firmware 1.0.4.83 (display timeout `light_ut` = 15): while the translation UI is open the Glass never
// dims its display, and right after it is closed (03/02) the Glass's own timeout turns the display off quickly.
// So the UI is closed after this much time without a HUD update and re-opened by the next one.
private const val IDLE_CLOSE_MS = 15_000L

/**
 * Shows the translation in the Glass's own translation UI (service 03). Frames are posted from one thread,
 * in call order, without waiting for a reply: callers hold locks or run on Main, and a blocking request could stall them.
 * After [idleCloseMs] without a [begin] or [show] the UI is closed so the Glass can turn its display off; the next
 * [show] re-opens it first.
 */
class NativeTranslationDisplay(
  private val gm: GmSession,
  private val title: String,
  private val idleCloseMs: Long = IDLE_CLOSE_MS,
) : GlassDisplay, AutoCloseable {
  private val executor = ScheduledThreadPoolExecutor(1) { r ->
    Thread(r, "glass-display").apply { isDaemon = true }
  }.apply {
    // A pending idle check must not run after close, and a cancelled one should not linger for 15 s.
    executeExistingDelayedTasksAfterShutdownPolicy = false
    removeOnCancelPolicy = true
  }
  private val lock = Any()
  private var closed = false

  // True once the idle check has closed the UI; set and cleared under [lock].
  private var idleClosed = false
  private var idleCheck: ScheduledFuture<*>? = null

  // Counts updates so a check that fired just as an update arrived can tell it is stale.
  private var updates = 0

  // Conversation uses the same 03/01–03/04 frames: they rendered correctly on the device, while the conversation
  // variants (03/05–03/08) were never tried.
  override fun begin(conversation: Boolean) = synchronized(lock) {
    idleClosed = false
    post(Commands.TRANSLATION, Commands.TR_START, Commands.title(title))
    scheduleIdleCheck()
  }

  override fun show(source: String, translation: String, final: Boolean) = synchronized(lock) {
    if (idleClosed) {
      idleClosed = false
      post(Commands.TRANSLATION, Commands.TR_START, Commands.title(title))
    }
    post(
      Commands.TRANSLATION,
      if (final) Commands.TR_FINAL else Commands.TR_PROVISIONAL,
      Commands.result(translation, source, ASCII_ONLY),
    )
    scheduleIdleCheck()
  }

  /** Nothing is sent when the idle check has already closed the UI. */
  override fun end() = synchronized(lock) {
    idleCheck?.cancel(false)
    if (!idleClosed) post(Commands.TRANSLATION, Commands.TR_STOP, ByteArray(0))
  }

  /** Waits up to 1 s for queued frames to be written, so the link can be closed after it. */
  override fun close() {
    synchronized(lock) {
      if (closed) return
      closed = true
      idleCheck?.cancel(false)
      executor.shutdown()
    }
    executor.awaitTermination(1, TimeUnit.SECONDS)
  }

  /** Call with [lock] held. */
  private fun scheduleIdleCheck() {
    if (closed) return
    idleCheck?.cancel(false)
    val seen = ++updates
    idleCheck = executor.schedule({
      synchronized(lock) {
        if (updates != seen || closed) return@schedule
        idleClosed = true
        post(Commands.TRANSLATION, Commands.TR_STOP, ByteArray(0))
      }
    }, idleCloseMs, TimeUnit.MILLISECONDS)
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
