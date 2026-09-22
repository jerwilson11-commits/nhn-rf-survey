package com.nhnengineering.rftest.modem

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Neighbour cells read from the modem itself, over QRTR, on a rooted handset.
 *
 * ## Why this is not a nicety
 *
 * `getAllCellInfo()` returns the serving cell alone on this handset, across all three surfaces.
 * A survey built on it reports zero neighbours everywhere and cannot tell that apart from a site
 * that genuinely has none — which is how this project put a confident "0.0% sector overlap" into a
 * client report for a system whose overlap had never been measured. The modem has the neighbours
 * the whole time. This reads them.
 *
 * ## Why it is gated, and gated honestly
 *
 * The QRTR socket needs root. Not the privileged install — root. So this is absent on any ordinary
 * handset, and absence is the normal case rather than a fault. Everything here distinguishes three
 * states that look identical downstream if they are allowed to collapse:
 *
 *  - **unavailable** — no root, wrong ABI, helper missing. We could not ask.
 *  - **asked, failed** — the helper ran and returned `ERR`. We asked and got nothing back.
 *  - **asked, answered, empty** — the modem reported no neighbours. A measurement.
 *
 * Only the third is a finding. [snapshot] carries which one happened.
 *
 * ## Why it does not block the caller
 *
 * Spawning `su` costs tens of milliseconds and the sampling path runs on a direct executor, so
 * doing this inline would drag the whole sample rate down. Reads happen on a private thread at a
 * throttled cadence, and the sampling path takes whatever the last one produced, with its age
 * attached so nothing reads as fresher than it is.
 */
class ModemNeighbourSource(context: Context) {

    companion object {
        private const val TAG = "ModemNeighbours"

        /** The asset shipped for the only ABI this helper is built for. */
        private const val ASSET = "qmihelper-arm64-v8a"
        private const val ABI = "arm64-v8a"

        /** QRTR address of the Network Access Service, as `qrtr-lookup` reports it. */
        const val NAS_NODE = 0
        const val NAS_PORT = 86

        /**
         * Minimum gap between modem reads.
         *
         * Each read forks `su`, so this is deliberately slower than the 1 Hz sampling loop.
         * Neighbour sets do not change meaningfully faster than this at walking or driving pace,
         * and every snapshot carries its age regardless.
         */
        const val MIN_READ_INTERVAL_MS = 3_000L

        /** A helper that has not answered by now is not going to. */
        private const val HELPER_TIMEOUT_MS = 6_000L
    }

    /** Why the modem cannot be read, or null when it can. */
    sealed interface Availability {
        object Available : Availability
        data class Unavailable(val reason: String) : Availability
    }

    /** One read, with enough context to say what kind of answer it was. */
    data class Snapshot(
        val result: QmiCellParser.Result?,
        /** Null when the helper produced no usable line; the reason it gave, if any. */
        val error: String?,
        val ageMs: Long,
    ) {
        /**
         * Whether this read produced a neighbour reading that can be relied on.
         *
         * Requires more than a SUCCESS result. On 5G NR SA the modem answers perfectly well with
         * serving-cell TLVs this build does not decode, and calling that "answered" would turn an
         * undecoded technology into a measured absence of neighbours.
         */
        val answered: Boolean
            get() = result != null && result.success == true && result.lteInfoPresent

        /** Read fine, but carried nothing this build understands. Worth saying out loud. */
        val undecodedTechnology: Boolean
            get() = result != null && result.success == true && !result.lteInfoPresent

        val cells: List<QmiCellParser.Cell> get() = result?.neighbours.orEmpty()
    }

    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "modem-neighbours").apply { isDaemon = true }
    }
    private val refreshing = AtomicBoolean(false)

    @Volatile private var latest: QmiCellParser.Result? = null
    @Volatile private var latestError: String? = null
    @Volatile private var latestAtElapsed = 0L
    @Volatile private var lastAttemptElapsed = 0L
    @Volatile private var availability: Availability? = null

    /**
     * Whether the modem can be read here, evaluated once and remembered.
     *
     * The evaluation actually runs the helper rather than only checking for `su`: a `su` binary
     * that exists and then denies the request, or an SELinux policy that blocks the socket, both
     * look like root right up until the first read fails.
     */
    fun availability(): Availability = availability ?: probe().also { availability = it }

    val unavailableReason: String?
        get() = (availability() as? Availability.Unavailable)?.reason

    private fun probe(): Availability {
        if (!Build.SUPPORTED_ABIS.contains(ABI)) {
            return Availability.Unavailable(
                "The modem helper is built for $ABI and this device is " +
                    "${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}.",
            )
        }
        val helper = extractHelper()
            ?: return Availability.Unavailable("The modem helper could not be unpacked.")

        val out = runHelper(helper)
        return when {
            out == null -> Availability.Unavailable(
                "No root. Reading neighbours from the modem needs a rooted handset; " +
                    "everything else in the app works without it.",
            )
            out.startsWith("OK ") -> Availability.Available
            else -> Availability.Unavailable("The modem refused the request: ${out.take(120)}")
        }
    }

    /**
     * Copies the helper out of assets, if it is not already there and current.
     *
     * Compared by length rather than trusting an existing file, so a helper replaced by an app
     * update is not shadowed by the old one.
     */
    private fun extractHelper(): File? = runCatching {
        val dest = File(appContext.filesDir, "qmihelper")
        val expected = appContext.assets.open(ASSET).use { it.available().toLong() }
        if (!dest.exists() || dest.length() != expected) {
            appContext.assets.open(ASSET).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }
        // Root execs this; the app's own domain is not allowed to, and does not need to.
        dest.setExecutable(true, false)
        dest.setReadable(true, false)
        dest
    }.getOrElse {
        Log.w(TAG, "could not unpack helper", it)
        null
    }

    /** Runs the helper as root. Returns its first line, or null if root was unavailable. */
    private fun runHelper(helper: File): String? = runCatching {
        val cmd = "${helper.absolutePath} $NAS_NODE $NAS_PORT " +
            "%04x".format(QmiCellParser.MSG_GET_CELL_LOCATION_INFO)
        val p = ProcessBuilder("su", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val line = p.inputStream.bufferedReader().use { it.readLine() }
        if (!p.waitFor(HELPER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            return "ERR helper timed out"
        }
        line
    }.getOrElse {
        // An IOException here is "su is not on the path", i.e. not rooted. Not an error.
        Log.i(TAG, "modem read unavailable: ${it.message}")
        null
    }

    /**
     * Kicks off a read if one is due, without waiting for it.
     *
     * Safe to call from the sampling loop every sample: it returns immediately, and at most one
     * read is ever in flight.
     */
    fun refreshIfDue() {
        if (availability() !is Availability.Available) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttemptElapsed < MIN_READ_INTERVAL_MS) return
        if (!refreshing.compareAndSet(false, true)) return
        lastAttemptElapsed = now
        worker.execute {
            try {
                val helper = extractHelper()
                val line = helper?.let { runHelper(it) }
                val parsed = line?.let { QmiCellParser.parseHelperLine(it) }
                if (parsed != null) {
                    latest = parsed
                    latestError = null
                } else {
                    // Keep the previous reading but record that this attempt failed, so a stale
                    // list cannot quietly present itself as a current one.
                    latestError = line?.removePrefix("ERR ")?.take(160) ?: "no response"
                }
                latestAtElapsed = SystemClock.elapsedRealtime()
            } catch (t: Throwable) {
                Log.w(TAG, "modem read failed", t)
                latestError = t.message
            } finally {
                refreshing.set(false)
            }
        }
    }

    /** The most recent read, or null if there has never been one. */
    fun snapshot(): Snapshot? {
        val r = latest
        val e = latestError
        if (r == null && e == null) return null
        return Snapshot(
            result = r,
            error = e,
            ageMs = if (latestAtElapsed == 0L) 0L
            else SystemClock.elapsedRealtime() - latestAtElapsed,
        )
    }

    fun stop() {
        worker.shutdownNow()
        latest = null
        latestError = null
    }
}
