package com.nhnengineering.rftest.session

import com.nhnengineering.rftest.model.CoverageArea
import com.nhnengineering.rftest.model.CoverageRegion
import com.nhnengineering.rftest.model.CoverageVertex
import com.nhnengineering.rftest.model.ERRCS_DEFAULT_GRID_COLS
import com.nhnengineering.rftest.model.ERRCS_DEFAULT_GRID_ROWS
import java.io.File

/**
 * Per-floorplan coverage regions, shared by the P. Safety grid method and cellular coverage reporting.
 *
 * A floorplan can hold **several** regions (a main building plus a detached outbuilding on the same
 * page), each with its own traced polygon and its own grid (rows × cols). One region per line:
 * `{"floorplanId":..,"region":N,"rows":R,"cols":C,"points":[[x,y],...]}`, the same flat hand-rolled
 * JSONL discipline as the other Safety stores (no `org.json`, temp-file-then-rename).
 *
 * Reads the earlier **single-polygon** format too (one `{floorplanId,i,x,y}` line per vertex), so
 * coverage areas traced before multi-region support load as one region and migrate on the next save.
 */
class CoverageAreaStore(private val file: File) {

    /** floorplanId → its coverage regions, in order. Absent floorplans have none. */
    fun load(): Map<String, List<CoverageRegion>> {
        if (!file.isFile) return emptyMap()
        val newByPlan = linkedMapOf<String, MutableMap<Int, CoverageRegion>>()
        val oldVerts = linkedMapOf<String, MutableList<Triple<Int, Float, Float>>>()
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val region = runCatching { parseRegion(line) }.getOrNull()
            if (region != null) {
                newByPlan.getOrPut(region.first) { sortedMapWrapper() }[region.second] = region.third
                return@forEachLine
            }
            val old = runCatching { parseOldVertex(line) }.getOrNull()
            if (old != null) {
                oldVerts.getOrPut(old.first) { mutableListOf() }.add(Triple(old.second, old.third, old.fourth))
            }
        }
        val out = linkedMapOf<String, List<CoverageRegion>>()
        newByPlan.forEach { (plan, m) -> out[plan] = m.values.toList() }
        oldVerts.forEach { (plan, verts) ->
            if (plan !in out) {
                val poly = CoverageArea(verts.sortedBy { it.first }.map { CoverageVertex(it.second, it.third) })
                if (poly.isDefined) out[plan] = listOf(CoverageRegion(poly))
            }
        }
        return out
    }

    fun regionsFor(floorplanId: String): List<CoverageRegion> = load()[floorplanId] ?: emptyList()

    /** Replace all regions for one floorplan (empty clears it), leaving other floorplans untouched. */
    fun setRegions(floorplanId: String, regions: List<CoverageRegion>): List<CoverageRegion> {
        val all = load().toMutableMap()
        val defined = regions.filter { it.polygon.isDefined }
        if (defined.isEmpty()) all.remove(floorplanId) else all[floorplanId] = defined
        save(all)
        return defined
    }

    private fun save(all: Map<String, List<CoverageRegion>>) {
        file.parentFile?.mkdirs()
        val body = buildString {
            all.forEach { (id, regions) ->
                regions.forEachIndexed { idx, region ->
                    append("{\"floorplanId\":").append(quote(id))
                    append(",\"region\":").append(idx)
                    append(",\"rows\":").append(region.rows)
                    append(",\"cols\":").append(region.cols)
                    append(",\"points\":[")
                    append(region.polygon.vertices.joinToString(",") { "[${it.x},${it.y}]" })
                    append("]}\n")
                }
            }
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun quote(v: String): String =
        "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun planId(line: String): String? =
        Regex("\"floorplanId\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(line)
            ?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")

    private fun parseRegion(line: String): Triple<String, Int, CoverageRegion>? {
        val id = planId(line) ?: return null
        val idx = Regex("\"region\"\\s*:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val ptsStr = Regex("\"points\"\\s*:\\s*\\[(.*)\\]").find(line)?.groupValues?.get(1) ?: return null
        val nums = Regex("-?\\d*\\.?\\d+").findAll(ptsStr).map { it.value.toFloat() }.toList()
        val verts = nums.chunked(2).filter { it.size == 2 }.map { CoverageVertex(it[0], it[1]) }
        val rows = Regex("\"rows\"\\s*:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?: ERRCS_DEFAULT_GRID_ROWS
        val cols = Regex("\"cols\"\\s*:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?: ERRCS_DEFAULT_GRID_COLS
        return Triple(id, idx, CoverageRegion(CoverageArea(verts), rows, cols))
    }

    /** Old single-polygon format: one vertex per line, no "region"/"points" keys. */
    private fun parseOldVertex(line: String): Quad? {
        if (line.contains("\"points\"") || line.contains("\"region\"")) return null
        val id = planId(line) ?: return null
        val i = Regex("\"i\"\\s*:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        fun num(k: String): Float? =
            Regex("\"$k\"\\s*:\\s*(-?\\d*\\.?\\d+)").find(line)?.groupValues?.get(1)?.toFloatOrNull()
        val x = num("x") ?: return null
        val y = num("y") ?: return null
        return Quad(id, i, x, y)
    }

    private data class Quad(val first: String, val second: Int, val third: Float, val fourth: Float)

    // A tiny insertion-ordered map keyed by Int, kept sorted on read via values(); regions are few.
    private fun sortedMapWrapper(): MutableMap<Int, CoverageRegion> = sortedMapOf()
}
