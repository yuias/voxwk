package app.rly3h.voxwk.glass.gm

object Lz4Block {
    /**
     * Decodes one raw LZ4 block (no frame header) that must expand to exactly [decodedSize] bytes;
     * IllegalArgumentException otherwise.
     *
     * Hand-written rather than a library because the size is known up front, so every step is bounds-checked
     * against it (the input comes over the air), and glass-gm stays free of native code.
     */
    fun decode(src: ByteArray, from: Int = 0, to: Int = src.size, decodedSize: Int): ByteArray {
        require(from in 0..to && to <= src.size && decodedSize >= 0) { "bad LZ4 range" }
        val out = ByteArray(decodedSize)
        var i = from
        var o = 0

        // A length is a nibble plus, when the nibble is 15, extension bytes of which 255 means "more follows".
        // Checking against decodedSize inside the loop keeps the sum from overflowing.
        fun length(nibble: Int): Int {
            var n = nibble
            if (nibble == 15) {
                var b: Int
                do {
                    require(i < to) { "truncated LZ4 length" }
                    b = src[i++].toInt() and 0xFF
                    n += b
                    require(n <= decodedSize) { "LZ4 length exceeds the output size" }
                } while (b == 255)
            }
            return n
        }

        while (i < to) {
            val token = src[i++].toInt() and 0xFF
            val literals = length(token ushr 4)
            require(literals <= to - i) { "truncated LZ4 literals" }
            require(literals <= decodedSize - o) { "LZ4 output overrun" }
            src.copyInto(out, o, i, i + literals)
            i += literals
            o += literals
            if (i == to) break // the last sequence carries literals only

            require(to - i >= 2) { "truncated LZ4 offset" }
            val offset = (src[i].toInt() and 0xFF) or ((src[i + 1].toInt() and 0xFF) shl 8)
            i += 2
            require(offset in 1..o) { "bad LZ4 offset" }
            val match = length(token and 15) + 4
            require(match <= decodedSize - o) { "LZ4 output overrun" }
            // Byte by byte: a match may overlap the bytes it is producing.
            repeat(match) { out[o] = out[o - offset]; o++ }
        }
        require(o == decodedSize) { "LZ4 output is $o bytes, expected $decodedSize" }
        return out
    }
}
