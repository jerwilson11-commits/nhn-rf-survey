package com.nhnengineering.rftest.session

import com.nhnengineering.rftest.model.RssiBucket
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
        String.format(Locale.US, "%.6f,%.6f,0", p.longitudeDeg, p.latitudeDeg)

    suspend fun writeKml(summary: SessionSummary, points: List<TrackPoint>, out: File): File =
        withContext(Dispatchers.IO) {
            val sb = StringBuilder(1 shl 16)
            sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            sb.append("""<kml xmlns="http://www.opengis.net/kml/2.2">""").append('\n')
            sb.append("<Document>\n")
            sb.append("<name>").append(xml(summary.displayName)).append("</name>\n")
            sb.append("<description>").append(
                xml(
                    "RF Test App session. ${summary.pointCount} geo-tagged samples of " +
                        "${summary.rowCount} rows. RSSI ${summary.rssiMax ?: "?"} to " +
                        "${summary.rssiMin ?: "?"} dBm."
                )
            ).append("</description>\n")

            // One style per colour bucket, referenced by id. Inlining a style per placemark would
            // multiply the file size by the sample count for no benefit.
            for (b in RssiBucket.entries) {
                sb.append("<Style id=\"rssi_").append(b.name).append("\">\n")
                sb.append("  <IconStyle><color>").append(b.toKmlColor()).append("</color>")
                sb.append("<scale>0.7</scale>")
                sb.append("<Icon><href>http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon>")
                sb.append("</IconStyle>\n")
                sb.append("  <LabelStyle><scale>0</scale></LabelStyle>\n")
                sb.append("</Style>\n")
            }
            sb.append("<Style id=\"track\"><LineStyle><color>ffcccccc</color><width>2</width></LineStyle></Style>\n")

            sb.append("<Placemark><name>Track</name><styleUrl>#track</styleUrl>\n")
            sb.append("<LineString><tessellate>1</tessellate><coordinates>\n")
            points.forEach { sb.append(coord(it)).append(' ') }
            sb.append("\n</coordinates></LineString></Placemark>\n")

            sb.append("<Folder><name>Samples</name>\n")
            for (p in points) {
                val bucket = RssiBucket.of(p.rssiDbm)
                sb.append("<Placemark>\n")
                sb.append("  <name>").append(p.rssiDbm?.let { "$it dBm" } ?: "no RF").append("</name>\n")
                if (bucket != null) sb.append("  <styleUrl>#rssi_").append(bucket.name).append("</styleUrl>\n")
                sb.append("  <TimeStamp><when>")
                    .append(Instant.ofEpochMilli(p.timestampUtcMillis).toString())
                    .append("</when></TimeStamp>\n")
                sb.append("  <description><![CDATA[")
                sb.append("seq ").append(p.sequence).append("<br/>")
                p.rssiDbm?.let { sb.append("RSSI ").append(it).append(" dBm<br/>") }
                p.ssid?.let { sb.append("SSID ").append(xml(it)).append("<br/>") }
                p.bssid?.let { sb.append("BSSID ").append(it).append("<br/>") }
                p.channel?.let { sb.append("ch ").append(it) }
                p.band?.let { sb.append(" · ").append(xml(it)).append("<br/>") }
                p.coChannel?.let { sb.append("co-channel ").append(it).append("<br/>") }
                p.adjacentChannel?.let { sb.append("adjacent ").append(it).append("<br/>") }
                p.accuracyM?.let { sb.append("GPS ±").append(it.toInt()).append(" m") }
                sb.append("]]></description>\n")
                sb.append("  <Point><coordinates>").append(coord(p)).append("</coordinates></Point>\n")
                sb.append("</Placemark>\n")
            }
            sb.append("</Folder>\n</Document>\n</kml>\n")

            out.writeText(sb.toString())
            out
        }

    suspend fun writeGeoJson(summary: SessionSummary, points: List<TrackPoint>, out: File): File =
        withContext(Dispatchers.IO) {
            val sb = StringBuilder(1 shl 16)
            sb.append("{\"type\":\"FeatureCollection\",")
            sb.append("\"name\":").append(json(summary.displayName)).append(",")
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
                sb.append(",\n{\"type\":\"Feature\",\"properties\":{")
                sb.append("\"seq\":").append(p.sequence)
                sb.append(",\"time\":").append(json(Instant.ofEpochMilli(p.timestampUtcMillis).toString()))
                p.rssiDbm?.let { sb.append(",\"wifi_rssi\":").append(it) }
                p.ssid?.let { sb.append(",\"wifi_ssid\":").append(json(it)) }
                p.bssid?.let { sb.append(",\"wifi_bssid\":").append(json(it)) }
                p.channel?.let { sb.append(",\"wifi_channel\":").append(it) }
                p.band?.let { sb.append(",\"wifi_band\":").append(json(it)) }
                p.coChannel?.let { sb.append(",\"wifi_cochannel_count\":").append(it) }
                p.adjacentChannel?.let { sb.append(",\"wifi_adjacent_count\":").append(it) }
                p.accuracyM?.let { sb.append(",\"gps_accuracy_m\":").append(it) }
                p.speedMps?.let { sb.append(",\"speed_mps\":").append(it) }
                RssiBucket.of(p.rssiDbm)?.let {
                    // Carried so a GIS can symbolise on the same buckets the app and the KML use,
                    // instead of inventing a third set of thresholds.
                    sb.append(",\"rssi_bucket\":").append(json(it.name))
                    sb.append(",\"marker_color\":").append(json("#%06X".format(it.argb and 0xFFFFFF)))
                }
                sb.append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                sb.append(String.format(Locale.US, "%.6f,%.6f", p.longitudeDeg, p.latitudeDeg))
                sb.append("]}}")
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
