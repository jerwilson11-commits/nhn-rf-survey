package com.nhnengineering.rftest.session

import com.nhnengineering.rftest.model.CoverageArea
import com.nhnengineering.rftest.model.CoverageVertex
import java.io.File

/**
 * Per-floorplan traced coverage-area polygons, shared by the P. Safety grid method and cellular
 * coverage reporting.
 *
 * One vertex per line (floorplanId + ordinal index + normalised x,y), the same flat hand-rolled JSONL
 * discipline as the other Safety stores: no `org.json`, temp-file-then-rename, and one bad line costs
 * one vertex rather than the whole outline. The explicit index keeps the polygon's winding order
 * independent of line order after a rewrite.
 */
class CoverageAreaStore(private val file: File) {

    /** floorplanId → its coverage polygon. A floorplan absent from the map has no area defined. */
    fun load(): Map<String, CoverageArea> {
        if (!file.isFile) return emptyMap()
        val byPlan = mutableMapOf<String, MutableList<Triple<Int, Float, Float>>>()
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val v = runCatching { parse(line) }.getOrNull() ?: return@forEachLine
            byPlan.getOrPut(v.first) { mutableListOf() }.add(Triple(v.second, v.third, v.fourth))
        }
        return byPlan.mapValues { (_, verts) ->
            CoverageArea(verts.sortedBy { it.first }.map { CoverageVertex(it.second, it.third) })
        }
    }

    fun areaFor(floorplanId: String): CoverageArea = load()[floorplanId] ?: CoverageArea.EMPTY

    /** Replace the polygon for one floorplan, leaving other floorplans untouched. An empty/undefined
     *  area clears the entry. */
    fun setArea(floorplanId: String, area: CoverageArea): CoverageArea {
        val all = load().toMutableMap()
        if (area.vertices.isEmpty()) all.remove(floorplanId) else all[floorplanId] = area
        save(all)
        return area
    }

    private fun save(all: Map<String, CoverageArea>) {
        file.parentFile?.mkdirs()
        val body = buildString {
            all.forEach { (id, area) ->
                area.vertices.forEachIndexed { i, v ->
                    append("{\"floorplanId\":").append(quote(id))
                    append(",\"i\":").append(i)
                    append(",\"x\":").append(v.x)
                    append(",\"y\":").append(v.y)
                    append("}\n")
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

    private data class Parsed(val first: String, val second: Int, val third: Float, val fourth: Float)

    private fun parse(line: String): Parsed? {
        val id = Regex("\"floorplanId\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(line)
            ?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            ?: return null
        val i = Regex("\"i\"\\s*:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        fun num(key: String): Float? =
            Regex("\"$key\"\\s*:\\s*(-?\\d*\\.?\\d+)").find(line)?.groupValues?.get(1)?.toFloatOrNull()
        val x = num("x") ?: return null
        val y = num("y") ?: return null
        return Parsed(id, i, x, y)
    }
}
