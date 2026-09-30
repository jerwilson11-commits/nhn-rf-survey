package com.nhnengineering.rftest.live

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One building footprint, a closed ring of (lat, lon) pairs. */
typealias Polygon = List<Pair<Double, Double>>

/**
 * Building footprints for the map, fetched from OpenStreetMap's public Overpass API and cached on
 * the phone -- the same shape as [TileProxy], for the same reason: this is a field tool, and a
 * missing overlay should leave the map exactly as useful as it was before this existed, not take
 * anything down.
 *
 * ## Why Overpass, and why this is not a permanent architecture
 *
 * The map itself is raster tiles (see [TileProxy]) with no building layer of any kind -- there is
 * nothing to enable, because raster imagery carries no vector geometry. OpenStreetMap's building
 * footprints, queried live via the public Overpass API, are the only building-outline data source
 * this app can reach without a wholesale map-SDK replacement.
 *
 * That public instance (`overpass-api.de`) is a shared community resource with a stated casual-use
 * guideline of under 100 queries and 10 MB per day. Quantizing requests to a coarse grid (see
 * [BuildingFootprintProxy.quantize]) keeps a normal survey comfortably inside that. If this app's
 * install volume ever grows enough that many concurrent surveys are hitting the same public
 * instance, that stops being polite and would need a self-hosted mirror or a different building
 * data source -- a scaling problem to solve later, not a reason to withhold the feature now.
 *
 * ## Licensing
 *
 * OpenStreetMap data is ODbL-licensed: using it requires visible "© OpenStreetMap contributors"
 * attribution wherever it's shown, alongside the existing basemap attribution.
 */
class BuildingFootprintProxy(private val cacheDir: File) {

    data class BboxCell(val south: Double, val west: Double, val north: Double, val east: Double)

    /**
     * Footprints covering [cell], from cache where possible.
     *
     * Returns null rather than throwing -- a missing overlay should leave the map exactly as it
     * was before this existed, not break anything else on it.
     */
    fun footprints(cell: BboxCell): List<Polygon>? {
        val cached = File(cacheDir, "${cacheKey(cell)}.json")
        val json = if (cached.isFile && cached.length() > 0) {
            runCatching { cached.readText() }.getOrNull()
        } else {
            fetchAndCache(cell, cached)
        } ?: return null
        return runCatching { parseBuildings(json) }.getOrNull()
    }

    private fun fetchAndCache(cell: BboxCell, cached: File): String? = runCatching {
        val query = buildOverpassQuery(cell)
        val c = (URL(OVERPASS_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            doOutput = true
        }
        try {
            c.outputStream.use {
                it.write("data=${java.net.URLEncoder.encode(query, "UTF-8")}".toByteArray())
            }
            if (c.responseCode != HttpURLConnection.HTTP_OK) {
                // Overpass's own guidance: back off on 429/406 rather than retry immediately.
                // This class does not retry at all -- the next pan/zoom naturally tries again
                // later, which is backoff enough for a field tool.
                Log.w(TAG, "footprints $cell returned HTTP ${c.responseCode}")
                return null
            }
            val text = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            runCatching {
                cacheDir.mkdirs()
                val tmp = File(cacheDir, "${cacheKey(cell)}.tmp")
                tmp.writeText(text)
                tmp.renameTo(cached)
            }
            text
        } finally {
            runCatching { c.disconnect() }
        }
    }.onFailure { Log.w(TAG, "footprints $cell failed", it) }.getOrNull()

    /** Bytes currently cached, for the UI to show what building outlines have cost. */
    fun cacheBytes(): Long =
        cacheDir.listFiles()?.filter { it.extension == "json" }?.sumOf { it.length() } ?: 0L

    fun clearCache() {
        cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    companion object {
        private const val TAG = "BuildingFootprintProxy"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val USER_AGENT = "NHN-RF-Survey/1.0 (field measurement tool)"
        const val OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"
        const val ATTRIBUTION = "© OpenStreetMap contributors"

        private fun cacheKey(cell: BboxCell): String =
            "bld-%.4f-%.4f-%.4f-%.4f".format(cell.south, cell.west, cell.north, cell.east)
    }
}

/**
 * Rounds a point down to the cell of a fixed-size grid it falls in, so repeated requests for the
 * same area produce the same cache key and the same Overpass query -- panning within roughly 500 m
 * (the default cell size) costs nothing after the first fetch.
 */
internal fun quantize(
    lat: Double,
    lon: Double,
    cellSizeDeg: Double = 0.005,
): BuildingFootprintProxy.BboxCell {
    val south = Math.floor(lat / cellSizeDeg) * cellSizeDeg
    val west = Math.floor(lon / cellSizeDeg) * cellSizeDeg
    return BuildingFootprintProxy.BboxCell(south, west, south + cellSizeDeg, west + cellSizeDeg)
}

/** `way["building"]` within [cell], with geometry resolved server-side (`out geom`) so each way's
 *  `geometry` array is usable directly, with no separate node-reference resolution pass needed. */
internal fun buildOverpassQuery(cell: BuildingFootprintProxy.BboxCell): String =
    "[out:json][timeout:25];way[\"building\"](%.6f,%.6f,%.6f,%.6f);out geom;".format(
        cell.south,
        cell.west,
        cell.north,
        cell.east,
    )

/** Parses an Overpass `out geom` JSON response into one [Polygon] per `way` element. A way with no
 *  usable geometry (fewer than 3 points) is skipped rather than producing a degenerate shape. */
internal fun parseBuildings(json: String): List<Polygon> {
    val elements = JSONObject(json).optJSONArray("elements") ?: JSONArray()
    val polygons = mutableListOf<Polygon>()
    for (i in 0 until elements.length()) {
        val el = elements.optJSONObject(i) ?: continue
        if (el.optString("type") != "way") continue
        val geometry = el.optJSONArray("geometry") ?: continue
        val ring = mutableListOf<Pair<Double, Double>>()
        for (j in 0 until geometry.length()) {
            val pt = geometry.optJSONObject(j) ?: continue
            val lat = pt.optDouble("lat", Double.NaN)
            val lon = pt.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            ring += lat to lon
        }
        if (ring.size >= 3) polygons += ring
    }
    return polygons
}
