package com.nhnengineering.rftest.model

/**
 * The one definition of the Wi-Fi RSSI colour scale.
 *
 * Shared deliberately between the on-screen plot and the KML/GeoJSON exporters. If the map on the
 * handset and the map the client opens in Google Earth use different thresholds, the same session
 * tells two different stories — and the discrepancy surfaces in a meeting, not in testing.
 *
 * Thresholds are Wi-Fi-tight. −70 dBm is already marginal on Wi-Fi, unlike cellular RSRP where it
 * is unremarkable. Phase 5 adds a separate scale for RSRP rather than reusing this one.
 */
enum class RssiBucket(
    val label: String,
    /** Inclusive lower bound in dBm. */
    val minDbm: Int,
    /** 0xAARRGGBB, the Android/Compose convention. */
    val argb: Int,
) {
    EXCELLENT("≥ −55 dBm", -55, 0xFF2E7D32.toInt()),
    GOOD("−56 to −65", -65, 0xFF689F38.toInt()),
    FAIR("−66 to −72", -72, 0xFFF9A825.toInt()),
    POOR("−73 to −80", -80, 0xFFEF6C00.toInt()),
    BAD("< −80 dBm", Int.MIN_VALUE, 0xFFC62828.toInt()),
    ;

    /**
     * KML wants `aabbggrr` — alpha, then **blue, green, red**, the reverse of the usual order.
     * Getting this wrong silently swaps red and blue, which on a coverage map means a strong
     * signal renders as a problem area. Worth the explicit conversion rather than a literal.
     */
    fun toKmlColor(): String {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return "%02x%02x%02x%02x".format(a, b, g, r)
    }

    companion object {
        fun of(rssiDbm: Int?): RssiBucket? {
            if (rssiDbm == null) return null
            return entries.firstOrNull { rssiDbm >= it.minDbm } ?: BAD
        }
    }
}
