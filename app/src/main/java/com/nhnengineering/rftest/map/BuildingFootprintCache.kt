package com.nhnengineering.rftest.map

import android.content.Context
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
    private val failed = mutableSetOf<BuildingFootprintProxy.BboxCell>()

    /** Caps concurrent fetches, the same reason [TileCache] does: unbounded parallelism here would
     *  compete with the radio the survey is measuring. */
    private val gate = Semaphore(MAX_CONCURRENT_FETCHES)

    /** The footprints if already fetched; otherwise null, having possibly started a fetch. */
    fun get(cell: BuildingFootprintProxy.BboxCell): List<Polygon>? {
        memory[cell]?.let { return it }
        if (cell in failed) return null

        synchronized(inFlight) {
            if (!inFlight.add(cell)) return null
        }
        scope.launch(Dispatchers.IO) {
            gate.withPermit {
                val polygons = proxy.footprints(cell)
                if (polygons != null) {
                    memory[cell] = polygons
                } else {
                    synchronized(failed) { failed.add(cell) }
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
        synchronized(failed) { failed.clear() }
        proxy.clearCache()
    }

    private companion object {
        const val MAX_CONCURRENT_FETCHES = 2
    }
}
