package com.nhnengineering.rftest.cellular

import android.content.Context
import android.util.Log
import com.nhnengineering.rftest.modem.QmiNasClient
import com.nhnengineering.rftest.modem.QmiSelectionPreference

/**
 * Applies and releases a [TechnologyLock] over QMI, and remembers enough to undo it after a crash.
 *
 * The transport (root helper, per-request port resolution) lives in [QmiNasClient], shared
 * with [BandLockController].
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
        // NR band masks as they were before NSA-only emptied the SA one. Present only while
        // NSA-only is (or may still be) in force.
        const val KEY_SA = "baseline_nr_sa"
        const val KEY_NSA = "baseline_nr_nsa"
    }

    private val client = QmiNasClient(context)
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What the radio was allowed before this app touched it, or null if it has not. */
    private var savedBaseline: Int?
        get() = if (prefs.contains(KEY_BASELINE)) prefs.getInt(KEY_BASELINE, 0) else null
        set(v) = prefs.edit().apply {
            if (v == null) remove(KEY_BASELINE) else putInt(KEY_BASELINE, v)
        }.apply()

    private fun putWords(key: String, v: List<Long>?) = prefs.edit().apply {
        if (v == null) remove(key) else putString(key, v.joinToString(","))
    }.apply()

    private fun getWords(key: String): List<Long>? =
        prefs.getString(key, null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 8 }

    /** True while NSA-only has emptied the SA band mask and not yet given it back. */
    val holdsSaMask: Boolean get() = getWords(KEY_SA) != null

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

    /** Reads the current Mode Preference, or the reason it could not be read. */
    private fun currentMaskOrReason(): Read = when (val r = client.read()) {
        is QmiNasClient.Snapshot.Failed -> Read.Failed(r.reason)
        is QmiNasClient.Snapshot.Ok -> Read.Ok(r.result.modePref!!)
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
        val snap = when (val r = client.read()) {
            is QmiNasClient.Snapshot.Failed -> return Outcome(
                false,
                "Could not read the current setting, so it could not be safely restored " +
                    "later. Nothing was changed.",
            )
            is QmiNasClient.Snapshot.Ok -> r.result
        }
        val currentMode = snap.modePref!!

        // A failure before anything was written leaves the modem untouched, so state saved a
        // moment ago would be a phantom "restore pending". Once something is held it must stay.
        val heldBefore = savedTechnology != null
        fun fail(message: String): Outcome {
            if (!heldBefore) {
                savedBaseline = null
                putWords(KEY_SA, null)
                putWords(KEY_NSA, null)
            }
            return Outcome(false, message)
        }

        if (savedBaseline == null) savedBaseline = currentMode
        val baseline = savedBaseline!!

        if (technology.excludesStandalone) {
            val writeMode = TechnologyLock.nsaOnlyWriteMode(baseline)
                ?: return fail(
                    "This handset does not allow both LTE and 5G at baseline, so NSA cannot " +
                        "exist here. Nothing was changed.",
                )
            val sa = getWords(KEY_SA) ?: snap.bands.nrSa
            val nsa = getWords(KEY_NSA) ?: snap.bands.nrNsa
            if (sa == null || nsa == null) {
                return fail(
                    "The modem did not report its 5G band masks, so NSA-only could not be safely " +
                        "undone. Nothing was changed.",
                )
            }
            // Saved before the write: the masks are only recoverable from here if it goes wrong.
            putWords(KEY_SA, sa)
            putWords(KEY_NSA, nsa)
            client.write(QmiSelectionPreference.nsaOnlyArgs(writeMode, nsa))?.let { return fail(it) }
        } else {
            // Leaving NSA-only: the SA mask has to come back *before* the mode narrows, or
            // "5G SA only" would find no SA band to camp on. Written under the mode already in
            // force, the combination that was verified.
            val sa = getWords(KEY_SA)
            val nsa = getWords(KEY_NSA)
            if (sa != null && nsa != null) {
                client.write(QmiSelectionPreference.restoreNrSaArgs(currentMode, sa, nsa))?.let {
                    return Outcome(false, "Could not give back the standalone bands: $it")
                }
                putWords(KEY_SA, null)
                putWords(KEY_NSA, null)
            }
            client.write(QmiSelectionPreference.setModePrefArgs(technology.modePref))?.let {
                return fail(it)
            }
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
        if (technology.excludesStandalone) return verifyNsaOnly(technology)
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
     * NSA-only is two settings, so both are read back: the mode alone cannot tell it from the
     * baseline, since the same mode allows standalone while the SA mask is intact.
     *
     * This confirms what the modem is allowed to do. It cannot show an NR carrier, because in
     * NSA that leg exists only while data flows -- idle, the phone looks like plain LTE. The
     * session's own record of technologies is where the behaviour shows.
     */
    private fun verifyNsaOnly(technology: TechnologyLock.Technology): Outcome {
        val snap = when (val r = client.read()) {
            is QmiNasClient.Snapshot.Failed -> return Outcome(false, "Could not read the modem to confirm the lock.")
            is QmiNasClient.Snapshot.Ok -> r.result
        }
        val mode = snap.modePref!!
        val sa = snap.bands.nrSa?.let { QmiSelectionPreference.bandsOf(it) }
            ?: return Outcome(false, "The modem did not report its standalone band mask, so the lock could not be confirmed.")
        return if (TechnologyLock.nsaOnlyHeld(mode, sa)) {
            Outcome(
                applied = true,
                message = "Holding ${technology.label}. Standalone is excluded (no SA band allowed); " +
                    "5G data appears only while traffic is flowing.",
                effectiveMask = mode,
            )
        } else {
            Log.w(TAG, "NSA-only did not hold: mode 0x%04x, SA bands $sa".format(mode))
            Outcome(
                applied = false,
                message = "This handset did not keep NSA-only. Mode is " +
                    "${TechnologyLock.describeMask(mode)} with ${sa.size} standalone bands still allowed. " +
                    "Measurements will not be constrained to NSA.",
                effectiveMask = mode,
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
        // With NSA-only held the SA mask goes back too, together with the baseline mode in the one
        // write the modem accepts for NR masks.
        val sa = getWords(KEY_SA)
        val nsa = getWords(KEY_NSA)
        val args = if (sa != null && nsa != null) {
            QmiSelectionPreference.restoreNrSaArgs(baseline, sa, nsa)
        } else {
            QmiSelectionPreference.setModePrefArgs(baseline)
        }
        client.write(args)?.let {
            return Outcome(
                false,
                "Could not release the lock: $it It clears itself on Airplane Mode or a restart.",
            )
        }

        savedBaseline = null
        savedTechnology = null
        putWords(KEY_SA, null)
        putWords(KEY_NSA, null)
        // Reports what was written, not a read taken straight afterwards: observed on 2026-09-26
        // that a GET issued immediately after a SET still returned the old value, which put
        // "Back to LTE" on screen after a restore to 0x5F.
        Log.i(TAG, "released, restored mask 0x%04x".format(baseline))
        return Outcome(
            applied = true,
            message = "Released. Restored to ${TechnologyLock.describeMask(baseline)}.",
            effectiveMask = null,
        )
    }
}
