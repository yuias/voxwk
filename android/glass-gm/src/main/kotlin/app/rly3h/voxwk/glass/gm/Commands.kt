package app.rly3h.voxwk.glass.gm

/** Request bodies for the GM commands the PoC uses (GlassSDK docs/DEVICE_BUSINESS_PROTOCOL.md). */
object Commands {
    const val SYSTEM = 0x01
    const val HEARTBEAT = 0x01
    const val DEVICE_INFO = 0x02
    const val DEVICE_STATUS = 0x06
    const val AUDIO_PROCESSING = 0x2F
    const val SCREENSHOT_REQUEST = 0x65
    const val SCREENSHOT_UPLOAD = 0x66
    const val SCREENSHOT_RESEND = 0x6A

    const val TRANSLATION = 0x03
    const val TR_START = 0x01
    const val TR_STOP = 0x02
    const val TR_PROVISIONAL = 0x03
    const val TR_FINAL = 0x04
    const val CONV_START = 0x05
    const val CONV_STOP = 0x06
    const val CONV_PROVISIONAL = 0x07
    const val CONV_FINAL = 0x08
    const val TR_RECORDING_SOURCE = 0x0C
    const val TR_LOCAL_PAUSE = 0x16

    /** `noise_mode` 0 = ENC, 1 = raw; `pickup_mode` 0..4, or null to keep the glasses' setting. */
    fun audioProcessing(noiseMode: Int, pickupMode: Int?): ByteArray =
        if (pickupMode == null) Tlv.int8(noiseMode) else Tlv.concat(Tlv.int8(noiseMode), Tlv.int8(pickupMode))

    fun title(title: String) = Tlv.string(title)

    /** Result array is translation first, then source. */
    fun result(translation: String, source: String, asciiOnly: Boolean) =
        Tlv.json(JsonText.stringArray(listOf(translation, source), asciiOnly))

    /** `source` 0 = glasses / 1 = phone; `direction` 0 = front / 1 = omni (phone requires omni). */
    fun recordingSource(source: Int, direction: Int) = Tlv.concat(Tlv.int8(source), Tlv.int8(direction))

    /** Conversation stop mode: 0 = exit, 1 = background. */
    fun conversationStop(mode: Int) = Tlv.int8(mode)

    /** First STATUS (system namespace) or INT8 (plugin namespace) value in a reply, or null. */
    fun status(reply: GmMessage): Long? =
        reply.tlvs.firstOrNull { it.type == Tlv.STATUS || it.type == Tlv.INT8 }?.int
}
