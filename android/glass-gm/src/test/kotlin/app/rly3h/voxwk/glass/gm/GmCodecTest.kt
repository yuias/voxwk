package app.rly3h.voxwk.glass.gm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class GmCodecTest {
    // Golden frames from GlassSDK docs/PROTOCOL.md and DEVICE_BUSINESS_PROTOCOL.md.
    @Test
    fun goldenFrames() {
        assertEquals("FA 00 00 0E 01 01 0E 08 00 00 01 05 01 26", Gm.encode(1, 1, 0x0E, Tlv.int8(5)).single().hex())
        val abc = Tlv.concat(Tlv.int16(1), Tlv.bytes("ABC".encodeToByteArray()))
        assertEquals("FA 00 00 16 01 0F 28 07 00 00 02 00 01 01 00 00 03 41 42 43 02 1C", Gm.encode(1, 0x0F, 0x28, abc).single().hex())
    }

    @Test
    fun fragmentedGolden() {
        val body = Tlv.concat(Tlv.int16(1), Tlv.bytes(ByteArray(600) { 0x41 }))
        val frames = Gm.encode(1, 0x0F, 0x28, body)
        assertEquals(listOf(512, 116), frames.map { it.size })
        assertEquals(listOf("7F 31", "1B D1"), frames.map { it.copyOfRange(it.size - 2, it.size).hex() })
        assertEquals("01 00 02 6B 01 0F 28", frames[1].copyOf(7).hex())
        assertArrayEquals(body, Gm.reassemble(frames).body)
        assertArrayEquals(body, single(GmStreamParser().feed(Gm.encodeJoined(1, 0x0F, 0x28, body))).body)
    }

    @Test
    fun streamSplitAtEveryOffset() {
        val body = Tlv.concat(Tlv.int16(2), Tlv.bytes(ByteArray(1200) { it.toByte() }))
        val wire = Gm.encodeJoined(7, 0x0F, 0x28, body) + Gm.encodeJoined(8, 1, 1, Tlv.encode(Tlv.STATUS, byteArrayOf(0)))
        for (cut in 1 until wire.size) {
            val p = GmStreamParser()
            val events = p.feed(wire.copyOf(cut)) + p.feed(wire.copyOfRange(cut, wire.size))
            val messages = events.filterIsInstance<GmEvent.Message>().map { it.message }
            assertEquals("cut $cut: $events", 2, events.size)
            assertArrayEquals(body, messages[0].body)
            assertEquals(8, messages[1].event)
        }
        // One byte per read.
        val p = GmStreamParser()
        val events = wire.flatMap { p.feed(byteArrayOf(it)) }
        assertEquals(2, events.filterIsInstance<GmEvent.Message>().size)
    }

    @Test
    fun resynchronisesAfterUnknownBytesAndBadChecksum() {
        val good = Gm.encodeJoined(1, 1, 1, ByteArray(0))
        val bad = good.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val events = GmStreamParser().feed("AA BB".unhex() + bad + good)
        assertEquals(1, events.filterIsInstance<GmEvent.Message>().size)
        assertTrue(events.first() is GmEvent.Skipped)
    }

    @Test
    fun newFirstFrameReplacesPartialMessage() {
        val long = Gm.encode(1, 0x0F, 0x28, Tlv.bytes(ByteArray(700)))
        val short = Gm.encodeJoined(2, 1, 1, ByteArray(0))
        val events = GmStreamParser().feed(long[0] + short)
        assertEquals(listOf(2), events.filterIsInstance<GmEvent.Message>().map { it.message.event })
        assertTrue(events.any { it is GmEvent.Skipped && it.reason.startsWith("partial message dropped") })
    }

    @Test
    fun strayFrameHeadDoesNotStallLaterFrames() {
        // A stray FA claiming a 4 KiB message, then a complete heartbeat reply on an otherwise idle link.
        val reply = Gm.encodeJoined(3, 1, 1, Tlv.encode(Tlv.STATUS, byteArrayOf(0)))
        val events = GmStreamParser().feed("FA 00 10 00 05 05 05".unhex() + reply)
        assertEquals(listOf(3), events.filterIsInstance<GmEvent.Message>().map { it.message.event })
    }

    @Test
    fun recoversAfterCorruptContinuationFrame() {
        val long = Gm.encode(1, 0x0F, 0x28, Tlv.bytes(ByteArray(700) { 0x33 }))
        val broken = long[1].copyOf().also { it[10] = (it[10] + 1).toByte() }
        val next = Gm.encodeJoined(2, 1, 1, Tlv.encode(Tlv.STATUS, byteArrayOf(0)))
        val events = GmStreamParser().feed(long[0] + broken + next)
        val messages = events.filterIsInstance<GmEvent.Message>().map { it.message }
        assertEquals(listOf(2), messages.map { it.event })
        assertTrue(events.any { it is GmEvent.Skipped && it.reason.startsWith("partial message dropped") })
    }

    @Test
    fun tlvRejectsMalformed() {
        for (hex in listOf("08000002ff", "080000020001", "010000")) {
            assertTrue(hex, runCatching { Tlv.parse(hex.unhex()) }.isFailure)
        }
    }

    @Test
    fun jsonEscaping() {
        assertEquals("""["こんにちは","Hello"]""", JsonText.stringArray(listOf("こんにちは", "Hello"), asciiOnly = false))
        // Regular string literal: Kotlin raw strings would turn \u3053 into the character itself.
        assertEquals("[\"\\u3053\",\"a\\\"b\"]", JsonText.stringArray(listOf("こ", "a\"b"), asciiOnly = true))
    }

    /**
     * Every request frame printed by the reference codec must survive the stream parser (whole and split) and
     * re-encode byte for byte, and the body must reassemble at BLE-sized frame limits.
     */
    @Test
    fun crossCheckAgainstReferenceCodec() {
        val script = File(System.getProperty("glassSdkDir"), "GlassSDK/docs/examples/bluetooth_wire.py")
        assumeTrue("SDK clone not present: $script", script.isFile)
        val proc = ProcessBuilder("python3", "-I", script.path).redirectErrorStream(true).start()
        val lines = proc.inputStream.bufferedReader().readLines()
        assertTrue(proc.waitFor(30, TimeUnit.SECONDS) && proc.exitValue() == 0)
        val vectors = lines.map { it.substringAfterLast(": ").unhex() }
        assertTrue("expected the reference catalogue, got ${lines.size} lines", vectors.size >= 50)
        for (frame in vectors) {
            val m = single(GmStreamParser().feed(frame))
            assertArrayEquals(frame, Gm.encodeJoined(m.event, m.service, m.command, m.body))
            Tlv.parse(m.body)
            val p = GmStreamParser()
            val split = frame.size / 2
            assertEquals(m, single(p.feed(frame.copyOf(split)) + p.feed(frame.copyOfRange(split, frame.size))))
            for (limit in listOf(20, 64, 244, 512)) {
                assertEquals(m, Gm.reassemble(Gm.encode(m.event, m.service, m.command, m.body, limit)))
            }
        }
    }

    private fun single(events: List<GmEvent>): GmMessage {
        assertEquals(events.toString(), 1, events.size)
        return (events.single() as GmEvent.Message).message
    }
}
