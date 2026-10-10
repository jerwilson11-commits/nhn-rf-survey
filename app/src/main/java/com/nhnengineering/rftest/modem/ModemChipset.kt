package com.nhnengineering.rftest.modem

import android.os.Build
import java.io.File

/**
 * Best-effort classification of the phone's modem vendor, for wording a failure message
 * accurately -- never for deciding whether a feature works.
 *
 * Every root-gated feature in this app (band lock, technology lock, modem neighbours, SIB1
 * capture) already fails gracefully by trying its Qualcomm-specific mechanism and catching the
 * failure -- that empirical check stays the sole source of truth for availability. What was
 * missing is that "the mechanism failed because there's no root" and "the mechanism failed
 * because this modem was never Qualcomm to begin with" collapsed into the same message, and the
 * message picked "no root" even on a rooted Samsung/MediaTek/Unisoc phone. This exists only to
 * pick the right words in [ModemFailureMessages] once a failure has already happened.
 */
object ModemChipset {

    enum class Vendor(val label: String) {
        QUALCOMM("Qualcomm"),
        EXYNOS("Samsung Exynos / Shannon"),
        MEDIATEK("MediaTek"),
        UNISOC("Unisoc"),
        OTHER_OR_UNKNOWN("an unrecognised"),
    }

    /** Raw signals, factored out so [classify] stays a pure, testable decision tree. */
    data class Signals(
        val socManufacturer: String?,
        val hardware: String,
        val board: String,
        val qrtrLookupPresent: Boolean,
    )

    private const val QRTR_LOOKUP_PATH = "/vendor/bin/qrtr-lookup"

    @Volatile
    private var cached: Vendor? = null

    /** Reads the live signals. Impure: `Build.*` and a local file stat, no shell call. */
    fun currentSignals(): Signals = Signals(
        socManufacturer = Build.SOC_MANUFACTURER?.takeIf { it != Build.UNKNOWN },
        hardware = Build.HARDWARE,
        board = Build.BOARD,
        qrtrLookupPresent = runCatching { File(QRTR_LOOKUP_PATH).exists() }.getOrDefault(false),
    )

    /**
     * The decision tree, weakest assumption first:
     *
     * 1. `Build.SOC_MANUFACTURER` (API 31+, always compiled -- this app's `minSdk` is 31) when the
     *    OEM actually populated it -- the strongest signal available.
     * 2. `/vendor/bin/qrtr-lookup` existing -- positive-only. Its absence proves nothing (SELinux
     *    can deny the stat on a genuine Qualcomm phone too), so it only ever pushes toward
     *    [Vendor.QUALCOMM], never away from it.
     * 3. `Build.HARDWARE`/`Build.BOARD` codename heuristics -- best-effort pattern matching against
     *    known vendor board-naming conventions, not a documented or stable contract.
     * 4. [Vendor.OTHER_OR_UNKNOWN] otherwise.
     */
    fun classify(signals: Signals): Vendor {
        signals.socManufacturer?.lowercase()?.let { m ->
            when {
                "qualcomm" in m -> return Vendor.QUALCOMM
                "samsung" in m -> return Vendor.EXYNOS
                // Google Tensor (Pixel 6+) pairs a Google AP with a Samsung "Shannon" modem
                // (g5123b/g5300), so for *modem*-vendor purposes a Tensor device is Exynos/Shannon.
                // Confirmed on a Pixel 6 Pro (raven, gs101): SOC_MANUFACTURER reports "Google", not
                // "Samsung", and the DM diag node is /dev/umts_dm0 over the cpif driver -- the
                // Samsung modem stack. Older Qualcomm Pixels (<=5) report "Qualcomm" and are caught
                // above, so this never mis-flags them.
                "google" in m -> return Vendor.EXYNOS
                "mediatek" in m -> return Vendor.MEDIATEK
                "unisoc" in m || "spreadtrum" in m -> return Vendor.UNISOC
            }
        }
        if (signals.qrtrLookupPresent) return Vendor.QUALCOMM

        val codename = "${signals.hardware} ${signals.board}".lowercase()
        return when {
            Regex("""\bqcom\b|lahaina|kalama|taro|kona|lito|bengal|\bsm\d{4,5}\b|\bsdm\d{3}\b""")
                .containsMatchIn(codename) -> Vendor.QUALCOMM
            // s5e*/universal*/exynos* are Samsung's own board codenames; gs\d{3}/zuma/tensor are
            // Google Tensor platforms (gs101/gs201/zuma), whose modem is Samsung Shannon -- a
            // weak fallback for when SOC_MANUFACTURER is unavailable.
            Regex("""\bs5e\w*|\buniversal\w*|\bexynos\w*|\bgs\d{3}\w*|\bzuma\w*|\btensor\w*""")
                .containsMatchIn(codename) -> Vendor.EXYNOS
            Regex("""\bmt6\w*|\bmt8\w*""").containsMatchIn(codename) -> Vendor.MEDIATEK
            Regex("""\bsp\d{4,}|\bums\d{3,}|\bsc\d{4,}""").containsMatchIn(codename) -> Vendor.UNISOC
            else -> Vendor.OTHER_OR_UNKNOWN
        }
    }

    /** [classify] against the live device, computed once and cached -- static for the process. */
    fun classify(): Vendor = cached ?: classify(currentSignals()).also { cached = it }
}
