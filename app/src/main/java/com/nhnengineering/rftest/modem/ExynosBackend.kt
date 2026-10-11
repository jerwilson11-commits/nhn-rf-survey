package com.nhnengineering.rftest.modem

import java.io.File

/**
 * The Exynos / Samsung Shannon backend: SDM over `/dev/umts_dm0` ([SdmTransport] + [SdmParser]),
 * feeding the shared `NrRrcDecoder` / `TddProfile` pipeline. Covers Tensor Pixels (Shannon modem) and
 * international Exynos Samsung. See `docs/exynos-spike.md`.
 *
 * ## Gated OFF until hardware-validated ([VALIDATED])
 *
 * The transport and parser are written from the SCAT reference but **not yet validated on a rooted
 * Shannon device** (RQ5 — our only Pixels are Verizon-locked and unrootable). Until a real SDM capture
 * decodes end-to-end, [availability] returns [BackendAvailability.Unsupported] so the public build
 * never sells a rooted Exynos user a feature we haven't confirmed — exactly what `ProModemCapability`
 * exists to prevent. The real rooted + node check is written below and behind the flag; flip
 * [VALIDATED] to true once a capture passes.
 */
object ExynosBackend : ModemBackend {

    /** Flip to true only after a real SDM capture decodes end-to-end on a rooted Shannon device. */
    const val VALIDATED = false

    /** The Shannon Diagnostic Monitor node, confirmed on a Pixel 6 Pro (raven). */
    const val DM_NODE = "/dev/umts_dm0"

    override val vendor: ModemChipset.Vendor = ModemChipset.Vendor.EXYNOS

    override fun availability(): BackendAvailability {
        if (!VALIDATED) {
            return BackendAvailability.Unsupported(
                "Exynos/Shannon modem tools are in development and not yet validated on hardware.",
            )
        }
        if (!modemRootAvailable()) return BackendAvailability.NeedsRoot
        // RQ1 confirmed the node exists but is system:system under SELinux Enforcing, so presence is a
        // necessary (not sufficient) signal; the transport self-reports if the open/enable still fails.
        val present = runCatching { File(DM_NODE).exists() }.getOrDefault(false)
        return if (present) {
            BackendAvailability.Available
        } else {
            BackendAvailability.Unsupported("No Shannon diagnostic node ($DM_NODE) on this device.")
        }
    }
}
