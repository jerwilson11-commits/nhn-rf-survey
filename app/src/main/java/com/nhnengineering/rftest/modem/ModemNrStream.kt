package com.nhnengineering.rftest.modem

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 5G NR neighbour cells, streamed from the modem's own measurement log over DCI.
 *
 * ## Why a stream and not a request
 *
 * The LTE neighbours come from a QMI request/response, one question and one answer. NR has no
 * such question: QMI NAS carries no cell list at all on NR SA, verified directly. The only place
 * the neighbours exist is the modem's `0xB97F` measurement log, which is pushed several times a
 * second once a DCI client subscribes to it. So this owns a subscription rather than making a
 * call, and the sampling loop reads whatever the last packet said.
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
        private const val ASSET = "dcilogger-arm64-v8a"
        private const val HELPER_NAME = "dcilogger"

        /**
         * Where the helper is run from, which is not where it is unpacked to.
         *
         * The helper dlopens /vendor/lib64/libdiag.so. Run from the app's own files directory
         * that fails: the linker puts a /data/data binary in the default namespace, whose
         * permitted paths cover /system and /system_ext but not /vendor, and the dlopen is
         * refused outright --
         *
         *     library "/vendor/lib64/libdiag.so" ... is not accessible for the namespace
         *     [name="(default)", permitted_paths="/system/lib64/drm:..."]
         *
         * The identical binary run from /data/local/tmp loads it fine, so that directory is
         * reached through a more permissive namespace. Staging it there is the whole fix.
         */
        private const val STAGED_PATH = "/data/local/tmp/nhn_dcilogger"

        /** NR ML1 Measurement Database Update. */
        private const val LOG_CODE_NR_ML1 = "b97f"

        /**
         * Length of one subscription window, in seconds.
         *
         * Long enough that relaunching is not a meaningful overhead, short enough that a mask
         * left set by a crash clears on its own shortly afterwards.
         */
        private const val WINDOW_SECONDS = 30

        /** A reading older than this is not shown at all; the modem logs far faster than this. */
        const val STALE_AFTER_MS = 10_000L
    }

    data class Snapshot(val result: NrMl1Parser.Result, val ageMs: Long)

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)

    @Volatile private var worker: Thread? = null
    @Volatile private var process: Process? = null
    @Volatile private var latest: NrMl1Parser.Result? = null
    @Volatile private var latestAtElapsed = 0L
    @Volatile private var lastError: String? = null

    /** Why NR neighbours are unavailable, or null once packets are arriving. */
    val unavailableReason: String? get() = lastError

    /** The most recent measurement, or null if none has arrived or the last one has gone stale. */
    fun snapshot(): Snapshot? {
        val r = latest ?: return null
        val age = SystemClock.elapsedRealtime() - latestAtElapsed
        if (age > STALE_AFTER_MS) return null
        return Snapshot(r, age)
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
    }

    private fun loop() {
        val helper = extractHelper()
        if (helper == null) {
            lastError = "The modem log helper could not be unpacked."
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
        // Staged on every window rather than once, so a tmp wipe or an app update heals
        // itself without needing to track whether the copy is still there and still current.
        val command = "cp ${helper.absolutePath} $STAGED_PATH" +
            " && chmod 755 $STAGED_PATH" +
            " && $STAGED_PATH $WINDOW_SECONDS $LOG_CODE_NR_ML1"
        val p = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true).start()
        process = p
        try {
            p.inputStream.bufferedReader().use { reader ->
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("LOG ") -> {
                            val parsed = NrMl1Parser.parseLogLine(line)
                            if (parsed != null && parsed.looksValid) {
                                latest = parsed
                                latestAtElapsed = SystemClock.elapsedRealtime()
                                lastError = null
                            } else if (parsed != null) {
                                // A packet that failed its own integrity check. Worth knowing,
                                // but not worth replacing a good reading with.
                                Log.w(TAG, "discarded packet: ${parsed.notes.firstOrNull()}")
                            }
                        }
                        line.startsWith("ERR ") -> lastError = line.removePrefix("ERR ").take(160)
                        line.startsWith("READY") -> lastError = null
                    }
                }
            }
        } finally {
            runCatching { p.destroy() }
            process = null
        }
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
        Log.w(TAG, "could not unpack the modem log helper", it)
        null
    }
}
