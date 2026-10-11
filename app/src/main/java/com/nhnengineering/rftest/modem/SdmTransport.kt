package com.nhnengineering.rftest.modem

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opens the Shannon diagnostic channel (`/dev/umts_dm0`), enables SDM logging, and streams decoded
 * [SdmParser.SdmFrame]s — the Exynos/Shannon counterpart to the Qualcomm `ModemNrStream`. The decoded
 * RRC bytes feed the shared `NrRrcDecoder` / `TddProfile` pipeline, unchanged.
 *
 * ## What is solid vs. what awaits hardware (RQ5)
 *
 * - **Solid & unit-tested:** [processStream] — the read-buffer accumulation, frame boundary handling,
 *   carry-over of partial frames across reads, and NR/LTE RRC dispatch. [enableSequence] uses the
 *   SCAT-referenced CONTROL_START frame.
 * - **NOT yet validated on a device:** [openDiagStream] — the exact `su` incantation to open
 *   `/dev/umts_dm0` read+write under SELinux (RQ1 found it `system:system` on an *Enforcing* device,
 *   so this likely needs `setenforce 0` or a policy shim), and the **per-domain item-select requests**
 *   that tell the modem *which* logs to stream (CONTROL_START alone may not surface LTE/NR RRC). These
 *   are the first things to nail on a rootable Shannon device. See `docs/exynos-spike.md`.
 */
class SdmTransport {

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    @Volatile
    var unavailableReason: String? = null
        private set

    /**
     * The frames to write to the diagnostic channel to begin logging. CONTROL_START is referenced
     * from SCAT; the **item-select requests are still a gap** — the modem likely needs
     * `COMMON_ITEM_SELECT_REQUEST` (0x10) / `LTE_ITEM_SELECT_REQUEST` (0x20) frames whose payloads
     * enumerate the wanted log items, and those payload formats must be confirmed on hardware before
     * RRC will actually stream. Returned as a list so the on-device work can append them here.
     */
    fun enableSequence(): List<ByteArray> = buildList {
        add(SdmParser.controlStartFrame())
        // TODO(RQ5): append COMMON_ITEM_SELECT_REQUEST / LTE_ITEM_SELECT_REQUEST frames selecting
        //  LTE_RRC_OTA_PACKET and COMMON_NR_RRC_SIGNALING_INFO once their payload layout is confirmed.
    }

    /**
     * Consume [input] (a live diagnostic byte stream) to completion or until [stop], decoding SDM
     * frames and handing RRC ones to the callbacks. Pure byte→frame logic: no device or root needed,
     * so this is the part [SdmTransportTest] exercises. Partial frames at a read boundary are carried
     * into the next read.
     */
    fun processStream(
        input: InputStream,
        onNrRrc: (SdmParser.SdmFrame) -> Unit,
        onLteRrc: (SdmParser.LteRrcOta) -> Unit,
    ) {
        // Arm the loop so this can be driven directly (both from start()'s worker and from tests);
        // it ends on EOF, a read error, or stop().
        running.set(true)
        var carry = ByteArray(0)
        val chunk = ByteArray(0x1000)
        while (running.get()) {
            val n = try {
                input.read(chunk)
            } catch (e: Exception) {
                unavailableReason = "Diagnostic stream read failed: ${e.message}"
                break
            }
            if (n < 0) break
            if (n == 0) continue
            val buf = if (carry.isEmpty()) chunk.copyOf(n) else carry + chunk.copyOf(n)
            val result = SdmParser.parseFrames(buf)
            for (f in result.frames) {
                when {
                    f.isNrRrc -> onNrRrc(f)
                    f.isLteRrcOta -> SdmParser.parseLteRrcOta(f.payload)?.let(onLteRrc)
                }
            }
            carry = if (result.nextOffset < buf.size) buf.copyOfRange(result.nextOffset, buf.size) else ByteArray(0)
            // Guard against an unbounded carry if the stream is pure garbage (never a valid frame).
            if (carry.size > MAX_CARRY) carry = ByteArray(0)
        }
    }

    /**
     * Start streaming on a background thread. **Device open + enable are not yet hardware-validated**
     * — see the class doc and [openDiagStream]. Returns false (with [unavailableReason] set) if the
     * stream could not be opened.
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
            }
        }.apply { isDaemon = true; name = "sdm-transport"; start() }
        return true
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    /**
     * Open `/dev/umts_dm0` as a readable stream with SDM logging enabled.
     *
     * **NOT hardware-validated (RQ5).** The outline: via `su`, (optionally `setenforce 0`), open the
     * node read+write on one persistent fd, write [enableSequence] to it, then stream it back. Doing
     * this with a single long-lived fd matters — the modem may stop logging when the fd closes. The
     * concrete `su` plumbing is deliberately left to the on-device spike rather than guessed here, so
     * this returns null with an honest reason until then.
     */
    private fun openDiagStream(): InputStream? {
        unavailableReason =
            "Exynos/Shannon live capture is not yet enabled — the /dev/umts_dm0 open + SDM enable " +
                "handshake needs validation on a rooted Shannon device (see docs/exynos-spike.md)."
        return null
    }

    private companion object {
        const val MAX_CARRY = 1 shl 20   // 1 MiB: a partial frame is never this large
    }
}
