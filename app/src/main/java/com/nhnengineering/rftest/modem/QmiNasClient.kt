package com.nhnengineering.rftest.modem

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sends QMI Network Access Service requests through the bundled `qmilock` helper, as root.
 *
 * `AF_QIPCRTR` needs root and is not reachable from the Java socket API, so the bytes go through a
 * small native helper that only sends what it is given and prints the reply as hex. All TLV
 * construction and decoding stays in Kotlin, in [QmiSelectionPreference].
 *
 * ## Why the port is resolved on every request
 *
 * NAS's QRTR port is assigned when it registers, not fixed by the protocol: observed at 86, 88, 87
 * and 80 across four modem restarts on this project, once from nothing more than an Airplane Mode
 * toggle. A stale port fails in the worst way -- something else answers, so the failure appears
 * only as a QMI result code. [QrtrServices] is asked fresh each time.
 *
 * Shared by the technology lock and the band lock, which both write the same message and must not
 * each carry their own copy of this plumbing.
 */
class QmiNasClient(context: Context) {

    private companion object {
        const val TAG = "QmiNasClient"
        const val ASSET = "qmilock-arm64-v8a"
        const val HELPER_NAME = "qmilock"
        const val TIMEOUT_MS = 6_000L
    }

    private val appContext = context.applicationContext

    sealed interface Response {
        /** The whole QMI response, header included. */
        data class Ok(val payload: ByteArray) : Response

        /** [reason] is phrased for display. */
        data class Failed(val reason: String) : Response
    }

    sealed interface Snapshot {
        data class Ok(val result: QmiSelectionPreference.GetResult) : Snapshot
        data class Failed(val reason: String) : Snapshot
    }

    private fun resolveNas(): QrtrServices.Address? = runCatching {
        val p = ProcessBuilder("su", "-c", "/vendor/bin/qrtr-lookup")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        QrtrServices.find(out, QrtrServices.SERVICE_NAS)
    }.getOrElse {
        Log.i(TAG, "could not resolve NAS: ${it.message}")
        null
    }

    private fun extractHelper(): File? = runCatching {
        val dest = File(appContext.filesDir, HELPER_NAME)
        val expected = appContext.assets.open(ASSET).use { it.available().toLong() }
        if (!dest.exists() || dest.length() != expected) {
            appContext.assets.open(ASSET).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }
        dest.setExecutable(true, false)
        dest.setReadable(true, false)
        dest
    }.getOrElse {
        Log.w(TAG, "could not unpack qmilock", it)
        null
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Sends [msgId] with [args] (`id:hexbytes` TLVs). */
    fun request(msgId: Int, args: List<String> = emptyList()): Response {
        val addr = resolveNas() ?: return Response.Failed(
            "The Network Access Service is not reachable over QRTR, so the modem cannot be " +
                "asked. This needs a rooted handset; everything else in the app works without it.",
        )
        val helper = extractHelper()
            ?: return Response.Failed("Could not unpack the modem helper.")
        val cmd = (
            listOf(helper.absolutePath, addr.node.toString(), addr.port.toString(), "%04x".format(msgId)) + args
            ).joinToString(" ")
        val line = runCatching {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val first = p.inputStream.bufferedReader().use { it.readLine() }
            if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return Response.Failed("The modem helper timed out.")
            }
            first
        }.getOrElse {
            // An IOException here means su is absent, i.e. not rooted. Not an error.
            Log.i(TAG, "qmilock unavailable: ${it.message}")
            return Response.Failed("No root. Talking to the modem needs a rooted handset.")
        } ?: return Response.Failed("The modem helper produced no output.")

        if (!line.startsWith("OK ")) {
            return Response.Failed("The modem refused the request: ${line.removePrefix("ERR ").take(160)}")
        }
        val hex = line.removePrefix("OK ").trim()
        if (hex.length % 2 != 0 || !hex.all { it in "0123456789abcdefABCDEF" }) {
            return Response.Failed("The modem's reply was not valid hex.")
        }
        return Response.Ok(hexToBytes(hex))
    }

    /** Reads mode and band preferences. */
    fun read(): Snapshot = when (val r = request(QmiSelectionPreference.MSG_GET)) {
        is Response.Failed -> Snapshot.Failed(r.reason)
        is Response.Ok -> {
            val parsed = QmiSelectionPreference.parseGet(r.payload)
            if (!parsed.looksValid || parsed.modePref == null) {
                Snapshot.Failed("Could not read the current setting from the modem's reply.")
            } else {
                Snapshot.Ok(parsed)
            }
        }
    }

    /** Writes preferences. Null means the modem's own result TLV said it accepted the write. */
    fun write(args: List<String>): String? = when (val r = request(QmiSelectionPreference.MSG_SET, args)) {
        is Response.Failed -> r.reason
        is Response.Ok ->
            if (QmiSelectionPreference.parseSetResult(r.payload) == true) null
            else "The modem rejected the change."
    }
}
