package app.rly3h.voxwk.glass.gm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class ScreenshotDecodeTest {
    private fun bytes(hex: String) = hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    private fun pixels(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun start(
        width: Int = 4, height: Int = 2, originalSize: Int = 4, extra: String = "",
        sizes: String = """"total_chunks":2,"data_size":5""", compression: String = "lz4", bpp: Int = 4,
    ) = """{"status":"start",$sizes,"width":$width,"height":$height,"original_size":$originalSize,""" +
        """"compression":"$compression","bpp":$bpp,"cmd_id":102$extra}"""

    private fun assertRejected(json: String) {
        assertThrows(json, IllegalArgumentException::class.java) { parseScreenshotStart(json) }
    }

    @Test
    fun parsesStart() {
        val s = parseScreenshotStart(
            """{"status":"start","total_chunks":2,"width":4,"height":2,"visible_width":3,"original_size":4,""" +
                """"data_size":5,"compression":"lz4","bpp":4,"cmd_id":102}""",
        )
        assertEquals(ScreenshotStart(2, 4, 2, 3, 4, 5, banded = false, bandCount = null), s)
    }

    @Test
    fun acceptsTotalSizeAndFallsBackToFullWidth() {
        val s = parseScreenshotStart(start(sizes = """"total_chunks":2,"total_size":5"""))
        assertEquals(5, s.dataSize)
        assertEquals(4, s.visibleWidth)
        assertEquals(4, parseScreenshotStart(start(extra = ""","visible_width":9""")).visibleWidth)
    }

    @Test
    fun derivesBandCount() {
        val s = parseScreenshotStart(start(width = 4, height = 3, originalSize = 6, extra = ""","format":"banded","band_rows":2"""))
        assertEquals(true, s.banded)
        assertEquals(2, s.bandCount)
    }

    @Test
    fun rejectsInvalidStart() {
        assertRejected(start(originalSize = 5))
        assertRejected(start(compression = "zstd"))
        assertRejected(start(bpp = 8))
        assertRejected(start(sizes = """"total_chunks":0,"data_size":5"""))
        assertRejected(start(sizes = """"total_chunks":2,"data_size":3000"""))
        assertRejected(start(width = 3, height = 2, originalSize = 3, extra = ""","format":"banded""""))
        assertRejected("[]")
        assertRejected("{")
    }

    @Test
    fun decodesAndCropsNonBanded() {
        val s = parseScreenshotStart(start(extra = ""","visible_width":3"""))
        val image = decodeScreenshot(s, bytes("40 01 23 45 67"))
        assertEquals(3, image.width)
        assertEquals(2, image.height)
        assertArrayEquals(pixels(0, 1, 2, 4, 5, 6), image.pixels)
    }

    @Test
    fun oddWidthRowsShareBytes() {
        val s = parseScreenshotStart(start(width = 3, height = 3, originalSize = 5))
        assertArrayEquals(pixels(0, 1, 2, 3, 4, 5, 6, 7, 8), decodeScreenshot(s, bytes("50 01 23 45 67 80")).pixels)
    }

    private val bandedStart = start(width = 4, height = 3, originalSize = 6, extra = ""","format":"banded"""")
    private val bands = "00 00 02 00 05 40 01 23 45 67 01 00 01 00 03 20 89 AB"

    @Test
    fun decodesBanded() {
        val image = decodeScreenshot(parseScreenshotStart(bandedStart), bytes(bands))
        assertArrayEquals(pixels(*IntArray(12) { it }), image.pixels)
    }

    @Test
    fun rejectsBadBands() {
        val s = parseScreenshotStart(bandedStart)
        for (bad in listOf(
            "01 00 02 00 05 40 01 23 45 67 01 00 01 00 03 20 89 AB", // first index 1
            "00 00 02 00 05 40 01 23 45 67", // second band missing
            "$bands 00", // trailing byte
            "00 FF FF 00 05 40 01 23 45 67", // rows beyond the height
            "00 00 02 00 09 40 01 23 45 67", // block size beyond the input
        )) {
            assertThrows(bad, IllegalArgumentException::class.java) { decodeScreenshot(s, bytes(bad)) }
        }
    }

    @Test
    fun pngRoundTripsThroughImageIo() {
        val image = Gray4Image(3, 2, pixels(0, 1, 2, 4, 5, 15))
        val png = image.toPng()
        assertEquals(4, png[24].toInt())
        assertEquals(0, png[25].toInt())
        val decoded = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(3, decoded.width)
        assertEquals(2, decoded.height)
        for (y in 0 until 2) for (x in 0 until 3) {
            assertEquals("($x,$y)", image.pixels[y * 3 + x] * 17, decoded.getRGB(x, y) and 0xFF)
        }
    }
}
