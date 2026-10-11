package com.nhnengineering.rftest.modem

import java.io.File

/**
 * The MediaTek backend — registered and gated OFF. Unlike [ExynosBackend], there is **no parser yet**,
 * on purpose.
 *
 * ## Why MediaTek has no parser (and Exynos does)
 *
 * The Exynos/Shannon parser could be written clean-room because SCAT documents the Samsung **SDM**
 * byte format completely. **No equivalent open reference exists for MediaTek's diagnostic log framing:**
 * MobileInsight's packet decoder (`dm_collector_c/log_packet.cpp`) is **entirely Qualcomm** — its own
 * docs list MediaTek as a *future* plan — and the MediaTek "MD log" byte layout is not published in a
 * form that can be ported. Writing a MediaTek log parser now would mean **inventing** the header/frame
 * offsets, which this project does not do (see the empirical discipline throughout `modem/`). The
 * parser therefore waits on a **real capture from a rooted MediaTek device** to reverse-engineer the
 * framing — which, with no MediaTek hardware on hand and MediaTek's lack of US B14/CBRS bands, keeps
 * this the lowest-priority chipset (see `docs/mediatek-spike.md`, `docs/test-device-research.md`).
 *
 * ## What *is* grounded (from the MobileInsight paper), for when a device is available
 *
 * - **Transport node:** `/dev/ccci_md_log_ctrl` (CCCI = MediaTek AP↔modem interface).
 * - **Enable:** via `ioctl` on that node, or the `AT+TRACE` command, depending on chipset/firmware.
 * - **Parse shape:** two-stage — a metadata parser reads the message type id + release version, then
 *   selects the per-message parser. Scope: 4G/3G NAS and RRC OTA (feeds the shared `NrRrcDecoder` /
 *   `TddProfile` pipeline once RRC bytes are in hand). The optional no-root **EngineerMode**
 *   (`*#*#3646633#*#*`) RF-info angle is a separate, device-dependent investigation.
 *
 * Until the framing is reverse-engineered on hardware and a capture decodes end-to-end, [availability]
 * returns [BackendAvailability.Unsupported] (so the capability gate reports `WRONG_CHIPSET`, unchanged).
 * The real rooted + node check is written behind [VALIDATED], mirroring [ExynosBackend].
 */
object MediaTekBackend : ModemBackend {

    /** Flip to true only after the MD-log framing is reverse-engineered and a capture decodes. */
    const val VALIDATED = false

    /** MediaTek's diagnostic/log control node (MobileInsight). */
    const val CCCI_MD_LOG_NODE = "/dev/ccci_md_log_ctrl"

    override val vendor: ModemChipset.Vendor = ModemChipset.Vendor.MEDIATEK

    override fun availability(): BackendAvailability {
        if (!VALIDATED) {
            return BackendAvailability.Unsupported(
                "MediaTek modem tools are not implemented yet — the MD-log format needs reverse-" +
                    "engineering on a rooted MediaTek device.",
            )
        }
        if (!modemRootAvailable()) return BackendAvailability.NeedsRoot
        val present = runCatching { File(CCCI_MD_LOG_NODE).exists() }.getOrDefault(false)
        return if (present) {
            BackendAvailability.Available
        } else {
            BackendAvailability.Unsupported("No MediaTek diagnostic node ($CCCI_MD_LOG_NODE) on this device.")
        }
    }
}
