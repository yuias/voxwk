package app.rly3h.voxwk.glass.gm

import java.io.IOException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The policy is decided, not measured. The firmware keeps its resend buffer about 15 s and takes at most 64 indices
// per request, so a resend after an 8 s idle is still inside that buffer.
const val SCREENSHOT_IDLE_TIMEOUT_MS = 8000L
const val SCREENSHOT_MAX_RESEND_ROUNDS = 8
const val SCREENSHOT_MAX_RESEND_INDICES = 64

private const val MAX_CHUNK_BYTES = 1024
private const val INDEX_START = 0xFFFE
private const val INDEX_COMPLETE = 0xFFFD
private const val INDEX_ERROR = 0xFFFF
private const val STATUS_BUSY = 7L

class ScreenshotException(message: String) : IOException(message)

/** Collects the `01/66` uploads of one capture. Holds no I/O and no clock: [captureScreenshot] drives it. */
class ScreenshotAssembler(private val maxResendRounds: Int = SCREENSHOT_MAX_RESEND_ROUNDS) {
    sealed interface Step {
        data object Wait : Step

        /** Ascending, at most [SCREENSHOT_MAX_RESEND_INDICES]. */
        data class Resend(val indices: List<Int>) : Step
        data class Done(val image: Gray4Image) : Step
        data class Failed(val reason: String) : Step
    }

    private var start: ScreenshotStart? = null
    private var chunks = arrayOfNulls<ByteArray>(0)
    private var rounds = 0

    /** Nothing thrown by the parsers leaves here: malformed over-the-air data gives [Step.Failed]. */
    fun onMessage(m: GmMessage): Step {
        if (m.service != Commands.SYSTEM) return Step.Wait
        return try {
            when (m.command) {
                Commands.SCREENSHOT_UPLOAD -> onUpload(m)
                Commands.SCREENSHOT_REQUEST, Commands.SCREENSHOT_RESEND -> onAck(m)
                else -> Step.Wait // HUD acks and the like
            }
        } catch (e: IllegalArgumentException) {
            Step.Failed(e.message ?: "malformed screenshot data")
        }
    }

    fun onIdle(): Step = if (start == null) Step.Failed("no response") else resendRound()

    private fun onAck(m: GmMessage): Step {
        val status = m.tlvs.firstOrNull { it.type == Tlv.STATUS }?.int ?: return Step.Wait
        if (status == 0L) return Step.Wait
        // For the resend command, status 1 can also mean that the firmware's resend buffer has expired.
        return Step.Failed(if (status == STATUS_BUSY) "busy" else "refused with status $status")
    }

    private fun onUpload(m: GmMessage): Step {
        val tlvs = m.tlvs
        val index = requireNotNull(tlvs.firstOrNull { it.type == Tlv.INT16 }) { "upload lacks an index" }.int.toInt()
        val json = tlvs.firstOrNull { it.type == Tlv.JSON }?.text.orEmpty()
        when (index) {
            INDEX_START -> {
                val parsed = parseScreenshotStart(json)
                start = parsed
                chunks = arrayOfNulls(parsed.totalChunks)
                rounds = 0
            }
            INDEX_ERROR -> {
                val message = runCatching { Json.parseToJsonElement(json).jsonObject["message"]?.jsonPrimitive?.content }
                    .getOrNull()
                return Step.Failed(message ?: "error")
            }
            // Until a start arrives, chunks and markers can be the tail of an earlier capture.
            INDEX_COMPLETE -> if (start != null) return resendRound()
            else -> {
                val data = tlvs.firstOrNull { it.type == Tlv.BYTES }?.value ?: ByteArray(0)
                if (start != null && index < chunks.size && data.size in 1..MAX_CHUNK_BYTES) {
                    chunks[index] = data
                    // The complete marker is not waited for: it would only repeat what the chunks already show.
                    if (chunks.all { it != null }) return finish()
                }
            }
        }
        return Step.Wait
    }

    private fun resendRound(): Step {
        if (++rounds > maxResendRounds) return Step.Failed("chunks still missing after $maxResendRounds resend rounds")
        val missing = chunks.indices.filter { chunks[it] == null }
        return Step.Resend(missing.take(SCREENSHOT_MAX_RESEND_INDICES))
    }

    private fun finish(): Step {
        val start = checkNotNull(start)
        val out = java.io.ByteArrayOutputStream(start.dataSize)
        chunks.forEach { out.write(it!!) }
        val joined = out.toByteArray()
        require(joined.size == start.dataSize) { "got ${joined.size} bytes, expected ${start.dataSize}" }
        return Step.Done(decodeScreenshot(start, joined))
    }
}

/**
 * Requests a screenshot and returns it once every chunk has arrived. Blocks the calling thread, so it must not run on
 * the gm reader thread: sending from there could stall reading, and the replies this waits for come only from that
 * thread. [inbox] must already receive the link's unsolicited messages.
 *
 * The idle deadline restarts only on the capture's own messages. The HUD's acks reach the same inbox several times a
 * second, so resetting on every message would never time out during speech.
 */
fun captureScreenshot(gm: GmSession, inbox: BlockingQueue<GmMessage>, idleTimeoutMs: Long = SCREENSHOT_IDLE_TIMEOUT_MS): Gray4Image {
    val assembler = ScreenshotAssembler()
    val idleNanos = TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs)
    // The request is posted, not awaited: the upload can start before its acknowledgement, which then arrives in the inbox.
    gm.post(Commands.SYSTEM, Commands.SCREENSHOT_REQUEST)
    var deadline = System.nanoTime() + idleNanos
    while (true) {
        val m = inbox.poll(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
        val step = if (m == null) {
            deadline = System.nanoTime() + idleNanos
            assembler.onIdle()
        } else {
            if (m.service == Commands.SYSTEM && m.command in CAPTURE_COMMANDS) deadline = System.nanoTime() + idleNanos
            assembler.onMessage(m)
        }
        when (step) {
            ScreenshotAssembler.Step.Wait -> Unit
            is ScreenshotAssembler.Step.Resend -> gm.post(
                Commands.SYSTEM,
                Commands.SCREENSHOT_RESEND,
                Tlv.json(step.indices.joinToString(",", """{"missing":[""", "]}")),
            )
            is ScreenshotAssembler.Step.Done -> return step.image
            is ScreenshotAssembler.Step.Failed -> throw ScreenshotException(step.reason)
        }
    }
}

private val CAPTURE_COMMANDS = setOf(Commands.SCREENSHOT_REQUEST, Commands.SCREENSHOT_UPLOAD, Commands.SCREENSHOT_RESEND)
