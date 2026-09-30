package com.nhnengineering.rftest.support

import android.content.Context
import android.os.Build
import android.os.Process
import com.nhnengineering.rftest.BuildConfig
import com.nhnengineering.rftest.billing.EntitlementRepository
import com.nhnengineering.rftest.modem.ModemChipset
import com.nhnengineering.rftest.modem.ModemNrStream
import com.nhnengineering.rftest.modem.QmiNasClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Everything support needs to root-cause a customer's bug report without their phone in hand.
 *
 * ## Why this exists
 *
 * The pro tier's real differentiator -- QMI band and technology locking -- only works through root
 * plus a native helper (`libqmilock.so`) talking to a Qualcomm modem over QRTR, and this app is sold
 * to whoever has a rooted Qualcomm phone, not a certified device list. That is the right call for
 * reach, but it means a bug report from a phone this project has never seen is the normal case, not
 * the exception. Without a way to see what that phone's modem actually said, every such report starts
 * from "doesn't work" and nothing else.
 *
 * This bundle is the fix: device identity, whether root and the helper are actually present, and a
 * live read of the modem's own GET response (mode preference, which band masks came back, and any
 * parse note [QmiSelectionPreference] already produces for a malformed TLV) -- the same GET this app
 * already uses to drive the lock UI, not a special probe that might behave differently. Nothing here
 * writes to the modem: a diagnostics bundle must never be the thing that leaves a customer's radio in
 * a state they did not ask for.
 *
 * ## Why it must work on every tier, not just pro
 *
 * A field-tier customer on a non-rooted phone can still hit a bug, and "no root" is itself useful
 * information to see in the bundle rather than have reported secondhand. Every field here is nullable
 * or has a plain "not available and why" value for exactly that reason.
 */
class DiagnosticsExporter(context: Context) {

    private val appContext = context.applicationContext
    private val qmiClient = QmiNasClient(appContext)

    suspend fun collect(): DiagnosticsBundle = withContext(Dispatchers.IO) {
        DiagnosticsBundle(
            generatedAtUtcMillis = System.currentTimeMillis(),
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            tier = EntitlementRepository.tier.value.name,
            device = collectDevice(),
            root = collectRoot(),
            qmi = collectQmi(),
            recentLog = collectRecentLog(),
        )
    }

    private fun collectDevice(): DeviceInfo = DeviceInfo(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        device = Build.DEVICE,
        board = Build.BOARD,
        hardware = Build.HARDWARE,
        fingerprint = Build.FINGERPRINT,
        androidRelease = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        chipsetVendor = ModemChipset.classify().label,
    )

    /**
     * The same `su -c id` shape [QmiNasClient.request] already relies on to tell "not rooted" apart
     * from every other failure -- an [java.io.IOException] here means no `su` binary, not an error.
     */
    private fun collectRoot(): RootInfo {
        val output = runCatching {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().use { it.readText() }.trim()
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
            text
        }.getOrNull()
        return RootInfo(suAvailable = output?.let { isRootedIdOutput(it) } ?: false, idOutput = output)
    }

    private fun collectQmi(): QmiProbe {
        val helperPresent = qmiClient.helperPresent()
        return when (val snap = qmiClient.read()) {
            is QmiNasClient.Snapshot.Failed -> QmiProbe(
                helperPresent = helperPresent,
                getSucceeded = false,
                failureReason = snap.reason,
            )
            is QmiNasClient.Snapshot.Ok -> {
                val r = snap.result
                QmiProbe(
                    helperPresent = helperPresent,
                    getSucceeded = true,
                    modePref = r.modePref,
                    lteMaskPresent = r.bands.lte != null,
                    nrSaMaskPresent = r.bands.nrSa != null,
                    nrNsaMaskPresent = r.bands.nrNsa != null,
                    parserNotes = r.notes,
                )
            }
        }
    }

    /**
     * The app's own recent log lines, read the way an app is allowed to read its own process's log
     * without `READ_LOGS` (a signature permission this app does not hold). No `su` needed or used --
     * unlike the QMI probe, this must still say something on a non-rooted install.
     *
     * `--pid` is explicit rather than relied on implicitly: a plain `logcat -d` on the 2026-09-29
     * test device (a rooted, Magisk-patched OnePlus 9) came back full of unrelated system entries --
     * that handset's logd policy is looser than the per-app restriction a stock, non-rooted phone
     * enforces. The customers this bundle is for are rooted by definition, so relying on the
     * restriction actually holding would have shipped an over-broad capture to exactly the install
     * base most likely to not have it.
     */
    private fun collectRecentLog(): String? = runCatching {
        val p = ProcessBuilder(
            "logcat", "-d", "-v", "time", "-t", "400", "--pid=${Process.myPid()}",
        ).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
        text.takeIf { it.isNotBlank() }
    }.getOrNull()
}

data class DiagnosticsBundle(
    val generatedAtUtcMillis: Long,
    val appVersionName: String,
    val appVersionCode: Int,
    val tier: String,
    val device: DeviceInfo,
    val root: RootInfo,
    val qmi: QmiProbe,
    val recentLog: String?,
)

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val device: String,
    val board: String,
    val hardware: String,
    val fingerprint: String,
    val androidRelease: String,
    val sdkInt: Int,
    /** [ModemChipset.classify]'s best-effort label -- useful on a bug report from a phone this
     *  project has never seen. */
    val chipsetVendor: String,
)

data class RootInfo(
    val suAvailable: Boolean,
    /** Raw (trimmed) output of `id` under `su`, or null if `su` could not be run at all. */
    val idOutput: String?,
)

data class QmiProbe(
    val helperPresent: Boolean,
    val getSucceeded: Boolean,
    val modePref: Int? = null,
    val lteMaskPresent: Boolean? = null,
    val nrSaMaskPresent: Boolean? = null,
    val nrNsaMaskPresent: Boolean? = null,
    /** [com.nhnengineering.rftest.modem.QmiSelectionPreference.GetResult.notes] verbatim. */
    val parserNotes: List<String> = emptyList(),
    val failureReason: String? = null,
)

/** `su -c id`'s own output names the root: a plain "not found" or a shell error is not root. */
internal fun isRootedIdOutput(output: String): Boolean =
    Regex("""uid=0\b""").containsMatchIn(output)

/**
 * Renders a [DiagnosticsBundle] as the plain-text report support actually reads -- pure so the
 * format is pinned by a test rather than only ever eyeballed after a real export.
 */
internal fun renderDiagnosticsText(bundle: DiagnosticsBundle): String {
    val at = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(bundle.generatedAtUtcMillis)

    return buildString {
        appendLine("RF Test App -- Diagnostics")
        appendLine("Generated: $at")
        appendLine("App version: ${bundle.appVersionName} (${bundle.appVersionCode})")
        appendLine("Tier: ${bundle.tier}")
        appendLine()
        appendLine("Device")
        appendLine("  Manufacturer / model: ${bundle.device.manufacturer} / ${bundle.device.model}")
        appendLine("  Device / board / hardware: ${bundle.device.device} / ${bundle.device.board} / ${bundle.device.hardware}")
        appendLine("  Android: ${bundle.device.androidRelease} (SDK ${bundle.device.sdkInt})")
        appendLine("  Build fingerprint: ${bundle.device.fingerprint}")
        appendLine("  Detected modem chipset: ${bundle.device.chipsetVendor}")
        appendLine()
        appendLine("Root")
        appendLine("  su available: ${if (bundle.root.suAvailable) "yes" else "no"}")
        appendLine("  id output: ${bundle.root.idOutput ?: "(su could not be run at all)"}")
        appendLine()
        appendLine("QMI modem probe (read-only GET -- nothing here was written to the modem)")
        appendLine("  Helper (libqmilock.so) present: ${if (bundle.qmi.helperPresent) "yes" else "no"}")
        appendLine("  GET succeeded: ${if (bundle.qmi.getSucceeded) "yes" else "no"}")
        if (bundle.qmi.getSucceeded) {
            appendLine("  Mode preference: ${bundle.qmi.modePref?.let { "0x%04x".format(it) } ?: "—"}")
            appendLine("  LTE band mask present: ${bundle.qmi.lteMaskPresent.label()}")
            appendLine("  NR SA band mask present: ${bundle.qmi.nrSaMaskPresent.label()}")
            appendLine("  NR NSA band mask present: ${bundle.qmi.nrNsaMaskPresent.label()}")
            appendLine(
                "  Parser notes: " +
                    (bundle.qmi.parserNotes.takeIf { it.isNotEmpty() }?.joinToString("; ") ?: "(none)"),
            )
        } else {
            appendLine("  Failure reason: ${bundle.qmi.failureReason ?: "unknown"}")
        }
        appendLine()
        appendLine("Recent app log (last 400 lines, this app's own process only)")
        appendLine(bundle.recentLog ?: "  (not available)")
    }
}

private fun Boolean?.label(): String = when (this) {
    true -> "yes"
    false -> "no"
    null -> "—"
}

/**
 * Renders a captured NAS-5GS/RRC OTA signaling sequence as plain text, one line per event --
 * pure, like [renderDiagnosticsText], so the format is pinned by a test rather than only ever
 * eyeballed after a real capture. [ModemNrStream.SignalingEvent] is inherently a sequence (see its
 * own doc), which is why this is a separate export rather than a section folded into
 * [DiagnosticsBundle]: that bundle is a single-shot "state right now" snapshot, and a signaling
 * capture is a timeline.
 */
internal fun renderSignalingLog(events: List<ModemNrStream.SignalingEvent>): String = buildString {
    appendLine("RF Test App -- Signaling capture (NAS-5GS / RRC OTA)")
    appendLine("${events.size} event(s)")
    appendLine()
    for (e in events) {
        val t = "%6d ms".format(e.atElapsedMs)
        when {
            e.nas != null -> {
                val dir = e.nas.direction?.name ?: "?"
                val summary = when {
                    e.nas.securityProtected == true -> "security protected"
                    e.nas.messageTypeName != null -> e.nas.messageTypeName
                    else -> "unrecognised (0x%02x)".format(e.nas.messageType ?: -1)
                }
                appendLine("[$t] NAS $dir: $summary")
                appendLine("    raw: ${e.nas.rawNasHex ?: "(none)"}")
            }
            e.rrc != null -> {
                val pduLabel = e.rrc.pduType?.label ?: "unrecognised PDU type"
                appendLine("[$t] RRC OTA: pci=${e.rrc.pci ?: "—"} arfcn=${e.rrc.nrArfcn ?: "—"} $pduLabel")
                appendLine("    uper: ${e.rrc.rawUperHex ?: "(none)"}")
            }
            else -> appendLine("[$t] (empty event)")
        }
    }
}
