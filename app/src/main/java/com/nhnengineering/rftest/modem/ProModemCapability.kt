package com.nhnengineering.rftest.modem

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether this device can actually run Pro's root-gated modem tools (band lock, technology lock,
 * VoNR, live SIB1/TDD decode, NR neighbours over QMI) -- a capability axis that is independent of
 * whether the user has paid for Pro.
 *
 * The upgrade screen uses this so it never sells Pro's modem tools to a device that physically can't
 * run them without saying so first: an honest caveat up front avoids refunds and protects trust with
 * the expert buyers this app targets.
 */
enum class ProModemCapability { CAPABLE, NEEDS_ROOT, WRONG_CHIPSET, UNKNOWN }

/** The resolved capability plus the modem vendor's label, for messaging. */
data class ProCapability(
    val state: ProModemCapability,
    val vendorLabel: String,
)

object ProModem {

    /** Resolves the capability off the main thread (reads modem identity, probes `su`). */
    suspend fun capability(): ProCapability = withContext(Dispatchers.IO) {
        val vendor = ModemChipset.classify()
        // Routed through the per-vendor backend registry so that adding a MediaTek / Exynos backend
        // automatically flips its chipset from WRONG_CHIPSET to CAPABLE/NEEDS_ROOT with no change
        // here (see ModemBackends). A vendor with no backend yet resolves to null -> WRONG_CHIPSET,
        // exactly as before. The vendor label still comes from the classifier, for messaging.
        val state = when (ModemBackends.forVendor(vendor)?.availability()) {
            BackendAvailability.Available -> ProModemCapability.CAPABLE
            BackendAvailability.NeedsRoot -> ProModemCapability.NEEDS_ROOT
            is BackendAvailability.Unsupported, null -> ProModemCapability.WRONG_CHIPSET
        }
        ProCapability(state, vendor.label)
    }
}
