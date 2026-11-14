package app.rly3h.voxwk.glass.gm

import java.io.ByteArrayOutputStream

sealed interface GmEvent {
    data class Message(val message: GmMessage) : GmEvent

    /** Bytes that did not form a valid frame (unknown outer envelope, corruption, or a dropped partial message). */
    data class Skipped(val bytes: ByteArray, val reason: String) : GmEvent {
        override fun equals(other: Any?) = other is Skipped && reason == other.reason && bytes.contentEquals(other.bytes)
        override fun hashCode() = 31 * reason.hashCode() + bytes.contentHashCode()
        override fun toString() = "Skipped($reason: ${bytes.hex()})"
    }
}

/**
 * Splits the GM command SPP byte stream into messages. The header carries only the logical length, so each physical
 * frame length is derived from the remaining body and the fixed 512-byte firmware transmit ceiling.
 */
class GmStreamParser {
    private var buf = ByteArray(0)
    private var partial: Partial? = null

    private class Partial(val logical: Int, val event: Int, val service: Int, val command: Int) {
        val body = ByteArrayOutputStream()
        var nextHead = 1
        val remaining get() = logical - Gm.ENVELOPE - body.size()
    }

    fun reset() {
        buf = ByteArray(0)
        partial = null
    }

    fun feed(data: ByteArray, len: Int = data.size): List<GmEvent> {
        buf += data.copyOf(len)
        val out = mutableListOf<GmEvent>()
        while (buf.isNotEmpty()) {
            val head = buf[0].toInt() and 0xFF
            val p = partial
            val isFirst = head == Gm.FIRST_HEAD
            if (!isFirst && (p == null || head != p.nextHead)) {
                val next = buf.indexOfFirst { (it.toInt() and 0xFF) == Gm.FIRST_HEAD }.let { if (it < 0) buf.size else it }
                dropPartial(out, "continuation missing")
                out += GmEvent.Skipped(buf.copyOf(next), "no frame head")
                buf = buf.copyOfRange(next, buf.size)
                continue
            }
            if (buf.size < Gm.HEADER) break
            val logical = Gm.u24(buf, 1)
            val event = buf[4].toInt() and 0xFF
            val service = buf[5].toInt() and 0xFF
            val command = buf[6].toInt() and 0xFF
            if (logical !in Gm.ENVELOPE..Gm.MAX_LOGICAL) {
                skipOne(out, "impossible logical length")
                continue
            }
            if (!isFirst && (p!!.logical != logical || p.event != event || p.service != service || p.command != command)) {
                dropPartial(out, "fragment identity changed")
                skipOne(out, "fragment identity changed")
                continue
            }
            val remaining = if (isFirst) logical - Gm.ENVELOPE else p!!.remaining
            val frameLen = Gm.HEADER + minOf(remaining, Gm.MAX_FRAME - Gm.ENVELOPE) + 2
            if (buf.size < frameLen) {
                // A stray 0xFA can claim a long frame and hold back complete frames queued behind it on an idle link.
                val later = (1 until buf.size).firstOrNull { completeFirstFrameAt(it) } ?: break
                dropPartial(out, "superseded by a complete frame")
                out += GmEvent.Skipped(buf.copyOf(later), "unfinished frame candidate")
                buf = buf.copyOfRange(later, buf.size)
                continue
            }
            if (Gm.checksum(buf, 0, frameLen - 2) != Gm.u16(buf, frameLen - 2)) {
                skipOne(out, "bad checksum")
                continue
            }
            val current = if (isFirst) {
                // A new first frame replaces an incomplete message; it does not resume it.
                dropPartial(out, "replaced by a new message")
                Partial(logical, event, service, command).also { partial = it }
            } else p!!
            current.body.write(buf, Gm.HEADER, frameLen - Gm.ENVELOPE)
            if (!isFirst) current.nextHead++
            buf = buf.copyOfRange(frameLen, buf.size)
            if (current.remaining == 0) {
                out += GmEvent.Message(GmMessage(event, service, command, current.body.toByteArray()))
                partial = null
            }
        }
        return out
    }

    private fun completeFirstFrameAt(at: Int): Boolean {
        if ((buf[at].toInt() and 0xFF) != Gm.FIRST_HEAD || buf.size - at < Gm.HEADER) return false
        val logical = Gm.u24(buf, at + 1)
        if (logical !in Gm.ENVELOPE..Gm.MAX_LOGICAL) return false
        val len = Gm.HEADER + minOf(logical - Gm.ENVELOPE, Gm.MAX_FRAME - Gm.ENVELOPE) + 2
        return buf.size - at >= len && Gm.checksum(buf, at, at + len - 2) == Gm.u16(buf, at + len - 2)
    }

    private fun skipOne(out: MutableList<GmEvent>, reason: String) {
        out += GmEvent.Skipped(buf.copyOf(1), reason)
        buf = buf.copyOfRange(1, buf.size)
    }

    private fun dropPartial(out: MutableList<GmEvent>, reason: String) {
        partial?.let { out += GmEvent.Skipped(it.body.toByteArray(), "partial message dropped: $reason") }
        partial = null
    }
}

sealed interface RecEvent {
    /** `52 11` start notification or `52 13` format response (6 bytes each). */
    data class Control(val bytes: ByteArray) : RecEvent {
        val command get() = bytes[1].toInt() and 0xFF
        override fun equals(other: Any?) = other is Control && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
        override fun toString() = "Control(${bytes.hex()})"
    }

    /** One recording packet: stream command (0x90..0x94) and its 40-byte Opus packets. */
    data class Audio(val stream: Int, val packets: List<ByteArray>) : RecEvent {
        override fun equals(other: Any?) = other is Audio && stream == other.stream && packets.size == other.packets.size &&
            packets.indices.all { packets[it].contentEquals(other.packets[it]) }
        override fun hashCode() = 31 * stream + packets.sumOf { it.contentHashCode() }
        override fun toString() = "Audio(%02X x%d)".format(stream, packets.size)
    }

    data class Skipped(val bytes: ByteArray, val reason: String) : RecEvent {
        override fun equals(other: Any?) = other is Skipped && reason == other.reason && bytes.contentEquals(other.bytes)
        override fun hashCode() = 31 * reason.hashCode() + bytes.contentHashCode()
        override fun toString() = "Skipped($reason: ${bytes.hex()})"
    }
}

/**
 * Splits the recording SPP byte stream (GlassSDK docs/AUDIO_PROTOCOL.md, "Reader algorithm"). The record layout
 * follows every `52 13` format response as it arrives, so a reply that comes after the negotiation deadline still
 * switches the parser along with the firmware.
 */
class RecordingStreamParser(@Volatile var compact: Boolean = false) {
    private var buf = ByteArray(0)

    fun reset() {
        buf = ByteArray(0)
    }

    fun feed(data: ByteArray, len: Int = data.size): List<RecEvent> {
        buf += data.copyOf(len)
        val out = mutableListOf<RecEvent>()
        while (buf.isNotEmpty()) {
            if ((buf[0].toInt() and 0xFF) != MAGIC) {
                val next = buf.indexOfFirst { (it.toInt() and 0xFF) == MAGIC }.let { if (it < 0) buf.size else it }
                out += RecEvent.Skipped(buf.copyOf(next), "no 52 magic")
                buf = buf.copyOfRange(next, buf.size)
                continue
            }
            if (buf.size < 2) break
            when (val cmd = buf[1].toInt() and 0xFF) {
                0x11, 0x13 -> {
                    if (buf.size < 6) break
                    if (cmd == 0x13) compact = buf[2].toInt() == 1
                    out += RecEvent.Control(buf.copyOf(6))
                    buf = buf.copyOfRange(6, buf.size)
                }
                in 0x90..0x94 -> {
                    if (buf.size < 12) break
                    val count = buf[6].toInt() and 0xFF
                    if (buf[2].toInt() != 0 || buf[3].toInt() != 0 || (buf[11].toInt() and 0xFF) != 0xFF || count !in 1..8) {
                        skipOne(out, "invalid audio header")
                        continue
                    }
                    val stride = if (compact) 40 else 49
                    val total = 12 + count * stride
                    if (buf.size < total) break
                    val packets = (0 until count).map { i ->
                        val start = 12 + i * stride
                        if (compact) buf.copyOfRange(start, start + 40) else buf.copyOfRange(start + 9, start + 49)
                    }
                    if (!compact && (0 until count).any { buf[12 + it * 49 + 4].toInt() != 0x28 }) {
                        skipOne(out, "bad legacy frame marker")
                        continue
                    }
                    out += RecEvent.Audio(cmd, packets)
                    buf = buf.copyOfRange(total, buf.size)
                }
                else -> skipOne(out, "unknown 52 command")
            }
        }
        return out
    }

    private fun skipOne(out: MutableList<RecEvent>, reason: String) {
        out += RecEvent.Skipped(buf.copyOf(1), reason)
        buf = buf.copyOfRange(1, buf.size)
    }

    companion object {
        const val MAGIC = 0x52
    }
}
