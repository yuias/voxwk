package app.rly3h.voxwk.glass.gm

import java.io.ByteArrayOutputStream

/** GM command framing (GlassSDK docs/PROTOCOL.md, "GM packet framing"). */
object Gm {
    const val FIRST_HEAD = 0xFA
    const val ENVELOPE = 9
    const val HEADER = 7
    const val MAX_FRAME = 512
    const val MAX_LOGICAL = 80 * 1024

    // Above 239 continuation frames the App and firmware disagree on the head wrap.
    const val MAX_FRAMES = 239

    fun checksum(bytes: ByteArray, from: Int, to: Int): Int {
        var sum = 0
        for (i in from until to) sum += bytes[i].toInt() and 0xFF
        return sum and 0xFFFF
    }

    /** Splits one logical message into physical frames, each with its own checksum. */
    fun encode(event: Int, service: Int, command: Int, body: ByteArray, frameBytes: Int = MAX_FRAME): List<ByteArray> {
        require(frameBytes in 10..MAX_FRAME) { "frameBytes must be 10..512" }
        val logical = body.size + ENVELOPE
        require(logical <= MAX_LOGICAL) { "logical packet too large" }
        val chunk = frameBytes - ENVELOPE
        val count = maxOf(1, (body.size + chunk - 1) / chunk)
        require(count <= MAX_FRAMES) { "message needs more than $MAX_FRAMES frames" }
        return List(count) { i ->
            val from = i * chunk
            val to = minOf(body.size, from + chunk)
            val frame = ByteArray(HEADER + (to - from) + 2)
            frame[0] = (if (i == 0) FIRST_HEAD else i).toByte()
            frame[1] = (logical ushr 16).toByte()
            frame[2] = (logical ushr 8).toByte()
            frame[3] = logical.toByte()
            frame[4] = event.toByte()
            frame[5] = service.toByte()
            frame[6] = command.toByte()
            body.copyInto(frame, HEADER, from, to)
            val sum = checksum(frame, 0, frame.size - 2)
            frame[frame.size - 2] = (sum ushr 8).toByte()
            frame[frame.size - 1] = sum.toByte()
            frame
        }
    }

    fun encodeJoined(event: Int, service: Int, command: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        encode(event, service, command, body).forEach { out.write(it) }
        return out.toByteArray()
    }

    /** Reassembles a list of complete, ordered physical frames of any size (as on BLE). */
    fun reassemble(frames: List<ByteArray>): GmMessage {
        require(frames.isNotEmpty()) { "missing frames" }
        val body = ByteArrayOutputStream()
        var identity: List<Int>? = null
        frames.forEachIndexed { i, f ->
            require(f.size in ENVELOPE..MAX_FRAME) { "invalid physical size" }
            val logical = u24(f, 1)
            require(logical in f.size..MAX_LOGICAL) { "invalid logical size" }
            require(checksum(f, 0, f.size - 2) == u16(f, f.size - 2)) { "bad checksum" }
            val head = f[0].toInt() and 0xFF
            require(head == (if (i == 0) FIRST_HEAD else i)) { "out-of-order fragment" }
            val current = listOf(logical, f[4].toInt() and 0xFF, f[5].toInt() and 0xFF, f[6].toInt() and 0xFF)
            if (identity == null) identity = current
            require(identity == current) { "fragment identity changed" }
            body.write(f, HEADER, f.size - ENVELOPE)
            if (i < frames.size - 1) require(body.size() < logical - ENVELOPE) { "extra fragment" }
        }
        val id = identity!!
        require(body.size() == id[0] - ENVELOPE) { "incomplete or oversized body" }
        return GmMessage(id[1], id[2], id[3], body.toByteArray())
    }

    internal fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    internal fun u24(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 16) or ((b[at + 1].toInt() and 0xFF) shl 8) or (b[at + 2].toInt() and 0xFF)
}

data class GmMessage(val event: Int, val service: Int, val command: Int, val body: ByteArray) {
    val tlvs: List<Tlv> by lazy { Tlv.parse(body) }

    /** Empty bodies are flow-control replies to a fragment, not the command's answer. */
    val isFlowControl: Boolean get() = body.isEmpty()

    override fun equals(other: Any?) = other is GmMessage && event == other.event && service == other.service &&
        command == other.command && body.contentEquals(other.body)

    override fun hashCode() = 31 * (31 * (31 * event + service) + command) + body.contentHashCode()

    override fun toString(): String {
        val fields = runCatching { tlvs.joinToString(", ") }.getOrElse { "unparsed ${body.hex()}" }
        return "ev=%d %02X/%02X [%s]".format(event, service, command, fields)
    }
}

fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

fun String.unhex(): ByteArray {
    val s = filterNot { it.isWhitespace() }
    require(s.length % 2 == 0) { "odd hex length" }
    return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
