package com.nhnengineering.rftest.session

import com.nhnengineering.rftest.model.ErrcsCriticalArea
import java.io.File

/**
 * Per-floorplan AHJ-designated critical areas for the Safety-tab grid method — the regions the tester
 * paints so readings inside them are graded at the critical (99%) requirement.
 *
 * One rectangle per line (floorplanId + normalised x0,y0,x1,y1), the same flat hand-rolled JSONL
 * discipline as [ErrcsGridStore]: no `org.json` (a JVM-test stub), temp-file-then-rename, and one
 * corrupt line costs one rectangle rather than the whole designation. Stored as regions rather than
 * grid-cell indices so a designation survives the tester changing the sampling grid's rows/cols.
 */
class ErrcsCriticalAreaStore(private val file: File) {

    /** floorplanId → its critical-area rectangles. A floorplan absent from the map has none. */
    fun load(): Map<String, List<ErrcsCriticalArea>> {
        if (!file.isFile) return emptyMap()
        val out = mutableMapOf<String, MutableList<ErrcsCriticalArea>>()
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val parsed = runCatching { parse(line) }.getOrNull() ?: return@forEachLine
            out.getOrPut(parsed.first) { mutableListOf() }.add(parsed.second)
        }
        return out
    }

    fun areasFor(floorplanId: String): List<ErrcsCriticalArea> = load()[floorplanId] ?: emptyList()

    /** Replace the full set of critical areas for one floorplan, leaving other floorplans untouched. */
    fun setAreas(floorplanId: String, areas: List<ErrcsCriticalArea>): List<ErrcsCriticalArea> {
        val all = load().toMutableMap()
        if (areas.isEmpty()) all.remove(floorplanId) else all[floorplanId] = areas
        save(all)
        return areas
    }

    private fun save(all: Map<String, List<ErrcsCriticalArea>>) {
        file.parentFile?.mkdirs()
        val body = buildString {
            all.forEach { (id, areas) ->
                areas.forEach { a ->
                    append("{\"floorplanId\":").append(quote(id))
                    append(",\"x0\":").append(a.x0)
                    append(",\"y0\":").append(a.y0)
                    append(",\"x1\":").append(a.x1)
                    append(",\"y1\":").append(a.y1)
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

    private fun parse(line: String): Pair<String, ErrcsCriticalArea>? {
        val id = Regex("\"floorplanId\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(line)
            ?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            ?: return null
        fun num(key: String): Float? =
            Regex("\"$key\"\\s*:\\s*(-?\\d*\\.?\\d+)").find(line)?.groupValues?.get(1)?.toFloatOrNull()
        val x0 = num("x0") ?: return null
        val y0 = num("y0") ?: return null
        val x1 = num("x1") ?: return null
        val y1 = num("y1") ?: return null
        return id to ErrcsCriticalArea(x0, y0, x1, y1)
    }
}
