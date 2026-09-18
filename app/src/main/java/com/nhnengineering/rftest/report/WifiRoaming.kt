package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint

/**
 * Finds where a client changed access point, and where it should have and didn't.
 *
 * ## Why this is the Wi-Fi finding worth having
 *
 * Coverage surveys answer "is there signal here". Roaming answers "does the connection survive
 * walking", which is the complaint an operator actually receives — a call that drops in the
 * corridor, a scanner that stalls between aisles, a video call that freezes at the lift. Those are
 * roaming faults on a network whose coverage map looks perfect.
 *
 * Two things are measurable from a walk:
 *
 * - **Roams**: the client moved from one BSSID to another, with the level at which it happened.
 *   A client roaming at −80 dBm is roaming too late; one that ping-pongs between two APs is
 *   roaming too eagerly.
 * - **Sticky clients**: the client stayed on an AP while a better one on the same SSID was
 *   available, by a wide margin, for a sustained stretch. This is the classic design fault, and it
 *   is invisible to a coverage map because the signal was fine — just from the wrong AP.
 *
 * ## What it cannot say
 *
 * The roam decision belongs to the client, not the network, so a sticky run is evidence about
 * *this handset's* behaviour. Another vendor's supplicant may roam differently on the same network.
 * The report says so; the finding is still worth having, because a building where one common client
 * goes sticky usually has APs too close together or a minimum basic rate set too low.
 */
object WifiRoaming {

    /**
     * How fresh a neighbour must be to count as "available".
     *
     * The neighbour set ages entries out over a minute, so a list can legitimately hold an AP last
     * heard half a minute and thirty metres ago. Comparing against that would manufacture sticky
     * findings from stale data — the AP was better *then*, somewhere else.
     */
    const val FRESH_NEIGHBOUR_MS = 10_000L

    data class Roam(
        val sequence: Long,
        val timestampUtcMillis: Long,
        val fromBssid: String,
        val toBssid: String,
        /** Level on the old AP in the sample before the change. */
        val rssiBeforeDbm: Int?,
        val rssiAfterDbm: Int?,
        /** False means the client changed network, not merely access point. */
        val sameSsid: Boolean,
    )

    data class Sticky(
        val bssid: String,
        val ssid: String?,
        /** Consecutive samples where a fresher, materially stronger AP on the same SSID existed. */
        val samples: Int,
        val maxDeltaDb: Int,
        val bestAlternativeBssid: String,
    )

    data class Analysis(
        val samplesWithServing: Int,
        val distinctAps: Int,
        val roams: List<Roam>,
        val sticky: List<Sticky>,
        /** Roams that returned to the previous AP within [PING_PONG_WINDOW] roams. */
        val pingPongs: Int,
    )

    const val PING_PONG_WINDOW = 2

    /**
     * @param stickyThresholdDb how much better an alternative must be before staying is a finding.
     *   8 dB is deliberately generous: a 3 dB difference is noise and flagging it would bury a real
     *   finding under dozens of false ones.
     * @param minStickySamples how long it must persist. A single sample is a scan artefact.
     */
    fun analyse(
        points: List<TrackPoint>,
        stickyThresholdDb: Int = 8,
        minStickySamples: Int = 5,
    ): Analysis {
        val served = points.filter { !it.bssid.isNullOrBlank() }
        if (served.isEmpty()) return Analysis(0, 0, emptyList(), emptyList(), 0)

        val roams = mutableListOf<Roam>()
        var previous: TrackPoint? = null
        for (p in served) {
            val prev = previous
            previous = p
            if (prev == null || prev.bssid == p.bssid) continue
            roams += Roam(
                sequence = p.sequence,
                timestampUtcMillis = p.timestampUtcMillis,
                fromBssid = prev.bssid!!,
                toBssid = p.bssid!!,
                rssiBeforeDbm = prev.rssiDbm,
                rssiAfterDbm = p.rssiDbm,
                sameSsid = prev.ssid != null && prev.ssid == p.ssid,
            )
        }

        var pingPongs = 0
        for (i in roams.indices) {
            val back = (i + 1..minOf(i + PING_PONG_WINDOW, roams.size - 1))
                .any { roams[it].toBssid == roams[i].fromBssid }
            if (back) pingPongs++
        }

        return Analysis(
            samplesWithServing = served.size,
            distinctAps = served.mapNotNull { it.bssid }.distinct().size,
            roams = roams,
            sticky = stickyRuns(served, stickyThresholdDb, minStickySamples),
            pingPongs = pingPongs,
        )
    }

    private fun stickyRuns(
        served: List<TrackPoint>,
        thresholdDb: Int,
        minSamples: Int,
    ): List<Sticky> {
        val out = mutableListOf<Sticky>()

        var runBssid: String? = null
        var runSsid: String? = null
        var runCount = 0
        var runMaxDelta = 0
        var runBest = ""

        fun close() {
            if (runBssid != null && runCount >= minSamples) {
                out += Sticky(runBssid!!, runSsid, runCount, runMaxDelta, runBest)
            }
            runBssid = null; runCount = 0; runMaxDelta = 0; runBest = ""
        }

        for (p in served) {
            val serving = p.rssiDbm
            val best = p.aps
                .filter { it.ageMs <= FRESH_NEIGHBOUR_MS }
                .filter { it.bssid != p.bssid }
                // Same network only. A stronger AP on a different SSID is not somewhere this
                // client could have roamed to; reporting it would be a finding about the
                // neighbours' Wi-Fi rather than about this one.
                .filter { it.ssid != null && it.ssid == p.ssid }
                .maxByOrNull { it.rssiDbm ?: Int.MIN_VALUE }

            val delta = if (serving != null && best?.rssiDbm != null) best.rssiDbm!! - serving else null

            if (delta != null && delta >= thresholdDb && p.bssid == runBssid) {
                runCount++
                if (delta > runMaxDelta) { runMaxDelta = delta; runBest = best!!.bssid }
            } else if (delta != null && delta >= thresholdDb) {
                close()
                runBssid = p.bssid; runSsid = p.ssid; runCount = 1
                runMaxDelta = delta; runBest = best!!.bssid
            } else {
                close()
            }
        }
        close()
        return out.sortedByDescending { it.samples }
    }
}
