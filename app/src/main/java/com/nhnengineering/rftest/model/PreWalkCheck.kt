package com.nhnengineering.rftest.model

/**
 * The things that must be true before a walk, checked while the operator can still fix them.
 *
 * ## Why this exists
 *
 * Two comparison walks were lost because the recorder was never started, which the NOT RECORDING
 * banner now catches. This covers the rest of the class: conditions that are wrong before the walk
 * begins, produce no error, and are only discoverable afterwards when the walk cannot be repeated.
 * A driveway can be walked again. A venue visit cannot.
 *
 * Every check here corresponds to something that has actually gone wrong or that silently would:
 *
 * - **Location services off at the OS level** returns an empty scan list with permissions granted
 *   and no error at all.
 * - **Wi-Fi scan throttling** leaves thirty seconds between scans, about thirty-five metres at
 *   walking pace, which is not a survey.
 * - **Nothing to place samples against** — no GPS fix and no floorplan — means every row is
 *   discarded when the session is read back, and the report comes out empty while the CSV looks
 *   full. Found by generating a report from a session with neither.
 * - **READ_PHONE_STATE** missing leaves every cellular column blank, which reads identically to no
 *   coverage.
 *
 * Statuses are deliberately only three, and BLOCK means the walk is not worth taking.
 */
object PreWalkCheck {

    enum class Status { OK, WARN, BLOCK }

    data class Check(val label: String, val status: Status, val detail: String)

    data class Inputs(
        val locationPermission: Boolean,
        val locationServicesOn: Boolean,
        val hasGpsFix: Boolean,
        val floorplanSelected: Boolean,
        val scanThrottleDisabled: Boolean,
        val phoneStatePermission: Boolean,
        val simPresent: Boolean,
        val batteryPct: Int?,
        val storageWritable: Boolean,
    )

    /** Below this, a long walk is likely to end early. */
    const val LOW_BATTERY_PCT = 30

    fun evaluate(i: Inputs): List<Check> = buildList {
        add(
            if (i.locationPermission) {
                Check("Location permission", Status.OK, "Granted.")
            } else {
                Check(
                    "Location permission", Status.BLOCK,
                    "Not granted. Without it Android returns no scan results and no cell " +
                        "identities, and reports no error while doing so.",
                )
            }
        )

        add(
            if (i.locationServicesOn) {
                Check("Location services", Status.OK, "On.")
            } else {
                Check(
                    "Location services", Status.BLOCK,
                    "Off at the system level. Granting the permission is not sufficient — with " +
                        "location services off the scan list comes back empty and looks exactly " +
                        "like a site with no Wi-Fi.",
                )
            }
        )

        // The check that came out of finding an empty report on a full CSV.
        add(
            when {
                i.hasGpsFix || i.floorplanSelected ->
                    Check(
                        "Somewhere to put the samples", Status.OK,
                        if (i.hasGpsFix) "GPS fix acquired." else "Floorplan selected.",
                    )
                else -> Check(
                    "Somewhere to put the samples", Status.BLOCK,
                    "No GPS fix and no floorplan. Samples that cannot be placed are discarded " +
                        "when the session is read back, so the CSV will look full and the report " +
                        "will come out empty. Indoors, select a floorplan before starting.",
                )
            }
        )

        add(
            if (i.scanThrottleDisabled) {
                Check("Wi-Fi scan throttling", Status.OK, "Disabled — scans roughly every 3 s.")
            } else {
                Check(
                    "Wi-Fi scan throttling", Status.WARN,
                    "Enabled, so Android allows four scans per two minutes. That is about " +
                        "thirty-five metres between neighbour updates at walking pace. Turn it " +
                        "off in Developer options if this walk includes Wi-Fi.",
                )
            }
        )

        add(
            when {
                i.phoneStatePermission -> Check("Phone state permission", Status.OK, "Granted.")
                // Without a SIM there is nothing to read anyway, so this is not worth blocking on.
                !i.simPresent -> Check(
                    "Phone state permission", Status.WARN,
                    "Not granted, but no SIM is present, so there is no cellular service to " +
                        "measure either way.",
                )
                else -> Check(
                    "Phone state permission", Status.BLOCK,
                    "Not granted. Every cellular column will be empty, which reads identically " +
                        "to no coverage.",
                )
            }
        )

        add(
            if (i.storageWritable) {
                Check("Storage", Status.OK, "Writable.")
            } else {
                Check("Storage", Status.BLOCK, "The session directory is not writable.")
            }
        )

        val battery = i.batteryPct
        add(
            when {
                battery == null -> Check("Battery", Status.OK, "Unknown.")
                battery < LOW_BATTERY_PCT -> Check(
                    "Battery", Status.WARN,
                    "$battery %. Continuous GPS, scanning and screen-on will not last a long " +
                        "walk; a session that dies partway cannot be repeated at a client site.",
                )
                else -> Check("Battery", Status.OK, "$battery %.")
            }
        )
    }

    /** True when nothing blocks. Warnings do not block; they are the operator's call. */
    fun clear(checks: List<Check>): Boolean = checks.none { it.status == Status.BLOCK }
}
