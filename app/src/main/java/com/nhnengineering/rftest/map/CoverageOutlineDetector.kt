package com.nhnengineering.rftest.map

import android.graphics.Bitmap
import com.nhnengineering.rftest.model.CoverageArea
import com.nhnengineering.rftest.model.CoverageVertex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Android wrapper around [CoverageOutlineTrace]: samples the floorplan bitmap at the tapped point and
 * returns the enclosed coverage polygon, or null when nothing usable is found.
 *
 * Runs off the main thread and downscales large rasters (a vendor PDF page can be many megapixels) to a
 * bounded working size before tracing — normalised coordinates make the result resolution-independent,
 * so the downscale costs accuracy only below the cap, not correctness.
 */
object CoverageOutlineDetector {

    suspend fun detect(
        bitmap: Bitmap,
        tapXNorm: Float,
        tapYNorm: Float,
        maxDim: Int = 1600,
    ): CoverageArea? = withContext(Dispatchers.Default) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return@withContext null
        val scale = min(1.0, maxDim.toDouble() / max(bitmap.width, bitmap.height))
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val working = if (scale < 1.0) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
        val px = IntArray(w * h)
        working.getPixels(px, 0, w, 0, 0, w, h)
        if (working !== bitmap) working.recycle()

        val tapX = (tapXNorm * w).toInt().coerceIn(0, w - 1)
        val tapY = (tapYNorm * h).toInt().coerceIn(0, h - 1)
        val verts = CoverageOutlineTrace.detect(px, w, h, tapX, tapY) ?: return@withContext null
        CoverageArea(verts.map { CoverageVertex(it.first, it.second) })
    }
}
