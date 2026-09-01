package com.nhnengineering.rftest.model

/**
 * Pass/fail limits evaluated on every sample.
 *
 * The point of alarms on a walk test is that you find out at the spot, not back at the desk. A
 * coverage hole discovered during analysis means a return visit; one that beeps while you are
 * standing in it gets investigated immediately.
 */
data class Thresholds(
    val enabled: Boolean = false,
    val audible: Boolean = true,
    /** Alarm when RSSI falls below this. */
    val rssiMinDbm: Int? = -75,
    /** Alarm when this many other APs share the serving channel. */
    val coChannelMax: Int? = null,
    /** Alarm when the GPS fix is worse than this — a bad fix poisons the map, not just one row. */
    val gpsAccuracyMaxM: Int? = null,
) {
    fun evaluate(wifi: WifiSample?, fix: GeoPoint?): List<Breach> {
        if (!enabled) return emptyList()
        val out = mutableListOf<Breach>()

        rssiMinDbm?.let { limit ->
            val v = wifi?.rssiDbm
            // A null reading is not a breach. "No measurement" and "bad measurement" are
            // different, and conflating them puts false failures in a report.
            if (v != null && v < limit) out += Breach("RSSI", "$v dBm", "< $limit dBm")
        }
        coChannelMax?.let { limit ->
            val v = wifi?.coChannelCount
            if (v != null && v > limit) out += Breach("Co-channel", "$v APs", "> $limit")
        }
        gpsAccuracyMaxM?.let { limit ->
            val v = fix?.accuracyM
            if (v != null && v > limit) out += Breach("GPS accuracy", "±${v.toInt()} m", "> ±$limit m")
        }
        return out
    }
}

data class Breach(val kpi: String, val value: String, val limit: String) {
    val label: String get() = "$kpi $value ($limit)"
}
