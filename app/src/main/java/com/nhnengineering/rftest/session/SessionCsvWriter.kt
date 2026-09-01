package com.nhnengineering.rftest.session

import android.content.Context
import com.nhnengineering.rftest.model.MeasurementSample
import com.nhnengineering.rftest.model.NeighborCell
import com.nhnengineering.rftest.model.WifiNeighbor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Streams samples to a CSV file, one row per sample, appended as they are taken.
 *
 * Streaming rather than accumulating and writing at the end, for three reasons: a session that
 * dies — crash, battery, force-stop — keeps every row written up to that point; memory stays flat
 * regardless of session length, which matters under Android 17's per-app RAM limits; and the file
 * is the deliverable, so there is no separate export step to fail.
 */
class SessionCsvWriter private constructor(
    val sessionId: String,
    val sessionName: String,
    val file: File,
    private val writer: BufferedWriter,
) {

    companion object {
        /**
         * Neighbour APs serialised into `wifi_neighbors_json`, strongest first.
         *
         * Capped because an unbounded list at 1 Hz produces very large files in a dense
         * environment. The cap is not silent: `wifi_neighbor_count` always carries the true
         * total, so a row showing 20 entries and a count of 39 is self-describing.
         */
        const val MAX_NEIGHBOURS_IN_JSON = 20

        private val FILENAME_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault())

        /** Where sessions land: app-specific external storage. No permission needed, and it is
         *  reachable over USB and from the Files app so a CSV can be pulled off the handset. */
        fun sessionsDir(context: Context): File =
            File(context.getExternalFilesDir(null), "sessions").apply { mkdirs() }

        fun listSessions(context: Context): List<File> =
            sessionsDir(context).listFiles { f -> f.extension == "csv" }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

        suspend fun create(context: Context, sessionName: String): SessionCsvWriter =
            withContext(Dispatchers.IO) {
                val now = Instant.now()
                val safeName = sessionName.trim()
                    .ifEmpty { "session" }
                    .replace(Regex("[^A-Za-z0-9 _-]"), "")
                    .replace(' ', '-')
                    .take(48)
                val stamp = FILENAME_STAMP.format(now)
                val file = File(sessionsDir(context), "${safeName}_$stamp.csv")
                val writer = BufferedWriter(FileWriter(file, /* append = */ true))
                writer.write(HEADER)
                writer.newLine()
                writer.flush()
                SessionCsvWriter(
                    sessionId = "${safeName}_$stamp",
                    sessionName = sessionName.trim().ifEmpty { "session" },
                    file = file,
                    writer = writer,
                )
            }
    }

    suspend fun writeRow(sample: MeasurementSample) = withContext(Dispatchers.IO) {
        writer.write(sample.toCsvRow())
        writer.newLine()
        // Flush every row. At 1 Hz the cost is irrelevant, and it means a session killed by the
        // OS or a flat battery loses nothing rather than losing the buffer.
        writer.flush()
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        runCatching {
            writer.flush()
            writer.close()
        }
        Unit
    }
}

// ---------------------------------------------------------------------------
// Schema
// ---------------------------------------------------------------------------

private val CORE_COLUMNS = listOf("timestamp_utc", "session_id", "seq")

private val LOCATION_COLUMNS = listOf(
    "lat", "lon", "alt_m", "gps_accuracy_m", "speed_mps", "bearing_deg", "gps_provider",
)

/**
 * Populated from Phase 5 onward. Order must match the emission order in toCsvRow() exactly — the
 * size assertion catches a count mismatch but cannot catch a transposition, which would silently
 * put RSRQ values in the RSRP column.
 */
private val CELLULAR_COLUMNS = listOf(
    "rat", "nr_state", "override_network_type", "is_roaming",
    "mcc", "mnc", "operator",
    "lte_ci", "lte_enb_id", "lte_pci", "lte_tac", "lte_earfcn", "lte_band", "lte_bw_khz",
    "lte_rsrp", "lte_rsrq", "lte_rssnr", "lte_rssi", "lte_cqi", "lte_ta",
    "nr_nci", "nr_pci", "nr_tac", "nr_arfcn", "nr_band",
    "nr_ss_rsrp", "nr_ss_rsrq", "nr_ss_sinr", "nr_csi_rsrp", "nr_csi_rsrq", "nr_csi_sinr",
    "cell_neighbor_count", "cell_neighbors_json",
)

private val WIFI_COLUMNS = listOf(
    "wifi_ssid", "wifi_bssid", "wifi_rssi", "wifi_freq_mhz", "wifi_channel", "wifi_band",
    "wifi_width_mhz", "wifi_standard", "wifi_security",
    "wifi_tx_mbps", "wifi_rx_mbps", "wifi_max_tx_mbps",
    "wifi_neighbor_count", "wifi_cochannel_count", "wifi_adjacent_count",
    "wifi_neighbors_json", "wifi_scan_age_ms",
)

/**
 * Populated only on the row where a speed test completed, so the result carries the position and
 * RF conditions it ran under. Every other row leaves these empty — which is correct, not missing
 * data: throughput is a point measurement, not a continuous one.
 *
 * `loss_pct` is empty whenever ICMP was unavailable. It is never back-filled from HTTP failures.
 */
private val THROUGHPUT_COLUMNS = listOf(
    "dl_mbps", "ul_mbps",
    "latency_ms", "latency_min_ms", "latency_max_ms", "jitter_ms",
    "loss_pct", "speedtest_server",
)

/**
 * Indoor positioning. Coordinates are normalised 0..1 to the floorplan image rather than stored in
 * pixels, so they remain valid at any display size or export resolution. `floorplan_id` is the
 * image filename, so an exported session and its floorplan can be handed over together.
 */
private val INDOOR_COLUMNS = listOf(
    "floorplan_id", "floorplan_x", "floorplan_y", "waypoint",
)

private val TRAILING_COLUMNS = listOf("note")

/**
 * The full unified schema, cellular and throughput columns included and left empty until their
 * phases land.
 *
 * Carrying the empty columns now is deliberate: the schema does not change when those collectors
 * arrive, so a file recorded today stays directly comparable with one recorded after. Changing
 * column layout between releases is how a measurement tool loses the trust of anyone building a
 * spreadsheet on top of it.
 *
 * The groups are separate lists so the row builder can derive how many blanks to emit with
 * `.size` instead of a hardcoded count. A literal here drifted from the header once already and
 * cost a debugging round — the assertion in toCsvRow caught it, but the fix is to make the two
 * impossible to disagree.
 */
private val COLUMNS =
    CORE_COLUMNS + LOCATION_COLUMNS + CELLULAR_COLUMNS + WIFI_COLUMNS + THROUGHPUT_COLUMNS +
        INDOOR_COLUMNS + TRAILING_COLUMNS

internal val CSV_HEADER = COLUMNS.joinToString(",")
private val HEADER = CSV_HEADER
internal val CSV_COLUMN_COUNT = COLUMNS.size

private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

internal fun MeasurementSample.toCsvRow(): String {
    val g = location
    val w = wifi
    val cells = mutableListOf<String?>()

    cells += TIMESTAMP.format(Instant.ofEpochMilli(timestampUtcMillis))
    cells += sessionId
    cells += sequence.toString()

    // Location. Six decimal places is ~0.1 m — beyond any consumer GPS, and enough that rounding
    // never becomes the error term.
    // Locale.US is not cosmetic here. String.format with a comma-decimal default locale emits
    // "26,050266", which splits the field and shifts every column after it — a corrupted file
    // that only appears on devices set to those locales.
    cells += g?.latitudeDeg?.let { String.format(Locale.US, "%.6f", it) }
    cells += g?.longitudeDeg?.let { String.format(Locale.US, "%.6f", it) }
    cells += g?.altitudeM?.let { String.format(Locale.US, "%.1f", it) }
    cells += g?.accuracyM?.let { String.format(Locale.US, "%.1f", it) }
    cells += g?.speedMps?.let { String.format(Locale.US, "%.2f", it) }
    cells += g?.bearingDeg?.let { String.format(Locale.US, "%.1f", it) }
    cells += g?.provider

    // Cellular. Column order must match CELLULAR_COLUMNS exactly; the assertion at the end of
    // this function catches a count mismatch, but not a transposition — so the two lists are kept
    // adjacent in this file deliberately.
    val c = cellular
    val lte = c?.lte
    val nr = c?.nr
    cells += c?.rat?.label
    cells += c?.nrState?.label
    cells += c?.overrideNetworkType
    cells += c?.isRoaming?.toString()
    cells += c?.mcc
    cells += c?.mnc
    cells += c?.operator
    cells += lte?.ci?.toString()
    cells += lte?.enbId?.toString()
    cells += lte?.pci?.toString()
    cells += lte?.tac?.toString()
    cells += lte?.earfcn?.toString()
    cells += lte?.band?.toString()
    cells += lte?.bandwidthKhz?.toString()
    cells += lte?.rsrpDbm?.toString()
    cells += lte?.rsrqDb?.toString()
    cells += lte?.rssnrDb?.toString()
    cells += lte?.rssiDbm?.toString()
    cells += lte?.cqi?.toString()
    cells += lte?.timingAdvance?.toString()
    cells += nr?.nci?.toString()
    cells += nr?.pci?.toString()
    cells += nr?.tac?.toString()
    cells += nr?.nrarfcn?.toString()
    // The plain band, without the UI's conflict marker — a warning symbol inside a categorical
    // field breaks grouping for anyone analysing the file. A band/ARFCN conflict stays derivable
    // because nr_arfcn is in the row alongside it.
    cells += nr?.bands?.joinToString("/")
    cells += nr?.ssRsrpDbm?.toString()
    cells += nr?.ssRsrqDb?.toString()
    cells += nr?.ssSinrDb?.toString()
    cells += nr?.csiRsrpDbm?.toString()
    cells += nr?.csiRsrqDb?.toString()
    cells += nr?.csiSinrDb?.toString()
    cells += c?.neighbors?.size?.toString()
    cells += c?.neighbors?.let { cellNeighborsToJson(it) }

    cells += w?.ssid
    cells += w?.bssid
    cells += w?.rssiDbm?.toString()
    cells += w?.frequencyMhz?.toString()
    cells += w?.channel?.toString()
    cells += w?.band?.label
    cells += w?.channelWidthMhz?.toString()
    cells += w?.standard?.label
    cells += w?.security?.label
    cells += w?.txLinkMbps?.toString()
    cells += w?.rxLinkMbps?.toString()
    cells += w?.maxSupportedTxMbps?.toString()
    cells += w?.neighbors?.size?.toString()
    cells += w?.coChannelCount?.toString()
    cells += w?.adjacentChannelCount?.toString()
    cells += w?.neighbors?.let { neighborsToJson(it) }
    cells += w?.neighborScanAgeMs?.toString()

    val tp = throughput
    cells += tp?.downloadMbps?.let { String.format(Locale.US, "%.3f", it) }
    cells += tp?.uploadMbps?.let { String.format(Locale.US, "%.3f", it) }
    cells += tp?.latencyMedianMs?.let { String.format(Locale.US, "%.2f", it) }
    cells += tp?.latencyMinMs?.let { String.format(Locale.US, "%.2f", it) }
    cells += tp?.latencyMaxMs?.let { String.format(Locale.US, "%.2f", it) }
    cells += tp?.jitterMs?.let { String.format(Locale.US, "%.2f", it) }
    cells += tp?.lossPct?.let { String.format(Locale.US, "%.1f", it) }
    cells += tp?.server

    val ind = indoor
    cells += ind?.floorplanId
    cells += ind?.let { String.format(Locale.US, "%.4f", it.xNorm) }
    cells += ind?.let { String.format(Locale.US, "%.4f", it.yNorm) }
    cells += ind?.label

    cells += note

    check(cells.size == COLUMNS.size) {
        "CSV row has ${cells.size} cells but the header declares ${COLUMNS.size}"
    }
    return cells.joinToString(",") { escapeCsv(it) }
}

/**
 * Cellular neighbours, strongest first.
 *
 * `age_ms` is carried per neighbour and is the reason a retained entry cannot be mistaken for a
 * fresh one: filter to `age_ms == 0` for only the cells present in that sample's own measurement
 * report. Weak neighbours near the detection floor genuinely come and go, and that behaviour is
 * itself information — smoothing it away without recording the age would hide it.
 */
private fun cellNeighborsToJson(neighbors: List<NeighborCell>): String =
    neighbors.sortedByDescending { it.rsrpDbm ?: Int.MIN_VALUE }
        .take(MAX_CELL_NEIGHBORS_IN_JSON)
        .joinToString(",", prefix = "[", postfix = "]") { n ->
            buildString {
                append("{")
                // Missing values are emitted as JSON null, never as a number. An absent RSRP
                // written as 0 would be the strongest reading in the file -- 0 dBm -- and would
                // outrank every real cell in any dominance or best-server calculation. This is
                // the same null-as-zero mistake that cost this project a walk of GPS distance
                // data; it is not repeated here.
                append("\"rat\":\"${escapeJson(n.rat)}\",")
                append("\"pci\":${n.pci ?: "null"},")
                append("\"ch\":${n.channel ?: "null"},")
                append("\"band\":${n.band?.let { "\"${escapeJson(it)}\"" } ?: "null"},")
                append("\"rsrp\":${n.rsrpDbm ?: "null"},")
                append("\"rsrq\":${n.rsrqDb ?: "null"},")
                append("\"age_ms\":${n.ageMs}")
                append("}")
            }
        }

/** Same reasoning as the Wi-Fi cap: bounded file size, with the true count in its own column. */
private const val MAX_CELL_NEIGHBORS_IN_JSON = 12

private fun neighborsToJson(neighbors: List<WifiNeighbor>): String =
    neighbors.sortedByDescending { it.rssiDbm }
        .take(SessionCsvWriter.MAX_NEIGHBOURS_IN_JSON)
        .joinToString(",", prefix = "[", postfix = "]") { n ->
            buildString {
                append("{")
                append("\"bssid\":\"${escapeJson(n.bssid)}\",")
                append("\"ssid\":\"${escapeJson(n.ssid ?: "")}\",")
                append("\"rssi\":${n.rssiDbm},")
                append("\"freq\":${n.frequencyMhz},")
                append("\"ch\":${n.channel ?: -1},")
                append("\"width\":${n.channelWidthMhz ?: -1},")
                append("\"age_ms\":${n.ageMs}")
                append("}")
            }
        }

/**
 * RFC 4180 escaping. Not optional here: SSIDs routinely contain commas, quotes and non-ASCII, and
 * the neighbours JSON contains both commas and quotes by construction. An unescaped SSID silently
 * shifts every subsequent column on that row — corruption that looks like a data problem rather
 * than a formatting one.
 */
private fun escapeCsv(value: String?): String {
    if (value.isNullOrEmpty()) return ""
    val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuoting) return value
    return "\"" + value.replace("\"", "\"\"") + "\""
}

private fun escapeJson(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
