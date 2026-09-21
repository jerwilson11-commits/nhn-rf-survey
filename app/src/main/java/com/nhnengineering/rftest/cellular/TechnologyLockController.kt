package com.nhnengineering.rftest.cellular

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Applies and releases a [TechnologyLock], and remembers enough to undo it after a crash.
 *
 * ## The failure this is built around
 *
 * A technology lock outlives the process that set it. It is written into the framework, not held
 * in memory, so an app that locks the radio to NR and is then killed -- by the user, by the
 * system, by a crash mid-survey -- leaves a handset with no data service and nothing on screen
 * explaining why. The obvious implementation, releasing the lock in `onDestroy`, is exactly the
 * one that does not run in any of those cases.
 *
 * So the baseline is persisted the moment before the first lock is applied, and [pendingRestore]
 * lets the next launch discover that a lock is outstanding and offer to undo it. The lock itself
 * is deliberately *not* auto-released at startup: a drive test that pauses to reopen the app
 * should not silently lose the constraint it is measuring under.
 */
class TechnologyLockController(context: Context) {

    private val appContext = context.applicationContext
    private val tm: TelephonyManager? =
        appContext.getSystemService(TelephonyManager::class.java)
    private val prefs =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What the radio was allowed before this app touched it, or null if it has not. */
    private var savedBaseline: Long?
        get() = if (prefs.contains(KEY_BASELINE)) prefs.getLong(KEY_BASELINE, 0L) else null
        set(v) = prefs.edit().apply {
            if (v == null) remove(KEY_BASELINE) else putLong(KEY_BASELINE, v)
        }.apply()

    private var savedTechnology: String?
        get() = prefs.getString(KEY_TECHNOLOGY, null)
        set(v) = prefs.edit().apply {
            if (v == null) remove(KEY_TECHNOLOGY) else putString(KEY_TECHNOLOGY, v)
        }.apply()

    /**
     * Why locking is unavailable, or null where it is available.
     *
     * Refusal is the ordinary case -- an unprivileged install cannot do this -- so the reason is
     * phrased for display rather than for a log.
     */
    val unavailableReason: String?
        get() {
            if (tm == null) return "This device has no telephony service."
            return when (val probe = probe()) {
                null -> null
                else -> probe
            }
        }

    /**
     * Reads the current user-reason allowance, returning a failure string if that is refused.
     *
     * The getter needs `READ_PRIVILEGED_PHONE_STATE`, so this doubles as the permission check:
     * if the allowance cannot be read, it certainly cannot be written.
     */
    @SuppressLint("MissingPermission")
    private fun probe(): String? = try {
        tm?.getAllowedNetworkTypesForReason(
            TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
        )
        null
    } catch (e: SecurityException) {
        Log.i(TAG, "technology lock unavailable: ${e.message}")
        "Needs a privileged install. The app must sit in /system/priv-app and be allowlisted " +
            "for MODIFY_PHONE_STATE."
    } catch (e: UnsupportedOperationException) {
        Log.i(TAG, "technology lock unsupported: ${e.message}")
        "This handset's telephony stack does not support setting allowed network types."
    } catch (e: IllegalStateException) {
        Log.i(TAG, "technology lock unavailable: ${e.message}")
        "The modem is not currently reachable."
    }

    /** The allowance in force for the user reason, or null if it cannot be read. */
    @SuppressLint("MissingPermission")
    fun currentMask(): Long? = try {
        tm?.getAllowedNetworkTypesForReason(
            TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
        )
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalStateException) {
        null
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
        val effectiveMask: Long? = null,
    )

    /**
     * Holds the radio on [technology] until [release] is called.
     *
     * The baseline is captured before the first lock only, so locking twice in a row still
     * restores to what the handset had before any of this started rather than to the first lock.
     */
    @SuppressLint("MissingPermission")
    fun lock(technology: TechnologyLock.Technology): Outcome {
        val manager = tm ?: return Outcome(false, "This device has no telephony service.")
        unavailableReason?.let { return Outcome(false, it) }

        if (savedBaseline == null) {
            val baseline = currentMask()
                ?: return Outcome(false, "Could not read the current setting, so it could not " +
                    "be safely restored later. Nothing was changed.")
            savedBaseline = baseline
        }

        return try {
            manager.setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
                technology.mask,
            )
            savedTechnology = technology.name
            Log.i(TAG, "requested ${technology.name}; awaiting verification")
            // Deliberately not reported as "holding" yet. The write having been accepted is not
            // evidence that the radio kept it -- see TechnologyLock.lockHeld. The caller confirms
            // with verify() a few seconds later, and only that result is worth showing.
            Outcome(
                applied = true,
                message = "Requested ${technology.label}. Confirming the radio holds it...",
                effectiveMask = null,
            )
        } catch (e: SecurityException) {
            // Baseline is left in place: the write may have partially landed, and a stale
            // baseline that restores correctly is safer than none that cannot.
            Log.w(TAG, "lock refused", e)
            Outcome(false, "Refused by the platform: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "lock failed", e)
            Outcome(false, "The modem rejected the change: ${e.message}")
        }
    }

    /**
     * Confirms, by observation, whether the radio is actually holding the requested technology.
     *
     * Call this a few seconds after [lock], never immediately: where a vendor layer overrides the
     * framework value it does so shortly after the write, so an instant read reports a lock that
     * is about to disappear.
     *
     * A false result is a real finding rather than an error. It means this handset does not
     * honour the platform's technology selection, and any measurement taken believing otherwise
     * would carry the wrong label.
     */
    fun verify(): Outcome {
        val technology = activeTechnology
            ?: return Outcome(true, "Nothing is being held.", currentMask())
        val observed = currentMask()
            ?: return Outcome(false, "Could not read the current allowance to confirm the lock.")

        return if (TechnologyLock.lockHeld(technology.mask, observed)) {
            Outcome(
                applied = true,
                message = "Holding ${technology.label}. Radio is allowing " +
                    "${TechnologyLock.describeMask(observed)}.",
                effectiveMask = observed,
            )
        } else {
            Log.w(
                TAG,
                "lock did not hold: requested ${technology.mask}, observed $observed",
            )
            Outcome(
                applied = false,
                message = "This handset did not keep the setting. It was asked for " +
                    "${technology.label} and is allowing " +
                    "${TechnologyLock.describeMask(observed)}. Measurements will not be " +
                    "constrained to ${technology.label}.",
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
    @SuppressLint("MissingPermission")
    fun release(): Outcome {
        val manager = tm ?: return Outcome(false, "This device has no telephony service.")
        val baseline = savedBaseline
            ?: return Outcome(true, "Nothing was being held.")

        return try {
            manager.setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
                baseline,
            )
            savedBaseline = null
            savedTechnology = null
            val now = currentMask()
            Log.i(TAG, "released, effective mask $now")
            Outcome(
                applied = true,
                message = "Released. Back to ${TechnologyLock.describeMask(now ?: baseline)}.",
                effectiveMask = now,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "release refused", e)
            Outcome(
                false,
                "Could not release the lock: ${e.message}. It can be cleared by hand from " +
                    "Settings > Network & internet > SIM > Preferred network type.",
            )
        } catch (e: IllegalStateException) {
            Log.w(TAG, "release failed", e)
            Outcome(
                false,
                "The modem rejected the change: ${e.message}. It can be cleared by hand from " +
                    "Settings > Network & internet > SIM > Preferred network type.",
            )
        }
    }

    private companion object {
        const val TAG = "TechnologyLock"
        const val PREFS = "technology_lock"
        const val KEY_BASELINE = "baseline_mask"
        const val KEY_TECHNOLOGY = "technology"
    }
}
