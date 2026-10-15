package com.nhnengineering.rftest.session

import com.nhnengineering.rftest.billing.SubscriptionTier
import com.nhnengineering.rftest.cellular.NrCellId
import com.nhnengineering.rftest.model.RsrpBucket
import com.nhnengineering.rftest.model.RssiBucket
import com.nhnengineering.rftest.report.MobilityEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * KML and GeoJSON writers.
 *
 * These exist because the CSV, while complete, is not what a client opens. A KML drops straight
 * into Google Earth with the coverage already colour-coded; GeoJSON goes into QGIS or any web map.
 * Both are built from the CSV on disk via [SessionReader], so what gets exported is provably the
 * same data that was recorded.
 */
object TrackExporters {

    /** Coordinates are lon,lat[,alt] in both formats — the reverse of how everyone says them. */
    private fun coord(p: TrackPoint) =
        String.format(Locale.US, "%.6f,%.6f,0", p.longitudeDeg!!, p.latitudeDeg!!)

    /**
     * KML and GeoJSON are geographic formats, so only GPS-located samples belong in them. An
     * indoor session may legitimately produce none — its positions live on a floorplan instead,
     * and forcing them into a coordinate system they were never in would fabricate geography.
     */
    private fun geoOnly(points: List<TrackPoint>) = points.filter { it.hasGpsPosition }

    /**
     * KML whose usefulness scales with the exporter's [tier], mirroring the app itself:
     *  - **FREE**  — one Coverage folder, pins coloured by the serving radio (cellular RSRP, else
     *    Wi-Fi RSSI), with the essential reading in each popup. A proper dual-radio coverage map
     *    (the old export was Wi-Fi-only and dropped all cellular data).
     *  - **FIELD** — cellular and Wi-Fi split into their own toggleable layers, richer popups
     *    (neighbours, EARFCN/NR-ARFCN, operator), plus a **Throughput** layer, an **Events** layer
     *    (serving-cell change / NR→LTE / service loss — the same analysis the PDF uses) and a
     *    **FirstNet (B14/n14)** layer.
     *  - **PRO**   — Field, plus deep cellular identity in the popup (NCI gNB/cell split, both EN-DC
     *    legs, modem-sourced neighbours) and a **Cell lock watch** layer.
     */
    suspend fun writeKml(
        summary: SessionSummary,
        allPoints: List<TrackPoint>,
        out: File,
        tier: SubscriptionTier = SubscriptionTier.FREE,
    ): File = withContext(Dispatchers.IO) {
        val points = geoOnly(allPoints)
        val field = tier.grantsField
        val pro = tier.grantsPro
        val sb = StringBuilder(1 shl 17)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<kml xmlns="http://www.opengis.net/kml/2.2">""").append('\n')
        sb.append("<Document>\n")
        sb.append("<name>").append(xml(summary.displayName)).append("</name>\n")
        sb.append("<description>").append(
            xml(
                "Site Survey Pro session — ${tier.name.lowercase()} tier. " +
                    "${summary.pointCount} geo-tagged samples of ${summary.rowCount} rows."
            )
        ).append("</description>\n")

        // Styles: one per colour bucket for each radio, referenced by id (inlining per placemark
        // would multiply file size by the sample count for no benefit).
        for (b in RsrpBucket.entries) iconStyle(sb, "rsrp_${b.name}", argbToKml(b.argb))
        for (b in RssiBucket.entries) iconStyle(sb, "rssi_${b.name}", b.toKmlColor())
        iconStyle(sb, "cell_nolevel", "ff9e9e9e")
        sb.append("<Style id=\"track\"><LineStyle><color>ffcccccc</color><width>2</width></LineStyle></Style>\n")
        if (field) {
            iconStyle(sb, "ev_handover", "ffffffff", 1.1) // serving-cell change — white, drive-test HO marker
            iconStyle(sb, "ev_fallback", "ff00a5ff", 1.1) // NR→LTE — orange
            iconStyle(sb, "ev_firstnet", "ff00ffff", 1.1) // off-FirstNet — yellow
            iconStyle(sb, "ev_loss", "ff0000ff", 1.3)     // service lost — red, larger
            iconStyle(sb, "tp", "ffe08020", 0.9)
            iconStyle(sb, "firstnet", "ffe0a000", 0.8)
        }
        if (pro) iconStyle(sb, "lockwatch", "ffff00ff", 1.1)

        // Route line — all tiers.
        sb.append("<Placemark><name>Route</name><styleUrl>#track</styleUrl>\n")
        sb.append("<LineString><tessellate>1</tessellate><coordinates>\n")
        points.forEach { sb.append(coord(it)).append(' ') }
        sb.append("\n</coordinates></LineString></Placemark>\n")

        if (!field) {
            // FREE: one folder, coloured by whichever radio is serving.
            sb.append("<Folder><name>Coverage</name>\n")
            for (p in points) {
                val onCell = p.rsrpDbm != null
                val style = if (onCell) RsrpBucket.of(p.rsrpDbm)?.let { "rsrp_${it.name}" }
                    else RssiBucket.of(p.rssiDbm)?.let { "rssi_${it.name}" }
                val label = if (onCell) "${p.rsrpDbm} dBm" else p.rssiDbm?.let { "$it dBm" } ?: "no RF"
                placemark(sb, label, style, coord(p), p.timestampUtcMillis, freePopup(p))
            }
            sb.append("</Folder>\n")
        } else {
            // FIELD/PRO: each radio its own toggleable layer.
            val cellPts = points.filter { it.rsrpDbm != null || it.cellBand != null }
            if (cellPts.isNotEmpty()) {
                sb.append("<Folder><name>Cellular coverage</name><open>1</open>\n")
                for (p in cellPts) {
                    val style = RsrpBucket.of(p.rsrpDbm)?.let { "rsrp_${it.name}" } ?: "cell_nolevel"
                    val label = p.rsrpDbm?.let { "$it dBm" } ?: (p.cellBand ?: "cell")
                    placemark(sb, label, style, coord(p), p.timestampUtcMillis, cellPopup(p, pro))
                }
                sb.append("</Folder>\n")
            }
            val wifiPts = points.filter { it.rssiDbm != null }
            if (wifiPts.isNotEmpty()) {
                sb.append("<Folder><name>Wi-Fi coverage</name>\n")
                for (p in wifiPts) {
                    placemark(sb, "${p.rssiDbm} dBm", RssiBucket.of(p.rssiDbm)?.let { "rssi_${it.name}" },
                        coord(p), p.timestampUtcMillis, wifiPopup(p))
                }
                sb.append("</Folder>\n")
            }

            val tpPts = points.filter { it.downloadMbps != null || it.uploadMbps != null }
            if (tpPts.isNotEmpty()) {
                sb.append("<Folder><name>Throughput</name>\n")
                for (p in tpPts) {
                    val d = p.downloadMbps?.let { "%.0f".format(it) } ?: "—"
                    val u = p.uploadMbps?.let { "%.0f".format(it) } ?: "—"
                    val desc = "Download ${p.downloadMbps?.let { "%.1f".format(it) } ?: "—"} Mbps<br/>" +
                        "Upload ${p.uploadMbps?.let { "%.1f".format(it) } ?: "—"} Mbps" +
                        (p.throughputError?.let { "<br/>${xml(it)}" } ?: "")
                    placemark(sb, "$d↓ / $u↑ Mbps", "tp", coord(p), p.timestampUtcMillis, desc)
                }
                sb.append("</Folder>\n")
            }

            // Events — reuse the exact analysis the PDF's mobility section uses.
            val events = MobilityEvents.analyse(points).events
                .filter { it.latitudeDeg != null && it.longitudeDeg != null }
            if (events.isNotEmpty()) {
                sb.append("<Folder><name>Events (handover / drop)</name>\n")
                for (e in events) {
                    val (style, name) = when (e.kind) {
                        MobilityEvents.Kind.SERVING_CELL_CHANGE -> "ev_handover" to "Serving-cell change"
                        MobilityEvents.Kind.NR_FALLBACK -> "ev_fallback" to "NR → LTE"
                        MobilityEvents.Kind.OFF_FIRSTNET -> "ev_firstnet" to "Off FirstNet"
                        MobilityEvents.Kind.SERVICE_LOST -> "ev_loss" to "Service lost"
                    }
                    val c = String.format(Locale.US, "%.6f,%.6f,0", e.longitudeDeg, e.latitudeDeg)
                    val desc = xml(e.detail) +
                        (if (e.cause != MobilityEvents.Cause.NONE) "<br/>cause: ${e.cause.name.lowercase().replace('_', ' ')}" else "")
                    placemark(sb, name, style, c, e.atUtcMillis, desc)
                }
                sb.append("</Folder>\n")
            }

            val fnPts = points.filter { isFirstNet(it.cellBand) }
            if (fnPts.isNotEmpty()) {
                sb.append("<Folder><name>FirstNet (Band 14 / n14)</name>\n")
                for (p in fnPts) {
                    placemark(sb, p.rsrpDbm?.let { "$it dBm" } ?: "B14", "firstnet",
                        coord(p), p.timestampUtcMillis, cellPopup(p, pro))
                }
                sb.append("</Folder>\n")
            }
        }

        if (pro) {
            val lwPts = points.filter { it.lockWatchPci != null || it.lockWatchArfcn != null }
            if (lwPts.isNotEmpty()) {
                sb.append("<Folder><name>Cell lock watch</name>\n")
                for (p in lwPts) {
                    val desc = "watch PCI ${p.lockWatchPci ?: "—"} · ARFCN ${p.lockWatchArfcn ?: "—"}<br/>" +
                        "serving PCI ${p.servingPci ?: "—"}"
                    placemark(sb, "lock watch", "lockwatch", coord(p), p.timestampUtcMillis, desc)
                }
                sb.append("</Folder>\n")
            }
        }

        sb.append("</Document>\n</kml>\n")
        out.writeText(sb.toString())
        out
    }

    private fun iconStyle(sb: StringBuilder, id: String, kmlColor: String, scale: Double = 0.7) {
        sb.append("<Style id=\"").append(id).append("\">\n")
        sb.append("  <IconStyle><color>").append(kmlColor).append("</color><scale>").append(scale).append("</scale>")
        sb.append("<Icon><href>http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon></IconStyle>\n")
        sb.append("  <LabelStyle><scale>0</scale></LabelStyle>\n")
        sb.append("</Style>\n")
    }

    private fun placemark(sb: StringBuilder, name: String, styleId: String?, coord: String, whenMs: Long, descHtml: String) {
        sb.append("<Placemark>\n")
        sb.append("  <name>").append(xml(name)).append("</name>\n")
        styleId?.let { sb.append("  <styleUrl>#").append(it).append("</styleUrl>\n") }
        sb.append("  <TimeStamp><when>").append(Instant.ofEpochMilli(whenMs).toString()).append("</when></TimeStamp>\n")
        sb.append("  <description><![CDATA[").append(descHtml).append("]]></description>\n")
        sb.append("  <Point><coordinates>").append(coord).append("</coordinates></Point>\n")
        sb.append("</Placemark>\n")
    }

    /** 0xAARRGGBB -> KML's aabbggrr (alpha, blue, green, red). Getting this wrong swaps red/blue. */
    private fun argbToKml(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return "%02x%02x%02x%02x".format(a, b, g, r)
    }

    private fun isFirstNet(cellBand: String?): Boolean =
        cellBand?.let { it == "B14" || it.split("/").any { b -> b.trim() == "n14" } } ?: false

    private fun freePopup(p: TrackPoint): String = buildString {
        append("seq ").append(p.sequence).append("<br/>")
        if (p.rsrpDbm != null) {
            append("<b>").append(xml(p.rat ?: "Cellular")).append(p.cellBand?.let { " · ${xml(it)}" } ?: "").append("</b><br/>")
            append("RSRP ").append(p.rsrpDbm).append(" dBm")
            p.sinrDb?.let { append(" · SINR ").append(it).append(" dB") }
            p.rsrqDb?.let { append(" · RSRQ ").append(it).append(" dB") }
            append("<br/>")
            p.servingPci?.let { append("PCI ").append(it).append("<br/>") }
            p.networkOperator?.let { append(xml(it)).append("<br/>") }
        } else if (p.rssiDbm != null) {
            append("<b>Wi-Fi").append(p.band?.let { " · ${xml(it)}" } ?: "").append("</b><br/>")
            append("RSSI ").append(p.rssiDbm).append(" dBm<br/>")
            p.ssid?.let { append(xml(it)).append("<br/>") }
        }
        p.accuracyM?.let { append("GPS ±").append(it.toInt()).append(" m") }
    }

    private fun cellPopup(p: TrackPoint, pro: Boolean): String = buildString {
        append("<b>").append(xml(p.rat ?: "Cellular")).append(p.cellBand?.let { " · ${xml(it)}" } ?: "").append("</b><br/>")
        p.rsrpDbm?.let { append("RSRP ").append(it).append(" dBm") }
        p.sinrDb?.let { append(" · SINR ").append(it).append(" dB") }
        p.rsrqDb?.let { append(" · RSRQ ").append(it).append(" dB") }
        append("<br/>")
        p.servingPci?.let { append("PCI ").append(it) }
        p.lteServingEarfcn?.let { append(" · EARFCN ").append(it) }
        p.nrServingArfcn?.let { append(" · NR-ARFCN ").append(it) }
        append("<br/>")
        p.networkOperator?.let { append(xml(it)) }
        if (p.mcc != null && p.mnc != null) append(" (").append(p.mcc).append("/").append(p.mnc).append(")")
        append("<br/>")
        val nbrs = p.cells.filter { !it.serving }
        if (nbrs.isNotEmpty()) {
            append("<b>Neighbours").append(if (pro && p.modemNeighbours == true) " (modem)" else "").append(":</b><br/>")
            nbrs.take(if (pro) 8 else 4).forEach { c ->
                append("· PCI ").append(c.pci ?: "—")
                c.band?.let { append(" ").append(xml(it)) }
                c.rsrpDbm?.let { append(" ").append(it).append(" dBm") }
                append("<br/>")
            }
        }
        if (pro) {
            p.servingNci?.let { nci ->
                append("NCI ").append(nci)
                NrCellId.split(nci)?.let { append(" (gNB ").append(it.gnbId).append(" / cell ").append(it.cellId).append(")") }
                append("<br/>")
            }
            if (p.nrServingPci != null || p.lteServingPci != null) {
                append("EN-DC:")
                p.lteServingPci?.let { append(" LTE PCI ").append(it) }
                p.nrServingPci?.let { append(" + NR PCI ").append(it) }
                append("<br/>")
            }
        }
        p.accuracyM?.let { append("GPS ±").append(it.toInt()).append(" m") }
    }

    private fun wifiPopup(p: TrackPoint): String = buildString {
        append("<b>Wi-Fi").append(p.band?.let { " · ${xml(it)}" } ?: "").append("</b><br/>")
        append("RSSI ").append(p.rssiDbm).append(" dBm<br/>")
        p.ssid?.let { append("SSID ").append(xml(it)).append("<br/>") }
        p.bssid?.let { append("BSSID ").append(it).append("<br/>") }
        p.channel?.let { append("ch ").append(it) }
        p.coChannel?.let { append(" · co-ch ").append(it) }
        p.adjacentChannel?.let { append(" · adj ").append(it) }
        append("<br/>")
        p.accuracyM?.let { append("GPS ±").append(it.toInt()).append(" m") }
    }

    /**
     * GeoJSON for QGIS or a web map, tiered to match [writeKml]. Every point feature carries a
     * `radio` ("cellular"/"wifi"), a `marker_color` and a `*_bucket` (so a GIS symbolises on the
     * same thresholds the app and KML use), then:
     *  - **FREE**  — serving-radio KPIs only (RSRP/SINR/RSRQ/PCI or RSSI/SSID).
     *  - **FIELD** — adds EARFCN/NR-ARFCN, operator/MCC-MNC, neighbour count, FirstNet flag,
     *    throughput where sampled, and separate `kind:"event"` features (the PDF's mobility events).
     *  - **PRO**   — adds the NCI gNB/cell split, both EN-DC legs, a modem-neighbour flag and
     *    lock-watch fields.
     *
     * The previous writer was Wi-Fi-only and dropped all cellular data; this makes GeoJSON a true
     * dual-radio export.
     */
    suspend fun writeGeoJson(
        summary: SessionSummary,
        allPoints: List<TrackPoint>,
        out: File,
        tier: SubscriptionTier = SubscriptionTier.FREE,
    ): File =
        withContext(Dispatchers.IO) {
            val points = geoOnly(allPoints)
            val field = tier.grantsField
            val pro = tier.grantsPro
            val sb = StringBuilder(1 shl 16)
            sb.append("{\"type\":\"FeatureCollection\",")
            sb.append("\"name\":").append(json(summary.displayName)).append(",")
            sb.append("\"tier\":").append(json(tier.name.lowercase())).append(",")
            sb.append("\"features\":[\n")

            // Track line first so point features draw on top of it in most renderers.
            sb.append("{\"type\":\"Feature\",\"properties\":{\"kind\":\"track\"},")
            sb.append("\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            points.forEachIndexed { i, p ->
                if (i > 0) sb.append(',')
                sb.append('[').append(String.format(Locale.US, "%.6f,%.6f", p.longitudeDeg, p.latitudeDeg)).append(']')
            }
            sb.append("]}}")

            for (p in points) {
                val onCell = p.rsrpDbm != null || p.cellBand != null
                sb.append(",\n{\"type\":\"Feature\",\"properties\":{")
                sb.append("\"kind\":\"sample\"")
                sb.append(",\"seq\":").append(p.sequence)
                sb.append(",\"time\":").append(json(Instant.ofEpochMilli(p.timestampUtcMillis).toString()))
                sb.append(",\"radio\":").append(json(if (onCell) "cellular" else "wifi"))

                if (onCell) {
                    p.rat?.let { sb.append(",\"rat\":").append(json(it)) }
                    p.cellBand?.let { sb.append(",\"band\":").append(json(it)) }
                    p.rsrpDbm?.let { sb.append(",\"rsrp_dbm\":").append(it) }
                    p.sinrDb?.let { sb.append(",\"sinr_db\":").append(it) }
                    p.rsrqDb?.let { sb.append(",\"rsrq_db\":").append(it) }
                    p.servingPci?.let { sb.append(",\"pci\":").append(it) }
                    RsrpBucket.of(p.rsrpDbm)?.let {
                        sb.append(",\"rsrp_bucket\":").append(json(it.name))
                        sb.append(",\"marker_color\":").append(json("#%06X".format(it.argb and 0xFFFFFF)))
                    }
                    if (field) {
                        p.networkOperator?.let { sb.append(",\"operator\":").append(json(it)) }
                        p.mcc?.let { sb.append(",\"mcc\":").append(json(it)) }
                        p.mnc?.let { sb.append(",\"mnc\":").append(json(it)) }
                        p.lteServingEarfcn?.let { sb.append(",\"earfcn\":").append(it) }
                        p.nrServingArfcn?.let { sb.append(",\"nrarfcn\":").append(it) }
                        val nbrs = p.cells.filter { !it.serving }
                        if (nbrs.isNotEmpty()) sb.append(",\"neighbour_count\":").append(nbrs.size)
                        if (isFirstNet(p.cellBand)) sb.append(",\"firstnet\":true")
                    }
                    if (pro) {
                        p.servingNci?.let { nci ->
                            sb.append(",\"nci\":").append(nci)
                            NrCellId.split(nci)?.let {
                                sb.append(",\"gnb_id\":").append(it.gnbId)
                                sb.append(",\"cell_id\":").append(it.cellId)
                            }
                        }
                        p.nrServingPci?.let { sb.append(",\"endc_nr_pci\":").append(it) }
                        p.lteServingPci?.let { sb.append(",\"endc_lte_pci\":").append(it) }
                        if (p.modemNeighbours == true) sb.append(",\"modem_neighbours\":true")
                        p.lockWatchPci?.let { sb.append(",\"lockwatch_pci\":").append(it) }
                        p.lockWatchArfcn?.let { sb.append(",\"lockwatch_arfcn\":").append(it) }
                    }
                } else {
                    p.rssiDbm?.let { sb.append(",\"wifi_rssi\":").append(it) }
                    p.ssid?.let { sb.append(",\"wifi_ssid\":").append(json(it)) }
                    p.bssid?.let { sb.append(",\"wifi_bssid\":").append(json(it)) }
                    p.channel?.let { sb.append(",\"wifi_channel\":").append(it) }
                    p.band?.let { sb.append(",\"wifi_band\":").append(json(it)) }
                    p.coChannel?.let { sb.append(",\"wifi_cochannel_count\":").append(it) }
                    p.adjacentChannel?.let { sb.append(",\"wifi_adjacent_count\":").append(it) }
                    RssiBucket.of(p.rssiDbm)?.let {
                        sb.append(",\"rssi_bucket\":").append(json(it.name))
                        sb.append(",\"marker_color\":").append(json("#%06X".format(it.argb and 0xFFFFFF)))
                    }
                }

                // Throughput where it was sampled — a Field capability, carried on the point itself.
                if (field) {
                    p.downloadMbps?.let { sb.append(",\"download_mbps\":").append(it) }
                    p.uploadMbps?.let { sb.append(",\"upload_mbps\":").append(it) }
                }
                p.accuracyM?.let { sb.append(",\"gps_accuracy_m\":").append(it) }
                p.speedMps?.let { sb.append(",\"speed_mps\":").append(it) }

                sb.append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                sb.append(String.format(Locale.US, "%.6f,%.6f", p.longitudeDeg, p.latitudeDeg))
                sb.append("]}}")
            }

            // Field+: the same mobility events the PDF and KML use, as their own features.
            if (field) {
                val events = MobilityEvents.analyse(points).events
                    .filter { it.latitudeDeg != null && it.longitudeDeg != null }
                for (e in events) {
                    sb.append(",\n{\"type\":\"Feature\",\"properties\":{")
                    sb.append("\"kind\":\"event\"")
                    sb.append(",\"event\":").append(json(e.kind.name.lowercase()))
                    if (e.cause != MobilityEvents.Cause.NONE) {
                        sb.append(",\"cause\":").append(json(e.cause.name.lowercase()))
                    }
                    sb.append(",\"detail\":").append(json(e.detail))
                    sb.append(",\"seq\":").append(e.sequence)
                    sb.append(",\"time\":").append(json(Instant.ofEpochMilli(e.atUtcMillis).toString()))
                    sb.append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                    sb.append(String.format(Locale.US, "%.6f,%.6f", e.longitudeDeg, e.latitudeDeg))
                    sb.append("]}}")
                }
            }

            sb.append("\n]}\n")
            out.writeText(sb.toString())
            out
        }

    private fun xml(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun json(s: String) = "\"" + s
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
