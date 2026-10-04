package com.nhnengineering.rftest.session

import com.nhnengineering.rftest.model.ErrcsAreaClass
import com.nhnengineering.rftest.model.ErrcsGridPoint
import java.io.File

/**
 * Reads and writes the Track A grid-point library -- one manually entered public-safety-coverage
 * reading per line.
 *
 * Same shape as `profile/ProfileStore.kt`, deliberately: hand-rolled JSON rather than `org.json`
 * (a stub in JVM unit tests), one object per line so one corrupted record loses one grid point
 * rather than the whole survey, written via a temp-file-then-rename so a process killed mid-write
 * cannot leave a half-written library. This is operator-typed data read off a separate instrument
 * -- exactly the class of data `ProfileStore` already exists to protect, for the same reason.
 */
class ErrcsGridStore(private val file: File) {

    data class LoadResult(val points: List<ErrcsGridPoint>, val skipped: Int)

    fun load(): LoadResult {
        if (!file.isFile) return LoadResult(emptyList(), 0)
        var skipped = 0
        val out = mutableListOf<ErrcsGridPoint>()
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val p = runCatching { parse(line) }.getOrNull()
            if (p == null) skipped++ else out += p
        }
        return LoadResult(out, skipped)
    }

    fun save(points: List<ErrcsGridPoint>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(points.joinToString("\n") { serialise(it) } + "\n")
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun upsert(point: ErrcsGridPoint): List<ErrcsGridPoint> {
        val current = load().points.toMutableList()
        val i = current.indexOfFirst { it.id == point.id }
        if (i >= 0) current[i] = point else current += point
        save(current)
        return current
    }

    fun delete(id: String): List<ErrcsGridPoint> {
        val remaining = load().points.filter { it.id != id }
        save(remaining)
        return remaining
    }

    // ---- serialisation ----------------------------------------------------

    internal fun serialise(p: ErrcsGridPoint): String = buildString {
        append('{')
        str("id", p.id); comma()
        str("floorplanId", p.floorplanId); comma()
        append("\"xNorm\":").append(p.xNorm); comma()
        append("\"yNorm\":").append(p.yNorm); comma()
        str("floor", p.floor); comma()
        str("areaClass", p.areaClass.name); comma()
        append("\"signalDbm\":").append(p.signalDbm); comma()
        append("\"inboundDbm\":").append(p.inboundDbm?.toString() ?: "null"); comma()
        append("\"daq\":").append(p.daq?.toString() ?: "null"); comma()
        str("systemLabel", p.systemLabel); comma()
        str("note", p.note); comma()
        append("\"recordedAtUtcMillis\":").append(p.recordedAtUtcMillis)
        append('}')
    }

    private fun StringBuilder.comma() = append(',')

    private fun StringBuilder.str(key: String, v: String?) {
        append('"').append(key).append("\":")
        if (v == null) append("null") else append(escape(v))
    }

    internal fun escape(v: String): String {
        val sb = StringBuilder("\"")
        for (ch in v) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    /** A real scan rather than a regex, for the same reason as `ProfileStore.parse` -- operator
     *  free text (`systemLabel`, `note`) must not be able to shift the fields after it. */
    internal fun parse(line: String): ErrcsGridPoint {
        val map = mutableMapOf<String, String?>()
        var i = line.indexOf('{') + 1
        require(i > 0) { "not an object" }

        while (i < line.length) {
            while (i < line.length && (line[i] == ',' || line[i].isWhitespace())) i++
            if (i >= line.length || line[i] == '}') break
            require(line[i] == '"') { "expected a key at $i" }
            val (key, afterKey) = readString(line, i)
            i = afterKey
            while (i < line.length && (line[i] == ':' || line[i].isWhitespace())) i++
            if (line.startsWith("null", i)) {
                map[key] = null
                i += 4
            } else if (line[i] == '"') {
                val (value, after) = readString(line, i)
                map[key] = value
                i = after
            } else {
                val start = i
                while (i < line.length && line[i] != ',' && line[i] != '}') i++
                map[key] = line.substring(start, i).trim()
            }
        }

        return ErrcsGridPoint(
            id = map["id"] ?: error("no id"),
            floorplanId = map["floorplanId"] ?: error("no floorplanId"),
            xNorm = map["xNorm"]?.toFloatOrNull() ?: error("no xNorm"),
            yNorm = map["yNorm"]?.toFloatOrNull() ?: error("no yNorm"),
            floor = map["floor"],
            areaClass = map["areaClass"]?.let { runCatching { ErrcsAreaClass.valueOf(it) }.getOrNull() }
                ?: error("no areaClass"),
            signalDbm = map["signalDbm"]?.toDoubleOrNull() ?: error("no signalDbm"),
            // Optional and added after the first release -- absent in older records, so a missing
            // key (or an explicit null) is a legitimate "not measured", never a parse failure.
            inboundDbm = map["inboundDbm"]?.toDoubleOrNull(),
            daq = map["daq"]?.toDoubleOrNull(),
            systemLabel = map["systemLabel"],
            note = map["note"],
            recordedAtUtcMillis = map["recordedAtUtcMillis"]?.toLongOrNull() ?: 0L,
        )
    }

    private fun readString(s: String, at: Int): Pair<String, Int> {
        require(s[at] == '"')
        val sb = StringBuilder()
        var i = at + 1
        while (i < s.length) {
            when (val ch = s[i]) {
                '\\' -> {
                    i++
                    when (val esc = s[i]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(esc)
                    }
                    i++
                }
                '"' -> return sb.toString() to (i + 1)
                else -> {
                    sb.append(ch)
                    i++
                }
            }
        }
        error("unterminated string")
    }
}
