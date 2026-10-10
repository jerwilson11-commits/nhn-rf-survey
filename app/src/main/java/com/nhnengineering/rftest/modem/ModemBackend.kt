package com.nhnengineering.rftest.modem

import java.util.concurrent.TimeUnit

/**
 * Whether a vendor's deep, root-gated modem features can actually run on this device right now.
 *
 * This is the one axis that is already vendor-agnostic today, so it is the first thing the
 * [ModemBackend] abstraction carries. [ProModem.capability] maps it onto the UI-facing
 * [ProModemCapability].
 */
sealed interface BackendAvailability {
    /** Transport reachable and prerequisites met -- the deep features can run. */
    data object Available : BackendAvailability

    /** This chipset could be served by this backend, but root isn't granted. */
    data object NeedsRoot : BackendAvailability

    /** This backend cannot serve this device; [reason] is wording-ready for the user. */
    data class Unsupported(val reason: String) : BackendAvailability
}

/**
 * A per-vendor implementation of the deep, root-gated modem features -- band lock, technology lock,
 * neighbour reads, and live RRC/SIB1 capture.
 *
 * Today only [QualcommBackend] is real. MediaTek and Exynos/Shannon backends land here as their
 * research spikes conclude (see `docs/multi-chipset-roadmap.md` and `docs/mediatek-spike.md`). The
 * ASN.1 decoder (`libnrrrc`), the `TddProfile` pipeline, the no-root paste-import path, and every
 * Field feature are vendor-agnostic and live *outside* this interface -- they already work on any
 * device.
 *
 * ## Why the per-feature accessors are not on this interface yet
 *
 * The obvious next members here are `bandLock()`, `technologyLock()`, `neighbours()`, and
 * `rrcCapture()`. They are deliberately omitted until the MediaTek and Exynos spikes land: their
 * cross-vendor shapes should be generalized from *at least two* real transports, not reverse-derived
 * from Qualcomm's QMI alone (whose LTE / NR-SA / NR-NSA band split, `Outcome` messaging, and
 * "mode preference rides along" constraints are QMI-specific and may not fit another vendor). The
 * existing controllers ([BandLockController], [TechnologyLockController], [ModemNeighbourSource],
 * [ModemNrStream]) stay the Qualcomm implementation and are migrated behind extracted interfaces
 * once a second backend exists to validate the shape. What this interface carries now is the part
 * that is already correct and already gates the UI: [availability].
 */
interface ModemBackend {
    /** The modem vendor this backend serves. */
    val vendor: ModemChipset.Vendor

    /**
     * Whether this backend's deep features can run on this device right now. May probe `su` and the
     * diagnostic transport, so call it off the main thread (as [ProModem.capability] does).
     */
    fun availability(): BackendAvailability
}

/**
 * The Qualcomm backend: QMI over QRTR (band/technology lock, neighbours) and Qualcomm DIAG
 * (SIB1/TDD, RRC/NAS OTA capture). The only backend implemented today. Availability is purely a
 * root question -- the QMI/DIAG mechanisms themselves self-report if they turn out unreachable on a
 * device the classifier mislabelled as Qualcomm, so this never pre-emptively declares a Qualcomm
 * (or unrecognised) modem unsupported.
 */
object QualcommBackend : ModemBackend {
    override val vendor: ModemChipset.Vendor = ModemChipset.Vendor.QUALCOMM

    override fun availability(): BackendAvailability =
        if (modemRootAvailable()) BackendAvailability.Available else BackendAvailability.NeedsRoot
}

/**
 * The registry that routes a classified modem [ModemChipset.Vendor] to the backend that serves it.
 *
 * Adding a vendor's deep-feature support is a one-line change here plus the new [ModemBackend]
 * implementation -- the capability gate ([ProModem.capability]) and the UI need no further change,
 * because they already read availability through this registry.
 */
object ModemBackends {

    /** The backend that serves [vendor], or null if none is implemented yet. */
    fun forVendor(vendor: ModemChipset.Vendor): ModemBackend? = when (vendor) {
        ModemChipset.Vendor.QUALCOMM,
        // An unrecognised modem is tried optimistically on the Qualcomm path: a genuine Qualcomm
        // device the classifier couldn't positively ID is never pre-emptively declared unsupported
        // -- the QMI/DIAG mechanisms self-report if they turn out unreachable.
        ModemChipset.Vendor.OTHER_OR_UNKNOWN -> QualcommBackend
        // MediaTek, Exynos/Shannon, Unisoc: research-first, no backend yet. Non-root users keep the
        // paste-import path and every Field feature regardless.
        ModemChipset.Vendor.MEDIATEK,
        ModemChipset.Vendor.EXYNOS,
        ModemChipset.Vendor.UNISOC -> null
    }

    /** The backend for the live device, or null if this chipset has no backend yet. */
    fun current(): ModemBackend? = forVendor(ModemChipset.classify())
}

/**
 * Whether `su` grants uid 0 within a short timeout -- the shared root probe every backend needs.
 * Blocking; callers run it off the main thread.
 */
internal fun modemRootAvailable(): Boolean = try {
    val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
    val line = p.inputStream.bufferedReader().use { it.readLine() }
    if (!p.waitFor(3, TimeUnit.SECONDS)) {
        p.destroyForcibly()
        false
    } else {
        line?.contains("uid=0") == true
    }
} catch (e: Exception) {
    false
}
