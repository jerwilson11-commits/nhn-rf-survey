package com.nhnengineering.rftest.session

import com.nhnengineering.rftest.model.ERRCS_DEFAULT_GRID_COLS
import com.nhnengineering.rftest.model.ERRCS_DEFAULT_GRID_ROWS
import java.io.File

/**
 * The grid dimensions (rows × cols) chosen per floorplan for the NFPA "divide the floor into ~20
 * squares" grid method, so the Safety-tab overlay and the PDF report grade against the same grid.
 *
 * Kept separate from [ErrcsGridStore] (which holds the readings) because this is a tiny bit of
 * configuration, not survey data: one line per floorplan, same hand-rolled JSONL discipline as the
 * rest of this package (no `org.json`, temp-file-then-rename) so a floorplan whose config is
 * corrupt loses only its own grid size and falls back to the default rather than taking the file
 * down with it.
 */
class ErrcsGridConfigStore(private val file: File) {

    /** rows × cols for one floorplan. */
    data class GridConfig(val rows: Int, val cols: Int)

    companion object {
        val DEFAULT = GridConfig(ERRCS_DEFAULT_GRID_ROWS, ERRCS_DEFAULT_GRID_COLS)
    }

    /** floorplanId → config. A floorplan absent from the map uses [DEFAULT]. */
    fun load(): Map<String, GridConfig> {
        if (!file.isFile) return emptyMap()
        val out = mutableMapOf<String, GridConfig>()
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            runCatching { parse(line) }.getOrNull()?.let { (id, cfg) -> out[id] = cfg }
        }
        return out
    }

    /** The config for one floorplan, or [DEFAULT] when none is stored. */
    fun configFor(floorplanId: String): GridConfig = load()[floorplanId] ?: DEFAULT

    fun set(floorplanId: String, rows: Int, cols: Int): Map<String, GridConfig> {
        val current = load().toMutableMap()
        current[floorplanId] = GridConfig(rows.coerceIn(1, 20), cols.coerceIn(1, 20))
        save(current)
        return current
    }

    private fun save(configs: Map<String, GridConfig>) {
        file.parentFile?.mkdirs()
        val body = configs.entries.joinToString("\n") { (id, c) ->
            "{\"floorplanId\":${quote(id)},\"rows\":${c.rows},\"cols\":${c.cols}}"
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body + "\n")
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun quote(v: String): String =
        "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun parse(line: String): Pair<String, GridConfig>? {
        val id = Regex("\"floorplanId\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(line)
            ?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            ?: return null
        val rows = Regex("\"rows\"\\s*:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val cols = Regex("\"cols\"\\s*:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        return id to GridConfig(rows.coerceIn(1, 20), cols.coerceIn(1, 20))
    }
}
