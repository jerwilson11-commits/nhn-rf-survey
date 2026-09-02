package com.nhnengineering.rftest.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * One cell observed in a sample: the serving cell or one of its neighbours.
 *
 * [rsrpDbm] is nullable and stays nullable all the way through. A cell the modem reported without
 * a level is a cell whose level is unknown, and substituting any number for it — 0, −140, the
 * serving level — invents a measurement. Dominance analysis simply excludes them and says how
 * many it excluded.
 */
data class ObservedCell(
    val pci: Int?,
    /**
     * Channel number (EARFCN or NR-ARFCN).
     *
     * Carried because **PCI alone does not identify a cell.** A PCI is unique only within a
     * carrier — 504 values for LTE, 1008 for NR — so the same PCI on two channels is two different
     * physical cells. Grouping a survey by PCI alone silently merges them, and this session
     * contains eight samples that saw one PCI on two channels at once.
     */
    val channel: Int?,
    val rsrpDbm: Int?,
    val band: String?,
    val serving: Boolean,
    /** Milliseconds since this cell was actually in a measurement report. 0 means this sample. */
    val ageMs: Long,
)

/** One plotted sample: what the map and the exporters need, nothing more. */
data class TrackPoint(
    val sequence: Long,
    val timestampUtcMillis: Long,
    /** Null when GPS could not place this sample — normal indoors, not an error. */
    val latitudeDeg: Double?,
    val longitudeDeg: Double?,
    val accuracyM: Float?,
    val speedMps: Float?,
    val rssiDbm: Int?,
    val ssid: String?,
    val bssid: String?,
    val channel: Int?,
    val band: String?,
    val coChannel: Int?,
    val adjacentChannel: Int?,
    /** Cellular serving-cell RSRP, whichever radio was serving. */
    val rsrpDbm: Int?,
    /**
     * Serving-cell SINR and RSRQ, whichever radio was serving.
     *
     * Read back for one specific purpose: measuring how often they change relative to RSRP. On the
     * 2026-09-02 walk SINR held a single value for 99 consecutive samples while RSRP moved 88
     * times, so presenting the two side by side implies a simultaneity the handset does not
     * deliver. The report states that, measured per session rather than asserted.
     */
    val sinrDb: Int?,
    val rsrqDb: Int?,
    val cellBand: String?,
    val rat: String?,
    /** Indoor position, present when the operator placed one on a floorplan. */
    val floorplanId: String?,
    val floorplanX: Float?,
    val floorplanY: Float?,
    val waypoint: String?,
    /** Serving-cell PCI, whichever radio was serving. */
    val servingPci: Int? = null,
    /**
     * Every cell this sample saw, serving first, strongest first thereafter. Empty for Wi-Fi
     * sessions and for cellular sessions recorded before neighbour logging existed.
     */
    val cells: List<ObservedCell> = emptyList(),
) {
    /** True when this sample can be placed on a floorplan even though GPS could not place it. */
    val hasIndoorPosition: Boolean
        get() = floorplanId != null && floorplanX != null && floorplanY != null

    val hasGpsPosition: Boolean
        get() = latitudeDeg != null && longitudeDeg != null
}

data class SessionSummary(
    val file: File,
    val displayName: String,
    val startedAtUtcMillis: Long?,
    val durationMs: Long,
    val rowCount: Int,
    val pointCount: Int,
    val rssiMin: Int?,
    val rssiMax: Int?,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    /** Floorplans referenced by this session, if any. */
    val floorplanIds: List<String> = emptyList(),
    val indoorPointCount: Int = 0,
    val waypoints: List<String> = emptyList(),
) {
    val hasTrack: Boolean get() = pointCount >= 2 && (maxLat > minLat || maxLon > minLon)

    /**
     * An indoor session is not a broken session. A venue walk can legitimately produce zero usable
     * GPS fixes and still be fully located, on a floorplan, by hand.
     */
    val hasIndoorTrack: Boolean get() = indoorPointCount > 0
}

/**
 * Reads sessions back off disk.
 *
 * The CSV is the storage format, so this is the read half of it. Reading our own written format
 * rather than keeping a parallel in-memory copy means the plot and the exports are built from the
 * exact bytes the client receives — if a column is malformed, it shows up here rather than only in
 * the customer's spreadsheet.
 */
object SessionReader {

    suspend fun list(dir: File): List<File> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.isFile && f.extension.equals("csv", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    suspend fun read(file: File): Pair<SessionSummary, List<TrackPoint>>? =
        withContext(Dispatchers.IO) {
            val lines = file.useLines { it.toList() }
            if (lines.size < 2) return@withContext null

            val header = splitCsv(lines.first())
            fun idx(name: String) = header.indexOf(name).takeIf { it >= 0 }

            val iSeq = idx("seq"); val iTime = idx("timestamp_utc")
            val iLat = idx("lat"); val iLon = idx("lon")
            val iAcc = idx("gps_accuracy_m"); val iSpeed = idx("speed_mps")
            val iRssi = idx("wifi_rssi"); val iSsid = idx("wifi_ssid")
            val iBssid = idx("wifi_bssid"); val iCh = idx("wifi_channel")
            val iBand = idx("wifi_band")
            val iCo = idx("wifi_cochannel_count"); val iAdj = idx("wifi_adjacent_count")
            val iRsrp = idx("lte_rsrp"); val iNrRsrp = idx("nr_ss_rsrp")
            val iNrSinr = idx("nr_ss_sinr"); val iRssnr = idx("lte_rssnr")
            val iNrRsrq = idx("nr_ss_rsrq"); val iLteRsrq = idx("lte_rsrq")
            val iLteBand = idx("lte_band"); val iNrBand = idx("nr_band"); val iRat = idx("rat")
            val iNrPci = idx("nr_pci"); val iLtePci = idx("lte_pci")
            val iNrArfcn = idx("nr_arfcn"); val iEarfcn = idx("lte_earfcn")
            val iCells = idx("cell_neighbors_json")
            val iFp = idx("floorplan_id"); val iFpX = idx("floorplan_x")
            val iFpY = idx("floorplan_y"); val iWp = idx("waypoint")
            if (iLat == null || iLon == null) return@withContext null

            val points = mutableListOf<TrackPoint>()
            var firstTime: Long? = null
            var lastTime: Long? = null
            var rows = 0

            for (line in lines.drop(1)) {
                if (line.isBlank()) continue
                rows++
                val c = splitCsv(line)
                fun s(i: Int?) = i?.let { c.getOrNull(it) }?.takeIf { it.isNotEmpty() }

                val t = s(iTime)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                if (t != null) {
                    if (firstTime == null) firstTime = t
                    lastTime = t
                }

                val lat = s(iLat)?.toDoubleOrNull()
                val lon = s(iLon)?.toDoubleOrNull()
                val fpX = s(iFpX)?.toFloatOrNull()
                val fpY = s(iFpY)?.toFloatOrNull()
                val fpId = s(iFp)

                // A row is keepable if it can be placed EITHER by GPS or on a floorplan. Requiring
                // a GPS fix would discard an entire indoor venue walk, which is precisely the case
                // floorplan mode exists to handle.
                val placeable = (lat != null && lon != null) || (fpId != null && fpX != null && fpY != null)
                if (!placeable) continue

                val servingRsrp = s(iNrRsrp)?.toIntOrNull() ?: s(iRsrp)?.toIntOrNull()
                val servingPci = s(iNrPci)?.toIntOrNull() ?: s(iLtePci)?.toIntOrNull()
                val servingBand = s(iNrBand) ?: s(iLteBand)?.let { "B" + it }
                val cells = buildList {
                    if (servingPci != null || servingRsrp != null) {
                        add(
                            ObservedCell(
                                pci = servingPci,
                                channel = s(iNrArfcn)?.toIntOrNull() ?: s(iEarfcn)?.toIntOrNull(),
                                rsrpDbm = servingRsrp,
                                band = servingBand,
                                serving = true,
                                ageMs = 0,
                            ),
                        )
                    }
                    addAll(parseCellNeighbors(s(iCells)))
                }

                points += TrackPoint(
                    sequence = s(iSeq)?.toLongOrNull() ?: rows.toLong(),
                    timestampUtcMillis = t ?: 0L,
                    latitudeDeg = lat,
                    longitudeDeg = lon,
                    accuracyM = s(iAcc)?.toFloatOrNull(),
                    speedMps = s(iSpeed)?.toFloatOrNull(),
                    rssiDbm = s(iRssi)?.toIntOrNull(),
                    ssid = s(iSsid),
                    bssid = s(iBssid),
                    channel = s(iCh)?.toIntOrNull(),
                    band = s(iBand),
                    coChannel = s(iCo)?.toIntOrNull(),
                    adjacentChannel = s(iAdj)?.toIntOrNull(),
                    rsrpDbm = servingRsrp,
                    sinrDb = s(iNrSinr)?.toIntOrNull() ?: s(iRssnr)?.toIntOrNull(),
                    rsrqDb = s(iNrRsrq)?.toIntOrNull() ?: s(iLteRsrq)?.toIntOrNull(),
                    cellBand = servingBand,
                    rat = s(iRat),
                    floorplanId = fpId,
                    floorplanX = fpX,
                    floorplanY = fpY,
                    waypoint = s(iWp),
                    servingPci = servingPci,
                    cells = cells,
                )
            }

            val rssis = points.mapNotNull { it.rssiDbm }
            // Bounds are computed from GPS-located points only. Including indoor-only rows would
            // be meaningless — they have no geographic position at all.
            val gps = points.filter { it.hasGpsPosition }
            val indoor = points.filter { it.hasIndoorPosition }

            val summary = SessionSummary(
                file = file,
                displayName = file.nameWithoutExtension,
                startedAtUtcMillis = firstTime,
                durationMs = if (firstTime != null && lastTime != null) lastTime - firstTime else 0L,
                rowCount = rows,
                pointCount = gps.size,
                rssiMin = rssis.minOrNull(),
                rssiMax = rssis.maxOrNull(),
                minLat = gps.minOfOrNull { it.latitudeDeg!! } ?: 0.0,
                maxLat = gps.maxOfOrNull { it.latitudeDeg!! } ?: 0.0,
                minLon = gps.minOfOrNull { it.longitudeDeg!! } ?: 0.0,
                maxLon = gps.maxOfOrNull { it.longitudeDeg!! } ?: 0.0,
                floorplanIds = indoor.mapNotNull { it.floorplanId }.distinct(),
                indoorPointCount = indoor.size,
                waypoints = points.mapNotNull { it.waypoint }.distinct(),
            )
            summary to points
        }

    /**
     * Reads `cell_neighbors_json` back into typed cells.
     *
     * Deliberately a small hand-rolled scan rather than a JSON library: the writer produces this
     * exact shape and nothing else, the array is capped at twelve entries, and this runs once per
     * row over a whole session.
     *
     * `null` in the file stays null here. The writer emits JSON null for an absent level precisely
     * so that it cannot be mistaken for a measurement, and parsing it back to 0 would undo that.
     */
    internal fun parseCellNeighbors(json: String?): List<ObservedCell> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        val out = mutableListOf<ObservedCell>()
        var i = 0
        while (true) {
            val open = json.indexOf('{', i)
            if (open < 0) break
            val close = json.indexOf('}', open)
            if (close < 0) break
            val fields = mutableMapOf<String, String>()
            for (part in json.substring(open + 1, close).split(',')) {
                val colon = part.indexOf(':')
                if (colon < 0) continue
                val key = part.substring(0, colon).trim().trim('"')
                val raw = part.substring(colon + 1).trim()
                // "null" stays out of the map entirely, so every read of a missing field yields
                // null rather than a parsed zero. The writer emits JSON null for an absent level
                // precisely so it cannot be mistaken for a measurement; turning it into 0 here
                // would make it 0 dBm -- the strongest reading in the file -- and it would win
                // every best-server and dominance ranking.
                if (raw != "null") fields[key] = raw.trim('"')
            }
            out += ObservedCell(
                pci = fields["pci"]?.toIntOrNull(),
                channel = fields["ch"]?.toIntOrNull(),
                rsrpDbm = fields["rsrp"]?.toIntOrNull(),
                band = fields["band"]?.takeIf { it.isNotEmpty() },
                serving = false,
                ageMs = fields["age_ms"]?.toLongOrNull() ?: 0L,
            )
            i = close + 1
        }
        return out
    }

    /**
     * RFC 4180 field splitter.
     *
     * `String.split(",")` would be wrong here and wrong in a way that only shows on real data: we
     * quote SSIDs containing commas and the neighbours JSON, which is full of them. A naive split
     * shreds those rows into the wrong number of fields and every column after them reads garbage.
     */
    internal fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++          // escaped quote inside a quoted field
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
