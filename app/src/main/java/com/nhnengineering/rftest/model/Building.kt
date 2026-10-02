package com.nhnengineering.rftest.model

import com.nhnengineering.rftest.map.GeoReference
import com.nhnengineering.rftest.map.TiePoint

/**
 * One floor's registration within a [Building].
 *
 * The reference floor carries >= 2 satellite [tiePoints]. An upper floor usually carries none and is
 * placed instead by its [stackAnchorU]/[stackAnchorV] -- the shared vertical feature (lift core,
 * stair) marked on its own sheet -- inheriting the reference floor's scale and rotation. A floor may
 * also carry its own >= 2 tie points to be georeferenced independently.
 */
data class FloorGeoref(
    val floorplanId: String,
    val widthPx: Int,
    val heightPx: Int,
    val label: String,
    val tiePoints: List<TiePoint> = emptyList(),
    val stackAnchorU: Double? = null,
    val stackAnchorV: Double? = null,
)

/**
 * A set of floorplans stacked into one building, iBwave-style.
 *
 * The reference floor is georeferenced against satellite imagery with >= 2 tie points. Every other
 * floor inherits that floor's scale and rotation and is placed by a single shared vertical reference
 * (its stack anchor): align one feature per floor and the whole stack lines up. A floor carrying its
 * own >= 2 tie points is georeferenced independently instead.
 */
data class Building(
    val id: String,
    val name: String,
    val referenceFloorId: String,
    val floors: List<FloorGeoref>,
) {
    /**
     * Resolves a [GeoReference] for every floor that can be placed, keyed by floorplan id.
     *
     * The reference floor is solved from its own tie points; each other floor is solved from its own
     * tie points when it has >= 2, otherwise inherited from the reference floor via its stack anchor.
     * A floor that can be neither is omitted rather than guessed at, so the caller never plots a floor
     * on a transform that was never actually established.
     */
    fun resolve(): Map<String, GeoReference> {
        val out = LinkedHashMap<String, GeoReference>()
        val ref = floors.firstOrNull { it.floorplanId == referenceFloorId } ?: return out
        val refGeo = GeoReference.solve(ref.widthPx, ref.heightPx, ref.tiePoints) ?: return out
        out[ref.floorplanId] = refGeo

        // Where the shared vertical feature actually is on the ground, read off the reference floor.
        val refWorld = if (ref.stackAnchorU != null && ref.stackAnchorV != null) {
            refGeo.toLatLon(ref.stackAnchorU, ref.stackAnchorV)
        } else {
            null
        }

        for (f in floors) {
            if (f.floorplanId == ref.floorplanId) continue
            val own = if (f.tiePoints.size >= 2) {
                GeoReference.solve(f.widthPx, f.heightPx, f.tiePoints)
            } else {
                null
            }
            val geo = own ?: if (refWorld != null && f.stackAnchorU != null && f.stackAnchorV != null) {
                GeoReference.inherited(
                    f.widthPx, f.heightPx, refGeo,
                    f.stackAnchorU, f.stackAnchorV,
                    refWorld.first, refWorld.second,
                )
            } else {
                null
            }
            if (geo != null) out[f.floorplanId] = geo
        }
        return out
    }
}
