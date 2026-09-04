package com.nhnengineering.rftest.session

import com.nhnengineering.rftest.cellular.BandMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Writes an iBwave-ready measurement CSV from a recorded session.
 *
 * iBwave (and most in-building design tools) calibrate a propagation model from a "long" measurement
 * table: one row per (position, layer, metric), longitude first, with a frequency and a value. The
 * session CSV is "wide" — many radios and metrics on one row — so this pivots it, and converts the
 * stored channel number (EARFCN / NR-ARFCN) into the MHz the model tunes against, reusing
 * [BandMapping] so the raster maths lives in exactly one place.
 *
 * It is built from the session CSV on disk, through the same RFC-4180 parser [SessionReader] uses,
 * so the export is provably the bytes that were recorded. It reads columns by name, so it is robust
 * to the schema carrying extra columns: a row is emitted for whichever layers a sample actually
 * holds — Wi-Fi, LTE, and NR.
 *
 * The [Result] carries the emitted-row count and the number of source rows skipped for having no
 * fix, so the caller can report "0 rows" honestly instead of handing over an empty file in silence.
 */
object IBwaveCsvExporter {

    /**
     * Longitude first: iBwave's measurement import defaults to lon,lat order, so this imports
     * without remapping. `value` is generic with an explicit `unit` because the metrics are not all
     * the same unit — RSRP/RSSI are dBm, RSRQ and SINR/SNR are dB — and labelling a dB figure dBm
     * would be exactly the quietly-wrong column this codebase refuses to ship.
     */
    private val HEADER = listOf(
        "longitude", "latitude", "timestamp_utc",
        "technology", "operator", "band", "freq_mhz", "pci", "metric", "value", "unit",
    )

    data class Result(val file: File, val rowsWritten: Int, val sourceRowsSkipped: Int)

    /**
     * @param includeQuality also emit the RSRQ / SINR / SNR layers. On by default; the power metric
     *   (RSRP / RSSI) is what calibrates coverage, and the quality metrics ride along as extra
     *   layers iBwave can filter to or ignore.
     */
    suspend fun write(session: File, out: File, includeQuality: Boolean = true): Result =
        withContext(Dispatchers.IO) {
            val lines = session.useLines { it.toList() }
            val header = if (lines.isEmpty()) emptyList() else SessionReader.splitCsv(lines.first())
            fun idx(name: String) = header.indexOf(name).takeIf { it >= 0 }

            val iLat = idx("lat"); val iLon = idx("lon"); val iTime = idx("timestamp_utc")
            val iOp = idx("operator")
            val iLteBand = idx("lte_band"); val iLteEarfcn = idx("lte_earfcn"); val iLtePci = idx("lte_pci")
            val iLteRsrp = idx("lte_rsrp"); val iLteRsrq = idx("lte_rsrq"); val iLteSnr = idx("lte_rssnr")
            val iNrBand = idx("nr_band"); val iNrArfcn = idx("nr_arfcn"); val iNrPci = idx("nr_pci")
            val iNrRsrp = idx("nr_ss_rsrp"); val iNrRsrq = idx("nr_ss_rsrq"); val iNrSinr = idx("nr_ss_sinr")
            val iWSsid = idx("wifi_ssid"); val iWBand = idx("wifi_band")
            val iWFreq = idx("wifi_freq_mhz"); val iWRssi = idx("wifi_rssi")

            var written = 0
            var skipped = 0

            out.bufferedWriter().use { w ->
                w.write(HEADER.joinToString(","))
                w.newLine()

                for (line in lines.drop(1)) {
                    if (line.isBlank()) continue
                    val c = SessionReader.splitCsv(line)
                    fun s(i: Int?) = i?.let { c.getOrNull(it) }?.takeIf { it.isNotEmpty() }

                    // A measurement with no position cannot be placed in the design. Count it —
                    // never drop it silently — so the export total is reconcilable with the session.
                    val lon = s(iLon); val lat = s(iLat)
                    if (lon == null || lat == null) { skipped++; continue }
                    val time = s(iTime).orEmpty()
                    val op = s(iOp).orEmpty()

                    fun emit(
                        tech: String, operator: String, band: String, freqMhz: String,
                        pci: String, metric: String, value: String, unit: String,
                    ) {
                        w.write(
                            row(lon, lat, time, tech, operator, band, freqMhz, pci, metric, value, unit)
                        )
                        w.newLine()
                        written++
                    }

                    // LTE
                    s(iLteRsrp)?.let { rsrp ->
                        val band = s(iLteBand).orEmpty()
                        val pci = s(iLtePci).orEmpty()
                        val f = fmtMhz(s(iLteEarfcn)?.toIntOrNull()?.let(BandMapping::lteDownlinkMhz))
                        emit("LTE", op, band, f, pci, "RSRP", rsrp, "dBm")
                        if (includeQuality) {
                            s(iLteRsrq)?.let { emit("LTE", op, band, f, pci, "RSRQ", it, "dB") }
                            s(iLteSnr)?.let { emit("LTE", op, band, f, pci, "RS-SNR", it, "dB") }
                        }
                    }

                    // 5G NR
                    s(iNrRsrp)?.let { rsrp ->
                        val band = s(iNrBand).orEmpty()
                        val pci = s(iNrPci).orEmpty()
                        val f = fmtMhz(s(iNrArfcn)?.toIntOrNull()?.let(BandMapping::nrArfcnToMhz))
                        emit("NR", op, band, f, pci, "SS-RSRP", rsrp, "dBm")
                        if (includeQuality) {
                            s(iNrRsrq)?.let { emit("NR", op, band, f, pci, "SS-RSRQ", it, "dB") }
                            s(iNrSinr)?.let { emit("NR", op, band, f, pci, "SS-SINR", it, "dB") }
                        }
                    }

                    // Wi-Fi — a valid iBwave layer too. The SSID is the layer identity, so it goes in
                    // the operator column; frequency is already in MHz.
                    s(iWRssi)?.let { rssi ->
                        emit("Wi-Fi", s(iWSsid).orEmpty(), s(iWBand).orEmpty(), fmtMhz(s(iWFreq)?.toDoubleOrNull()), "", "RSSI", rssi, "dBm")
                    }
                }
            }

            Result(out, written, skipped)
        }

    /** Trims a computed frequency to a tidy number: 5180.0 → "5180", 3489.42 → "3489.42", null → "". */
    private fun fmtMhz(mhz: Double?): String {
        if (mhz == null) return ""
        return String.format(Locale.US, "%.3f", mhz).trimEnd('0').trimEnd('.')
    }

    private fun row(vararg fields: String): String = fields.joinToString(",") { esc(it) }

    /**
     * RFC 4180 escaping, matching [SessionCsvWriter]. Operator and SSID strings can carry commas and
     * quotes, and an unescaped one shifts every column after it on that row.
     */
    private fun esc(value: String): String {
        if (value.isEmpty()) return ""
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
}
