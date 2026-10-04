package com.nhnengineering.rftest.session

import android.content.Context
import android.util.Log
import com.nhnengineering.rftest.map.GeoReference
import com.nhnengineering.rftest.map.TiePoint
import com.nhnengineering.rftest.model.Building
import com.nhnengineering.rftest.model.FloorGeoref
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists [Building] georeferences as a single JSON file beside the floorplan images.
 *
 * One file, not a sidecar per image, because a building is inherently cross-floor: the stack only
 * means anything as a set. It lives in [FloorplanStore.dir] so a floorplan and its georeference travel
 * together, the same reasoning that keeps the images there rather than behind a content URI.
 *
 * `org.json` rather than a serialization library: it is in the Android platform already, this schema
 * is small and stable, and adding a dependency to store six numbers per tie point is not worth it.
 * (The tradeoff is that this object is not JVM-unit-testable -- `org.json` is stubbed on that
 * classpath -- so the maths it feeds, [GeoReference] and [Building.resolve], is tested directly.)
 */
object BuildingStore {

    private const val TAG = "BuildingStore"
    private const val FILE = "buildings.json"

    private fun file(context: Context): File = File(FloorplanStore.dir(context), FILE)

    suspend fun load(context: Context): List<Building> = withContext(Dispatchers.IO) {
        val f = file(context)
        if (!f.exists()) return@withContext emptyList()
        try {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("buildings") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i -> parseBuilding(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            Log.w(TAG, "could not read $FILE", e)
            emptyList()
        }
    }

    suspend fun save(context: Context, buildings: List<Building>) = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray()
            buildings.forEach { arr.put(encodeBuilding(it)) }
            val root = JSONObject().put("buildings", arr)
            file(context).writeText(root.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "could not write $FILE", e)
        }
    }

    /** Convenience: the resolved [GeoReference] for a single floorplan id, across all buildings, or
     *  null when that floorplan is not georeferenced. */
    suspend fun geoReferenceFor(context: Context, floorplanId: String): GeoReference? {
        val building = load(context).firstOrNull { b -> b.floors.any { it.floorplanId == floorplanId } }
            ?: return null
        return building.resolve()[floorplanId]
    }

    /** Every floorplan id that currently resolves to a georeference (own tie points or inherited),
     *  across all buildings -- for showing a "georeferenced" marker in the floorplan library. */
    suspend fun georeferencedIds(context: Context): Set<String> =
        load(context).flatMap { it.resolve().keys }.toSet()

    /** Drops a floorplan from any building it belongs to (used when the image is deleted). A building
     *  left with no floors is removed; if the deleted floor was the reference, the first remaining
     *  floor becomes the reference. */
    suspend fun removeFloorplan(context: Context, floorplanId: String) {
        val buildings = load(context)
        if (buildings.none { b -> b.floors.any { it.floorplanId == floorplanId } }) return
        val updated = buildings.mapNotNull { b ->
            val floors = b.floors.filterNot { it.floorplanId == floorplanId }
            when {
                floors.isEmpty() -> null
                b.referenceFloorId == floorplanId ->
                    b.copy(floors = floors, referenceFloorId = floors.first().floorplanId)
                else -> b.copy(floors = floors)
            }
        }
        save(context, updated)
    }

    // ---- JSON <-> model -----------------------------------------------------

    private fun parseBuilding(o: JSONObject): Building? {
        val id = o.optString("id").ifEmpty { return null }
        val floorsArr = o.optJSONArray("floors") ?: JSONArray()
        val floors = (0 until floorsArr.length()).mapNotNull { parseFloor(floorsArr.getJSONObject(it)) }
        if (floors.isEmpty()) return null
        val refId = o.optString("referenceFloorId").ifEmpty { floors.first().floorplanId }
        return Building(id = id, name = o.optString("name", id), referenceFloorId = refId, floors = floors)
    }

    private fun parseFloor(o: JSONObject): FloorGeoref? {
        val fpId = o.optString("floorplanId").ifEmpty { return null }
        val tpArr = o.optJSONArray("tiePoints") ?: JSONArray()
        val tps = (0 until tpArr.length()).map { i ->
            val t = tpArr.getJSONObject(i)
            TiePoint(t.getDouble("u"), t.getDouble("v"), t.getDouble("lat"), t.getDouble("lon"))
        }
        return FloorGeoref(
            floorplanId = fpId,
            widthPx = o.optInt("widthPx"),
            heightPx = o.optInt("heightPx"),
            label = o.optString("label", ""),
            tiePoints = tps,
            stackAnchorU = if (o.has("stackAnchorU")) o.getDouble("stackAnchorU") else null,
            stackAnchorV = if (o.has("stackAnchorV")) o.getDouble("stackAnchorV") else null,
        )
    }

    private fun encodeBuilding(b: Building): JSONObject {
        val floors = JSONArray()
        b.floors.forEach { floors.put(encodeFloor(it)) }
        return JSONObject()
            .put("id", b.id)
            .put("name", b.name)
            .put("referenceFloorId", b.referenceFloorId)
            .put("floors", floors)
    }

    private fun encodeFloor(f: FloorGeoref): JSONObject {
        val tps = JSONArray()
        f.tiePoints.forEach { t ->
            tps.put(
                JSONObject().put("u", t.uNorm).put("v", t.vNorm).put("lat", t.lat).put("lon", t.lon),
            )
        }
        val o = JSONObject()
            .put("floorplanId", f.floorplanId)
            .put("widthPx", f.widthPx)
            .put("heightPx", f.heightPx)
            .put("label", f.label)
            .put("tiePoints", tps)
        f.stackAnchorU?.let { o.put("stackAnchorU", it) }
        f.stackAnchorV?.let { o.put("stackAnchorV", it) }
        return o
    }
}
