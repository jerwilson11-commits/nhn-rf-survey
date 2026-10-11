package com.nhnengineering.rftest.modem

/**
 * Samsung **SDM** (Shannon Diagnostic Monitor) log framing + RRC extraction, for the Exynos/Shannon
 * backend ([ExynosBackend]). A Shannon modem emits SDM frames on `/dev/umts_dm0` (confirmed on a
 * Pixel 6 Pro, see `docs/exynos-spike.md`); [SdmTransport] opens that node and feeds the bytes here.
 *
 * ## Provenance and validation status
 *
 * The on-the-wire format below (frame markers, the 14-byte header, the command groups/ids, and the
 * LTE RRC-OTA inner header) is taken as an **interoperability reference** from SCAT
 * (github.com/fgsect/scat, `parsers/samsung`) and reimplemented here in clean Kotlin. SCAT is GPL, so
 * none of its code is copied — only the protocol it documents, which is what the roadmap sanctions
 * (`docs/multi-chipset-roadmap.md`). The **outer framing** and the **LTE RRC-OTA inner header** are
 * covered by unit tests against synthetic frames ([SdmParserTest]). What is **not yet validated on
 * hardware** (RQ5, pending a rootable Shannon device): the NR RRC inner container layout, and the real
 * logging-enable handshake. Those are marked below.
 *
 * ## Frame layout
 * ```
 *   0x7F | <14-byte header> | payload… | 0x7E
 * ```
 * header, little-endian `'<HBHHBBBL'`:
 * `length1`(u16) `zero`(u8) `length2`(u16) `stamp`(u16) `direction`(u8) `group`(u8) `command`(u8)
 * `timestamp`(u32). The `length1` bytes *follow* the `0x7F`, then one `0x7E` closes the frame, so the
 * whole frame is `length1 + 2` bytes and the payload is `bytes[15 .. 1+length1)` (the `0x7E` is not
 * payload). `length2 + 3 == length1`. The `group` byte carries `radioId = group >> 5` and the real
 * group in its low 5 bits.
 */
object SdmParser {

    const val FRAME_START = 0x7F
    const val FRAME_END = 0x7E
    const val HEADER_LEN = 14          // bytes 1..14 after the start byte
    const val PAYLOAD_OFFSET = 15      // 1 (start) + 14 (header)

    // direction / command type
    const val IPC_DM_CMD = 0xA0
    const val IPC_CT_CMD = 0xA1

    // command groups (the low 5 bits of the group byte)
    const val GROUP_CONTROL = 0x00
    const val GROUP_COMMON = 0x01
    const val GROUP_LTE = 0x02

    // LTE data messages (group GROUP_LTE)
    const val LTE_RRC_SERVING_CELL = 0x50
    const val LTE_RRC_OTA_PACKET = 0x52

    // common data messages (group GROUP_COMMON)
    const val COMMON_NR_RRC_SIGNALING_INFO = 0x08
    const val COMMON_NR_NAS_SIGNALING_INFO = 0x09

    // control messages (group GROUP_CONTROL)
    const val CONTROL_START = 0x00
    const val CONTROL_STOP = 0x02

    /** The default start magic SCAT sends in CONTROL_START (`>L`), kept for the enable handshake. */
    const val CONTROL_START_MAGIC = 0x41414141

    /** One decoded SDM frame. [payload] is the bytes between the header and the trailing `0x7E`. */
    class SdmFrame(
        val direction: Int,
        val radioId: Int,
        val group: Int,
        val command: Int,
        val timestamp: Long,
        val payload: ByteArray,
    ) {
        /** `(group << 8) | command` — the dispatch key, matching SCAT's `cmd_sig`. */
        val cmdSig: Int get() = (group shl 8) or command
        val isLteRrcOta: Boolean get() = group == GROUP_LTE && command == LTE_RRC_OTA_PACKET
        val isNrRrc: Boolean get() = group == GROUP_COMMON && command == COMMON_NR_RRC_SIGNALING_INFO

        override fun equals(other: Any?): Boolean = other is SdmFrame &&
            direction == other.direction && radioId == other.radioId && group == other.group &&
            command == other.command && timestamp == other.timestamp && payload.contentEquals(other.payload)

        override fun hashCode(): Int {
            var r = direction
            r = 31 * r + radioId; r = 31 * r + group; r = 31 * r + command
            r = 31 * r + timestamp.hashCode(); r = 31 * r + payload.contentHashCode()
            return r
        }
    }

    /** Parsed frames plus the offset of the first byte not yet consumed (carry the tail forward). */
    class ParseResult(val frames: List<SdmFrame>, val nextOffset: Int)

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun indexOf(b: ByteArray, value: Int, from: Int): Int {
        var i = from
        while (i < b.size) { if ((b[i].toInt() and 0xFF) == value) return i; i++ }
        return -1
    }

    /**
     * Parse every whole SDM frame in [buf] from [start]. Returns the frames and the offset of the
     * first unconsumed byte, so a streaming caller can keep the trailing partial frame and prepend it
     * to the next read. Malformed frames resync to the next `0x7F` (no exceptions thrown), matching
     * SCAT's recovery.
     */
    fun parseFrames(buf: ByteArray, start: Int = 0): ParseResult {
        val frames = ArrayList<SdmFrame>()
        var pos = start.coerceAtLeast(0)
        while (true) {
            val s = indexOf(buf, FRAME_START, pos)
            if (s < 0) { pos = buf.size; break }
            // Need the start byte + enough header to read length1.
            if (s + 1 + 2 > buf.size) { pos = s; break }
            val length1 = u16(buf, s + 1)
            // A valid frame must at least cover the full header.
            if (length1 < HEADER_LEN) { pos = s + 2; continue }
            val endPos = s + 1 + length1          // index of the trailing 0x7E
            if (endPos >= buf.size) { pos = s; break } // incomplete: carry from s
            if ((buf[endPos].toInt() and 0xFF) != FRAME_END) { pos = s + 2; continue } // bad: resync

            val direction = buf[s + 8].toInt() and 0xFF
            val groupByte = buf[s + 9].toInt() and 0xFF
            val command = buf[s + 10].toInt() and 0xFF
            val timestamp = u32(buf, s + 11)
            val payload = buf.copyOfRange(s + PAYLOAD_OFFSET, endPos)
            frames += SdmFrame(direction, groupByte ushr 5, groupByte and 0x1F, command, timestamp, payload)
            pos = endPos + 1
        }
        return ParseResult(frames, pos)
    }

    /** An LTE RRC OTA message: the logical [channel], [direction] (0=DL,1=UL), and the raw RRC PDU. */
    class LteRrcOta(val channel: Int, val direction: Int, val rrc: ByteArray)

    /**
     * Inner parse of an [SdmFrame.isLteRrcOta] payload: `'<BBH'` = channel(u8), direction(u8),
     * declaredLen(u16), then the raw RRC PDU from offset 4. The declared length is informational
     * (SCAT does not enforce it). Null if the payload is too short to hold the 4-byte inner header.
     */
    fun parseLteRrcOta(payload: ByteArray): LteRrcOta? {
        if (payload.size < 4) return null
        return LteRrcOta(payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF, payload.copyOfRange(4, payload.size))
    }

    /**
     * Raw NR RRC payload from an [SdmFrame.isNrRrc] frame. **The NR inner container layout is not yet
     * confirmed** — unlike the LTE OTA packet, the exact fields before the NR RRC PDU still need the
     * `sdmcommonparser` reference cross-checked against a real capture (RQ5). For now this returns the
     * whole frame payload so the transport can surface it; do not assume `[0]` is the first RRC byte.
     */
    fun nrRrcPayloadUnparsed(payload: ByteArray): ByteArray = payload

    private fun putU16(b: ByteArray, o: Int, v: Int) { b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v ushr 8) and 0xFF).toByte() }
    private fun putU32(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 4) b[o + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    /**
     * Build a well-formed SDM frame (used for the control/enable messages in [SdmTransport], and to
     * round-trip test [parseFrames]). Mirrors the layout documented above.
     */
    fun buildFrame(
        direction: Int,
        group: Int,
        command: Int,
        payload: ByteArray,
        timestamp: Long = 0,
        radioId: Int = 0,
    ): ByteArray {
        val length1 = HEADER_LEN + payload.size
        val out = ByteArray(1 + length1 + 1)
        out[0] = FRAME_START.toByte()
        putU16(out, 1, length1)
        out[3] = 0
        putU16(out, 4, length1 - 3)                 // length2 + 3 == length1
        putU16(out, 6, 0)                           // stamp
        out[8] = direction.toByte()
        out[9] = (((radioId and 0x7) shl 5) or (group and 0x1F)).toByte()
        out[10] = command.toByte()
        putU32(out, 11, timestamp)
        System.arraycopy(payload, 0, out, PAYLOAD_OFFSET, payload.size)
        out[1 + length1] = FRAME_END.toByte()
        return out
    }

    /**
     * The CONTROL_START frame that begins SDM logging (`CONTROL_START` in the control group, payload =
     * the 4-byte start magic, big-endian — SCAT's default `0x41414141`). **The full enable sequence
     * (CONTROL_START plus the per-domain item-select requests that choose *which* logs stream) is not
     * yet hardware-confirmed** — see [SdmTransport].
     */
    fun controlStartFrame(): ByteArray {
        val magic = byteArrayOf(
            ((CONTROL_START_MAGIC ushr 24) and 0xFF).toByte(),
            ((CONTROL_START_MAGIC ushr 16) and 0xFF).toByte(),
            ((CONTROL_START_MAGIC ushr 8) and 0xFF).toByte(),
            (CONTROL_START_MAGIC and 0xFF).toByte(),
        )
        return buildFrame(IPC_DM_CMD, GROUP_CONTROL, CONTROL_START, magic)
    }

    /** The CONTROL_STOP frame (payload = four zero bytes). */
    fun controlStopFrame(): ByteArray =
        buildFrame(IPC_DM_CMD, GROUP_CONTROL, CONTROL_STOP, ByteArray(4))
}
