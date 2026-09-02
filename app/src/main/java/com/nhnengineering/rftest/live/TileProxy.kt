package com.nhnengineering.rftest.live

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Satellite tiles for the live view, fetched by the phone and cached on it.
 *
 * ## Why the phone fetches them, not the browser
 *
 * The laptop is cabled to a phone in a basement and frequently has no internet of its own. The
 * phone does — that is the entire subject of the survey. So the page asks the phone for tiles and
 * the phone fetches them over the connection being measured.
 *
 * That has an obvious cost and it has to be stated plainly: **tile fetches consume the same radio
 * the survey is measuring.** They are small and cached, but they are not free, and a throughput
 * burst overlapping a tile fetch is measuring both. The cache exists mostly to bound that: a tile
 * is fetched once per session, and panning back over ground already walked costs nothing.
 *
 * ## Licensing
 *
 * The default source is Esri World Imagery, which needs no API key and requires attribution — the
 * page carries it. **Confirm Esri's terms permit commercial survey deliverables before a report
 * built on these tiles goes to a paying client.** That is a business decision, not a technical one,
 * and this class does not pretend to have made it: the URL template is a constructor argument, so
 * moving to a paid source with unambiguous commercial terms is a one-line change and no rework.
 */
class TileProxy(
    private val cacheDir: File,
    private val urlTemplate: String = ESRI_WORLD_IMAGERY,
) {

    /**
     * Fetches one tile, from cache where possible.
     *
     * Returns null rather than throwing: a missing tile should leave a blank square under a trail
     * that still draws correctly, not take down the live view. Losing the base map mid-walk is an
     * inconvenience; losing the position display is not.
     */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (z !in 0..22) return null
        val max = 1 shl z
        if (x < 0 || y < 0 || x >= max || y >= max) return null

        val cached = File(cacheDir, "$z-$x-$y.jpg")
        if (cached.isFile && cached.length() > 0) {
            return runCatching { cached.readBytes() }.getOrNull()
        }

        val url = urlTemplate
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())

        return runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // Identifying the client is the polite minimum when using someone else's tiles,
                // and some providers reject requests without it outright.
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (c.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "tile $z/$x/$y returned HTTP ${c.responseCode}")
                    return null
                }
                val bytes = c.inputStream.use { it.readBytes() }
                // Written via a temporary file: a half-written tile left by a process killed
                // mid-walk would be served from cache forever afterwards.
                runCatching {
                    cacheDir.mkdirs()
                    val tmp = File(cacheDir, "$z-$x-$y.tmp")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(cached)
                }
                bytes
            } finally {
                runCatching { c.disconnect() }
            }
        }.onFailure { Log.w(TAG, "tile $z/$x/$y failed", it) }.getOrNull()
    }

    /** Bytes currently cached, for the UI to show what the base map has cost. */
    fun cacheBytes(): Long =
        cacheDir.listFiles()?.filter { it.extension == "jpg" }?.sumOf { it.length() } ?: 0L

    fun clearCache() {
        cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    companion object {
        private const val TAG = "TileProxy"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
        private const val USER_AGENT = "NHN-RF-Survey/1.0 (field measurement tool)"

        /**
         * Esri World Imagery. No API key. Note the axis order is `{z}/{y}/{x}`, not `{z}/{x}/{y}`
         * as most XYZ schemes use — getting it backwards yields tiles that load successfully and
         * show entirely the wrong place, which is worse than tiles that fail.
         */
        const val ESRI_WORLD_IMAGERY =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

        const val ATTRIBUTION = "Imagery © Esri, Maxar, Earthstar Geographics"
    }
}
