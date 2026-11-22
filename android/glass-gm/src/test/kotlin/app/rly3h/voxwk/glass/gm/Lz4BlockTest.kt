package app.rly3h.voxwk.glass.gm

import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class Lz4BlockTest {
    private fun bytes(hex: String) = hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .map { it.toInt(16).toByte() }.toByteArray()

    private fun decode(hex: String, size: Int) = Lz4Block.decode(bytes(hex), decodedSize = size)

    private val overlapping = "14 41 01 00 50 42 42 42 42 42"

    @Test
    fun literalOnlyBlock() {
        // The "Web Bridge LZ4 bitmap" block from WIRE_EXAMPLES.md.
        assertArrayEquals(bytes("F0 0F"), decode("20 F0 0F", 2))
    }

    @Test
    fun overlappingMatch() {
        val expected = ByteArray(9) { 0x41 } + ByteArray(5) { 0x42 }
        assertArrayEquals(expected, decode(overlapping, 14))
    }

    @Test
    fun matchLengthExtension() {
        val expected = ByteArray(25) { 0x41 } + ByteArray(5) { 0x42 }
        assertArrayEquals(expected, decode("1F 41 01 00 05 50 42 42 42 42 42", 30))
    }

    @Test
    fun literalLengthExtension() {
        val data = ByteArray(16) { it.toByte() }
        assertArrayEquals(data, Lz4Block.decode(bytes("F0 01") + data, decodedSize = 16))
    }

    @Test
    fun literalLengthWithContinuationByte() {
        val data = ByteArray(270) { (it and 0xFF).toByte() }
        assertArrayEquals(data, Lz4Block.decode(bytes("F0 FF 00") + data, decodedSize = 270))
    }

    @Test
    fun rejectsMalformedBlocks() {
        for ((hex, size) in listOf(
            "14 41 00 00 50 42 42 42 42 42" to 14, // offset 0
            "14 41 02 00 50 42 42 42 42 42" to 14, // offset beyond the output
            "14 41 01" to 14, // truncated offset
            "20 F0" to 2, // truncated literals
            overlapping to 13, // overrun
            overlapping to 15, // short output
        )) {
            assertThrows(hex, IllegalArgumentException::class.java) { decode(hex, size) }
        }
    }

    @Test
    fun roundTripsLargeBuffer() {
        val rows = ByteArray(128000 - 16384) { ((it / 400) % 16 * 17).toByte() }
        val data = rows + Random(1).nextBytes(16384)
        val packed = ByteArrayOutputStream().also { sink ->
            BlockLZ4CompressorOutputStream(sink).use { it.write(data) }
        }.toByteArray()
        assertArrayEquals(data, Lz4Block.decode(packed, decodedSize = data.size))
    }
}
