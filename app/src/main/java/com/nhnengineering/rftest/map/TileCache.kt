package com.nhnengineering.rftest.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import com.nhnengineering.rftest.live.TileProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * Decoded tiles for the on-device map.
 *
 * Sits on top of [TileProxy], which owns the network fetch and the disk cache. This layer holds
 * decoded bitmaps in memory, because decoding a JPEG on every frame of a redraw would make the map
 * stutter while walking — which is when it is being looked at.
 *
 * **Requests are non-blocking and deduplicated.** A Compose canvas redraws often; asking for the
 * same missing tile on every frame would queue dozens of identical fetches over the cellular link
 * the survey is measuring. [get] therefore returns whatever is in memory *now*, starts at most one
 * fetch per tile, and signals through [onLoaded] when a redraw is worth doing.
 */
class TileCache(
    context: Context,
    private val scope: CoroutineScope,
    private val onLoaded: () -> Unit,
) {

    private val proxy = TileProxy(File(context.cacheDir, "tiles"))

    // Roughly an eighth of the app's heap. A 256px ARGB_8888 tile is 256 KB, so this holds a few
    // dozen — comfortably more than one screenful, which is all that is ever drawn at once.
    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val inFlight = mutableSetOf<String>()

    /**
     * Caps concurrent fetches. Unbounded parallelism here would saturate the radio during a
     * throughput burst and corrupt the very measurement the map is displaying.
     */
    private val gate = Semaphore(MAX_CONCURRENT_FETCHES)

    /** Tiles that were requested and definitively failed, so they are not retried every frame. */
    private val failed = mutableSetOf<String>()

    /** The bitmap if it is already decoded; otherwise null, having possibly started a fetch. */
    fun get(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        memory.get(key)?.let { return it }
        if (key in failed) return null

        synchronized(inFlight) {
            if (!inFlight.add(key)) return null
        }
        scope.launch(Dispatchers.IO) {
            gate.withPermit {
                val bytes = proxy.tile(z, x, y)
                val bitmap = bytes?.let {
                    runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
                }
                if (bitmap != null) {
                    memory.put(key, bitmap)
                } else {
                    synchronized(failed) { failed.add(key) }
                }
                synchronized(inFlight) { inFlight.remove(key) }
                if (bitmap != null) onLoaded()
            }
        }
        return null
    }

    fun diskBytes(): Long = proxy.cacheBytes()

    fun clear() {
        memory.evictAll()
        synchronized(failed) { failed.clear() }
        proxy.clearCache()
    }

    private companion object {
        const val MAX_CONCURRENT_FETCHES = 4
    }
}
