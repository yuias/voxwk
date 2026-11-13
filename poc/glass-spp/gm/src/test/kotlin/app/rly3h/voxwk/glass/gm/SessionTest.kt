package app.rly3h.voxwk.glass.gm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Sessions against a fake glasses peer over piped streams. */
class SessionTest {
    private class Link {
        val toGlasses = PipedOutputStream()
        val glassesIn = PipedInputStream(toGlasses, 1 shl 16)
        val toPhone = PipedOutputStream()
        val phoneIn = PipedInputStream(toPhone, 1 shl 16)
    }

    /** Fake peer: answers each complete GM request via [answer]. */
    private fun gmPeer(link: Link, answer: (GmMessage) -> List<ByteArray>) = Thread {
        val parser = GmStreamParser()
        val buf = ByteArray(1024)
        while (true) {
            val n = runCatching { link.glassesIn.read(buf) }.getOrDefault(-1)
            if (n < 0) break
            parser.feed(buf, n).filterIsInstance<GmEvent.Message>().forEach { ev ->
                answer(ev.message).forEach { link.toPhone.write(it); link.toPhone.flush() }
            }
        }
    }.apply { isDaemon = true; start() }

    private fun status(m: GmMessage, value: Int) =
        Gm.encodeJoined(m.event, m.service, m.command, Tlv.encode(Tlv.STATUS, byteArrayOf(value.toByte())))

    @Test
    fun heartbeat() {
        val link = Link()
        gmPeer(link) { listOf(status(it, 0)) }
        GmSession(link.phoneIn, link.toGlasses).use { s ->
            assertEquals(0L, Commands.status(s.request(Commands.SYSTEM, Commands.HEARTBEAT)))
        }
    }

    @Test
    fun skipsFlowControlAndRoutesUnsolicited() {
        val link = Link()
        val unsolicited = CopyOnWriteArrayList<GmMessage>()
        val info = """{"model":"test","ver":"1.0"}"""
        gmPeer(link) { m ->
            listOf(
                Gm.encodeJoined(m.event, m.service, m.command, ByteArray(0)),
                Gm.encodeJoined(200, Commands.TRANSLATION, Commands.TR_LOCAL_PAUSE, Tlv.int8(0)),
                // Long reply so it arrives in two physical frames.
                Gm.encodeJoined(m.event, m.service, m.command, Tlv.string(info + " ".repeat(600))),
            )
        }
        GmSession(link.phoneIn, link.toGlasses, onUnsolicited = { unsolicited += it }).use { s ->
            val reply = s.request(Commands.SYSTEM, Commands.DEVICE_INFO)
            assertEquals(info, reply.tlvs.single().text.trim())
            assertEquals(listOf(Commands.TR_LOCAL_PAUSE), unsolicited.map { it.command })
        }
    }

    @Test
    fun timeout() {
        val link = Link()
        gmPeer(link) { emptyList() }
        GmSession(link.phoneIn, link.toGlasses).use { s ->
            val t0 = System.nanoTime()
            val r = runCatching { s.request(Commands.SYSTEM, Commands.HEARTBEAT, timeoutMs = 200) }
            assertTrue(r.exceptionOrNull() is GmTimeoutException)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2000)
        }
    }

    @Test
    fun pendingRequestFailsWhenLinkCloses() {
        val link = Link()
        gmPeer(link) { emptyList() }
        GmSession(link.phoneIn, link.toGlasses).use { s ->
            Thread { Thread.sleep(200); link.toPhone.close() }.start()
            val t0 = System.nanoTime()
            val r = runCatching { s.request(Commands.SYSTEM, Commands.HEARTBEAT, timeoutMs = 10_000) }
            assertTrue(r.toString(), r.exceptionOrNull() is java.io.IOException && r.exceptionOrNull() !is GmTimeoutException)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 3000)
        }
    }

    @Test
    fun lateFormatResponseStillSwitchesLayout() {
        val opus = ByteArray(40) { 9 }
        val p = RecordingStreamParser()
        // Negotiation timed out (legacy), then 52 13 01 arrives before compact audio.
        val events = p.feed("52 13 01 31 28 00".unhex() + "52 92 00 00 09 FF 01 00 00 00 00 FF".unhex() + opus)
        assertEquals(1, events.filterIsInstance<RecEvent.Audio>().size)
        assertTrue(p.compact)
    }

    @Test
    fun wireTapSeesBothDirections() {
        val link = Link()
        gmPeer(link) { listOf(status(it, 0)) }
        val seen = CopyOnWriteArrayList<Pair<Boolean, String>>()
        GmSession(link.phoneIn, link.toGlasses, tap = { out, b -> seen += out to b.hex() }).use { s ->
            s.request(Commands.SYSTEM, Commands.HEARTBEAT)
        }
        assertEquals("FA 00 00 09 01 01 01 01 06", seen.first { it.first }.second)
        assertTrue(seen.any { !it.first })
    }

    private fun recPeer(link: Link, answerNegotiate: Boolean, packets: List<ByteArray>) = Thread {
        val cmd = ByteArray(3)
        while (true) {
            var got = 0
            while (got < 3) {
                val n = runCatching { link.glassesIn.read(cmd, got, 3 - got) }.getOrDefault(-1)
                if (n < 0) return@Thread
                got += n
            }
            when (cmd[1].toInt()) {
                0x12 -> if (answerNegotiate) link.toPhone.write(byteArrayOf(0x52, 0x13, cmd[2], 0x31, 0x28, 0x00))
                0x01 -> {
                    link.toPhone.write("52 11 00 01 02 03".unhex())
                    packets.forEach { link.toPhone.write(it) }
                }
                0x00 -> Unit
            }
            link.toPhone.flush()
        }
    }.apply { isDaemon = true; start() }

    @Test
    fun compactRecording() {
        val link = Link()
        val opus = List(3) { i -> ByteArray(40) { (i * 40 + it).toByte() } }
        val packet = "52 92 00 00 09 FF 03 00 00 00 00 FF".unhex() + opus.reduce(ByteArray::plus)
        recPeer(link, answerNegotiate = true, packets = listOf(packet, "52 90 00 00 09 FF 01 00 00 00 00 FF".unhex() + opus[0]))
        val got = CopyOnWriteArrayList<Pair<Int, List<ByteArray>>>()
        RecordingSession(link.phoneIn, link.toGlasses, onAudio = { s, p -> got += s to p }).use { r ->
            assertEquals("52 13 01 31 28 00", r.negotiate(compact = true)!!.bytes.hex())
            assertEquals("52 11 00 01 02 03", r.start()!!.bytes.hex())
            r.stop(drainMs = 200)
        }
        assertEquals(listOf(0x92, 0x90), got.map { it.first })
        opus.forEachIndexed { i, p -> assertArrayEquals(p, got[0].second[i]) }
    }

    @Test
    fun legacyFallbackWhenNegotiationUnanswered() {
        val link = Link()
        val opus = ByteArray(40) { it.toByte() }
        val packet = "52 92 00 00 09 FF 01 00 00 00 00 FF".unhex() + ByteArray(4) + byteArrayOf(0x28) + ByteArray(4) + opus
        recPeer(link, answerNegotiate = false, packets = listOf(packet))
        val got = AtomicReference<ByteArray>()
        RecordingSession(link.phoneIn, link.toGlasses, onAudio = { _, p -> got.set(p.single()) }).use { r ->
            assertNull(r.negotiate(compact = true, timeoutMs = 200))
            r.start()
            r.stop(drainMs = 200)
        }
        assertArrayEquals(opus, got.get())
    }

    @Test
    fun recordingParserSplitsAndResyncs() {
        val opus = ByteArray(40) { 7 }
        val audio = "52 92 00 00 09 FF 01 00 00 00 00 FF".unhex() + opus
        val wire = "00 FF".unhex() + "52 13 01 31 28 00".unhex() + audio + audio
        for (cut in 1 until wire.size) {
            val p = RecordingStreamParser(compact = true)
            val events = p.feed(wire.copyOf(cut)) + p.feed(wire.copyOfRange(cut, wire.size))
            assertEquals("cut $cut: $events", 2, events.filterIsInstance<RecEvent.Audio>().size)
            assertEquals(1, events.filterIsInstance<RecEvent.Control>().size)
        }
    }
}
