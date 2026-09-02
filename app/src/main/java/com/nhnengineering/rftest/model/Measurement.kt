package com.nhnengineering.rftest.model

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo

/**
 * Core measurement types.
 *
 * Deliberately radio-agnostic at the top level: [MeasurementSample] is the unit the sampling loop,
 * database, CSV writer, and map overlay all deal in. Wi-Fi is filled in now; cellular plugs into
 * the same envelope in Phase 5 without any of those four components changing.
 */

// ---------------------------------------------------------------------------
// Location
// ---------------------------------------------------------------------------

/**
 * A GPS fix. Everything past [longitude] is nullable because Android genuinely omits these
 * depending on the provider and the fix quality — an indoor fix often has no speed or bearing.
 */
data class GeoPoint(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Double?,
    val accuracyM: Float?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val fixTimeUtcMillis: Long,
    /** Which provider produced this fix — "gps" or "fused". Recorded because they are not
     *  equivalent: a fused fix may be smoothed or derived from Wi-Fi/cell, which is fine for
     *  navigation and misleading in a drive test. The log should say which one it was. */
    val provider: String,
)

// ---------------------------------------------------------------------------
// Wi-Fi
// ---------------------------------------------------------------------------

enum class WifiBand(val label: String) {
    BAND_2_4("2.4 GHz"),
    BAND_5("5 GHz"),
    BAND_6("6 GHz"),
    UNKNOWN("unknown"),
}

enum class WifiStandard(val label: String) {
    LEGACY("802.11a/b/g"),
    N("802.11n (Wi-Fi 4)"),
    AC("802.11ac (Wi-Fi 5)"),
    AX("802.11ax (Wi-Fi 6/6E)"),
    AD("802.11ad"),
    BE("802.11be (Wi-Fi 7)"),
    UNKNOWN("unknown"),
    ;

    companion object {
        /** Maps the `ScanResult.WIFI_STANDARD_*` int constants. API 30+ for the field itself. */
        fun fromScanResultConstant(value: Int): WifiStandard = when (value) {
            ScanResult.WIFI_STANDARD_LEGACY -> LEGACY
            ScanResult.WIFI_STANDARD_11N -> N
            ScanResult.WIFI_STANDARD_11AC -> AC
            ScanResult.WIFI_STANDARD_11AX -> AX
            ScanResult.WIFI_STANDARD_11AD -> AD
            // Added API 33. Safe to reference despite minSdk 31: constants are resolved against
            // compileSdk and inlined at compile time, so this is just an int comparison at runtime.
            // (Calling an API 33 *method* on a 31 device would crash — constants are different.)
            ScanResult.WIFI_STANDARD_11BE -> BE
            else -> UNKNOWN
        }
    }
}

enum class WifiSecurity(val label: String) {
    OPEN("Open"),
    WEP("WEP"),
    WPA2_PSK("WPA2-PSK"),
    WPA_EAP("WPA-Enterprise"),
    WPA3_SAE("WPA3-SAE"),
    WPA3_ENTERPRISE("WPA3-Enterprise"),
    OWE("OWE (Enhanced Open)"),
    UNKNOWN("unknown"),
    ;

    companion object {
        /** Maps the `WifiInfo.SECURITY_TYPE_*` constants returned by `getCurrentSecurityType()`. */
        fun fromWifiInfoConstant(value: Int): WifiSecurity = when (value) {
            WifiInfo.SECURITY_TYPE_OPEN -> OPEN
            WifiInfo.SECURITY_TYPE_WEP -> WEP
            WifiInfo.SECURITY_TYPE_PSK -> WPA2_PSK
            WifiInfo.SECURITY_TYPE_EAP -> WPA_EAP
            WifiInfo.SECURITY_TYPE_SAE -> WPA3_SAE
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE,
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> WPA3_ENTERPRISE
            WifiInfo.SECURITY_TYPE_OWE -> OWE
            else -> UNKNOWN
        }

        /**
         * Parses the `ScanResult.capabilities` string, e.g. `[WPA2-PSK-CCMP][ESS]`. Scan results
         * carry no structured security field, so string matching is the only option here.
         * Ordering matters — WPA3 markers must be tested before WPA2.
         */
        fun fromCapabilitiesString(capabilities: String): WifiSecurity = when {
            capabilities.contains("SAE") -> WPA3_SAE
            capabilities.contains("OWE") -> OWE
            capabilities.contains("EAP_SUITE_B") -> WPA3_ENTERPRISE
            capabilities.contains("EAP") -> WPA_EAP
            capabilities.contains("WPA2") || capabilities.contains("RSN") -> WPA2_PSK
            capabilities.contains("WEP") -> WEP
            capabilities.contains("ESS") -> OPEN
            else -> UNKNOWN
        }
    }
}

/** One observed access point from a scan. */
data class WifiNeighbor(
    val ssid: String?,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val channel: Int?,
    val band: WifiBand,
    val channelWidthMhz: Int?,
    val standard: WifiStandard,
    val security: WifiSecurity,
    /**
     * How long ago this AP was actually observed. Not every AP appears in every scan — the OS
     * sweeps subsets of channels — so entries persist across scans and age out. Anything with a
     * large age was seen recently but not *now*, which matters while walking.
     */
    val ageMs: Long,
)

/**
 * A full Wi-Fi observation: the connected AP plus its RF neighbourhood.
 *
 * [neighborScanAgeMs] exists because connected-AP values and scan results refresh at different
 * rates — the OS hard-throttles scans to 4 per 2 minutes. Recording the age keeps the CSV honest
 * rather than implying the neighbour list is as fresh as the RSSI beside it.
 */
data class WifiSample(
    val ssid: String?,
    val bssid: String?,
    val rssiDbm: Int?,
    val frequencyMhz: Int?,
    val channel: Int?,
    val band: WifiBand,
    val channelWidthMhz: Int?,
    val standard: WifiStandard,
    val security: WifiSecurity,
    val txLinkMbps: Int?,
    val rxLinkMbps: Int?,
    val maxSupportedTxMbps: Int?,
    val neighbors: List<WifiNeighbor>,
    val neighborScanAgeMs: Long?,
    /** Other BSSIDs sharing our exact primary channel, above the RSSI floor. */
    val coChannelCount: Int,
    /** Other BSSIDs whose occupied spectrum overlaps ours on a different primary channel. */
    val adjacentChannelCount: Int,
)

// ---------------------------------------------------------------------------
// Throughput
// ---------------------------------------------------------------------------

/**
 * One completed speed test.
 *
 * Every field is nullable because every one of them can legitimately fail to be measured, and a
 * zero would read as a real result. [lossPct] in particular is null far more often than not — see
 * the note on [SpeedTester.measureLoss].
 */
data class ThroughputSample(
    val downloadMbps: Double?,
    val uploadMbps: Double?,
    val latencyMedianMs: Double?,
    val latencyMinMs: Double?,
    val latencyMaxMs: Double?,
    /** Mean absolute difference between successive latency samples. */
    val jitterMs: Double?,
    /** ICMP loss, or null when ICMP was unavailable. Never inferred from HTTP failures. */
    val lossPct: Double?,
    val server: String,
    val error: String? = null,
)

// ---------------------------------------------------------------------------
// Sample envelope
// ---------------------------------------------------------------------------

/**
 * One row of the log. [wifi] and (from Phase 5) `cellular` are independently nullable so a session
 * can carry either radio or both.
 */
data class MeasurementSample(
    val sessionId: String,
    val sequence: Long,
    val timestampUtcMillis: Long,
    val location: GeoPoint?,
    val wifi: WifiSample?,
    val cellular: CellularSample? = null,
    /** Operator-placed indoor position, where GPS cannot help. */
    val indoor: IndoorPosition? = null,
    /**
     * Free-standing area label for an outdoor or unplanned walk — "Indoor", "Driveway", "Street".
     *
     * Separate from [IndoorPosition.label] because that one requires a floorplan, and a GPS walk
     * has none. Both feed the same `waypoint` CSV column; the indoor label wins where both exist,
     * since it is the more specific of the two.
     */
    val areaLabel: String? = null,
    /** Present only on the sample written when a speed test completes, so the throughput row
     *  carries the position and RF conditions the test actually ran under. */
    val throughput: ThroughputSample? = null,
    val note: String? = null,
)

// ---------------------------------------------------------------------------
// Frequency math
// ---------------------------------------------------------------------------

/**
 * Channel and band derivation. Android reports frequency in MHz and leaves the rest to us.
 *
 * Pure functions with no Android dependencies, so these are unit-testable on the JVM without a
 * device — worth having tests here, because an off-by-one in channel math is invisible in the UI
 * and corrupts every exported file.
 */
object WifiFrequency {

    private const val RSSI_FLOOR_DBM = -85

    fun bandOf(frequencyMhz: Int): WifiBand = when (frequencyMhz) {
        in 2401..2495 -> WifiBand.BAND_2_4
        in 5150..5895 -> WifiBand.BAND_5
        in 5925..7125 -> WifiBand.BAND_6
        else -> WifiBand.UNKNOWN
    }

    /**
     * Returns the 802.11 channel number, or null if the frequency is outside known bands.
     *
     * The 6 GHz cases must be tested before any naive `frequency > 5000` logic — treating 6 GHz as
     * 5 GHz silently mislabels every 6E AP, and the Pixel 6 Pro is 6E capable.
     */
    fun channelOf(frequencyMhz: Int): Int? = when {
        frequencyMhz == 2484 -> 14                              // the 2.4 GHz special case
        frequencyMhz in 2401..2483 -> (frequencyMhz - 2407) / 5
        frequencyMhz in 5150..5895 -> (frequencyMhz - 5000) / 5
        frequencyMhz == 5935 -> 2                               // the 6 GHz special case
        frequencyMhz in 5925..7125 -> (frequencyMhz - 5950) / 5
        else -> null
    }

    /** Maps the `ScanResult.CHANNEL_WIDTH_*` constants to a width in MHz. */
    fun channelWidthMhz(scanResultConstant: Int): Int? = when (scanResultConstant) {
        ScanResult.CHANNEL_WIDTH_20MHZ -> 20
        ScanResult.CHANNEL_WIDTH_40MHZ -> 40
        ScanResult.CHANNEL_WIDTH_80MHZ -> 80
        ScanResult.CHANNEL_WIDTH_160MHZ -> 160
        // 80+80 is two non-contiguous 80 MHz segments. Reported as its 160 MHz total capacity;
        // the discontiguity does not affect the overlap maths below, which uses each segment.
        ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
        ScanResult.CHANNEL_WIDTH_320MHZ -> 320                  // API 34+, 802.11be
        else -> null
    }

    /**
     * The spectrum an AP actually occupies, in MHz.
     *
     * Uses `centerFreq0` when present — for anything wider than 20 MHz, `frequency` is the primary
     * 20 MHz channel, not the centre of the occupied bandwidth, so using it would place a 160 MHz
     * carrier in the wrong place.
     */
    fun occupiedSpan(scanResult: ScanResult): IntRange {
        val width = channelWidthMhz(scanResult.channelWidth) ?: 20
        val center = if (scanResult.centerFreq0 > 0) scanResult.centerFreq0 else scanResult.frequency
        val half = width / 2
        return (center - half)..(center + half)
    }

    /**
     * Counts APs sharing our exact primary channel, excluding ourselves.
     *
     * Co-channel and adjacent-channel counts are computed rather than read. They are the two
     * numbers that actually diagnose a bad Wi-Fi deployment, and no consumer app reports them.
     */
    fun countCoChannel(
        ours: ScanResult,
        all: List<ScanResult>,
        rssiFloorDbm: Int = RSSI_FLOOR_DBM,
    ): Int = all.count {
        it.BSSID != ours.BSSID && it.frequency == ours.frequency && it.level >= rssiFloorDbm
    }

    /**
     * Counts APs whose occupied spectrum overlaps ours from a *different* primary channel.
     *
     * On 2.4 GHz this catches the classic partial overlap between channels 1/3/6. On 5 and 6 GHz
     * it catches wide-channel deployments stepping on each other, which 20 MHz channel-number
     * comparison alone would miss entirely.
     */
    fun countAdjacentChannel(
        ours: ScanResult,
        all: List<ScanResult>,
        rssiFloorDbm: Int = RSSI_FLOOR_DBM,
    ): Int {
        val ourSpan = occupiedSpan(ours)
        return all.count { other ->
            if (other.BSSID == ours.BSSID) return@count false
            if (other.frequency == ours.frequency) return@count false   // that is co-channel
            if (other.level < rssiFloorDbm) return@count false
            val theirSpan = occupiedSpan(other)
            ourSpan.first <= theirSpan.last && theirSpan.first <= ourSpan.last
        }
    }
}
