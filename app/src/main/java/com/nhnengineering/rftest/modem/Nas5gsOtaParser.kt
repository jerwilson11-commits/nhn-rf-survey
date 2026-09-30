package com.nhnengineering.rftest.modem

/**
 * Decodes DIAG log codes `0xB80A`/`0xB80B` -- incoming/outgoing plain NAS-5GS OTA (5GMM signaling)
 * -- turning the one-off manual investigation from 2026-09-27 (`docs/modem-diag-access.md`, "The
 * runtime decision: the phone registers on n41 SA, then leaves voluntarily") into a repeatable
 * in-app capture instead of an external, by-hand pycrate decode.
 *
 * ## Where the payload layout comes from
 *
 * Validated against a real capture on 2026-09-27, not guessed: after the standard 12-byte
 * DIAG_LOG_F header [NrMl1Parser] already confirmed empirically, a `u32` version field, then 3
 * version-component bytes, then the raw NAS-5GS message starting at payload byte 7 -- i.e.
 * `packet[HEADER_BYTES + 4 + 3 + byte]`.
 *
 * ## Message type table
 *
 * TS 24.501 Table 9.7.1 (5GS mobility management message types) is a 3GPP standard constant
 * table, not Qualcomm-proprietary and not device-specific -- safe to hardcode, unlike the DIAG
 * payload offset above, which needed a real capture to pin down. This is the same table pycrate's
 * `NAS5G` module used to print "Service Accept" / "Deregistration Request" in the original
 * investigation.
 *
 * ## Security-protected messages are not decoded further
 *
 * NAS-5GS octet 2's low nibble is the Security Header Type (TS 24.007 §11.2.3.1.1): `0x0` is a
 * plain message, where the very next byte is the Message Type. Any other value means the message
 * carries a 4-byte MAC and 1-byte sequence number before the (possibly ciphered) content. The
 * original investigation's pycrate decode read plainly-named messages, but that does not by itself
 * confirm what this app's own capture bytes look like at the wire level -- stripping the security
 * envelope here without checking a real capture first would be exactly the kind of guess this
 * project has repeatedly found and removed elsewhere (see [NrRrcServingCellParser]'s own history).
 * So a non-zero security header type is reported honestly as "security protected", with the raw
 * hex still exposed, rather than assumed to decode a message type from bytes that have not been
 * validated.
 */
object Nas5gsOtaParser {

    const val LOG_CODE_INCOMING = 0xB80A
    const val LOG_CODE_OUTGOING = 0xB80B

    enum class Direction { INCOMING, OUTGOING }

    private const val HEADER_BYTES = 12
    private const val NAS_AT = HEADER_BYTES + 4 + 3 // version u32 + 3 version-component bytes

    /** TS 24.501 Table 9.7.1 -- 5GMM message types. Standard, not device-specific. */
    private val MESSAGE_TYPES = mapOf(
        0x41 to "Registration request", 0x42 to "Registration accept",
        0x43 to "Registration complete", 0x44 to "Registration reject",
        0x45 to "Deregistration request (UE orig.)", 0x46 to "Deregistration accept (UE orig.)",
        0x47 to "Deregistration request (UE term.)", 0x48 to "Deregistration accept (UE term.)",
        0x4C to "Service request", 0x4D to "Service reject", 0x4E to "Service accept",
        0x54 to "Authentication request", 0x55 to "Authentication response",
        0x56 to "Authentication reject", 0x57 to "Authentication failure",
        0x58 to "Authentication result", 0x5B to "Identity request", 0x5C to "Identity response",
        0x5D to "Security mode command", 0x5E to "Security mode complete",
        0x5F to "Security mode reject", 0x64 to "5GMM status", 0x65 to "Notification",
        0x66 to "Notification response", 0x67 to "UL NAS transport", 0x68 to "DL NAS transport",
    )

    data class Result(
        val looksValid: Boolean = false,
        val direction: Direction? = null,
        /** Null when there were not even enough bytes to read the security header type. */
        val securityProtected: Boolean? = null,
        /** Only set when [securityProtected] is false -- see the class doc for why. */
        val messageType: Int? = null,
        val messageTypeName: String? = null,
        /** The raw NAS-5GS message bytes (from payload byte 7 on), always present when reachable. */
        val rawNasHex: String? = null,
        val notes: List<String> = emptyList(),
    )

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun hex(b: ByteArray, from: Int) =
        b.copyOfRange(from, b.size).joinToString("") { "%02x".format(it) }

    fun parse(packet: ByteArray): Result {
        if (packet.size < HEADER_BYTES) {
            return Result(notes = listOf("Packet is ${packet.size} bytes, too short for a log header."))
        }
        val code = u16(packet, 2)
        val direction = when (code) {
            LOG_CODE_INCOMING -> Direction.INCOMING
            LOG_CODE_OUTGOING -> Direction.OUTGOING
            else -> return Result(
                notes = listOf("Log code 0x%04x is not NAS-5GS OTA (incoming or outgoing).".format(code)),
            )
        }
        val declared = u16(packet, 0)
        if (declared != packet.size) {
            return Result(
                direction = direction,
                notes = listOf("Packet declares $declared bytes but ${packet.size} arrived."),
            )
        }
        if (packet.size <= NAS_AT) {
            return Result(
                looksValid = true,
                direction = direction,
                notes = listOf("Packet has no NAS-5GS bytes after the version header."),
            )
        }

        // EPD is packet[NAS_AT]; unused directly -- 5GMM is the only family this app subscribes
        // to (0xB80A/0xB80B are the 5GMM-specific log codes), so it is not re-checked here.
        val secondByte = u8(packet, NAS_AT + 1)
        val securityHeaderType = secondByte and 0x0F
        val rawHex = hex(packet, NAS_AT)

        if (securityHeaderType != 0) {
            return Result(
                looksValid = true,
                direction = direction,
                securityProtected = true,
                rawNasHex = rawHex,
            )
        }

        if (packet.size <= NAS_AT + 2) {
            return Result(
                looksValid = true,
                direction = direction,
                securityProtected = false,
                rawNasHex = rawHex,
                notes = listOf("Plain message has no message-type byte."),
            )
        }
        val messageType = u8(packet, NAS_AT + 2)
        return Result(
            looksValid = true,
            direction = direction,
            securityProtected = false,
            messageType = messageType,
            messageTypeName = MESSAGE_TYPES[messageType],
            rawNasHex = rawHex,
            notes = if (MESSAGE_TYPES[messageType] == null) {
                listOf("Message type 0x%02x is not in the known 5GMM table.".format(messageType))
            } else {
                emptyList()
            },
        )
    }

    /** Parses a `LOG <hex>` line from the helper, or null when it is not one. */
    fun parseLogLine(line: String): Result? {
        val t = line.trim()
        if (!t.startsWith("LOG ")) return null
        val hexStr = t.removePrefix("LOG ").trim()
        if (hexStr.isEmpty() || hexStr.length % 2 != 0) return null
        val bytes = ByteArray(hexStr.length / 2)
        for (i in bytes.indices) {
            val v = hexStr.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            bytes[i] = v.toByte()
        }
        return parse(bytes)
    }
}
