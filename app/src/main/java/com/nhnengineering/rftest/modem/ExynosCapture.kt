package com.nhnengineering.rftest.modem

import java.util.Collections

/**
 * Drives the experimental Exynos/Shannon SDM capture ([SdmTransport]) and feeds any RRC bytes to the
 * shared NR RRC decoder ([NrRrcTdd]) — the beta field-validation path. Self-contained on purpose: it
 * does **not** touch the Qualcomm `ModemNrStream` event model, so the (working, Qualcomm-tested) live
 * capture is unaffected.
 *
 * Why this ships before it's validated: we have no rootable Shannon device, so the only way to learn
 * what real Exynos devices do is to let rooted Exynos users run it and report the instrumentation
 * ([summary]). Even "node denied" or "N frames, no RRC" is exactly the signal we need. See
 * [ExynosBackend.CAPTURE_BETA] and docs/exynos-spike.md.
 */
class ExynosCapture {

    private val transport = SdmTransport()

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    private var bestTdd: NrRrcTdd.Decoded? = null

    // A bounded sample of raw NR RRC payloads, so a field report can carry real bytes to pin down the
    // (still-unconfirmed) NR inner-container offset.
    private val nrSamples = Collections.synchronizedList(ArrayList<ByteArray>())

    /** True only on a device this beta should even attempt (rooted Exynos/Shannon, beta flag on). */
    fun canAttempt(): Boolean =
        ExynosBackend.CAPTURE_BETA &&
            ModemChipset.classify() == ModemChipset.Vendor.EXYNOS &&
            modemRootAvailable()

    fun start(): Boolean {
        bestTdd = null
        nrSamples.clear()
        running = transport.start(
            onNrRrc = { f ->
                val payload = SdmParser.nrRrcPayloadUnparsed(f.payload)
                if (nrSamples.size < MAX_SAMPLES) nrSamples.add(payload)
                // Best-effort: the NR inner container offset isn't confirmed, so try decoding the raw
                // payload as SIB1 then RRCReconfiguration. A success means we're already extracting a
                // real TDD config; a failure is fine — the samples + stats let us find the offset.
                (NrRrcTdd.decode(1, payload) ?: NrRrcTdd.decode(0, payload))?.let { bestTdd = it }
            },
            onLteRrc = { /* counted in summary; not used for the NR TDD decode */ },
        )
        return running
    }

    fun stop() {
        running = false
        transport.stop()
    }

    fun summary(): SdmTransport.CaptureSummary = transport.summary()

    /** A decoded TDD config, if the best-effort decode already succeeded (unlikely pre-validation). */
    fun decodedTdd(): NrRrcTdd.Decoded? = bestTdd

    fun unavailableReason(): String? = transport.unavailableReason

    private companion object {
        const val MAX_SAMPLES = 50
    }
}
