package app.rly3h.voxwk.glass.gm

import app.rly3h.voxwk.glass.gm.ScreenshotAssembler.Step
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ScreenshotTransferTest {
    private fun bytes(hex: String) = hex.unhex()

    private val chunk0 = bytes("40 01 23")
    private val chunk1 = bytes("45 67")
    private val expectedPixels = byteArrayOf(0, 1, 2, 4, 5, 6)

    private fun start(totalChunks: Int = 2, dataSize: Int = 5) =
        """{"status":"start","total_chunks":$totalChunks,"data_size":$dataSize,"width":4,"height":2,"visible_width":3,""" +
            """"original_size":4,"compression":"lz4","bpp":4,"cmd_id":102}"""

    private fun upload(index: Int, json: String = "{}", data: ByteArray = ByteArray(0), event: Int = 200) = GmMessage(
        event, Commands.SYSTEM, Commands.SCREENSHOT_UPLOAD,
        Tlv.concat(Tlv.int16(index), Tlv.json(json), Tlv.bytes(data)),
    )

    private fun ack(command: Int, status: Int, service: Int = Commands.SYSTEM) =
        GmMessage(1, service, command, Tlv.encode(Tlv.STATUS, byteArrayOf(status.toByte())))

    private fun startMsg(totalChunks: Int = 2, dataSize: Int = 5) = upload(0xFFFE, start(totalChunks, dataSize))
    private fun complete() = upload(0xFFFD, """{"status":"complete"}""")

    private fun assertDone(step: Step) {
        val image = (step as Step.Done).image
        assertEquals(3, image.width)
        assertEquals(2, image.height)
        assertArrayEquals(expectedPixels, image.pixels)
    }

    @Test
    fun outOfOrderChunksFinish() {
        val a = ScreenshotAssembler()
        assertEquals(Step.Wait, a.onMessage(startMsg()))
        assertEquals(Step.Wait, a.onMessage(upload(1, data = chunk1)))
        assertDone(a.onMessage(upload(0, data = chunk0)))
    }

    @Test
    fun duplicateChunkIsHarmless() {
        val a = ScreenshotAssembler()
        a.onMessage(startMsg())
        assertEquals(Step.Wait, a.onMessage(upload(0, data = chunk0)))
        assertEquals(Step.Wait, a.onMessage(upload(0, data = chunk0)))
        assertDone(a.onMessage(upload(1, data = chunk1)))
    }

    @Test
    fun completeAsksForMissingChunk() {
        val a = ScreenshotAssembler()
        a.onMessage(startMsg())
        a.onMessage(upload(0, data = chunk0))
        assertEquals(Step.Resend(listOf(1)), a.onMessage(complete()))
        assertDone(a.onMessage(upload(1, """{"is_resend":true}""", chunk1)))
    }

    @Test
    fun resendIsLimitedTo64Indices() {
        val a = ScreenshotAssembler()
        a.onMessage(startMsg(totalChunks = 70, dataSize = 100))
        assertEquals(Step.Resend((0..63).toList()), a.onMessage(complete()))
    }

    @Test
    fun errorMarkerFails() {
        val a = ScreenshotAssembler()
        val step = a.onMessage(upload(0xFFFF, """{"status":"error","message":"busy"}"""))
        assertTrue((step as Step.Failed).reason.contains("busy"))
    }

    @Test
    fun busyAckFails() {
        assertTrue(ScreenshotAssembler().onMessage(ack(Commands.SCREENSHOT_REQUEST, 7)) is Step.Failed)
        assertEquals(Step.Wait, ScreenshotAssembler().onMessage(ack(Commands.SCREENSHOT_REQUEST, 0)))
    }

    @Test
    fun dataBeforeStartIsIgnored() {
        val a = ScreenshotAssembler()
        assertEquals(Step.Wait, a.onMessage(upload(0, data = chunk0)))
        assertEquals(Step.Wait, a.onMessage(complete()))
        assertTrue(a.onIdle() is Step.Failed)
    }

    @Test
    fun ninthResendRoundFails() {
        val a = ScreenshotAssembler()
        a.onMessage(startMsg())
        repeat(8) { assertEquals(Step.Resend(listOf(0, 1)), a.onIdle()) }
        assertTrue(a.onIdle() is Step.Failed)
    }

    @Test
    fun startResetsTheRounds() {
        val a = ScreenshotAssembler(maxResendRounds = 1)
        a.onMessage(startMsg())
        assertTrue(a.onIdle() is Step.Resend)
        a.onMessage(startMsg())
        assertTrue(a.onIdle() is Step.Resend)
    }

    @Test
    fun wrongJoinedSizeFails() {
        val a = ScreenshotAssembler()
        a.onMessage(startMsg(dataSize = 6))
        a.onMessage(upload(0, data = chunk0))
        assertTrue(a.onMessage(upload(1, data = chunk1)) is Step.Failed)
    }

    @Test
    fun malformedBlockFailsWithoutThrowing() {
        val a = ScreenshotAssembler()
        a.onMessage(startMsg())
        a.onMessage(upload(0, data = bytes("14 41 00")))
        assertTrue(a.onMessage(upload(1, data = bytes("00 50"))) is Step.Failed)
    }

    @Test
    fun malformedUploadFailsWithoutThrowing() {
        val a = ScreenshotAssembler()
        assertTrue(a.onMessage(GmMessage(1, Commands.SYSTEM, Commands.SCREENSHOT_UPLOAD, bytes("07 00 00 05"))) is Step.Failed)
        assertTrue(a.onMessage(upload(0xFFFE, "not json")) is Step.Failed)
    }

    @Test
    fun hudAckWaits() {
        assertEquals(Step.Wait, ScreenshotAssembler().onMessage(ack(Commands.TR_FINAL, 0, Commands.TRANSLATION)))
    }

    // Fake glasses peer over pipes, as in SessionTest.
    private class Link {
        val toGlasses = PipedOutputStream()
        val glassesIn = PipedInputStream(toGlasses, 1 shl 16)
        val toPhone = PipedOutputStream()
        val phoneIn = PipedInputStream(toPhone, 1 shl 16)
    }

    private fun peer(link: Link, onMessage: (GmMessage, ByteArray) -> List<ByteArray>) = Thread {
        val parser = GmStreamParser()
        val buf = ByteArray(1024)
        while (true) {
            val n = runCatching { link.glassesIn.read(buf) }.getOrDefault(-1)
            if (n < 0) break
            parser.feed(buf, n).filterIsInstance<GmEvent.Message>().forEach { ev ->
                onMessage(ev.message, buf.copyOf(n)).forEach { link.toPhone.write(it); link.toPhone.flush() }
            }
        }
    }.apply { isDaemon = true; start() }

    private fun frame(m: GmMessage) = Gm.encodeJoined(m.event, m.service, m.command, m.body)

    @Test
    fun captureWithResend() {
        val link = Link()
        val received = CopyOnWriteArrayList<GmMessage>()
        val rawFirst = CopyOnWriteArrayList<ByteArray>()
        peer(link) { m, raw ->
            received += m
            if (rawFirst.isEmpty()) rawFirst += raw
            when (m.command) {
                Commands.SCREENSHOT_REQUEST -> listOf(
                    frame(ack(Commands.SCREENSHOT_REQUEST, 0).copy(event = m.event)),
                    frame(startMsg()), frame(upload(0, data = chunk0)), frame(complete()),
                )
                Commands.SCREENSHOT_RESEND -> listOf(frame(upload(1, """{"is_resend":true}""", chunk1)))
                else -> emptyList()
            }
        }
        val inbox = LinkedBlockingQueue<GmMessage>()
        GmSession(link.phoneIn, link.toGlasses, onUnsolicited = inbox::add).use { gm ->
            val image = captureScreenshot(gm, inbox)
            assertEquals(3, image.width)
            assertEquals(2, image.height)
            assertArrayEquals(expectedPixels, image.pixels)
        }
        assertEquals("FA 00 00 09 01 01 65 01 6A", rawFirst.first().hex())
        assertEquals(listOf(Commands.SCREENSHOT_REQUEST, Commands.SCREENSHOT_RESEND), received.map { it.command })
        assertEquals("""{"missing":[1]}""", received[1].tlvs.single().text)
    }

    private fun assertFailsWithin(ms: Long, block: () -> Unit) {
        val t0 = System.nanoTime()
        assertThrows(ScreenshotException::class.java) { block() }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < ms)
    }

    @Test
    fun silentPeerTimesOut() {
        val link = Link()
        peer(link) { _, _ -> emptyList() }
        val inbox = LinkedBlockingQueue<GmMessage>()
        GmSession(link.phoneIn, link.toGlasses, onUnsolicited = inbox::add).use { gm ->
            assertFailsWithin(1000) { captureScreenshot(gm, inbox, idleTimeoutMs = 200) }
        }
    }

    @Test
    fun hudAcksDoNotExtendTheDeadline() {
        val link = Link()
        peer(link) { _, _ -> emptyList() }
        val acks = Thread {
            runCatching {
                while (true) {
                    link.toPhone.write(frame(ack(Commands.TR_FINAL, 0, Commands.TRANSLATION)))
                    link.toPhone.flush()
                    Thread.sleep(50)
                }
            }
        }.apply { isDaemon = true; start() }
        val inbox = LinkedBlockingQueue<GmMessage>()
        GmSession(link.phoneIn, link.toGlasses, onUnsolicited = inbox::add).use { gm ->
            assertFailsWithin(1000) { captureScreenshot(gm, inbox, idleTimeoutMs = 200) }
        }
        acks.interrupt()
    }
}
