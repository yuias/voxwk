package app.rly3h.voxwk.glass.gm

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Receives every byte crossing a link, for raw hex captures. The official App cannot run alongside this client, so
 * its traffic cannot be observed from the side.
 */
fun interface WireTap {
    fun onBytes(outgoing: Boolean, bytes: ByteArray)
}

class GmTimeoutException(message: String) : IOException(message)

/**
 * GM request/response over a command-link byte stream. Responses are matched on event, service and command; empty
 * flow-control replies are skipped. Anything unmatched goes to [onUnsolicited].
 */
class GmSession(
    private val input: InputStream,
    private val output: OutputStream,
    private val tap: WireTap = WireTap { _, _ -> },
    private val onUnsolicited: (GmMessage) -> Unit = {},
    private val onSkipped: (GmEvent.Skipped) -> Unit = {},
    private val onClosed: (Throwable?) -> Unit = {},
) : AutoCloseable {
    private val parser = GmStreamParser()
    private val pending = ConcurrentHashMap<Int, Pending>()
    private var nextEvent = 1
    private val writeLock = Any()

    @Volatile
    private var closed = false

    private class Pending(val service: Int, val command: Int) {
        val replies = LinkedBlockingQueue<GmMessage>()
    }

    private val reader = Thread({ readLoop() }, "gm-reader").apply { isDaemon = true; start() }

    private fun readLoop() {
        val buf = ByteArray(4096)
        var error: Throwable? = null
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                tap.onBytes(false, buf.copyOf(n))
                for (ev in parser.feed(buf, n)) when (ev) {
                    is GmEvent.Skipped -> onSkipped(ev)
                    is GmEvent.Message -> {
                        val m = ev.message
                        val p = pending[m.event]
                        if (p != null && p.service == m.service && p.command == m.command) p.replies.add(m)
                        else onUnsolicited(m)
                    }
                }
            }
        } catch (t: Throwable) {
            error = t
        }
        closed = true
        onClosed(error)
    }

    /** Sends one command and waits for its non-empty reply. */
    fun request(service: Int, command: Int, body: ByteArray = ByteArray(0), timeoutMs: Long = 3000): GmMessage {
        val event: Int
        val p = Pending(service, command)
        synchronized(writeLock) {
            event = allocateEvent()
            pending[event] = p
        }
        try {
            send(event, service, command, body)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (true) {
                val left = deadline - System.nanoTime()
                val reply = p.replies.poll(minOf(maxOf(0, left), POLL_NANOS), TimeUnit.NANOSECONDS)
                if (reply == null) {
                    if (closed && p.replies.isEmpty()) throw IOException("command link closed")
                    if (left <= 0) throw GmTimeoutException("no reply to %02X/%02X (event %d) in %d ms".format(service, command, event, timeoutMs))
                    continue
                }
                if (!reply.isFlowControl) return reply
            }
        } finally {
            pending.remove(event)
        }
    }

    /** Sends without waiting, e.g. for frequent display updates. */
    fun post(service: Int, command: Int, body: ByteArray = ByteArray(0)) {
        synchronized(writeLock) { send(allocateEvent(), service, command, body) }
    }

    private fun send(event: Int, service: Int, command: Int, body: ByteArray) {
        // Frames go out back to back; the documented per-fragment wait for a flow-control reply applies to BLE.
        val bytes = Gm.encodeJoined(event, service, command, body)
        synchronized(output) {
            tap.onBytes(true, bytes)
            output.write(bytes)
            output.flush()
        }
    }

    private fun allocateEvent(): Int {
        repeat(255) {
            val e = nextEvent
            nextEvent = if (nextEvent == 255) 1 else nextEvent + 1
            if (!pending.containsKey(e)) return e
        }
        throw IOException("no free event id")
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private companion object {
        val POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(100)
    }
}

/** Recording-link control: negotiate the format, start, stream Opus packets, stop and drain. */
class RecordingSession(
    private val input: InputStream,
    private val output: OutputStream,
    private val tap: WireTap = WireTap { _, _ -> },
    private val onAudio: (stream: Int, packets: List<ByteArray>) -> Unit,
    private val onSkipped: (RecEvent.Skipped) -> Unit = {},
    private val onClosed: (Throwable?) -> Unit = {},
) : AutoCloseable {
    private val parser = RecordingStreamParser()
    private val controls = ArrayBlockingQueue<RecEvent.Control>(16)

    @Volatile
    private var started = false

    private val reader = Thread({ readLoop() }, "rec-reader").apply { isDaemon = true; start() }

    private fun readLoop() {
        val buf = ByteArray(4096)
        var error: Throwable? = null
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                tap.onBytes(false, buf.copyOf(n))
                for (ev in parser.feed(buf, n)) when (ev) {
                    is RecEvent.Control -> controls.offer(ev)
                    is RecEvent.Audio -> {
                        started = true
                        onAudio(ev.stream, ev.packets)
                    }
                    is RecEvent.Skipped -> onSkipped(ev)
                }
            }
        } catch (t: Throwable) {
            error = t
        }
        onClosed(error)
    }

    private fun write(bytes: ByteArray) = synchronized(output) {
        tap.onBytes(true, bytes)
        output.write(bytes)
        output.flush()
    }

    /**
     * Requests compact (40-byte) or legacy (49-byte) records. Returns the `52 13` response, or null when the firmware
     * does not answer within [timeoutMs]; the parser then stays on legacy records, as old firmware expects.
     */
    fun negotiate(compact: Boolean, timeoutMs: Long = 800): RecEvent.Control? {
        controls.clear()
        parser.compact = false
        write(byteArrayOf(0x52, 0x12, if (compact) 1 else 0))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val c = controls.poll(maxOf(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS) ?: break
            // The parser has already switched to the effective mode in the response.
            if (c.command == 0x13) return c
        }
        return null
    }

    /** Sends start; returns the `52 11` notification, or null if audio arrived first or nothing came in time. */
    fun start(timeoutMs: Long = 3000): RecEvent.Control? {
        started = false
        write(byteArrayOf(0x52, 0x01, 0x00))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val c = controls.poll(50, TimeUnit.MILLISECONDS)
            if (c != null && c.command == 0x11) return c
            if (started) return null
        }
        throw GmTimeoutException("recording did not start in $timeoutMs ms")
    }

    /** Stop has no acknowledgement; keep reading for [drainMs] so queued tail audio is not lost. */
    fun stop(drainMs: Long = 500) {
        write(byteArrayOf(0x52, 0x00, 0x00))
        Thread.sleep(drainMs)
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }
}
