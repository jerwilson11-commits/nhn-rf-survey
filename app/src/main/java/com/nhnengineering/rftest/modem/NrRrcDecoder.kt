package com.nhnengineering.rftest.modem

/**
 * On-device NR RRC → TDD-config decoder, backed by the generated ASN.1 UPER decoder in
 * `libnrrrc.so` (sources + build recipe in `tools/asn1/`). Pure computation — no root — so it loads
 * in-process via [System.loadLibrary], unlike the su-run DIAG helpers.
 *
 * Returns the TDD configuration as a JSON string carrying raw ASN.1 enum indices (e.g. `refSCS` 1 =
 * kHz30, `periodicity` 0 = ms0p5); the mapping to ms/kHz lives on the Kotlin side so no 3GPP enum
 * semantics sit in C. Returns null when the native library is absent (e.g. a build without the
 * prebuilt `.so`, or a non-arm64 device) or the decode failed — callers fall back to the
 * paste/upload path, so absence is never fatal.
 *
 * [pduKind]: 0 = RRCReconfiguration (NSA SCG-add), 1 = BCCH-DL-SCH-Message / SIB1 (SA broadcast).
 */
object NrRrcDecoder {

    /** True once `libnrrrc.so` has loaded. False is normal on a build/device without it. */
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("nrrrc") }.isSuccess
    }

    /** Decode [uper] (one NR RRC message body, as captured at RrcOtaParser's offset 23). */
    fun decode(pduKind: Int, uper: ByteArray): String? =
        if (available) runCatching { decodeTdd(pduKind, uper) }.getOrNull() else null

    private external fun decodeTdd(pduKind: Int, uper: ByteArray): String?
}
