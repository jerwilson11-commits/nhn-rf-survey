package com.nhnengineering.rftest.modem

/**
 * Reads and builds `QMI_NAS_{GET,SET}_SYSTEM_SELECTION_PREFERENCE` (0x0034 / 0x0033) payloads.
 *
 * ## Why this is the mechanism, not `TelephonyManager`
 *
 * `TelephonyManager.setAllowedNetworkTypesForReason` is accepted by the framework and then
 * recomputed straight back to the handset default by `OplusNetworkUtils`, measured directly on
 * this handset. This QMI message sits underneath that layer, so the vendor framework does not get
 * a vote. Driven successfully by hand on 2026-09-22: `mode_pref` written to `0x0010` moved the
 * radio from NR SA to LTE in under five seconds, and restoring `0x005F` moved it back.
 *
 * ## Field layout
 *
 * Confirmed against libqmi's own `qmi-service-nas.json`, not guessed:
 *
 *  - **TLV 0x11, Mode Preference** -- `guint16` RAT bitmask. `0x005F` decodes as
 *    CDMA-1x | HRPD | GSM | UMTS | LTE | NR with TD-SCDMA absent, which matches the modem RAF
 *    the framework itself logged (`UMTS|EvDo|1xRTT|LTE|GSM|LTE_CA|NR`).
 *  - **TLV 0x17, Change Duration** -- `guint8`. `0x00` = until power cycle, `0x01` = permanent.
 *    Every write from this app uses `0x00`: it makes a reboot -- or, observed 2026-09-22, a bare
 *    airplane-mode toggle -- an unconditional way out of a lock that goes wrong.
 *  - **TLV 0x2C / 0x2F, NR5G SA Band Preference** (get / set) -- eight `guint64` masks, 512 bits,
 *    bit *N* is band n(N+1). This is the lever for forcing 5G NSA rather than SA: zeroing it
 *    leaves standalone NR nowhere to camp, so NR service (if the network offers it at all) can
 *    only be reached via NSA. Untried as of 2026-09-22 -- see [ModeMask].
 */
object QmiSelectionPreference {

    const val MSG_GET = 0x0034
    const val MSG_SET = 0x0033

    private const val TLV_MODE_PREFERENCE = 0x11
    private const val TLV_CHANGE_DURATION = 0x17
    private const val TLV_NR5G_SA_BAND_PREF_GET = 0x2C
    private const val TLV_NR5G_SA_BAND_PREF_SET = 0x2F

    /** `0x00`: the modem forgets this preference at the next power cycle. Always used. */
    const val DURATION_UNTIL_POWER_CYCLE: Int = 0x00

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)

    /** One TLV's id and body, as scanned out of a QMI response. */
    private data class Tlv(val id: Int, val body: ByteArray)

    /** Scans a whole response (QMI header included) into its TLVs. Malformed input yields none. */
    private fun tlvsOf(payload: ByteArray): List<Tlv> {
        if (payload.size < 7) return emptyList()
        val out = mutableListOf<Tlv>()
        var off = 7
        while (off + 3 <= payload.size) {
            val id = u8(payload, off)
            val len = u16(payload, off + 1)
            off += 3
            if (off + len > payload.size) break
            out += Tlv(id, payload.copyOfRange(off, off + len))
            off += len
        }
        return out
    }

    data class GetResult(
        val looksValid: Boolean = false,
        val success: Boolean? = null,
        /** The RAT bitmask in force, from TLV 0x11. Null if the TLV was absent or malformed. */
        val modePref: Int? = null,
        val notes: List<String> = emptyList(),
    )

    /** Parses a `GET_SYSTEM_SELECTION_PREFERENCE` (0x0034) response. */
    fun parseGet(payload: ByteArray): GetResult {
        if (payload.size < 7) {
            return GetResult(notes = listOf("Response is ${payload.size} bytes, shorter than a QMI header."))
        }
        val msgId = u16(payload, 3)
        if (msgId != MSG_GET) {
            return GetResult(notes = listOf("Response is for message 0x%04x, not GET_SYSTEM_SELECTION_PREFERENCE.".format(msgId)))
        }
        val tlvs = tlvsOf(payload)
        var success: Boolean? = null
        var modePref: Int? = null
        val notes = mutableListOf<String>()
        for (t in tlvs) {
            when (t.id) {
                0x02 -> if (t.body.size >= 2) success = u16(t.body, 0) == 0
                TLV_MODE_PREFERENCE -> if (t.body.size == 2) {
                    modePref = u16(t.body, 0)
                } else {
                    notes += "Mode Preference TLV is ${t.body.size} bytes, not the expected 2."
                }
            }
        }
        return GetResult(looksValid = true, success = success, modePref = modePref, notes = notes)
    }

    /** Whether a write's own result TLV said the modem accepted it. Null if that TLV was absent. */
    fun parseSetResult(payload: ByteArray): Boolean? {
        val tlvs = tlvsOf(payload)
        val result = tlvs.firstOrNull { it.id == 0x02 } ?: return null
        if (result.body.size < 2) return null
        return u16(result.body, 0) == 0
    }

    /**
     * Builds the `id:hexbytes` argument list `qmilock` expects for a mode-preference write.
     *
     * Always carries the change-duration TLV, and always [DURATION_UNTIL_POWER_CYCLE] -- see the
     * class doc for why every write in this app uses it.
     */
    fun setModePrefArgs(modePref: Int): List<String> = listOf(
        "%02x:%02x%02x".format(TLV_MODE_PREFERENCE, modePref and 0xFF, (modePref shr 8) and 0xFF),
        "%02x:%02x".format(TLV_CHANGE_DURATION, DURATION_UNTIL_POWER_CYCLE),
    )
}
