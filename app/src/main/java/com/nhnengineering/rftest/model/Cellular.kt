package com.nhnengineering.rftest.model

/**
 * Cellular measurement types.
 *
 * **Validation status: unproven.** Everything here and in `CellularCollector` was written against
 * 3GPP specifications and the Android telephony documentation, with no live SIM to test against.
 * `BandMapping` is the exception — it is pure arithmetic and is covered by unit tests.
 *
 * Treat the first live session as a debugging exercise, not as data. Every prior collector in this
 * project produced believable wrong numbers on first contact with reality, and there is no reason
 * to expect this one to behave differently.
 */

/**
 * Radio access technology, resolved properly rather than from the status-bar icon.
 *
 * The distinction that matters: under **NSA** the device is registered on an LTE anchor with an NR
 * secondary cell group added, so the registered cell reported by the framework is LTE and the NR
 * carrier may not appear at all. Under **SA** the device is registered directly on NR. Conflating
 * them makes an NSA venue look like it has no 5G, or an SA venue look like it has no LTE.
 */
enum class Rat(val label: String) {
    NR_SA("5G SA"),
    NR_NSA("5G NSA"),
    LTE("LTE"),
    WCDMA("WCDMA"),
    GSM("GSM"),
    UNKNOWN("unknown"),
    NO_SERVICE("no service"),
}

/** `NetworkRegistrationInfo.getNrState()`. CONNECTED means an NR secondary cell group is active. */
enum class NrState(val label: String) {
    NONE("none"),
    RESTRICTED("restricted"),
    NOT_RESTRICTED("available, not connected"),
    CONNECTED("connected"),
    UNKNOWN("unknown"),
}

/** SIM presence, reported explicitly because "no SIM" and "no coverage" are different findings. */
enum class SimState(val label: String) {
    READY("ready"),
    ABSENT("no SIM"),
    LOCKED("locked"),
    NOT_READY("not ready"),
    UNKNOWN("unknown"),
}

data class LteCell(
    val registered: Boolean,
    /** E-UTRAN Cell Identity, 28 bits. */
    val ci: Int?,
    /** Derived: the upper 20 bits of CI. */
    val enbId: Int?,
    /** Derived: the lower 8 bits of CI. */
    val sectorId: Int?,
    val pci: Int?,
    val tac: Int?,
    val earfcn: Int?,
    val band: Int?,
    val bandLabel: String?,
    val dlFreqMhz: Double?,
    val bandwidthKhz: Int?,
    val rsrpDbm: Int?,
    val rsrqDb: Int?,
    val rssnrDb: Int?,
    val rssiDbm: Int?,
    val cqi: Int?,
    /** Timing advance. Distance to the eNB is roughly TA x 78.125 m. */
    val timingAdvance: Int?,
    val mcc: String?,
    val mnc: String?,
    val operator: String?,
)

data class NrCell(
    val registered: Boolean,
    /** NR Cell Identity, 36 bits. gNB ID length is operator-configured, so it is not split here. */
    val nci: Long?,
    val pci: Int?,
    val tac: Int?,
    val nrarfcn: Int?,
    /** From `CellIdentityNr.getBands()` when the modem supplies it; otherwise derived. */
    val bands: List<String>,
    val bandLabel: String?,
    /**
     * Set when the modem's reported band contradicts the band implied by its own channel number.
     *
     * Observed live on T-Mobile 5G SA: a registered cell reporting `mBands=[25]` while its
     * NR-ARFCN mapped to 2606.55 MHz, which is n41 — n25 spans 1930–1995 MHz. One of the two
     * fields is stale or wrong and the handset does not say which. A report stating "n25 at
     * 2606 MHz" is internally incoherent, so the disagreement is carried rather than resolved.
     */
    val bandConflict: String?,
    val dlFreqMhz: Double?,
    val ssRsrpDbm: Int?,
    val ssRsrqDb: Int?,
    val ssSinrDb: Int?,
    val csiRsrpDbm: Int?,
    val csiRsrqDb: Int?,
    val csiSinrDb: Int?,
    val mcc: String?,
    val mnc: String?,
    val operator: String?,
)

/** A neighbour cell, flattened across technologies for logging. */
data class NeighborCell(
    val rat: String,
    val pci: Int?,
    val channel: Int?,
    val band: String?,
    val rsrpDbm: Int?,
    val rsrqDb: Int?,
    /**
     * How long ago this neighbour was actually observed, in milliseconds. Zero means it was in
     * the measurement report for this very sample.
     *
     * Weak neighbours near the detection floor genuinely appear and disappear between reports —
     * that is real radio behaviour, not a scanning artefact, and it is exactly what makes a live
     * neighbour list unreadable while walking. A short retention window keeps the display stable,
     * and recording the age means nothing is invented: an analyst can filter to `age_ms == 0` for
     * only what was seen in that sample.
     */
    val ageMs: Long = 0,
)

/** Which role a configured carrier plays. Secondary means carrier aggregation is active. */
enum class CarrierRole(val label: String) {
    PRIMARY("primary"),
    SECONDARY("secondary"),
    UNKNOWN("unknown"),
}

/**
 * One carrier the modem currently has configured, from `PhysicalChannelConfig`.
 *
 * ## Why this is worth the privileged install
 *
 * `getAllCellInfo()` describes the cell the handset is camped on. This describes what the modem
 * has actually configured, which is not the same thing: an aggregated deployment has a primary
 * carrier and one or more secondary ones, and only this surface names them. A survey that reports
 * the primary alone describes a fraction of the radio the user is actually being served by.
 *
 * It also answers a question no other surface on this platform can: **which bands is this handset
 * genuinely using**, component by component, rather than which one it happens to be camped on.
 *
 * The listener behind it needs `READ_PRECISE_PHONE_STATE`, which is `signature|privileged` --
 * available to an app installed in `/system/priv-app`, not to an ordinary one. So this is empty
 * on a normal install and populated on a survey handset, and [CellularSample.channelConfigAvailable]
 * is what separates those two cases.
 */
data class ComponentCarrier(
    val role: CarrierRole,
    /** True when this carrier is NR. The numbering spaces differ -- n41 is not band 41. */
    val isNr: Boolean,
    val band: Int?,
    val downlinkArfcn: Int?,
    val downlinkFrequencyKhz: Int?,
    val downlinkBandwidthKhz: Int?,
    val uplinkBandwidthKhz: Int?,
    val pci: Int?,
) {
    /** "n41" or "B2", or null when the modem did not report a band. */
    val bandLabel: String?
        get() = band?.let { if (isNr) "n$it" else "B$it" }
}

data class CellularSample(
    val simState: SimState,
    val rat: Rat,
    val nrState: NrState,
    /** `TelephonyDisplayInfo.getOverrideNetworkType()` — what the status bar shows. Marketing-layer,
     *  logged for comparison against the actual registration, never used as the RAT. */
    val overrideNetworkType: String?,
    val isRoaming: Boolean,
    val mcc: String?,
    val mnc: String?,
    val operator: String?,
    val lte: LteCell?,
    val nr: NrCell?,
    val neighbors: List<NeighborCell>,
    /**
     * Bandwidth in kHz of every currently active carrier, from `ServiceState.getCellBandwidths()`.
     *
     * A list rather than a single value because aggregation is the normal case: two entries means
     * two aggregated carriers, which is a fact about the deployment worth recording. Observed on
     * T-Mobile n41 as 100 MHz plus 90 MHz, independently corroborating the SSB analysis that found
     * two SSB positions sharing one PCI plan.
     *
     * Empty when the platform declines to say, which is not the same as a single carrier — so it
     * is empty rather than defaulted to anything.
     */
    val cellBandwidthsKhz: List<Int> = emptyList(),
    /**
     * True when the app lacks READ_PHONE_STATE, so registration and NR state could not be read.
     * Surfaced rather than left as silent nulls — an empty field that means "not permitted" reads
     * identically to one meaning "no coverage", and only one of those is a finding.
     */
    val permissionLimited: Boolean = false,
    /**
     * Every carrier the modem has configured, primary and aggregated secondaries.
     *
     * Empty on an ordinary install, where the listener behind it cannot be registered. Read it
     * together with [channelConfigAvailable] and never alone: an empty list means "not permitted
     * to look" far more often than it means "one carrier", and reporting the second when the
     * first is true is how this project already put a confident 0.0% overlap in a client report.
     */
    /**
     * Whether any neighbour has been reported at any point since collection started.
     *
     * Distinguishes the two things a neighbour count of zero can mean. "No neighbour right now"
     * is a measurement of the site. "This handset has never reported one" is a fact about the
     * instrument, and on some handsets it never will -- verified on the OnePlus, where all three
     * Android cell surfaces return the serving cell alone while a diagnostic tool reading the
     * modem lists two neighbours alongside it.
     *
     * A bare 0 in front of an engineer reads as the first and is often the second.
     */
    val neighboursEverSeen: Boolean = false,
    val carriers: List<ComponentCarrier> = emptyList(),
    /**
     * Whether the privileged channel-config listener is actually registered.
     *
     * False is the normal case and not an error -- it means the app is not installed privileged,
     * so [carriers] carries no information at all. Anything reporting on aggregation must branch
     * on this before it says a word about how many carriers were in use.
     */
    val channelConfigAvailable: Boolean = false,
) {
    /** Primary serving-cell coverage KPI, whichever radio is serving. */
    val servingRsrpDbm: Int? get() = nr?.ssRsrpDbm ?: lte?.rsrpDbm

    val servingBandLabel: String? get() = nr?.bandLabel ?: lte?.bandLabel
}

/**
 * Colour scale for LTE RSRP and NR SS-RSRP.
 *
 * Deliberately separate from [RssiBucket], which is Wi-Fi-tight. −70 dBm is marginal on Wi-Fi and
 * unremarkable on cellular; sharing one scale would paint a healthy DAS as a problem.
 * Thresholds follow common RAN design practice for in-building coverage.
 */
enum class RsrpBucket(val label: String, val minDbm: Int, val argb: Int) {
    EXCELLENT("≥ −85 dBm", -85, 0xFF2E7D32.toInt()),
    GOOD("−86 to −95", -95, 0xFF689F38.toInt()),
    FAIR("−96 to −105", -105, 0xFFF9A825.toInt()),
    POOR("−106 to −115", -115, 0xFFEF6C00.toInt()),
    BAD("< −115 dBm", Int.MIN_VALUE, 0xFFC62828.toInt()),
    ;

    companion object {
        fun of(rsrpDbm: Int?): RsrpBucket? {
            if (rsrpDbm == null) return null
            return entries.firstOrNull { rsrpDbm >= it.minDbm } ?: BAD
        }
    }
}
