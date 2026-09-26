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
 *    only be reached via NSA. Zeroing was never tried. Restricting it to a single band was, on
 *    2026-09-26 -- see [setNrSaBandArgs] for what the modem insists on.
 */
object QmiSelectionPreference {

    const val MSG_GET = 0x0034
    const val MSG_SET = 0x0033

    private const val TLV_MODE_PREFERENCE = 0x11
    private const val TLV_CHANGE_DURATION = 0x17
    private const val TLV_LTE_BAND_PREF = 0x15
    private const val TLV_NR5G_SA_BAND_PREF_GET = 0x2C
    private const val TLV_NR5G_SA_BAND_PREF_SET = 0x2F
    private const val TLV_NR5G_NSA_BAND_PREF_GET = 0x2D
    private const val TLV_NR5G_NSA_BAND_PREF_SET = 0x30

    /** Eight `guint64` words: 512 bits, bit N of word K is band 64K+N+1. */
    private const val NR_BAND_WORDS = 8

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

    /**
     * The band masks in force, each null where the modem did not report it or reported it
     * malformed. NR masks are always [NR_BAND_WORDS] words.
     */
    data class BandPrefs(
        /** TLV 0x15, bands 1-64. */
        val lte: Long? = null,
        /** TLV 0x2C. */
        val nrSa: List<Long>? = null,
        /** TLV 0x2D. */
        val nrNsa: List<Long>? = null,
    )

    data class GetResult(
        val looksValid: Boolean = false,
        val success: Boolean? = null,
        /** The RAT bitmask in force, from TLV 0x11. Null if the TLV was absent or malformed. */
        val modePref: Int? = null,
        val bands: BandPrefs = BandPrefs(),
        val notes: List<String> = emptyList(),
    )

    private fun u64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or u8(b, o + i).toLong()
        return v
    }

    private fun words(body: ByteArray, count: Int): List<Long>? =
        if (body.size == count * 8) List(count) { u64(body, it * 8) } else null

    /** Band numbers set in a little-endian multi-word mask: bit N of word K is band 64K+N+1. */
    fun bandsOf(words: List<Long>): Set<Int> = buildSet {
        words.forEachIndexed { k, w ->
            for (n in 0 until 64) if ((w ushr n) and 1L != 0L) add(64 * k + n + 1)
        }
    }

    /** The mask with exactly [bands] set, over [wordCount] words. Bands outside it are an error. */
    fun maskOf(bands: Set<Int>, wordCount: Int): List<Long> {
        val out = LongArray(wordCount)
        for (b in bands) {
            require(b in 1..(64 * wordCount)) { "Band $b does not fit in a $wordCount-word mask." }
            out[(b - 1) / 64] = out[(b - 1) / 64] or (1L shl ((b - 1) % 64))
        }
        return out.toList()
    }

    private fun wordsHex(words: List<Long>): String = words.joinToString("") { w ->
        (0 until 8).joinToString("") { "%02x".format(((w ushr (8 * it)) and 0xFF).toInt()) }
    }

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
        var bands = BandPrefs()
        val notes = mutableListOf<String>()
        for (t in tlvs) {
            when (t.id) {
                0x02 -> if (t.body.size >= 2) success = u16(t.body, 0) == 0
                TLV_LTE_BAND_PREF -> words(t.body, 1)?.let { bands = bands.copy(lte = it[0]) }
                    ?: run { notes += "LTE Band Preference TLV is ${t.body.size} bytes, not 8." }
                TLV_NR5G_SA_BAND_PREF_GET -> words(t.body, NR_BAND_WORDS)?.let { bands = bands.copy(nrSa = it) }
                    ?: run { notes += "NR5G SA Band Preference TLV is ${t.body.size} bytes, not 64." }
                TLV_NR5G_NSA_BAND_PREF_GET -> words(t.body, NR_BAND_WORDS)?.let { bands = bands.copy(nrNsa = it) }
                    ?: run { notes += "NR5G NSA Band Preference TLV is ${t.body.size} bytes, not 64." }
                TLV_MODE_PREFERENCE -> if (t.body.size == 2) {
                    modePref = u16(t.body, 0)
                } else {
                    notes += "Mode Preference TLV is ${t.body.size} bytes, not the expected 2."
                }
            }
        }
        return GetResult(looksValid = true, success = success, modePref = modePref, bands = bands, notes = notes)
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

    /**
     * Argument list restricting LTE to [bands] (1-64 only).
     *
     * Only the base LTE mask (TLV 0x15) is written. Hand-tested 2026-09-26: the modem rejects an
     * all-zero base mask (error 48) and rejects any use of the extended-mask TLV 0x24 beyond word
     * 0, so bands above 64 cannot be selected this way and this refuses them up front.
     */
    fun setLteBandArgs(bands: Set<Int>): List<String> {
        require(bands.isNotEmpty()) { "Restricting to no bands would leave the modem nothing." }
        require(bands.all { it in 1..64 }) { "Only LTE bands 1-64 can be selected." }
        return restoreLteArgs(maskOf(bands, 1)[0])
    }

    /** Writes the base LTE mask exactly as given, e.g. to restore what was read. */
    fun restoreLteArgs(lteMask: Long): List<String> = listOf(
        "%02x:%s".format(TLV_LTE_BAND_PREF, wordsHex(listOf(lteMask))),
        "%02x:%02x".format(TLV_CHANGE_DURATION, DURATION_UNTIL_POWER_CYCLE),
    )

    /**
     * Argument list restricting standalone NR to [saBands].
     *
     * Hand-tested 2026-09-26 on the OnePlus 9: writing the SA mask (TLV 0x2F) alone is refused
     * with MissingArgument (error 17), and so is SA plus the NSA mask. It is accepted only when
     * Mode Preference (TLV 0x11) rides along in the same write, so [currentModePref] is required
     * and must be the value already in force -- passing a different one would change technology
     * as a side effect. The NSA mask is written back as read ([nsaWords]) so it is left alone.
     */
    fun setNrSaBandArgs(currentModePref: Int, saBands: Set<Int>, nsaWords: List<Long>): List<String> {
        require(saBands.isNotEmpty()) { "Restricting to no bands would leave the modem nothing." }
        return restoreNrSaArgs(currentModePref, maskOf(saBands, NR_BAND_WORDS), nsaWords)
    }

    /**
     * Argument list for NSA-only: [modePref] (LTE + NR) with the SA mask emptied and the NSA mask
     * written back as read. Hand-verified 2026-09-26; unlike an empty LTE base mask, an empty SA
     * mask is accepted.
     */
    fun nsaOnlyArgs(modePref: Int, nsaWords: List<Long>): List<String> =
        restoreNrSaArgs(modePref, List(NR_BAND_WORDS) { 0L }, nsaWords)

    /** Writes the SA mask exactly as given, with the mode preference and NSA mask it needs. */
    fun restoreNrSaArgs(currentModePref: Int, saWords: List<Long>, nsaWords: List<Long>): List<String> {
        require(saWords.size == NR_BAND_WORDS && nsaWords.size == NR_BAND_WORDS) {
            "NR masks must be $NR_BAND_WORDS words."
        }
        return listOf(
            setModePrefArgs(currentModePref)[0],
            "%02x:%s".format(TLV_NR5G_SA_BAND_PREF_SET, wordsHex(saWords)),
            "%02x:%s".format(TLV_NR5G_NSA_BAND_PREF_SET, wordsHex(nsaWords)),
            "%02x:%02x".format(TLV_CHANGE_DURATION, DURATION_UNTIL_POWER_CYCLE),
        )
    }
}
