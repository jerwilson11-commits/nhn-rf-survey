package com.nhnengineering.rftest.model

/**
 * Indoor positioning — a manual answer to a problem GPS cannot solve.
 *
 * Inside a venue, GPS accuracy collapses from a few metres to tens of metres, or the fix disappears
 * entirely. Samples still carry perfectly good RF data, but a coverage measurement you cannot place
 * on a map is most of the way to useless. The measured driveway walk held ±3 m; a concrete-framed
 * resort interior will not.
 *
 * So the operator places themselves by hand. That is how commercial walk-test tools have always
 * handled indoor work, and it is more accurate than any consumer-grade automatic alternative.
 */

/**
 * A position on a floorplan image.
 *
 * Coordinates are **normalised to the image**, 0.0–1.0 on each axis, rather than stored in pixels.
 * Pixel coordinates would be tied to the display size at the moment of capture and would silently
 * break the instant the image is shown at a different zoom, on a different screen, or re-exported
 * at another resolution. Normalised coordinates survive all of that: multiply by the image
 * dimensions whenever you need pixels.
 */
data class IndoorPosition(
    /** Stable identifier for the floorplan image — its filename in app storage. */
    val floorplanId: String,
    /** 0.0 = left edge, 1.0 = right edge. */
    val xNorm: Float,
    /** 0.0 = top edge, 1.0 = bottom edge. */
    val yNorm: Float,
    /** Optional operator label — "Lobby", "Elevator bank 2", "Ballroom NE corner". */
    val label: String? = null,
) {
    init {
        require(xNorm in 0f..1f && yNorm in 0f..1f) {
            "floorplan coordinates must be normalised to 0..1, got ($xNorm, $yNorm)"
        }
    }
}

/** A floorplan image available to place positions on. */
data class Floorplan(
    val id: String,
    val displayName: String,
    val widthPx: Int,
    val heightPx: Int,
) {
    val aspectRatio: Float get() = if (heightPx > 0) widthPx.toFloat() / heightPx else 1f
}
