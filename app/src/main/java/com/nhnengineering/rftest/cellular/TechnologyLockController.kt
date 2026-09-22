package com.nhnengineering.rftest.cellular

import android.content.Context
import android.util.Log
import com.nhnengineering.rftest.modem.QmiSelectionPreference
import com.nhnengineering.rftest.modem.QrtrServices
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Applies and releases a [TechnologyLock] over QMI, and remembers enough to undo it after a crash.
 *
 * ## Why this shells out
 *
 * `AF_QIPCRTR` needs root and is not reachable from the Java socket API, so the actual write goes
 * through `qmilock`, a small native helper that does nothing but send the bytes it is given and
 * print the reply as hex -- exactly the same split already used for reading neighbours in
 * [com.nhnengineering.rftest.modem.ModemNeighbourSource]. Every byte of TLV construction and
 * response decoding happens here in Kotlin, in [QmiSelectionPreference], not in the helper.
 *
 * ## Why the port is resolved on every call
 *
 * The Network Access Service's QRTR port is assigned when it registers, not fixed by the
 * protocol. It has been observed at four different values across four modem restarts on this
 * project -- 86, 88, 87, 80 -- including one caused by nothing more than an Airplane Mode toggle.
 * A cached port fails in the worst available way: the request still gets *a* reply, because
 * something is listening, so the failure only shows up in the QMI result rather than as a
 * connection error. [QrtrServices] is asked fresh every time rather than trusted from last time.
 *
 * ## The failure this whole class is built around
 *
 * A technology lock outlives the process that set it. It is written into the modem, not held in
 * memory, so an app that locks the radio to NR and is then killed -- by the user, by the system,
 * by a crash mid-survey -- leaves a handset with no data service and nothing on screen explaining
 * why. The obvious implementation, releasing the lock in `onDestroy`, is exactly the one that does
 * not run in any of those cases.
 *
 * So the baseline is persisted the moment before the first lock is applied, and [pendingRestore]
 * lets the next launch discover that a lock is outstanding and offer to undo it. The lock itself
 * is deliberately *not* auto-released at startup: a drive test that pauses to reopen the app
 * should not silently lose the constraint it is measuring under. Every write additionally carries
 * change duration "until power cycle", so a reboot or an Airplane Mode toggle is always a way out
 * even if this bookkeeping is somehow lost too.
 */
class TechnologyLockController(context: Context) {

    private companion object {
        const val TAG = "TechnologyLock"
        const val PREFS = "technology_lock"
        const val KEY_BASELINE = "baseline_mode_pref"
        const val KEY_TECHNOLOGY = "technology"

        const val ASSET = "qmilock-arm64-v8a"
        const val HELPER_NAME = "qmilock"
        const val NAS_SERVICE = QrtrServices.SERVICE_NAS
        const val HELPER_TIMEOUT_MS = 6_000L
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What the radio was allowed before this app touched it, or null if it has not. */
    private var savedBaseline: Int?
        get() = if (prefs.contains(KEY_BASELINE)) prefs.getInt(KEY_BASELINE, 0) else null
        set(v) = prefs.edit().apply {
            if (v == null) remove(KEY_BASELINE) else putInt(KEY_BASELINE, v)
        }.apply()

    private var savedTechnology: String?
        get() = prefs.getString(KEY_TECHNOLOGY, null)
        set(v) = prefs.edit().apply {
            if (v == null) remove(KEY_TECHNOLOGY) else putString(KEY_TECHNOLOGY, v)
        }.apply()

    /**
     * Why locking is unavailable, or null where it is available.
     *
     * Refusal is the ordinary case -- this needs root, which an ordinary install does not have --
     * so the reason is phrased for display rather than for a log.
     */
    val unavailableReason: String?
        get() = when (val r = currentMaskOrReason()) {
            is Read.Failed -> r.reason
            is Read.Ok -> null
        }

    /** The technology this app currently has applied, or null if it has applied none. */
    val activeTechnology: TechnologyLock.Technology?
        get() = savedTechnology?.let { name ->
            TechnologyLock.Technology.entries.firstOrNull { it.name == name }
        }

    /**
     * True when a lock from an earlier run is still in force.
     *
     * The caller is expected to say so prominently. A handset silently held off 5G for a week
     * because a survey app died is a support call, and a confusing one.
     */
    val pendingRestore: Boolean get() = savedBaseline != null

    /** Outcome of a lock or release, including what the radio actually ended up allowing. */
    data class Outcome(
        val applied: Boolean,
        val message: String,
        val effectiveMask: Int? = null,
    )

    private sealed interface Read {
        data class Ok(val modePref: Int) : Read
        data class Failed(val reason: String) : Read
    }

    /** Resolves NAS and unpacks the helper. Cached only for the lifetime of one call. */
    private fun resolveNas(): QrtrServices.Address? = runCatching {
        val p = ProcessBuilder("su", "-c", "/vendor/bin/qrtr-lookup")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(HELPER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        QrtrServices.find(out, NAS_SERVICE)
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

    /** Runs the helper as root against [args], returning its first line or null if it could not run. */
    private fun runHelper(addr: QrtrServices.Address, msgIdHex: String, args: List<String>): String? {
        val helper = extractHelper() ?: return null
        val cmd = (listOf(helper.absolutePath, addr.node.toString(), addr.port.toString(), msgIdHex) + args)
            .joinToString(" ")
        return runCatching {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val line = p.inputStream.bufferedReader().use { it.readLine() }
            if (!p.waitFor(HELPER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return "ERR helper timed out"
            }
            line
        }.getOrElse {
            // An IOException here means su is absent, i.e. not rooted. Not an error.
            Log.i(TAG, "qmilock unavailable: ${it.message}")
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Reads the current Mode Preference, or the reason it could not be read. */
    private fun currentMaskOrReason(): Read {
        val addr = resolveNas()
            ?: return Read.Failed(
                "The Network Access Service is not reachable over QRTR, so the modem cannot be " +
                    "asked. This needs a rooted handset; everything else in the app works " +
                    "without it.",
            )
        val line = runHelper(addr, "%04x".format(QmiSelectionPreference.MSG_GET), emptyList())
            ?: return Read.Failed(
                "No root. Reading or setting the technology lock needs a rooted handset.",
            )
        if (!line.startsWith("OK ")) {
            return Read.Failed("The modem refused the request: ${line.removePrefix("ERR ").take(160)}")
        }
        val parsed = QmiSelectionPreference.parseGet(hexToBytes(line.removePrefix("OK ").trim()))
        val modePref = parsed.modePref
            ?: return Read.Failed("Could not read the current setting from the modem's reply.")
        return Read.Ok(modePref)
    }

    /** The allowance in force, or null if it cannot currently be read. */
    fun currentMask(): Int? = (currentMaskOrReason() as? Read.Ok)?.modePref

    /**
     * Holds the radio on [technology] until [release] is called.
     *
     * The baseline is captured before the first lock only, so locking twice in a row still
     * restores to what the handset had before any of this started rather than to the first lock.
     */
    fun lock(technology: TechnologyLock.Technology): Outcome {
        val addr = resolveNas()
            ?: return Outcome(false, "The Network Access Service is not reachable over QRTR.")

        if (savedBaseline == null) {
            val baseline = currentMask()
                ?: return Outcome(
                    false,
                    "Could not read the current setting, so it could not be safely restored " +
                        "later. Nothing was changed.",
                )
            savedBaseline = baseline
        }

        val args = QmiSelectionPreference.setModePrefArgs(technology.modePref)
        val line = runHelper(addr, "%04x".format(QmiSelectionPreference.MSG_SET), args)
            ?: return Outcome(false, "No root. Could not write the setting.")
        if (!line.startsWith("OK ")) {
            return Outcome(false, "Refused: ${line.removePrefix("ERR ").take(160)}")
        }
        val accepted = QmiSelectionPreference.parseSetResult(hexToBytes(line.removePrefix("OK ").trim()))
        if (accepted != true) {
            return Outcome(false, "The modem rejected the change.")
        }

        savedTechnology = technology.name
        Log.i(TAG, "requested ${technology.name} (0x%04x); awaiting verification".format(technology.modePref))
        // Deliberately not reported as "holding" yet. Acceptance is not evidence the radio kept
        // it -- see TechnologyLock.lockHeld. The caller confirms with verify() a few seconds
        // later, and only that result is worth showing.
        return Outcome(
            applied = true,
            message = "Requested ${technology.label}. Confirming the radio holds it...",
            effectiveMask = null,
        )
    }

    /**
     * Confirms, by observation, whether the radio is actually holding the requested technology.
     *
     * Call this a few seconds after [lock], never immediately: where a vendor layer overrides a
     * setting it does so shortly after the write, so an instant read reports a lock that is about
     * to disappear. QMI has not shown that behaviour in testing, but the check costs a few lines
     * and the alternative is trusting a write that has already lied to this app once, over a
     * different transport.
     */
    fun verify(): Outcome {
        val technology = activeTechnology
            ?: return Outcome(true, "Nothing is being held.", currentMask())
        val observed = currentMask()
            ?: return Outcome(false, "Could not read the current allowance to confirm the lock.")

        return if (TechnologyLock.lockHeld(technology.modePref, observed)) {
            Outcome(
                applied = true,
                message = "Holding ${technology.label}. Radio is allowing " +
                    "${TechnologyLock.describeMask(observed)}.",
                effectiveMask = observed,
            )
        } else {
            Log.w(TAG, "lock did not hold: requested ${technology.modePref}, observed $observed")
            Outcome(
                applied = false,
                message = "This handset did not keep the setting. It was asked for " +
                    "${technology.label} and is allowing ${TechnologyLock.describeMask(observed)}. " +
                    "Measurements will not be constrained to ${technology.label}.",
                effectiveMask = observed,
            )
        }
    }

    /**
     * Restores the allowance captured before the first lock.
     *
     * Succeeds and clears its own state when there was nothing to restore, so a caller can use
     * this as an unconditional "make sure nothing is held" without checking first.
     */
    fun release(): Outcome {
        val baseline = savedBaseline
            ?: return Outcome(true, "Nothing was being held.")
        val addr = resolveNas()
            ?: return Outcome(
                false,
                "The Network Access Service is not reachable to release the lock. It clears " +
                    "itself on Airplane Mode or a restart.",
            )

        val args = QmiSelectionPreference.setModePrefArgs(baseline)
        val line = runHelper(addr, "%04x".format(QmiSelectionPreference.MSG_SET), args)
            ?: return Outcome(
                false,
                "No root, so the lock could not be released here. It clears itself on Airplane " +
                    "Mode or a restart.",
            )
        if (!line.startsWith("OK ") ||
            QmiSelectionPreference.parseSetResult(hexToBytes(line.removePrefix("OK ").trim())) != true
        ) {
            return Outcome(
                false,
                "Could not release the lock: ${line.take(160)}. It clears itself on Airplane " +
                    "Mode or a restart.",
            )
        }

        savedBaseline = null
        savedTechnology = null
        val now = currentMask()
        Log.i(TAG, "released, effective mask $now")
        return Outcome(
            applied = true,
            message = "Released. Back to ${TechnologyLock.describeMask(now ?: baseline)}.",
            effectiveMask = now,
        )
    }
}
