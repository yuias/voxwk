package app.rly3h.voxwk.translate

import app.rly3h.voxwk.lang.AsrEngine

/** SenseVoice side: a VAD cuts utterance segments out of the chunk stream. */
internal interface SegmentPath {
  /** Segments completed by this chunk. */
  fun accept(pcm: ByteArray): List<ByteArray>

  /** The segment in progress, if any; the VAD is left empty afterwards. */
  fun flush(): List<ByteArray>
}

/** What the streaming recognizer made of one chunk. */
internal class StreamStep(val text: String, val endpoint: Boolean)

/** Nemotron side: one resident stream that decodes the chunks as they arrive. */
internal interface StreamPath {
  fun accept(pcm: ByteArray): StreamStep

  /** Starts a fresh stream. */
  fun reset()
}

internal interface DirectionalAsrSink {
  /** A VAD segment, still to be recognized. */
  suspend fun onSegment(pcm: ByteArray, speaker: Speaker)

  /** The live transcript of the stream; empty when there is none. */
  suspend fun onPartial(text: String)

  /** A finished transcript from the stream. */
  suspend fun onText(text: String, speaker: Speaker)

  /** A stream failure; the chunk is dropped and the session goes on. */
  suspend fun onError(e: Throwable)
}

/**
 * Feeds one capture to two recognizers, for a Conversation whose directions use different
 * ones (ja on SenseVoice, the partner on Nemotron). Each chunk goes to the path of its
 * speaker's engine. Both paths hold state, so when the speaker changes the path that
 * served the previous speaker is closed first and its output is tagged with that
 * speaker. Not thread-safe; one consumer drives it.
 */
internal class DirectionalAsrDispatcher(
  private val engineFor: (Speaker) -> AsrEngine,
  private val segments: SegmentPath,
  private val stream: StreamPath,
  private val sink: DirectionalAsrSink,
) {
  private var direction: Speaker? = null
  private var partial = ""

  suspend fun accept(pcm: ByteArray, speaker: Speaker) {
    val previous = direction
    if (previous != null && previous != speaker) close(previous)
    direction = speaker
    when (engineFor(speaker)) {
      AsrEngine.SENSEVOICE -> for (seg in segments.accept(pcm)) sink.onSegment(seg, speaker)
      AsrEngine.NEMOTRON -> {
        val step = try {
          stream.accept(pcm)
        } catch (e: Throwable) {
          sink.onError(e)
          return
        }
        partial = step.text
        sink.onPartial(step.text)
        if (step.endpoint) {
          val text = step.text.trim()
          partial = ""
          sink.onPartial("")
          if (text.isNotEmpty()) sink.onText(text, speaker)
        }
      }
    }
  }

  /** Capture ended: whatever the active path still holds is emitted. */
  suspend fun finish() {
    direction?.let { close(it) }
  }

  private suspend fun close(speaker: Speaker) {
    when (engineFor(speaker)) {
      AsrEngine.SENSEVOICE -> for (seg in segments.flush()) sink.onSegment(seg, speaker)
      AsrEngine.NEMOTRON -> {
        val text = partial.trim()
        partial = ""
        sink.onPartial("")
        if (text.isNotEmpty()) sink.onText(text, speaker)
        try {
          stream.reset()
        } catch (e: Throwable) {
          sink.onError(e)
        }
      }
    }
  }
}
