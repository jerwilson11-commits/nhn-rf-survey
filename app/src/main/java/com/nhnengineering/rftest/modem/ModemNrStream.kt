package com.nhnengineering.rftest.modem

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 5G NR neighbour cells and RRC-layer serving-cell info, streamed from the modem's own logs over
 * DCI, one subscription carrying both.
 *
 * ## Why a stream and not a request
 *
 * The LTE neighbours come from a QMI request/response, one question and one answer. NR has no
 * such question: QMI NAS carries no cell list at all on NR SA, verified directly. The only place
 * the neighbours exist is the modem's `0xB97F` measurement log, which is pushed several times a
 * second once a DCI client subscribes to it. So this owns a subscription rather than making a
 * call, and the sampling loop reads whatever the last packet said. `0xB823`, RRC's own serving-cell
 * record (see [NrRrcServingCellParser]), rides the same subscription -- one client, one log mask,
 * two codes multiplexed on the one stream rather than a second subscriber competing for the same
 * global modem state.
 *
 * ## Why it runs in bounded windows
 *
 * The log mask is **global modem state**. A subscriber that dies without clearing it leaves the
 * modem logging for nobody. Rather than trust a single long-lived process to always get its
 * cleanup right, the helper is run for a fixed window and relaunched: every window ends by
 * disabling the mask it set, so the worst case after any crash is one window's worth of stale
 * masking rather than an indefinitely chattering modem.
 *
 * The helper also stops itself if its output pipe closes, which covers the case where this
 * process goes away without getting the chance to tidy up.
 */
class ModemNrStream(context: Context) {

    companion object {
        private const val TAG = "ModemNrStream"
        private const val HELPER_NAME = "libdcilogger.so"

        /**
         * Where the helper is run from, which is not where it ships.
         *
         * `libdcilogger.so` ships as an ordinary native library under `jniLibs` now, so Android's
         * own installer places it in the app's nativeLibraryDir and makes it executable at
         * install time -- there is no longer a runtime copy-and-chmod step to get the binary onto
         * disk in the first place. But it still cannot be *run* from there: it dlopens
         * /vendor/lib64/libdiag.so, and a binary executed from the app's own namespace (files
         * directory or nativeLibraryDir alike) sits in a linker namespace whose permitted paths
         * cover /system and /system_ext but not /vendor, so the dlopen is refused outright --
         *
         *     library "/vendor/lib64/libdiag.so" ... is not accessible for the namespace
         *     [name="(default)", permitted_paths="/system/lib64/drm:..."]
         *
         * The identical binary run from /data/local/tmp loads it fine, so that directory is
         * reached through a more permissive namespace. Staging it there is the whole fix, and is
         * still root copying its own already-installed library to a location it can actually
         * execute from -- not this app writing or elevating anything new of its own.
         *
         * The size is part of the name for two reasons: a new build never runs behind an old
         * staged copy, and the copy can be skipped when a good one is already there -- which
         * matters because copying over a *running* binary fails with "Text file busy", and a
         * second collector starting while the first still streams is an ordinary thing.
         */
        private fun stagedPath(helper: java.io.File) =
            "/data/local/tmp/nhn_dcilogger_${helper.length()}"

        /** NR ML1 Measurement Database Update. */
        private const val LOG_CODE_NR_ML1 = "b97f"

        /** NR RRC Serving Cell Info -- see [NrRrcServingCellParser]. */
        private const val LOG_CODE_NR_RRC_SCELL = "b823"

        /** NAS-5GS OTA, incoming/outgoing -- see [Nas5gsOtaParser]. Only requested while a
         *  signaling capture is running (see [setSignalingCapture]), not on every window. */
        private const val LOG_CODE_NAS5GS_IN = "b80a"
        private const val LOG_CODE_NAS5GS_OUT = "b80b"

        /** NR RRC OTA -- see [RrcOtaParser]. Same capture-only gating as the NAS-5GS codes. */
        private const val LOG_CODE_NR_RRC_OTA = "b821"

        /**
         * Length of one subscription window, in seconds.
         *
         * Long enough that relaunching is not a meaningful overhead, short enough that a mask
         * left set by a crash clears on its own shortly afterwards.
         */
        private const val WINDOW_SECONDS = 30

        /** A reading older than this is not shown at all; the modem logs far faster than this. */
        const val STALE_AFTER_MS = 10_000L

        /** Oldest events are dropped first once a capture exceeds this -- bounded, not unlimited. */
        private const val MAX_SIGNALING_EVENTS = 200
    }

    data class Snapshot(val result: NrMl1Parser.Result, val ageMs: Long)
    data class ScellSnapshot(val result: NrRrcServingCellParser.Result, val ageMs: Long)

    /**
     * One decoded NAS-5GS or RRC OTA packet from a signaling capture, in arrival order.
     *
     * Unlike [Snapshot]/[ScellSnapshot], signaling is inherently a *sequence* -- the point of a
     * capture is the order of messages (Service Accept, then a Deregistration Request, ...), not
     * just the latest one -- so this accumulates in [signalingEvents] rather than overwriting a
     * single field the way ML1/RRC-scell readings do.
     */
    data class SignalingEvent(
        val atElapsedMs: Long,
        val nas: Nas5gsOtaParser.Result? = null,
        val rrc: RrcOtaParser.Result? = null,
    )

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)

    @Volatile private var worker: Thread? = null
    @Volatile private var process: Process? = null
    @Volatile private var latest: NrMl1Parser.Result? = null
    @Volatile private var latestAtElapsed = 0L
    @Volatile private var latestScell: NrRrcServingCellParser.Result? = null
    @Volatile private var latestScellAtElapsed = 0L
    @Volatile private var lastError: String? = null

    /** Whether the next (or current) window should also subscribe to the signaling log codes.
     *  Toggling takes effect within one [WINDOW_SECONDS] relaunch, not instantly. */
    @Volatile private var captureSignaling: Boolean = false
    private val signalingEvents = Collections.synchronizedList(mutableListOf<SignalingEvent>())

    /** Why NR neighbours are unavailable, or null once packets are arriving. */
    val unavailableReason: String? get() = lastError

    /**
     * Starts or stops including NAS-5GS/RRC OTA in the subscription. Starting clears any events
     * from a previous capture; stopping leaves [signalingEvents] alone, since inspecting what was
     * captured is the entire point of having stopped.
     */
    fun setSignalingCapture(capture: Boolean) {
        if (capture) signalingEvents.clear()
        captureSignaling = capture
    }

    /** Every signaling event captured since the last [setSignalingCapture]`(true)`, in order. */
    fun signalingEvents(): List<SignalingEvent> = signalingEvents.toList()

    /** The most recent measurement, or null if none has arrived or the last one has gone stale. */
    fun snapshot(): Snapshot? {
        val r = latest ?: return null
        val age = SystemClock.elapsedRealtime() - latestAtElapsed
        if (age > STALE_AFTER_MS) return null
        return Snapshot(r, age)
    }

    /** The most recent RRC serving-cell reading, or null if none has arrived or gone stale. */
    fun scellSnapshot(): ScellSnapshot? {
        val r = latestScell ?: return null
        val age = SystemClock.elapsedRealtime() - latestScellAtElapsed
        if (age > STALE_AFTER_MS) return null
        return ScellSnapshot(r, age)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = Thread({ loop() }, "modem-nr-stream").apply { isDaemon = true }
        worker = t
        t.start()
    }

    fun stop() {
        running.set(false)
        // Closing the pipe is what tells the helper to release its mask, so destroy the process
        // rather than only interrupting the thread.
        runCatching { process?.destroy() }
        worker?.interrupt()
        worker = null
        latest = null
        latestScell = null
    }

    private fun loop() {
        val helper = helperFile()
        if (helper == null) {
            lastError = "The modem log helper is missing from this build."
            running.set(false)
            return
        }
        while (running.get()) {
            val started = SystemClock.elapsedRealtime()
            runCatching { runWindow(helper) }.onFailure {
                // An IOException here means su is absent, i.e. not rooted. Not an error.
                lastError = it.message ?: "could not start the modem log helper"
                Log.i(TAG, "NR stream unavailable: ${it.message}")
            }
            if (!running.get()) break
            // A window that died immediately would otherwise spin. One second is long enough to
            // stop that and short enough not to matter when the window ran its full length.
            if (SystemClock.elapsedRealtime() - started < 1_000) {
                runCatching { Thread.sleep(1_000) }.onFailure { return }
            }
        }
    }

    private fun runWindow(helper: File) {
        // Separated by ';' not '&&' on purpose. The copy fails with "Text file busy" whenever
        // another instance is already running this exact binary, and that is a reason to skip
        // the copy, not to skip the run: a busy file means a good copy is already in place. A
        // wiped tmp still heals, because then the copy simply succeeds.
        val staged = stagedPath(helper)
        val logCodes = if (captureSignaling) {
            "$LOG_CODE_NR_ML1 $LOG_CODE_NR_RRC_SCELL $LOG_CODE_NAS5GS_IN $LOG_CODE_NAS5GS_OUT $LOG_CODE_NR_RRC_OTA"
        } else {
            "$LOG_CODE_NR_ML1 $LOG_CODE_NR_RRC_SCELL"
        }
        val command = "cp -f ${helper.absolutePath} $staged 2>/dev/null" +
            " ; chmod 755 $staged 2>/dev/null" +
            " ; $staged $WINDOW_SECONDS $logCodes"
        Log.i(TAG, "window starting: $command")
        val p = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true).start()
        process = p
        var lines = 0
        var stored = 0
        var storedScell = 0
        try {
            p.inputStream.bufferedReader().use { reader ->
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    lines++
                    when {
                        line.startsWith("LOG ") -> {
                            // One subscription now carries two log codes multiplexed on the same
                            // stream, so the code embedded in each packet decides which parser
                            // gets it -- trying both would silently double-parse every line.
                            when (logCodeOf(line)) {
                                NrMl1Parser.LOG_CODE -> {
                                    val parsed = NrMl1Parser.parseLogLine(line)
                                    if (parsed != null && parsed.looksValid) {
                                        latest = parsed
                                        latestAtElapsed = SystemClock.elapsedRealtime()
                                        lastError = null
                                        stored++
                                        if (stored == 1) {
                                            Log.i(TAG, "stored first ML1 packet: serving=${parsed.servingPci} " +
                                                "neighbours=${parsed.neighbours.map { it.pci }}")
                                        }
                                    } else if (parsed != null) {
                                        // A packet that failed its own integrity check. Worth
                                        // knowing, but not worth replacing a good reading with.
                                        Log.w(TAG, "discarded ML1 packet: ${parsed.notes.firstOrNull()}")
                                    }
                                }
                                NrRrcServingCellParser.LOG_CODE -> {
                                    val parsed = NrRrcServingCellParser.parseLogLine(line)
                                    if (parsed != null && parsed.looksValid) {
                                        latestScell = parsed
                                        latestScellAtElapsed = SystemClock.elapsedRealtime()
                                        storedScell++
                                        if (storedScell == 1) {
                                            Log.i(TAG, "stored first RRC scell packet: v${parsed.payloadVersion} " +
                                                "pci=${parsed.pci} band=${parsed.band}")
                                        }
                                    } else if (parsed != null) {
                                        Log.w(TAG, "discarded RRC scell packet: ${parsed.notes.firstOrNull()}")
                                    }
                                }
                                Nas5gsOtaParser.LOG_CODE_INCOMING, Nas5gsOtaParser.LOG_CODE_OUTGOING -> {
                                    Nas5gsOtaParser.parseLogLine(line)?.takeIf { it.looksValid }?.let {
                                        addSignalingEvent(SignalingEvent(SystemClock.elapsedRealtime(), nas = it))
                                    }
                                }
                                RrcOtaParser.LOG_CODE -> {
                                    RrcOtaParser.parseLogLine(line)?.takeIf { it.looksValid }?.let {
                                        addSignalingEvent(SignalingEvent(SystemClock.elapsedRealtime(), rrc = it))
                                    }
                                }
                            }
                        }
                        line.startsWith("ERR ") -> lastError = ModemFailureMessages.decorateHelperError(
                            line.removePrefix("ERR ").take(160),
                            ModemChipset.classify(),
                        )
                        line.startsWith("READY") -> lastError = null
                    }
                }
            }
        } finally {
            Log.i(
                TAG,
                "window ended: $lines line(s), $stored ML1 stored, $storedScell RRC scell stored, " +
                    "lastError=$lastError",
            )
            runCatching { p.destroy() }
            process = null
        }
    }

    /** Appends to [signalingEvents], dropping the oldest once [MAX_SIGNALING_EVENTS] is exceeded. */
    private fun addSignalingEvent(event: SignalingEvent) {
        signalingEvents.add(event)
        while (signalingEvents.size > MAX_SIGNALING_EVENTS) {
            runCatching { signalingEvents.removeAt(0) }
        }
    }

    /** The log code embedded in a `LOG <hex>` line (bytes 2-3, little-endian), or null. */
    private fun logCodeOf(line: String): Int? {
        val hex = line.removePrefix("LOG ").trim()
        if (hex.length < 8) return null
        val lo = hex.substring(4, 6).toIntOrNull(16) ?: return null
        val hi = hex.substring(6, 8).toIntOrNull(16) ?: return null
        return (hi shl 8) or lo
    }

    /**
     * `libdcilogger.so` ships as an ordinary native library under `jniLibs`, installed and made
     * executable by Android itself. See [stagedPath] for why it still has to be copied again
     * before it can actually run.
     */
    private fun helperFile(): File? =
        File(appContext.applicationInfo.nativeLibraryDir, HELPER_NAME).takeIf { it.exists() }
}
