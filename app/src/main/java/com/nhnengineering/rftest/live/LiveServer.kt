package com.nhnengineering.rftest.live

import android.util.Log
import com.nhnengineering.rftest.service.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale

/**
 * Serves the live walk view to a laptop or tablet connected over USB.
 *
 * ## Why USB, and why loopback only
 *
 * The socket binds to **the loopback address**, never to `0.0.0.0`. The laptop reaches it through
 * `adb forward`, which tunnels over the USB cable. Three reasons, in order of importance:
 *
 * 1. **It does not publish the operator's live position.** A server on `0.0.0.0` would serve this
 *    feed — real-time location, serving cell, signal level — to anyone else on the venue Wi-Fi. A
 *    loopback socket is unreachable from the network by construction, not by a password that could
 *    be left at its default.
 * 2. **It works where there is no network.** Basements, plant rooms, stairwells and half-built
 *    venues are exactly where survey work happens, and exactly where a shared network is missing.
 * 3. **It does not disturb the measurement.** Enabling Wi-Fi to carry the feed changes what is
 *    being measured — Wi-Fi calling can move voice off the cellular network entirely.
 *
 * The trade is that the laptop must be cabled to the phone. On a walk test it already is, for
 * power if nothing else.
 *
 * ## Why a hand-rolled server
 *
 * Three routes, GET only, one client. The same reasoning as using the platform `PdfDocument` rather
 * than a PDF library: a web framework would be a large dependency for something this small. What is
 * given up is real HTTP conformance, and that is acceptable for a socket that only ever talks to a
 * page this project also writes.
 */
class LiveServer(private val port: Int = DEFAULT_PORT) {

    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null

    val running: Boolean get() = server?.isClosed == false

    /**
     * The address the listening socket binds to. Loopback, always.
     *
     * Kept as a named property rather than inlined into the `ServerSocket` call so that
     * `LiveViewTest` can assert it. Passing `null` here — the obvious "simplification" — would bind
     * every interface and publish the operator's live position to the venue Wi-Fi. That change
     * would look harmless in a diff, so it fails a test instead.
     *
     * Verified on device 2026-09-02: `netstat -ltn` reported `::1:8787`, not `:::8787`.
     */
    internal val bindAddress: InetAddress = InetAddress.getLoopbackAddress()

    fun start() {
        if (running) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            try {
                val socket = ServerSocket(port, BACKLOG, bindAddress)
                server = socket
                Log.i(TAG, "live view on ${socket.inetAddress.hostAddress}:$port")
                while (isActive && !socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    s.launch { handle(client) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "live server stopped", t)
                RecordingState.liveServerError.value = t.message ?: "live view failed to start"
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        scope?.cancel()
        scope = null
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            runCatching {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val request = reader.readLine() ?: return
                val path = request.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
                // Drain headers so the client does not see a reset before it finishes writing.
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                }
                val out = sock.getOutputStream()
                when (path) {
                    "/", "/index.html" -> respond(out, "text/html; charset=utf-8", LivePage.HTML)
                    "/api/state" -> respond(out, "application/json", stateJson())
                    else -> {
                        val body = "not found"
                        out.write(
                            ("HTTP/1.1 404 Not Found\r\nContent-Length: ${body.length}\r\n" +
                                "Connection: close\r\n\r\n$body").toByteArray()
                        )
                    }
                }
                out.flush()
            }.onFailure { Log.w(TAG, "request failed", it) }
        }
    }

    private fun respond(out: java.io.OutputStream, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            // The page polls; a stale cached response would freeze the display at whatever the
            // first poll returned, which looks exactly like a hung app.
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray())
        out.write(bytes)
    }

    /**
     * The live snapshot.
     *
     * Hand-built JSON. Every string that could contain a quote or a backslash goes through
     * [jsonString] — an SSID or a venue area label is operator- or vendor-supplied text, and one
     * unescaped quote would produce a document the page cannot parse, which presents as a frozen
     * display rather than as an error.
     */
    private fun stateJson(): String {
        val cell = RecordingState.cellular.value
        val wifi = RecordingState.wifi.value
        val fix = RecordingState.fix.value
        val tp = RecordingState.lastThroughput.value
        val track = RecordingState.liveTrack.value

        return buildString {
            append('{')
            append("\"recording\":${RecordingState.active.value},")
            append("\"rows\":${RecordingState.rowCount.value},")
            append("\"elapsedMs\":${RecordingState.elapsedMs.value},")
            append("\"distanceM\":${num(RecordingState.distanceM.value)},")
            append("\"area\":${jsonString(RecordingState.areaLabel.value)},")
            append("\"throughputBusy\":${RecordingState.throughputBusy.value},")

            append("\"cell\":")
            if (cell == null) append("null") else {
                append('{')
                append("\"rat\":${jsonString(cell.rat.label)},")
                append("\"operator\":${jsonString(cell.operator)},")
                append("\"rsrp\":${cell.servingRsrpDbm ?: "null"},")
                append("\"band\":${jsonString(cell.servingBandLabel)},")
                append("\"sinr\":${cell.nr?.ssSinrDb ?: cell.lte?.rssnrDb ?: "null"},")
                append("\"rsrq\":${cell.nr?.ssRsrqDb ?: cell.lte?.rsrqDb ?: "null"},")
                append("\"pci\":${cell.nr?.pci ?: cell.lte?.pci ?: "null"},")
                append("\"channel\":${cell.nr?.nrarfcn ?: cell.lte?.earfcn ?: "null"},")
                append("\"neighbours\":${cell.neighbors.size}")
                append('}')
            }
            append(',')

            append("\"wifi\":")
            if (wifi == null) append("null") else {
                append('{')
                append("\"ssid\":${jsonString(wifi.ssid)},")
                append("\"rssi\":${wifi.rssiDbm ?: "null"},")
                append("\"channel\":${wifi.channel ?: "null"}")
                append('}')
            }
            append(',')

            append("\"fix\":")
            if (fix == null) append("null") else {
                append('{')
                append("\"lat\":${num(fix.latitudeDeg)},")
                append("\"lon\":${num(fix.longitudeDeg)},")
                append("\"accuracyM\":${fix.accuracyM?.let { num(it.toDouble()) } ?: "null"},")
                append("\"speedMps\":${fix.speedMps?.let { num(it.toDouble()) } ?: "null"}")
                append('}')
            }
            append(',')

            append("\"throughput\":")
            if (tp == null) append("null") else {
                append('{')
                append("\"downMbps\":${tp.downloadMbps?.let { num(it) } ?: "null"},")
                append("\"upMbps\":${tp.uploadMbps?.let { num(it) } ?: "null"},")
                append("\"server\":${jsonString(tp.server)},")
                append("\"error\":${jsonString(tp.error)}")
                append('}')
            }
            append(',')

            append("\"track\":[")
            track.forEachIndexed { i, p ->
                if (i > 0) append(',')
                append('{')
                append("\"lat\":${num(p.lat)},")
                append("\"lon\":${num(p.lon)},")
                append("\"rsrp\":${p.rsrpDbm ?: "null"},")
                append("\"rssi\":${p.rssiDbm ?: "null"}")
                append('}')
            }
            append(']')
            append('}')
        }
    }

    /** Locale-independent, because a comma decimal separator produces invalid JSON. */
    internal fun num(v: Double): String =
        if (v.isFinite()) String.format(Locale.US, "%.6f", v) else "null"

    internal fun jsonString(v: String?): String {
        if (v == null) return "null"
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

    companion object {
        private const val TAG = "LiveServer"
        private const val BACKLOG = 4
        const val DEFAULT_PORT = 8787
    }
}
