package app.rly3h.voxwk.translate

import android.content.Context
import android.media.AudioDeviceInfo
import app.rly3h.voxwk.audio.AudioInput
import app.rly3h.voxwk.audio.CaptureFormat
import app.rly3h.voxwk.audio.CapturePurpose
import app.rly3h.voxwk.audio.EchoGate
import app.rly3h.voxwk.audio.OFFLINE_CHUNK_MS
import app.rly3h.voxwk.audio.SAMPLE_RATE_16K
import app.rly3h.voxwk.data.AppSettings
import app.rly3h.voxwk.data.Models
import app.rly3h.voxwk.data.SessionMode
import app.rly3h.voxwk.lang.AsrEngine
import app.rly3h.voxwk.lang.Language
import app.rly3h.voxwk.lang.asrEngineFor
import app.rly3h.voxwk.lang.listenSkips
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.translatecore.TranslationSink
import uniffi.translatecore.asrRecognize
import uniffi.translatecore.asrStreamAccept
import uniffi.translatecore.asrStreamLoad
import uniffi.translatecore.asrStreamReset
import uniffi.translatecore.mtLoad
import uniffi.translatecore.mtTranslateStreaming
import uniffi.translatecore.vadAccept
import uniffi.translatecore.vadFlush
import uniffi.translatecore.vadLoad
import uniffi.translatecore.vadReset

/**
 * Conversation-mode hooks for [OfflineTranslator]. With them the direction of each
 * utterance comes from the Speaking control instead of language auto-detection, the
 * microphone is gated against the engine's own playback, and translations are
 * handed to [speak] instead of the Listen-mode `onSpeak` callback.
 */
internal class OfflineConversationHooks(
  val speaking: () -> Boolean,
  val gate: EchoGate,
  val mine: Language,
  val partner: Language,
  val speak: (audience: Audience, text: String, tgt: Language) -> Unit,
  val preferredMic: AudioDeviceInfo?,
)

/** A VAD-cut segment tagged with the direction in force when it was captured. */
private class DirectedSegment(val pcm: ByteArray, val speaker: Speaker)

/** A raw mic chunk tagged with the direction in force when it was captured. */
private class DirectedChunk(val pcm: ByteArray, val speaker: Speaker)

/** A finalized streaming transcript tagged with the direction it was spoken in. */
private class DirectedText(val text: String, val speaker: Speaker)

/** What the mixed Conversation's recognizers hand to its worker. */
private sealed interface Utterance {
  class Segment(val pcm: ByteArray, val speaker: Speaker) : Utterance

  class Text(val text: String, val speaker: Speaker) : Utterance
}

/**
 * The on-device pipeline: continuous capture → (Silero VAD segments | streaming
 * recognizer) → SenseVoice/Nemotron ASR → GGUF translation (llama.cpp) → OS TTS.
 * Screen state is driven through [TranslatorCallbacks]. [settings] is the snapshot
 * taken by the caller at session start, and all audio comes from [input]. Listen
 * translates [source] (null = Auto) into [target]; a non-null [conversation] switches
 * to the Conversation-mode variants of the capture loops, which take their directions
 * from the hooks instead.
 */
internal class OfflineTranslator(
  private val context: Context,
  private val settings: AppSettings,
  private val input: AudioInput,
  private val source: Language?,
  private val target: Language,
  private val conversation: OfflineConversationHooks? = null,
) : SpeechTranslator {
  private val asrDir = Models.dir(context, "asr")
  private val streamDir = Models.dir(context, "asr_stream")
  private val mtPath = modelFile(context, settings.mtModel.modelId).absolutePath
  private val vadDir = Models.dir(context, "vad")

  // Chosen by the same function as the start-time model check. Listen has one recognizer;
  // a Conversation has two when its directions use different ones.
  private val asrEngines: Set<AsrEngine> = asrEnginesFor(
    if (conversation != null) SessionMode.CONVERSATION else SessionMode.LISTEN,
    source,
    conversation?.mine ?: target,
    conversation?.partner ?: target,
    settings.jaRecognizer,
  )

  private val running = AtomicBoolean(false)

  private val listenFormat = CaptureFormat(SAMPLE_RATE_16K, OFFLINE_CHUNK_MS, CapturePurpose.RECOGNITION)

  // COMMUNICATION keeps the platform AEC in the path; the built-in mic is pinned by the hooks.
  private fun conversationFormat(conv: OfflineConversationHooks) =
    CaptureFormat(SAMPLE_RATE_16K, OFFLINE_CHUNK_MS, CapturePurpose.COMMUNICATION, conv.preferredMic)

  private fun recognitionFailed(e: Throwable) = EngineStatus.Failed(EngineStatus.Stage.RECOGNITION, e.message)

  // Background model load. Launched on the caller's scope rather than as a child of
  // the session coroutine so a slow native load never delays onFinished; stop()
  // cancels it. The native call itself can't be interrupted, only its result dropped.
  @Volatile private var mtLoadJob: Job? = null

  override fun start(scope: CoroutineScope, callbacks: TranslatorCallbacks) {
    running.set(true)
    scope.launch {
      try {
        withContext(Dispatchers.IO) { releaseUnusedEngines(asrEngines) }
        // Overlaps the weight load with the first utterance. Translation takes the
        // same engine mutex, so it is correct even if this hasn't finished yet.
        mtLoadJob = scope.launch(Dispatchers.IO) {
          try {
            mtLoad(mtPath)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Throwable) {
            withContext(Dispatchers.Main) {
              callbacks.onStatus(EngineStatus.Failed(EngineStatus.Stage.TRANSLATION, e.message))
            }
          }
        }
        if (!running.get()) mtLoadJob?.cancel() // stop() raced the launch
        val conv = conversation
        val engine = asrEngines.singleOrNull()
        when {
          conv != null && engine == null -> runMixedConversation(callbacks, conv)
          engine == AsrEngine.NEMOTRON ->
            if (conv == null) runStreaming(callbacks) else runStreamingConversation(callbacks, conv)
          else ->
            if (conv == null) runSegmented(callbacks) else runSegmentedConversation(callbacks, conv)
        }
        callbacks.onFinished(null)
      } catch (e: Throwable) {
        callbacks.onFinished(e.message)
      }
    }
  }

  override fun stop() {
    running.set(false)
    mtLoadJob?.cancel()
  }

  // Final transcript -> turn (shown at once) -> translation (patched in) ->
  // spoken. `lang` is the ASR language tag, empty for the streaming recognizer.
  // Listen translates into the target; an utterance already in the target language
  // is dropped before it becomes a turn. In Conversation mode [speaker] (the
  // direction at capture time) picks the direction; `lang` is then only kept as the
  // turn's `detected` tag.
  private suspend fun finalizeTurn(
    transcript: String,
    lang: String,
    cb: TranslatorCallbacks,
    speaker: Speaker? = null,
  ) {
    val conv = conversation
    val (src, tgt) = if (conv != null && speaker != null) {
      conversationDirection(speaker, conv.mine, conv.partner)
    } else {
      if (listenSkips(target, lang, transcript)) return
      source to target
    }

    val id = TurnIds.next()
    val at = System.currentTimeMillis()
    withContext(Dispatchers.Main) {
      cb.onTurnStart(LiveTurn(id, transcript, src, tgt, lang, translation = null, speaker = speaker, at = at))
    }
    // Patch the same row as the model decodes, so the translation streams in word by
    // word. Compose snapshot state is safe to write from this background thread.
    val sink = object : TranslationSink {
      override fun onPartial(text: String) {
        cb.onTurnUpdate(id, translation = text)
      }
    }
    val translation = try {
      withContext(Dispatchers.IO) { mtTranslateStreaming(mtPath, transcript, tgt.code, sink) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      // Keep capturing: a failed translation (unsupported pair, missing file) only
      // costs this turn's translation, not the session.
      withContext(Dispatchers.Main) {
        // Clear the "…" placeholder so the row doesn't look like it's still translating.
        cb.onTurnUpdate(id, translation = "")
        cb.onStatus(EngineStatus.Failed(EngineStatus.Stage.TRANSLATION, e.message))
        cb.onTurnComplete(id)
      }
      return
    }
    withContext(Dispatchers.Main) {
      cb.onTurnUpdate(id, translation = translation)
      cb.onTurnComplete(id)
      cb.onPersist()
    }
    if (conv != null && speaker != null) {
      conv.speak(audienceFor(speaker), translation, tgt)
    } else {
      cb.onSpeak(translation, tgt)
    }
  }

  // Offline (SenseVoice) path: one VAD-cut segment -> transcript -> finalize.
  private suspend fun processSegment(pcm: ByteArray, cb: TranslatorCallbacks, speaker: Speaker? = null) {
    val asr = withContext(Dispatchers.IO) {
      Models.ensure(context, "asr")
      asrRecognize(asrDir.absolutePath, pcm, SAMPLE_RATE_16K)
    }
    val transcript = asr.text.trim()
    if (transcript.isEmpty()) return // drop non-speech segments
    finalizeTurn(transcript, asr.lang, cb, speaker)
  }

  private suspend fun loadVad() = withContext(Dispatchers.IO) {
    val vad = settings.vad // latest knobs at the start of this session
    val vadPath = vadDir.absolutePath
    Models.ensure(context, "vad")
    vadLoad(vadPath, vad.threshold, vad.minSilenceMs / 1000f, vad.minSpeechMs / 1000f, vad.maxSpeechMs / 1000f)
    vadReset(vadPath)
  }

  // `language` is a Nemotron prompt key or "auto". With the same model and rules, a reload only
  // changes the resident stream's language, so a conversation can call it on every direction switch.
  private suspend fun loadStream(language: String = "auto") = withContext(Dispatchers.IO) {
    val ep = settings.endpoint // latest knobs at the start of this session
    asrStreamLoad(streamDir.absolutePath, ep.rule1, ep.rule2, ep.rule3, language)
    asrStreamReset(streamDir.absolutePath)
  }

  // Capture and processing are decoupled by a channel so recording keeps running
  // while each finished segment is transcribed/translated. Raw ~100 ms chunks are
  // fed to the resident Silero VAD, which emits a clean PCM segment per detected
  // utterance; on stop we flush whatever was mid-sentence.
  private suspend fun runSegmented(cb: TranslatorCallbacks) = coroutineScope {
    val vadPath = vadDir.absolutePath
    loadVad()
    val channel = Channel<ByteArray>(Channel.UNLIMITED)
    val capture = launch(Dispatchers.IO) {
      try {
        input.capture(listenFormat, running) { chunk ->
          try {
            for (seg in vadAccept(vadPath, chunk, SAMPLE_RATE_16K)) channel.trySend(seg)
          } catch (_: Throwable) { /* drop a bad chunk; keep recording */ }
        }
      } finally {
        // Emit any utterance still in progress when recording stopped.
        runCatching { for (seg in vadFlush(vadPath)) channel.trySend(seg) }
        channel.close()
      }
    }
    val consumer = launch(Dispatchers.IO) {
      for (seg in channel) {
        try {
          processSegment(seg, cb)
        } catch (e: Throwable) {
          withContext(Dispatchers.Main) { cb.onStatus(recognitionFailed(e)) }
        }
      }
    }
    capture.join()
    consumer.join()
  }

  // Streaming (Nemotron) path: feed raw mic chunks into the online recognizer,
  // show its partial transcript live, and finalize each utterance on its endpoint.
  private suspend fun runStreaming(cb: TranslatorCallbacks) = coroutineScope {
    loadStream()
    val channel = Channel<ByteArray>(Channel.UNLIMITED)
    // Endpointed utterances hand off here so translation (blocking, ~1.3s) runs on
    // its own worker instead of inside the ASR loop. Otherwise the loop would stop
    // draining mic chunks while translating, and the live partial for the next
    // utterance would only appear in one burst once translation finished.
    val finalized = Channel<String>(Channel.UNLIMITED)
    val capture = launch(Dispatchers.IO) {
      try { input.capture(listenFormat, running) { channel.trySend(it) } } finally { channel.close() }
    }
    // Single worker: sequential so turns commit in spoken order.
    val translator = launch(Dispatchers.IO) {
      for (t in finalized) finalizeTurn(t, "", cb)
    }
    val consumer = launch(Dispatchers.IO) {
      var current = ""
      for (chunk in channel) {
        val r = try {
          asrStreamAccept(streamDir.absolutePath, chunk, SAMPLE_RATE_16K)
        } catch (e: Throwable) {
          withContext(Dispatchers.Main) { cb.onStatus(recognitionFailed(e)) }
          continue
        }
        current = r.text
        withContext(Dispatchers.Main) { cb.onPartial(r.text) }
        if (r.endpoint) {
          val t = r.text.trim()
          current = ""
          withContext(Dispatchers.Main) { cb.onPartial("") }
          if (t.isNotEmpty()) finalized.trySend(t)
        }
      }
      // Stopped mid-utterance: flush whatever was decoded so far.
      val tail = current.trim()
      withContext(Dispatchers.Main) { cb.onPartial("") }
      if (tail.isNotEmpty()) finalized.trySend(tail)
      finalized.close()
      withContext(Dispatchers.IO) { asrStreamReset(streamDir.absolutePath) }
    }
    capture.join()
    consumer.join()
    translator.join() // drain any translations still in flight after Stop
  }

  // Conversation variant of runSegmented. A separate function so the Listen loop above
  // stays untouched. Each segment carries the direction of the chunk that produced it.
  private suspend fun runSegmentedConversation(
    cb: TranslatorCallbacks,
    conv: OfflineConversationHooks,
  ) = coroutineScope {
    val vadPath = vadDir.absolutePath
    loadVad()
    val channel = Channel<DirectedSegment>(Channel.UNLIMITED)
    val capture = launch(Dispatchers.IO) {
      val gate = ConversationInputGate()
      var current = Speaker.PARTNER // direction of the audio the VAD currently holds
      try {
        input.capture(conversationFormat(conv), running) { raw ->
          val decision = gate.decide(conv.speaking(), conv.gate.speakerBusy)
          decision.switchedFrom?.let { prev ->
            // Close what was said in the previous direction before this chunk can mix into it.
            runCatching { for (seg in vadFlush(vadPath)) channel.trySend(DirectedSegment(seg, prev)) }
            runCatching { vadReset(vadPath) }
          }
          current = decision.active
          // Zeros rather than a dropped chunk keep the VAD's time axis continuous.
          val chunk = if (decision.muted) ByteArray(raw.size) else raw
          try {
            for (seg in vadAccept(vadPath, chunk, SAMPLE_RATE_16K)) channel.trySend(DirectedSegment(seg, decision.active))
          } catch (_: Throwable) { /* drop a bad chunk; keep recording */ }
        }
      } finally {
        runCatching { for (seg in vadFlush(vadPath)) channel.trySend(DirectedSegment(seg, current)) }
        channel.close()
      }
    }
    val consumer = launch(Dispatchers.IO) {
      for (seg in channel) {
        try {
          processSegment(seg.pcm, cb, seg.speaker)
        } catch (e: Throwable) {
          withContext(Dispatchers.Main) { cb.onStatus(recognitionFailed(e)) }
        }
      }
    }
    capture.join()
    consumer.join()
  }

  // Conversation variant of runStreaming. The recognizer holds one stream, so when the
  // direction changes the utterance in progress is closed under its old direction and
  // the stream is reset before the new direction's audio reaches it.
  private suspend fun runStreamingConversation(
    cb: TranslatorCallbacks,
    conv: OfflineConversationHooks,
  ) = coroutineScope {
    val channel = Channel<DirectedChunk>(Channel.UNLIMITED)
    val finalized = Channel<DirectedText>(Channel.UNLIMITED)
    val capture = launch(Dispatchers.IO) {
      val gate = ConversationInputGate()
      try {
        input.capture(conversationFormat(conv), running) { raw ->
          val decision = gate.decide(conv.speaking(), conv.gate.speakerBusy)
          val chunk = if (decision.muted) ByteArray(raw.size) else raw
          channel.trySend(DirectedChunk(chunk, decision.active))
        }
      } finally {
        channel.close()
      }
    }
    // Single worker: sequential so turns commit in spoken order.
    val translator = launch(Dispatchers.IO) {
      for (t in finalized) finalizeTurn(t.text, "", cb, t.speaker)
    }
    val consumer = launch(Dispatchers.IO) {
      var current = ""
      var direction: Speaker? = null
      for (chunk in channel) {
        if (direction != null && direction != chunk.speaker) {
          val t = current.trim()
          current = ""
          withContext(Dispatchers.Main) { cb.onPartial("") }
          if (t.isNotEmpty()) finalized.trySend(DirectedText(t, direction))
        }
        if (direction != chunk.speaker) {
          // Each direction has one known language; "auto" decoded Japanese as Chinese on a device.
          try {
            loadStream(speakerLanguage(conv, chunk.speaker).nemotronCode ?: "auto")
          } catch (e: Throwable) {
            withContext(Dispatchers.Main) { cb.onStatus(recognitionFailed(e)) }
          }
        }
        direction = chunk.speaker
        val r = try {
          asrStreamAccept(streamDir.absolutePath, chunk.pcm, SAMPLE_RATE_16K)
        } catch (e: Throwable) {
          withContext(Dispatchers.Main) { cb.onStatus(recognitionFailed(e)) }
          continue
        }
        current = r.text
        withContext(Dispatchers.Main) { cb.onPartial(r.text) }
        if (r.endpoint) {
          val t = r.text.trim()
          current = ""
          withContext(Dispatchers.Main) { cb.onPartial("") }
          if (t.isNotEmpty()) finalized.trySend(DirectedText(t, chunk.speaker))
        }
      }
      // Stopped mid-utterance: flush whatever was decoded so far.
      val tail = current.trim()
      withContext(Dispatchers.Main) { cb.onPartial("") }
      if (tail.isNotEmpty() && direction != null) finalized.trySend(DirectedText(tail, direction))
      finalized.close()
      withContext(Dispatchers.IO) { asrStreamReset(streamDir.absolutePath) }
    }
    capture.join()
    consumer.join()
    translator.join() // drain any translations still in flight after Stop
  }

  // Conversation whose directions use different recognizers: one capture, dispatched per
  // direction. Recognition and translation run on one worker so turns commit in spoken order
  // even though the two paths produce them differently.
  private suspend fun runMixedConversation(
    cb: TranslatorCallbacks,
    conv: OfflineConversationHooks,
  ) = coroutineScope {
    val vadPath = vadDir.absolutePath
    val streamPath = streamDir.absolutePath
    loadVad()
    // Only one direction uses Nemotron here (the other is SenseVoice), so its language is fixed.
    val streamSpeaker = Speaker.entries.first { asrEngineFor(speakerLanguage(conv, it), settings.jaRecognizer) == AsrEngine.NEMOTRON }
    loadStream(speakerLanguage(conv, streamSpeaker).nemotronCode ?: "auto")
    val chunks = Channel<DirectedChunk>(Channel.UNLIMITED)
    val utterances = Channel<Utterance>(Channel.UNLIMITED)
    val capture = launch(Dispatchers.IO) {
      val gate = ConversationInputGate()
      try {
        input.capture(conversationFormat(conv), running) { raw ->
          val decision = gate.decide(conv.speaking(), conv.gate.speakerBusy)
          val chunk = if (decision.muted) ByteArray(raw.size) else raw
          chunks.trySend(DirectedChunk(chunk, decision.active))
        }
      } finally {
        chunks.close()
      }
    }
    val segmentPath = object : SegmentPath {
      override fun accept(pcm: ByteArray): List<ByteArray> =
        try {
          vadAccept(vadPath, pcm, SAMPLE_RATE_16K)
        } catch (_: Throwable) {
          emptyList() // drop a bad chunk; keep recording
        }

      override fun flush(): List<ByteArray> {
        val rest = runCatching { vadFlush(vadPath) }.getOrDefault(emptyList())
        runCatching { vadReset(vadPath) }
        return rest
      }
    }
    val stream = object : StreamPath {
      override fun accept(pcm: ByteArray): StreamStep {
        val r = asrStreamAccept(streamPath, pcm, SAMPLE_RATE_16K)
        return StreamStep(r.text, r.endpoint)
      }

      override fun reset() = asrStreamReset(streamPath)
    }
    val sink = object : DirectionalAsrSink {
      override suspend fun onSegment(pcm: ByteArray, speaker: Speaker) {
        utterances.trySend(Utterance.Segment(pcm, speaker))
      }

      override suspend fun onPartial(text: String) = withContext(Dispatchers.Main) { cb.onPartial(text) }

      override suspend fun onText(text: String, speaker: Speaker) {
        utterances.trySend(Utterance.Text(text, speaker))
      }

      override suspend fun onError(e: Throwable) =
        withContext(Dispatchers.Main) { cb.onStatus(recognitionFailed(e)) }
    }
    val dispatcher = DirectionalAsrDispatcher(
      engineFor = { asrEngineFor(speakerLanguage(conv, it), settings.jaRecognizer) },
      segments = segmentPath,
      stream = stream,
      sink = sink,
    )
    val consumer = launch(Dispatchers.IO) {
      for (c in chunks) dispatcher.accept(c.pcm, c.speaker)
      dispatcher.finish()
      utterances.close()
    }
    // Single worker: sequential so turns commit in spoken order.
    val worker = launch(Dispatchers.IO) {
      for (u in utterances) {
        try {
          when (u) {
            is Utterance.Segment -> processSegment(u.pcm, cb, u.speaker)
            is Utterance.Text -> finalizeTurn(u.text, "", cb, u.speaker)
          }
        } catch (e: Throwable) {
          withContext(Dispatchers.Main) { cb.onStatus(recognitionFailed(e)) }
        }
      }
    }
    capture.join()
    consumer.join()
    worker.join() // drain any translations still in flight after Stop
  }
}

private fun speakerLanguage(conv: OfflineConversationHooks, speaker: Speaker): Language =
  if (speaker == Speaker.ME) conv.mine else conv.partner
