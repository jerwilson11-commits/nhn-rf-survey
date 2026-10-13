package com.nhnengineering.rftest.map

import android.content.Context
import android.os.SystemClock
import com.nhnengineering.rftest.live.BuildingFootprintProxy
import com.nhnengineering.rftest.live.Polygon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * Parsed building footprints for the map. Sits on top of [BuildingFootprintProxy] the same way
 * [TileCache] sits on [com.nhnengineering.rftest.live.TileProxy] -- non-blocking, deduplicated
 * requests, so a Compose canvas redrawing every frame does not queue a fetch per frame for ground
 * already requested.
 *
 * No `LruCache`/byte-size eviction here unlike [TileCache]'s decoded bitmaps: a parsed polygon list
 * is small (a handful of coordinate pairs per building), so a plain map holding every cell visited
 * this session is cheap enough not to need one.
 */
class BuildingFootprintCache(
    context: Context,
    private val scope: CoroutineScope,
    private val onLoaded: () -> Unit,
) {

    private val proxy = BuildingFootprintProxy(File(context.cacheDir, "buildings"))

    private val memory = mutableMapOf<BuildingFootprintProxy.BboxCell, List<Polygon>>()
    private val inFlight = mutableSetOf<BuildingFootprintProxy.BboxCell>()

    /**
     * When a cell's fetch may be tried again, keyed by cell (elapsedRealtime millis).
     *
     * Replaces a permanent "failed" set. Overpass is a shared, frequently-overloaded public endpoint
     * -- a single 504/429/timeout is the common case, not a verdict that the area has no buildings --
     * and marking the cell failed forever meant one transient error hid every building in that area
     * until the app restarted. A success (even an empty footprint list, which IS a real answer) is
     * cached in [memory] and never refetched; only a transient miss lands here, and a draw after the
     * backoff retries it.
     */
    private val retryAfter = mutableMapOf<BuildingFootprintProxy.BboxCell, Long>()

    /** Caps concurrent fetches, the same reason [TileCache] does: unbounded parallelism here would
     *  compete with the radio the survey is measuring. */
    private val gate = Semaphore(MAX_CONCURRENT_FETCHES)

    /** The footprints if already fetched; otherwise null, having possibly started a fetch. */
    fun get(cell: BuildingFootprintProxy.BboxCell): List<Polygon>? {
        memory[cell]?.let { return it }
        val now = SystemClock.elapsedRealtime()
        synchronized(retryAfter) {
            retryAfter[cell]?.let { if (now < it) return null }
        }

        synchronized(inFlight) {
            if (!inFlight.add(cell)) return null
        }
        scope.launch(Dispatchers.IO) {
            gate.withPermit {
                val polygons = proxy.footprints(cell)
                if (polygons != null) {
                    memory[cell] = polygons
                    synchronized(retryAfter) { retryAfter.remove(cell) }
                } else {
                    // Transient (Overpass 504/429/timeout). Allow a retry after a short backoff
                    // instead of failing the cell for the whole session.
                    synchronized(retryAfter) {
                        retryAfter[cell] = SystemClock.elapsedRealtime() + RETRY_BACKOFF_MS
                    }
                }
                synchronized(inFlight) { inFlight.remove(cell) }
                if (polygons != null) onLoaded()
            }
        }
        return null
    }

    fun diskBytes(): Long = proxy.cacheBytes()

    fun clear() {
        memory.clear()
        synchronized(retryAfter) { retryAfter.clear() }
        proxy.clearCache()
    }

    private companion object {
        const val MAX_CONCURRENT_FETCHES = 2

        /** How long to wait before re-trying a cell Overpass could not serve. Long enough not to
         *  hammer a struggling public endpoint, short enough that buildings appear once it recovers. */
        const val RETRY_BACKOFF_MS = 20_000L
    }
}
