package com.nhnengineering.rftest.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** One plotted sample: what the map and the exporters need, nothing more. */
data class TrackPoint(
    val sequence: Long,
    val timestampUtcMillis: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val accuracyM: Float?,
    val speedMps: Float?,
    val rssiDbm: Int?,
    val ssid: String?,
    val bssid: String?,
    val channel: Int?,
    val band: String?,
    val coChannel: Int?,
    val adjacentChannel: Int?,
)

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
) {
    val hasTrack: Boolean get() = pointCount >= 2 && (maxLat > minLat || maxLon > minLon)
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
                // Rows without a fix are perfectly valid — they still carry RF data — but they
                // cannot be plotted, so they are counted and skipped rather than dropped silently.
                if (lat == null || lon == null) continue

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
                )
            }

            val rssis = points.mapNotNull { it.rssiDbm }
            val summary = SessionSummary(
                file = file,
                displayName = file.nameWithoutExtension,
                startedAtUtcMillis = firstTime,
                durationMs = if (firstTime != null && lastTime != null) lastTime - firstTime else 0L,
                rowCount = rows,
                pointCount = points.size,
                rssiMin = rssis.minOrNull(),
                rssiMax = rssis.maxOrNull(),
                minLat = points.minOfOrNull { it.latitudeDeg } ?: 0.0,
                maxLat = points.maxOfOrNull { it.latitudeDeg } ?: 0.0,
                minLon = points.minOfOrNull { it.longitudeDeg } ?: 0.0,
                maxLon = points.maxOfOrNull { it.longitudeDeg } ?: 0.0,
            )
            summary to points
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
