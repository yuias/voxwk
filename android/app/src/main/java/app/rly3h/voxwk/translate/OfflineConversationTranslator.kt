package app.rly3h.voxwk.translate

import android.content.Context
import app.rly3h.voxwk.audio.AudioInput
import app.rly3h.voxwk.audio.AudioRouter
import app.rly3h.voxwk.audio.TtsPlayback
import app.rly3h.voxwk.data.AppSettings
import app.rly3h.voxwk.lang.Language
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Offline Conversation engine: the on-device [OfflineTranslator] pipeline driven by
 * the Speaking control, with translations spoken through [TtsPlayback] on the
 * output the audience's route selects. This class owns the router and TTS
 * lifetime so they are released on every exit path, including a stop (or a
 * cancelled scope) that arrives before the work has started.
 */
internal class OfflineConversationTranslator(
  private val context: Context,
  private val settings: AppSettings,
  private val autoSpeak: () -> Boolean,
  private val speechRate: () -> Float,
  private val input: AudioInput,
  private val mine: Language,
  private val partner: Language,
  private val router: AudioRouter,
) : ConversationTranslator {
  private val started = AtomicBoolean(false)
  private val finishedOnce = AtomicBoolean(false)
  private val firstError = AtomicReference<String?>(null)
  private val innerFinished = CompletableDeferred<Unit>()

  @Volatile private var speaking = false
  @Volatile private var stopRequested = false
  @Volatile private var engineRef: OfflineTranslator? = null

  override fun setSpeaking(speaking: Boolean) {
    this.speaking = speaking
  }

  @OptIn(DelicateCoroutinesApi::class)
  override fun start(scope: CoroutineScope, callbacks: TranslatorCallbacks) {
    if (!started.compareAndSet(false, true)) return
    // ATOMIC: the body must begin even if the scope is already cancelled, so that the finally
    // below always runs and the router is always ended.
    scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
      var playback: TtsPlayback? = null
      try {
        if (!isActive || stopRequested) return@launch
        router.begin()?.let { callbacks.onStatus(it) }
        // Created only now so a session that never starts does not leave a TTS engine bound.
        val tts = TtsPlayback(context, router, autoSpeak, speechRate)
        playback = tts
        launch { tts.run() }

        val hooks = OfflineConversationHooks(
          speaking = { speaking },
          gate = tts,
          mine = mine,
          partner = partner,
          speak = { audience, text, tgt -> tts.speak(audience, text, tgt) },
          preferredMic = router.builtinMic(),
        )
        // Direction comes from the hooks, so the source and target are only placeholders.
        val engine = OfflineTranslator(context, settings, input, partner, mine, hooks)
        engineRef = engine
        if (stopRequested) return@launch // engines ignore stop() until their own start()
        // Not a child of this coroutine, mirroring Listen: a slow native model load must not
        // be what holds the session open.
        engine.start(scope, InnerCallbacks(callbacks))
        // A stop() between the check above and start() was a no-op for the engine.
        if (stopRequested) engine.stop()
        innerFinished.await()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        firstError.compareAndSet(null, e.message ?: e.toString())
      } finally {
        runCatching { engineRef?.stop() }
        playback?.let { runCatching { it.close() } }
        runCatching { router.end() }
        if (finishedOnce.compareAndSet(false, true)) callbacks.onFinished(firstError.get())
        coroutineContext.cancelChildren()
      }
    }
  }

  override fun stop() {
    stopRequested = true
    engineRef?.stop()
  }

  // The inner engine reports its end here; the outer onFinished waits until the router and
  // TTS are released so the caller never sees "finished" while the audio mode is still held.
  private inner class InnerCallbacks(private val outer: TranslatorCallbacks) : TranslatorCallbacks by outer {
    override fun onFinished(error: String?) {
      if (error != null) firstError.compareAndSet(null, error)
      innerFinished.complete(Unit)
    }
  }
}
