package com.nhnengineering.rftest.profile

import java.io.File

/**
 * Reads and writes the profile library.
 *
 * One JSON object per line. Hand-rolled rather than using `org.json`, for the same reason the rest
 * of this project hand-rolls its serialisation: `org.json` on Android is a stub in unit tests, and
 * a store whose round trip cannot be tested is a store that will lose someone's data quietly.
 *
 * Line-delimited rather than one document, so a single corrupted record loses one profile instead
 * of the library. [load] skips what it cannot parse and reports how many it skipped, because
 * silently returning a shorter list would look like the entries were never saved.
 */
class ProfileStore(private val file: File) {

    data class LoadResult(val profiles: List<TddProfile>, val skipped: Int)

    fun load(): LoadResult {
        if (!file.isFile) return LoadResult(emptyList(), 0)
        var skipped = 0
        val out = mutableListOf<TddProfile>()
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val p = runCatching { parse(line) }.getOrNull()
            if (p == null) skipped++ else out += p
        }
        return LoadResult(out, skipped)
    }

    fun save(profiles: List<TddProfile>) {
        file.parentFile?.mkdirs()
        // Written via a temporary file and renamed: a process killed mid-write would otherwise
        // leave a half-written library, and this is data a person typed by hand and cannot
        // reconstruct from a measurement.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(profiles.joinToString("\n") { serialise(it) } + "\n")
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun upsert(profile: TddProfile): List<TddProfile> {
        val current = load().profiles.toMutableList()
        val i = current.indexOfFirst { it.id == profile.id }
        if (i >= 0) current[i] = profile else current += profile
        save(current)
        return current
    }

    fun delete(id: String): List<TddProfile> {
        val remaining = load().profiles.filter { it.id != id }
        save(remaining)
        return remaining
    }

    // ---- serialisation ----------------------------------------------------

    internal fun serialise(p: TddProfile): String = buildString {
        append('{')
        str("id", p.id); comma()
        str("vendor", p.vendor); comma()
        str("operator", p.operator); comma()
        str("mcc", p.mcc); comma()
        str("mnc", p.mnc); comma()
        str("band", p.band); comma()
        str("market", p.market); comma()
        str("siteName", p.siteName); comma()
        str("tddPattern", p.tddPattern); comma()
        str("tddPeriodicityMs", p.tddPeriodicityMs); comma()
        num("dlSlots", p.dlSlots); comma()
        num("dlSymbols", p.dlSymbols); comma()
        num("ulSlots", p.ulSlots); comma()
        num("ulSymbols", p.ulSymbols); comma()
        num("ssbPeriodicityMs", p.ssbPeriodicityMs); comma()
        str("ssbPositionsInBurst", p.ssbPositionsInBurst); comma()
        num("scsKhz", p.scsKhz); comma()
        str("source", p.source); comma()
        append("\"recordedAtUtcMillis\":").append(p.recordedAtUtcMillis); comma()
        str("note", p.note)
        append('}')
    }

    private fun StringBuilder.comma() = append(',')

    private fun StringBuilder.str(key: String, v: String?) {
        append('"').append(key).append("\":")
        if (v == null) append("null") else append(escape(v))
    }

    private fun StringBuilder.num(key: String, v: Int?) {
        append('"').append(key).append("\":").append(v?.toString() ?: "null")
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

    /**
     * Parses one line.
     *
     * A real scan rather than a regex, because these fields carry operator-typed free text — a
     * vendor note containing a brace or a quote must not be able to shorten the record or shift
     * the following fields.
     */
    internal fun parse(line: String): TddProfile {
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

        fun int(k: String) = map[k]?.takeIf { it.isNotBlank() }?.toIntOrNull()

        return TddProfile(
            id = map["id"] ?: error("no id"),
            vendor = map["vendor"] ?: "",
            operator = map["operator"] ?: "",
            mcc = map["mcc"],
            mnc = map["mnc"],
            band = map["band"] ?: error("no band"),
            market = map["market"],
            siteName = map["siteName"],
            tddPattern = map["tddPattern"],
            tddPeriodicityMs = map["tddPeriodicityMs"],
            dlSlots = int("dlSlots"),
            dlSymbols = int("dlSymbols"),
            ulSlots = int("ulSlots"),
            ulSymbols = int("ulSymbols"),
            ssbPeriodicityMs = int("ssbPeriodicityMs"),
            ssbPositionsInBurst = map["ssbPositionsInBurst"],
            scsKhz = int("scsKhz"),
            source = map["source"] ?: "",
            recordedAtUtcMillis = map["recordedAtUtcMillis"]?.toLongOrNull() ?: 0L,
            note = map["note"],
        )
    }

    /** Reads a quoted string starting at [at], returning it and the index just past the closer. */
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
