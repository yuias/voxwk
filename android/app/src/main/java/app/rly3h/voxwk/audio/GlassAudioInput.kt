package app.rly3h.voxwk.audio

import app.rly3h.voxwk.glass.GlassLink
import app.rly3h.voxwk.glass.gm.Commands
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 0x92 is this client's recording; 0x93 replaces it while HFP/SCO is up and carries the same mic (measured: 0x92
 * switches to 0x93 within ~0.5 s of SCO up). 0x90/0x91 are the Glass's own VAD recordings and arrive unsolicited, so
 * they are not ours. 0x94 was never observed and the docs say not to mix it into a session.
 */
internal fun isMicStream(stream: Int): Boolean = stream == 0x92 || stream == 0x93

/** Regroups PCM of any length into chunks of exactly [chunkBytes]; the remainder is carried to the next call. */
internal class PcmRechunker(private val chunkBytes: Int) {
  private val buf = ByteArray(chunkBytes)
  private var filled = 0

  fun add(pcm: ByteArray, emit: (ByteArray) -> Unit) {
    var offset = 0
    while (offset < pcm.size) {
      val n = minOf(chunkBytes - filled, pcm.size - offset)
      pcm.copyInto(buf, filled, offset, offset + n)
      filled += n
      offset += n
      if (filled == chunkBytes) {
        // Copy: consumers queue chunks while the next ones reuse buf.
        emit(buf.copyOf())
        filled = 0
      }
    }
  }
}

/**
 * The Glass microphone over the recording link: 20 ms Opus packets, decoded to 16 kHz PCM16 and regrouped into the
 * engine's chunks. [decode] takes one packet and returns 640 bytes; it is injected so tests need no native library.
 */
class GlassAudioInput(
  private val link: GlassLink,
  private val decode: (ByteArray) -> ByteArray,
  private val pickupMode: Int,
  // Packets arrive every 20 ms, so 300 ms without one means the stream stopped; a longer wait only extends
  // the deaf window.
  private val restartAfterMs: Long = 300,
) : AudioInput {
  override val kind = InputKind.GLASSES

  override fun capture(format: CaptureFormat, running: AtomicBoolean, emit: (ByteArray) -> Unit) {
    val packets = LinkedBlockingQueue<ByteArray>()
    val rechunker = PcmRechunker(SAMPLE_RATE_16K * format.chunkMs / 1000 * 2)
    val upsample = format.sampleRate == SAMPLE_RATE_24K
    link.onAudio = { stream, received -> if (isMicStream(stream)) packets.addAll(received) }
    try {
      val reply = link.gm.request(Commands.SYSTEM, Commands.AUDIO_PROCESSING, Commands.audioProcessing(0, pickupMode))
      val status = Commands.status(reply)
      if (status != 0L) throw IOException("Glass rejected the audio settings (status $status)")
      link.recording.negotiate(compact = true)
      link.recording.start()

      val restartAfterNs = TimeUnit.MILLISECONDS.toNanos(restartAfterMs)
      var lastPacketNs = System.nanoTime()
      var restarts = 0
      while (running.get()) {
        if (link.isClosed) throw IOException("Glass link closed")
        val packet = packets.poll(POLL_MS, TimeUnit.MILLISECONDS)
        if (packet != null) {
          lastPacketNs = System.nanoTime()
          restarts = 0
          rechunker.add(decode(packet)) { emit(if (upsample) linearResample16to24(it) else it) }
        } else if (System.nanoTime() - lastPacketNs >= restartAfterNs) {
          // When SCO ends the Glass stops recording and does not resume by itself, whoever ended SCO (measured: 0x92
          // did not return within 10 s after SCO ended; resending start restored it). Watching the stream covers every
          // cause.
          if (restarts == MAX_RESTARTS) throw IOException("Glass stopped sending audio")
          restarts++
          link.recording.negotiate(compact = true)
          link.recording.start()
          lastPacketNs = System.nanoTime()
        }
      }
    } finally {
      if (!running.get()) {
        try {
          // No drain: nobody reads the queue after the loop, so the tail audio would only delay the stop.
          link.recording.stop(drainMs = 0)
        } catch (_: IOException) {
          // The link is already closed: nothing to stop.
        }
      }
      link.onAudio = null
    }
  }

  private companion object {
    const val POLL_MS = 50L

    // A restart cycle is ~300 ms plus the round trips, so 10 cycles are ~3.5 s of silence; the worst measured
    // recovery is 1.24 s.
    const val MAX_RESTARTS = 10
  }
}
