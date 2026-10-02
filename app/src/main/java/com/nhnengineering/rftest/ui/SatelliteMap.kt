package com.nhnengineering.rftest.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.nhnengineering.rftest.live.TileProxy
import com.nhnengineering.rftest.map.Mercator
import com.nhnengineering.rftest.map.TileCache
import kotlin.math.floor

/**
 * An interactive Esri satellite map: pan by drag, pinch to zoom, tap to read a latitude/longitude.
 *
 * Tiles come from [TileCache], which serves whatever is decoded now and fetches the rest off-thread,
 * signalling a redraw when they land -- so the map never blocks the UI thread fetching imagery. The
 * centre and zoom are hoisted so the georeference screen can place the view near the building and
 * remember it across its sub-steps.
 *
 * Coordinates throughout are Web Mercator world pixels (tile * 256) at the integer [zoom], the same
 * projection the satellite tiles are drawn in and the report already uses, so a tapped point lands
 * exactly where the imagery shows it.
 */
@Composable
fun SatelliteMap(
    modifier: Modifier = Modifier,
    centerLat: Double,
    centerLon: Double,
    zoom: Int,
    onCenterChange: (Double, Double) -> Unit,
    onZoomChange: (Int) -> Unit,
    onTapLatLon: (Double, Double) -> Unit,
    /** lat/lon points to mark on the map (completed tie points / the pending satellite pick). */
    markers: List<Pair<Double, Double>> = emptyList(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var redraw by remember { mutableIntStateOf(0) }
    val cache = remember { TileCache(context, scope, TileProxy.Basemap.SATELLITE) { redraw++ } }

    // Latest hoisted values, so the long-lived gesture coroutines (keyed on Unit) always act on the
    // current centre/zoom rather than a value frozen when the pointerInput first ran.
    val lat by rememberUpdatedState(centerLat)
    val lon by rememberUpdatedState(centerLon)
    val z by rememberUpdatedState(zoom)
    val onCenter by rememberUpdatedState(onCenterChange)
    val onZoom by rememberUpdatedState(onZoomChange)
    val onTap by rememberUpdatedState(onTapLatLon)
    val markerState by rememberUpdatedState(markers)

    Box(
        modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    // Pan: shift the centre by the drag, opposite direction, in world pixels.
                    val ts = Mercator.TILE_SIZE
                    val cx = Mercator.lonToTileX(lon, z) * ts - pan.x
                    val cy = Mercator.latToTileY(lat, z) * ts - pan.y
                    onCenter(Mercator.tileYToLat(cy / ts, z), Mercator.tileXToLon(cx / ts, z))
                    // Zoom: step the integer level when a pinch passes a threshold.
                    if (gestureZoom > 1.15f && z < TileProxy.Basemap.SATELLITE.maxZoom) onZoom(z + 1)
                    else if (gestureZoom < 0.87f && z > 2) onZoom(z - 1)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val ts = Mercator.TILE_SIZE
                    val originX = Mercator.lonToTileX(lon, z) * ts - size.width / 2.0
                    val originY = Mercator.latToTileY(lat, z) * ts - size.height / 2.0
                    val worldX = originX + tap.x
                    val worldY = originY + tap.y
                    onTap(Mercator.tileYToLat(worldY / ts, z), Mercator.tileXToLon(worldX / ts, z))
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            redraw // read so tile arrivals trigger a redraw
            val ts = Mercator.TILE_SIZE
            val n = 1 shl z
            val originX = Mercator.lonToTileX(lon, z) * ts - size.width / 2.0
            val originY = Mercator.latToTileY(lat, z) * ts - size.height / 2.0

            val tx0 = floor(originX / ts).toInt()
            val ty0 = floor(originY / ts).toInt()
            val tx1 = floor((originX + size.width) / ts).toInt()
            val ty1 = floor((originY + size.height) / ts).toInt()

            for (ty in ty0..ty1) {
                if (ty < 0 || ty >= n) continue
                for (tx in tx0..tx1) {
                    val wrapped = ((tx % n) + n) % n   // longitude wraps around the world
                    val bmp = cache.get(z, wrapped, ty) ?: continue
                    val left = (tx * ts - originX).toFloat()
                    val top = (ty * ts - originY).toFloat()
                    drawContext.canvas.nativeCanvas.drawBitmap(bmp, left, top, null)
                }
            }

            // Markers.
            val ring = Color(0xFFFFEB3B).toArgb()
            markerState.forEach { (mlat, mlon) ->
                val sx = (Mercator.lonToTileX(mlon, z) * ts - originX).toFloat()
                val sy = (Mercator.latToTileY(mlat, z) * ts - originY).toFloat()
                drawCircle(Color.Black, radius = 9f, center = Offset(sx, sy))
                drawCircle(Color(ring), radius = 6f, center = Offset(sx, sy))
            }

            // Centre crosshair, so a tap can be aimed precisely.
            val cxp = size.width / 2f
            val cyp = size.height / 2f
            drawLine(Color.White, Offset(cxp - 16f, cyp), Offset(cxp + 16f, cyp), strokeWidth = 1.5f)
            drawLine(Color.White, Offset(cxp, cyp - 16f), Offset(cxp, cyp + 16f), strokeWidth = 1.5f)
        }
    }
}
