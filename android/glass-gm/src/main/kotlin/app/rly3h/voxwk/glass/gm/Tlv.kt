package app.rly3h.voxwk.glass.gm

import java.io.ByteArrayOutputStream

/** GM TLV: one type byte, a 24-bit big-endian value length, then the value. */
class Tlv(val type: Int, val value: ByteArray) {
    val int: Long
        get() {
            require(type in INT_WIDTH) { "not an integer TLV" }
            return value.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        }

    val text: String get() = value.decodeToString().trimEnd('\u0000')

    override fun toString(): String = when (type) {
        BYTES -> "BYTES(${value.size}: ${value.take(32).toByteArray().hex()}${if (value.size > 32) " …" else ""})"
        STRING -> "STRING($text)"
        JSON -> "JSON($text)"
        STATUS -> "STATUS($int)"
        INT8, INT16, INT32 -> "INT${value.size * 8}($int)"
        else -> "TYPE$type(${value.hex()})"
    }

    companion object {
        const val BYTES = 0x01
        const val STRING = 0x02
        const val INT32 = 0x03
        const val JSON = 0x05
        const val STATUS = 0x06
        const val INT16 = 0x07
        const val INT8 = 0x08

        private val INT_WIDTH = mapOf(INT32 to 4, STATUS to 1, INT16 to 2, INT8 to 1)

        fun encode(type: Int, value: ByteArray): ByteArray {
            val out = ByteArray(4 + value.size)
            out[0] = type.toByte()
            out[1] = (value.size ushr 16).toByte()
            out[2] = (value.size ushr 8).toByte()
            out[3] = value.size.toByte()
            value.copyInto(out, 4)
            return out
        }

        fun int8(v: Int) = encode(INT8, byteArrayOf(v.toByte()))
        fun int16(v: Int) = encode(INT16, byteArrayOf((v ushr 8).toByte(), v.toByte()))
        fun string(s: String) = encode(STRING, s.encodeToByteArray())
        fun json(s: String) = encode(JSON, s.encodeToByteArray())
        fun bytes(b: ByteArray) = encode(BYTES, b)

        fun concat(vararg parts: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            parts.forEach { out.write(it) }
            return out.toByteArray()
        }

        fun parse(body: ByteArray): List<Tlv> {
            val result = mutableListOf<Tlv>()
            var pos = 0
            while (pos < body.size) {
                require(body.size - pos >= 4) { "truncated TLV header" }
                val type = body[pos].toInt() and 0xFF
                val size = Gm.u24(body, pos + 1)
                pos += 4
                require(pos + size <= body.size) { "truncated TLV value" }
                INT_WIDTH[type]?.let { require(size == it) { "invalid integer/status width" } }
                result += Tlv(type, body.copyOfRange(pos, pos + size))
                pos += size
            }
            return result
        }
    }
}

/** Minimal JSON string encoding; [asciiOnly] mirrors the reference encoder's \u escapes for non-ASCII. */
object JsonText {
    fun string(s: String, asciiOnly: Boolean): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' || (asciiOnly && c.code > 0x7E) -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    fun stringArray(items: List<String>, asciiOnly: Boolean) =
        items.joinToString(",", "[", "]") { string(it, asciiOnly) }
}
