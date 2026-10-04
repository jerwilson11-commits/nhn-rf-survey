package com.nhnengineering.rftest.modem

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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
        val state = when (vendor) {
            ModemChipset.Vendor.EXYNOS,
            ModemChipset.Vendor.MEDIATEK,
            ModemChipset.Vendor.UNISOC -> ProModemCapability.WRONG_CHIPSET
            // Qualcomm or unrecognised: root is the deciding factor. "Unrecognised" is treated
            // optimistically (checked for root, not declared wrong-chipset) so a Qualcomm device the
            // classifier couldn't positively ID is never wrongly told its modem is unsupported -- the
            // modem features themselves self-report if QMI turns out unreachable.
            else -> if (isRooted()) ProModemCapability.CAPABLE else ProModemCapability.NEEDS_ROOT
        }
        ProCapability(state, vendor.label)
    }

    private fun isRooted(): Boolean = try {
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
}
