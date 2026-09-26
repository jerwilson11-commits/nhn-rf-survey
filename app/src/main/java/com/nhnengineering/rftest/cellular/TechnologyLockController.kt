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
    }

    private val client = QmiNasClient(context)
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
        if (savedBaseline == null) {
            val baseline = currentMask()
                ?: return Outcome(
                    false,
                    "Could not read the current setting, so it could not be safely restored " +
                        "later. Nothing was changed.",
                )
            savedBaseline = baseline
        }

        client.write(QmiSelectionPreference.setModePrefArgs(technology.modePref))?.let {
            return Outcome(false, it)
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
        client.write(QmiSelectionPreference.setModePrefArgs(baseline))?.let {
            return Outcome(
                false,
                "Could not release the lock: $it It clears itself on Airplane Mode or a restart.",
            )
        }

        savedBaseline = null
        savedTechnology = null
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
