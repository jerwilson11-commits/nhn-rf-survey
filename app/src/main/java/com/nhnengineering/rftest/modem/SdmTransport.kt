package com.nhnengineering.rftest.modem

import java.io.InputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Opens the Shannon diagnostic channel (`/dev/umts_dm0`), enables SDM logging, and streams decoded
 * [SdmParser.SdmFrame]s — the Exynos/Shannon counterpart to the Qualcomm `ModemNrStream`. Decoded RRC
 * bytes feed the shared `NrRrcDecoder` / `TddProfile` pipeline, unchanged (via `ExynosCapture`).
 *
 * ## Solid vs. best-effort (no Shannon hardware to validate on — see docs/exynos-spike.md)
 *
 * - **Solid & unit-tested:** [processStream] (read accumulation, frame boundaries, partial-frame
 *   carry-over, RRC dispatch) and the [CaptureSummary] instrumentation. Those stats are the whole
 *   point of the beta: a field tester's device reports *what it actually saw* (frame counts by
 *   `cmd_sig`, bytes, errors), which is how we fix the unknowns below without owning the hardware.
 * - **Best-effort, UNVALIDATED:** [openDiagStream] — the `su` open of `/dev/umts_dm0` and the SDM
 *   enable handshake. CONTROL_START is from SCAT; the per-domain *item-select* requests that choose
 *   which logs stream are still unknown, so on many firmwares this may open the node but surface no
 *   RRC. That's an expected beta outcome — the stats tell us what happened.
 *
 * ## Deliberately does NOT weaken device security
 *
 * RQ1 found `/dev/umts_dm0` is `system:system` under SELinux Enforcing, so a plain `su` open may be
 * denied. This code **does not** run `setenforce 0` or otherwise relax SELinux — globally disabling
 * enforcement from a shipped app would be an unacceptable device-wide downgrade for the user. If the
 * node is denied, the capture reports that (via [summary]/[unavailableReason]); the correct fix is a
 * **user-installed sepolicy / Magisk module**, not the app lowering the device's security.
 */
class SdmTransport {

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    @Volatile private var process: Process? = null

    @Volatile
    var unavailableReason: String? = null
        private set

    // --- instrumentation (thread-safe; written on the worker, read from the UI) ---
    private val framesTotal = AtomicLong()
    private val nrRrcFrames = AtomicLong()
    private val lteRrcFrames = AtomicLong()
    private val bytesRead = AtomicLong()
    @Volatile private var firstError: String? = null
    private val cmdSigLock = Any()
    private val cmdSigCounts = HashMap<Int, Long>()

    /** A point-in-time view of what the capture has seen — what a field tester sends back to us. */
    data class CaptureSummary(
        val framesTotal: Long,
        val nrRrcFrames: Long,
        val lteRrcFrames: Long,
        val bytesRead: Long,
        /** Count per `(group << 8) | command`, so an unexpected mix of logs is visible. */
        val cmdSigCounts: Map<Int, Long>,
        val firstError: String?,
    )

    fun summary(): CaptureSummary = CaptureSummary(
        framesTotal.get(), nrRrcFrames.get(), lteRrcFrames.get(), bytesRead.get(),
        synchronized(cmdSigLock) { HashMap(cmdSigCounts) }, firstError,
    )

    /**
     * The frames to write to the diagnostic channel to begin logging. CONTROL_START is referenced
     * from SCAT; the **item-select requests are still a gap** — the modem likely needs
     * `COMMON_ITEM_SELECT_REQUEST` (0x10) / `LTE_ITEM_SELECT_REQUEST` (0x20) frames whose payloads
     * enumerate the wanted log items. Returned as a list so the on-device work can append them here.
     */
    fun enableSequence(): List<ByteArray> = buildList {
        add(SdmParser.controlStartFrame())
        // TODO(RQ5): append COMMON_ITEM_SELECT_REQUEST / LTE_ITEM_SELECT_REQUEST frames selecting
        //  LTE_RRC_OTA_PACKET and COMMON_NR_RRC_SIGNALING_INFO once their payload layout is confirmed.
    }

    /**
     * Consume [input] (a live diagnostic byte stream) until EOF/error/[stop], decoding SDM frames,
     * updating [summary], and handing RRC ones to the callbacks. Pure byte→frame logic — no device or
     * root — so [SdmTransportTest] exercises it directly. Partial frames carry into the next read.
     */
    fun processStream(
        input: InputStream,
        onNrRrc: (SdmParser.SdmFrame) -> Unit,
        onLteRrc: (SdmParser.LteRrcOta) -> Unit,
    ) {
        running.set(true)
        var carry = ByteArray(0)
        val chunk = ByteArray(0x1000)
        while (running.get()) {
            val n = try {
                input.read(chunk)
            } catch (e: Exception) {
                recordError("Diagnostic stream read failed: ${e.message}")
                break
            }
            if (n < 0) break
            if (n == 0) continue
            bytesRead.addAndGet(n.toLong())
            val buf = if (carry.isEmpty()) chunk.copyOf(n) else carry + chunk.copyOf(n)
            val result = SdmParser.parseFrames(buf)
            for (f in result.frames) {
                framesTotal.incrementAndGet()
                synchronized(cmdSigLock) { cmdSigCounts[f.cmdSig] = (cmdSigCounts[f.cmdSig] ?: 0L) + 1L }
                when {
                    f.isNrRrc -> { nrRrcFrames.incrementAndGet(); onNrRrc(f) }
                    f.isLteRrcOta -> {
                        lteRrcFrames.incrementAndGet()
                        SdmParser.parseLteRrcOta(f.payload)?.let(onLteRrc)
                    }
                }
            }
            carry = if (result.nextOffset < buf.size) buf.copyOfRange(result.nextOffset, buf.size) else ByteArray(0)
            if (carry.size > MAX_CARRY) carry = ByteArray(0)
        }
    }

    private fun recordError(msg: String) {
        if (firstError == null) firstError = msg
        unavailableReason = msg
    }

    /**
     * Start streaming on a background thread. Returns false (with [unavailableReason] set) if the
     * stream couldn't be opened. **The device open + enable are best-effort and unvalidated** — a
     * false return, or a running capture that yields zero frames, is an expected beta outcome; read
     * [summary] either way.
     */
    fun start(
        onNrRrc: (SdmParser.SdmFrame) -> Unit,
        onLteRrc: (SdmParser.LteRrcOta) -> Unit,
    ): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val stream = openDiagStream()
        if (stream == null) {
            running.set(false)
            return false
        }
        worker = Thread {
            try {
                processStream(stream, onNrRrc, onLteRrc)
            } finally {
                runCatching { stream.close() }
                runCatching { process?.destroy() }
            }
        }.apply { isDaemon = true; name = "sdm-transport"; start() }
        return true
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
        runCatching { process?.destroy() }
        process = null
    }

    /**
     * Best-effort open of `/dev/umts_dm0` with SDM logging enabled, via `su`. **Unvalidated**, and
     * deliberately does not relax SELinux (see the class doc).
     *
     * Runs one `su` shell that opens the node read+write on a single persistent fd (fd 3 — the modem
     * can stop logging if the fd closes), writes the [enableSequence] to it, then `cat`s the node to
     * stdout, which becomes the returned stream. If the node is denied (e.g. SELinux) or the enable is
     * wrong for a firmware, the read loop yields no frames and [summary] records it. The enable bytes
     * are base64'd and decoded in-shell to avoid binary-in-argv issues; a missing `base64` fails soft.
     */
    private fun openDiagStream(): InputStream? {
        val enableBytes = enableSequence().fold(ByteArray(0)) { acc, f -> acc + f }
        val enableB64 = Base64.getEncoder().encodeToString(enableBytes)
        val script = buildString {
            append("exec 3<>").append(ExynosBackend.DM_NODE).append(" || exit 11; ")
            append("echo ").append(enableB64).append(" | base64 -d >&3 2>/dev/null; ")
            append("cat <&3")
        }
        return try {
            val p = ProcessBuilder("su", "-c", script).redirectErrorStream(false).start()
            process = p
            p.inputStream
        } catch (e: Exception) {
            recordError("Could not open ${ExynosBackend.DM_NODE} via su: ${e.message}")
            null
        }
    }

    private companion object {
        const val MAX_CARRY = 1 shl 20   // 1 MiB: a partial frame is never this large
    }
}
