package app.rly3h.voxwk.glass.gm

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 4-bit grayscale image: one value 0..15 per pixel, row-major. */
class Gray4Image(val width: Int, val height: Int, val pixels: ByteArray)

/** Start-marker metadata, validated once by [parseScreenshotStart]. */
data class ScreenshotStart(
    val totalChunks: Int,
    val width: Int,
    val height: Int,
    val visibleWidth: Int,
    val originalSize: Int,
    val dataSize: Int,
    val banded: Boolean,
    val bandCount: Int?,
)

/** Throws IllegalArgumentException (malformed JSON included) when the metadata is not a screenshot this decoder handles. */
fun parseScreenshotStart(json: String): ScreenshotStart {
    val obj = Json.parseToJsonElement(json).jsonObject
    fun int(key: String): Int? = obj[key]?.jsonPrimitive?.content?.toIntOrNull()
    fun required(key: String) = requireNotNull(int(key)) { "screenshot start lacks $key" }

    require(obj["compression"]?.jsonPrimitive?.content == "lz4") { "unsupported screenshot compression" }
    require((int("bpp") ?: 4) == 4) { "unsupported screenshot bpp" }
    val width = required("width")
    val height = required("height")
    require(width in 1..4096 && height in 1..4096) { "bad screenshot size ${width}x$height" }
    val totalChunks = required("total_chunks")
    require(totalChunks in 1..4096) { "bad total_chunks $totalChunks" }
    val originalSize = required("original_size")
    require(originalSize == (width * height + 1) / 2) { "original_size $originalSize does not match ${width}x$height" }
    val dataSize = requireNotNull(int("data_size") ?: int("total_size")) { "screenshot start lacks data_size" }
    require(dataSize in 1..totalChunks * 1024) { "bad data_size $dataSize" }
    val banded = obj["format"]?.jsonPrimitive?.content == "banded"
    require(!banded || width % 2 == 0) { "banded screenshot needs an even width" }
    val bandRows = int("band_rows")
    require(bandRows == null || bandRows >= 1) { "bad band_rows $bandRows" }
    return ScreenshotStart(
        totalChunks = totalChunks,
        width = width,
        height = height,
        visibleWidth = int("visible_width")?.takeIf { it in 1..width } ?: width,
        originalSize = originalSize,
        dataSize = dataSize,
        banded = banded,
        bandCount = int("band_count") ?: bandRows?.let { (height + it - 1) / it },
    )
}

/** Decodes the joined chunks; IllegalArgumentException when they do not match [start]. The result is cropped to the visible width. */
fun decodeScreenshot(start: ScreenshotStart, compressed: ByteArray): Gray4Image {
    val packed = if (start.banded) decodeBands(start, compressed) else Lz4Block.decode(compressed, decodedSize = start.originalSize)
    // The linear index is used because an odd width leaves rows that do not start on a byte boundary.
    // The framebuffer holds the first pixel of a pair in the high nibble.
    val w = start.visibleWidth
    val pixels = ByteArray(w * start.height)
    for (y in 0 until start.height) {
        for (x in 0 until w) {
            val i = y * start.width + x
            val b = packed[i / 2].toInt()
            pixels[y * w + x] = (if (i % 2 == 0) b shr 4 else b).and(0x0F).toByte()
        }
    }
    return Gray4Image(w, start.height, pixels)
}

/** Bands are `[index u8][rows u16 BE][size u16 BE][LZ4 block]`, in order, covering all rows. */
private fun decodeBands(start: ScreenshotStart, compressed: ByteArray): ByteArray {
    val packed = ByteArray(start.originalSize)
    var pos = 0
    var bands = 0
    var rowsDone = 0
    var offset = 0
    while (pos < compressed.size) {
        require(compressed.size - pos >= 5) { "truncated band header" }
        val index = compressed[pos].toInt() and 0xFF
        val rows = ((compressed[pos + 1].toInt() and 0xFF) shl 8) or (compressed[pos + 2].toInt() and 0xFF)
        val size = ((compressed[pos + 3].toInt() and 0xFF) shl 8) or (compressed[pos + 4].toInt() and 0xFF)
        pos += 5
        require(index == bands) { "band index $index, expected $bands" }
        // Checked before decoding: rows comes off the air and sizes the decoder's output array.
        require(rows <= start.height - rowsDone) { "bands exceed the image height" }
        require(size <= compressed.size - pos) { "truncated band $index" }
        val band = Lz4Block.decode(compressed, pos, pos + size, decodedSize = rows * start.width / 2)
        band.copyInto(packed, offset)
        offset += band.size
        pos += size
        rowsDone += rows
        bands++
    }
    require(rowsDone == start.height) { "bands cover $rowsDone of ${start.height} rows" }
    require(start.bandCount == null || start.bandCount == bands) { "got $bands bands, expected ${start.bandCount}" }
    return packed
}

/** Grayscale PNG with bit depth 4, which keeps the 16 levels exactly. */
fun Gray4Image.toPng(): ByteArray {
    // Written by hand because android.graphics.Bitmap does not exist in this module and javax.imageio does not exist
    // on Android. PNG packs sub-byte samples high nibble first, the same order as the framebuffer.
    val rowBytes = (width + 1) / 2
    val raw = ByteArray(height * (1 + rowBytes))
    for (y in 0 until height) {
        val row = y * (1 + rowBytes) + 1
        for (x in 0 until width) {
            val v = pixels[y * width + x].toInt()
            raw[row + x / 2] = (raw[row + x / 2].toInt() or if (x % 2 == 0) v shl 4 else v).toByte()
        }
    }
    val deflater = Deflater()
    deflater.setInput(raw)
    deflater.finish()
    val idat = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (!deflater.finished()) idat.write(buf, 0, deflater.deflate(buf))
    deflater.end()

    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
    fun chunk(type: String, data: ByteArray) {
        val head = type.encodeToByteArray()
        val crc = CRC32().apply { update(head); update(data) }.value
        out.write(be32(data.size.toLong()))
        out.write(head)
        out.write(data)
        out.write(be32(crc))
    }
    chunk("IHDR", be32(width.toLong()) + be32(height.toLong()) + byteArrayOf(4, 0, 0, 0, 0))
    chunk("IDAT", idat.toByteArray())
    chunk("IEND", ByteArray(0))
    return out.toByteArray()
}

private fun be32(v: Long) = ByteArray(4) { (v shr (24 - 8 * it)).toByte() }
