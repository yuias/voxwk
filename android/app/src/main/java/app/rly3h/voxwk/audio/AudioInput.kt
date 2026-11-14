package app.rly3h.voxwk.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

// The offline paths capture 16 kHz mono in 100 ms chunks; the online paths use
// 40 ms chunks (OpenAI Realtime wants 24 kHz, Gemini Live 16 kHz). Segmentation
// happens downstream (Silero on the Rust side, sherpa's endpointer, or the
// online model), not here.
internal const val SAMPLE_RATE_16K = 16000
internal const val SAMPLE_RATE_24K = 24000

// Offline chunks feed VAD/ASR, which gain nothing from smaller frames. Online chunks
// are as small as is still cheap per WebSocket frame, to cut first-audio latency.
internal const val OFFLINE_CHUNK_MS = 100
internal const val ONLINE_CHUNK_MS = 40

/** Phone: VOICE_RECOGNITION for offline ASR, VOICE_COMMUNICATION (hardware AEC) for online. */
enum class CapturePurpose { RECOGNITION, COMMUNICATION }

enum class InputKind { PHONE, GLASSES }

/**
 * @param sampleRate 16000 or 24000; the input must deliver exactly this rate
 * @param chunkMs 100 offline, 40 online
 * @param preferredDevice phone only: Conversation pins the built-in mic so a
 *   Bluetooth headset doesn't take over capture; null keeps the system default
 */
data class CaptureFormat(
  val sampleRate: Int,
  val chunkMs: Int,
  val purpose: CapturePurpose,
  val preferredDevice: AudioDeviceInfo? = null,
)

/**
 * Where an engine's audio comes from. Engines never open the microphone
 * themselves, so a source other than the phone mic can be injected.
 */
interface AudioInput {
  /** Logged per session. */
  val kind: InputKind

  /**
   * Blocks, emitting PCM16 LE mono chunks in [format] until [running] is false or
   * the source ends (then throws). Run on IO.
   */
  fun capture(format: CaptureFormat, running: AtomicBoolean, emit: (ByteArray) -> Unit)
}

/** True if AudioRecord can capture at [sampleRate] mono PCM16 on this device. */
internal fun supportsCaptureRate(sampleRate: Int): Boolean {
  val n = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
  return n != AudioRecord.ERROR && n != AudioRecord.ERROR_BAD_VALUE && n > 0
}

/**
 * Linear upsample of 16 kHz PCM16 (LE bytes) to 24 kHz.
 * Cold fallback used only when a device can't open a 24 kHz AudioRecord; the OS
 * resampler (native 24 kHz capture) is preferred for the 3:2 non-integer ratio.
 */
internal fun linearResample16to24(src: ByteArray): ByteArray {
  val inN = src.size / 2
  if (inN == 0) return ByteArray(0)
  val outN = (inN * 3) / 2 // 16k -> 24k
  val out = ByteArray(outN * 2)
  fun sample(i: Int): Int {
    val j = i.coerceIn(0, inN - 1) * 2
    return (src[j].toInt() and 0xFF) or (src[j + 1].toInt() shl 8)
  }
  for (o in 0 until outN) {
    val pos = o * (inN - 1).toFloat() / (outN - 1).coerceAtLeast(1)
    val i0 = pos.toInt()
    val frac = pos - i0
    val v = (sample(i0) * (1 - frac) + sample(i0 + 1) * frac).toInt()
    out[o * 2] = (v and 0xFF).toByte()
    out[o * 2 + 1] = ((v shr 8) and 0xFF).toByte()
  }
  return out
}

/**
 * The phone microphone. Needs the RECORD_AUDIO permission; the caller (the session
 * screen) requests it, and capture fails with [SecurityException] if it is missing.
 */
class PhoneAudioInput(private val context: Context) : AudioInput {
  override val kind = InputKind.PHONE

  override fun capture(format: CaptureFormat, running: AtomicBoolean, emit: (ByteArray) -> Unit) {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      throw SecurityException("RECORD_AUDIO is not granted")
    }
    val source = when (format.purpose) {
      CapturePurpose.RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
      CapturePurpose.COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
    }
    // A device that can't open a 24 kHz AudioRecord captures 16 kHz and upsamples.
    val upsample = format.sampleRate == SAMPLE_RATE_24K && !supportsCaptureRate(SAMPLE_RATE_24K)
    val rate = if (upsample) SAMPLE_RATE_16K else format.sampleRate
    val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val record = AudioRecord(
      source,
      rate,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
      maxOf(minBuf, rate * 2),
    )
    // PCM16 is little-endian on every Android ABI, so the byte read is the wire format.
    val buf = ByteArray(rate * format.chunkMs / 1000 * 2)
    try {
      format.preferredDevice?.let { record.setPreferredDevice(it) }
      record.startRecording()
      while (running.get()) {
        val n = record.read(buf, 0, buf.size)
        val len = n and 1.inv() // never emit half a sample
        if (len <= 0) continue
        // Copy: consumers queue chunks while the next read reuses buf.
        val chunk = buf.copyOf(len)
        emit(if (upsample) linearResample16to24(chunk) else chunk)
      }
    } finally {
      record.stop()
      record.release()
    }
  }
}
