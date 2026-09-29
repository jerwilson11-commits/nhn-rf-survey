package com.nhnengineering.rftest.cellular

import android.content.Context
import android.util.Log
import com.nhnengineering.rftest.modem.QmiNasClient
import com.nhnengineering.rftest.modem.QmiSelectionPreference

/**
 * Restricts the modem to chosen bands over QMI and restores it exactly afterwards.
 *
 * Same shape and same safety net as [TechnologyLockController], because it has the same failure: a
 * band restriction is written into the modem and outlives this process. Every write uses "until
 * power cycle", and the masks read before the first change are persisted so a later launch can put
 * them back. See [BandLock] for what was proven, what the modem refused, and what this cannot do.
 *
 * Restoring writes back the *bit patterns that were read*, not a recomputed set, so a band the
 * modem reported but this app does not know about survives the round trip.
 *
 * [verify] reads the masks back from the modem. That confirms what the modem is now allowed to
 * use, not where the radio is camped -- re-selection takes seconds, and the report separately
 * compares the bands the session actually saw against the label, which is the independent check.
 */
class BandLockController(context: Context) {

    private companion object {
        const val TAG = "BandLock"
        const val PREFS = "band_lock"
        const val KEY_BASE_LTE = "baseline_lte"
        const val KEY_BASE_SA = "baseline_nr_sa"
        const val KEY_BASE_NSA = "baseline_nr_nsa"
        const val KEY_WANT_LTE = "locked_lte"
        const val KEY_WANT_SA = "locked_nr_sa"
        const val KEY_WANT_NSA = "locked_nr_nsa"
    }

    private val client = QmiNasClient(context)
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun putLongs(key: String, v: List<Long>?) = prefs.edit().apply {
        if (v == null) remove(key) else putString(key, v.joinToString(","))
    }.apply()

    private fun getLongs(key: String): List<Long>? =
        prefs.getString(key, null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.isNotEmpty() }

    private fun getBands(key: String): Set<Int> =
        prefs.getString(key, null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet().orEmpty()

    /** True while a restriction from this app may still be in force. */
    val pendingRestore: Boolean get() = prefs.contains(KEY_BASE_LTE)

    /** What this app last locked to, empty if nothing. */
    val lockedLte: Set<Int> get() = getBands(KEY_WANT_LTE)
    val lockedNrSa: Set<Int> get() = getBands(KEY_WANT_SA)
    val lockedNrNsa: Set<Int> get() = getBands(KEY_WANT_NSA)

    data class Outcome(val applied: Boolean, val message: String)

    data class Supported(val lte: Set<Int>, val nrSa: Set<Int>, val nrNsa: Set<Int> = emptySet())

    /**
     * The bands that may be chosen, or the reason they cannot be read. While a lock is held this
     * reports the *original* allowance, not the restricted one, so the picker still lists
     * everything that can be chosen rather than only what is currently allowed.
     */
    fun supported(): Result<Supported> = when (val r = client.read()) {
        is QmiNasClient.Snapshot.Failed -> Result.failure(IllegalStateException(r.reason))
        is QmiNasClient.Snapshot.Ok -> {
            val lte = getLongs(KEY_BASE_LTE)?.let { QmiSelectionPreference.bandsOf(it) }
                ?: r.result.bands.lte?.let { QmiSelectionPreference.bandsOf(listOf(it)) }
            val sa = getLongs(KEY_BASE_SA)?.let { QmiSelectionPreference.bandsOf(it) }
                ?: r.result.bands.nrSa?.let { QmiSelectionPreference.bandsOf(it) }
            val nsa = getLongs(KEY_BASE_NSA)?.let { QmiSelectionPreference.bandsOf(it) }
                ?: r.result.bands.nrNsa?.let { QmiSelectionPreference.bandsOf(it) }
            if (lte == null && sa == null && nsa == null) {
                Result.failure(IllegalStateException("The modem did not report any band masks."))
            } else {
                Result.success(Supported(BandLock.selectableLte(lte.orEmpty()), sa.orEmpty(), nsa.orEmpty()))
            }
        }
    }

    /**
     * Restricts LTE to [lte], standalone NR to [nrSa], and the NSA NR leg to [nrNsa]. An empty set
     * means "leave that side as it was originally", not "allow nothing". [nrSa] and [nrNsa] are
     * independent: locking NSA does not touch the SA mask and vice versa, since a technology lock
     * held alongside (LTE-only, SA-only, ...) decides which one the radio actually uses.
     */
    fun lock(lte: Set<Int>, nrSa: Set<Int>, nrNsa: Set<Int> = emptySet()): Outcome {
        val snap = when (val r = client.read()) {
            is QmiNasClient.Snapshot.Failed -> return Outcome(false, r.reason)
            is QmiNasClient.Snapshot.Ok -> r.result
        }
        val curLte = snap.bands.lte
        val curSa = snap.bands.nrSa
        val curNsa = snap.bands.nrNsa
        val mode = snap.modePref ?: return Outcome(false, "Could not read the current mode. Nothing was changed.")

        // Baseline once, before the first change, so locking twice still restores to the original.
        if (!pendingRestore) {
            if (curLte == null && curSa == null) {
                return Outcome(false, "The modem reported no band masks, so nothing could be restored later. Nothing was changed.")
            }
            prefs.edit().putString(KEY_BASE_LTE, (curLte?.let { listOf(it) }).orEmpty().joinToString(",")).apply()
            putLongs(KEY_BASE_SA, curSa)
            putLongs(KEY_BASE_NSA, curNsa)
        }
        val baseLte = getLongs(KEY_BASE_LTE)?.firstOrNull()
        val baseSa = getLongs(KEY_BASE_SA)
        val baseNsa = getLongs(KEY_BASE_NSA)

        val supportedLte = baseLte?.let { BandLock.selectableLte(QmiSelectionPreference.bandsOf(listOf(it))) }.orEmpty()
        val supportedSa = baseSa?.let { QmiSelectionPreference.bandsOf(it) }.orEmpty()
        val supportedNsa = baseNsa?.let { QmiSelectionPreference.bandsOf(it) }.orEmpty()
        // A failure before any write leaves the modem untouched, so a baseline captured just now
        // would be a stale "restore pending" with nothing to restore. After a write it must stay.
        var wrote = false
        fun fail(message: String): Outcome {
            if (!wrote && !pendingLockHeld()) clearBaseline()
            return Outcome(false, message)
        }
        BandLock.validate(lte, nrSa, supportedLte, supportedSa, nrNsa, supportedNsa)?.let {
            return fail("$it Nothing was changed.")
        }

        if (lte.isNotEmpty()) {
            client.write(QmiSelectionPreference.setLteBandArgs(lte))?.let {
                return fail("LTE band write failed: $it")
            }
            wrote = true
            prefs.edit().putString(KEY_WANT_LTE, lte.sorted().joinToString(",")).apply()
        } else if (baseLte != null && curLte != baseLte) {
            client.write(QmiSelectionPreference.restoreLteArgs(baseLte))?.let {
                return fail("Could not restore LTE bands: $it")
            }
            wrote = true
        }

        // SA and NSA share one QMI write (mode + both masks travel together, see
        // QmiSelectionPreference), so they are resolved to one target pair and written at most
        // once -- two sequential writes would each read the other mask back to build its own
        // request, and a GET right after a SET can still return the stale value (observed
        // 2026-09-26), which would silently undo whichever mask was written first.
        val targetSa = when {
            nrSa.isNotEmpty() -> QmiSelectionPreference.maskOfNr(nrSa)
            baseSa != null && curSa != baseSa -> baseSa
            else -> curSa
        }
        val targetNsa = when {
            nrNsa.isNotEmpty() -> QmiSelectionPreference.maskOfNr(nrNsa)
            baseNsa != null && curNsa != baseNsa -> baseNsa
            else -> curNsa
        }
        val nrChanged = targetSa != curSa || targetNsa != curNsa
        if (nrChanged) {
            if (targetSa == null || targetNsa == null) {
                return fail("The modem reported no NR band masks, which the NR write must carry.")
            }
            // The NR write must carry the mode preference already in force (see
            // restoreNrSaArgs), so a technology lock held alongside is left as it is.
            client.write(QmiSelectionPreference.restoreNrSaArgs(mode, targetSa, targetNsa))?.let {
                return fail("NR band write failed: $it")
            }
            wrote = true
        }

        prefs.edit()
            .putString(KEY_WANT_LTE, lte.sorted().joinToString(","))
            .putString(KEY_WANT_SA, nrSa.sorted().joinToString(","))
            .putString(KEY_WANT_NSA, nrNsa.sorted().joinToString(","))
            .apply()
        Log.i(TAG, "requested LTE=$lte NR-SA=$nrSa NR-NSA=$nrNsa; awaiting verification")
        return Outcome(true, "Requested. Confirming the modem holds it...")
    }

    private fun pendingLockHeld() = lockedLte.isNotEmpty() || lockedNrSa.isNotEmpty() || lockedNrNsa.isNotEmpty()

    private fun clearBaseline() {
        prefs.edit().remove(KEY_BASE_LTE).remove(KEY_BASE_SA).remove(KEY_BASE_NSA).apply()
    }

    /** Whether the modem's own masks now match what was asked for. */
    fun verify(): Outcome {
        val wantLte = lockedLte
        val wantSa = lockedNrSa
        val wantNsa = lockedNrNsa
        if (wantLte.isEmpty() && wantSa.isEmpty() && wantNsa.isEmpty()) {
            return Outcome(true, "No band lock is being held.")
        }
        val snap = when (val r = client.read()) {
            is QmiNasClient.Snapshot.Failed -> return Outcome(false, r.reason)
            is QmiNasClient.Snapshot.Ok -> r.result
        }
        val baseLte = getLongs(KEY_BASE_LTE)?.firstOrNull()
        val baseSa = getLongs(KEY_BASE_SA)
        val baseNsa = getLongs(KEY_BASE_NSA)
        val gotLte = snap.bands.lte?.let { QmiSelectionPreference.bandsOf(listOf(it)) }.orEmpty()
        val gotSa = snap.bands.nrSa?.let { QmiSelectionPreference.bandsOf(it) }.orEmpty()
        val gotNsa = snap.bands.nrNsa?.let { QmiSelectionPreference.bandsOf(it) }.orEmpty()
        val expectLte = if (wantLte.isNotEmpty()) wantLte else baseLte?.let { QmiSelectionPreference.bandsOf(listOf(it)) }.orEmpty()
        val expectSa = if (wantSa.isNotEmpty()) wantSa else baseSa?.let { QmiSelectionPreference.bandsOf(it) }.orEmpty()
        val expectNsa = if (wantNsa.isNotEmpty()) wantNsa else baseNsa?.let { QmiSelectionPreference.bandsOf(it) }.orEmpty()

        val held = (wantLte.isEmpty() || gotLte == expectLte) &&
            (wantSa.isEmpty() || gotSa == expectSa) &&
            (wantNsa.isEmpty() || gotNsa == expectNsa)
        return if (held) {
            Outcome(
                true,
                "Modem allows " + describe(wantLte, wantSa, wantNsa) +
                    ". Where the phone camps follows within seconds; the report checks the bands the " +
                    "session actually saw against this.",
            )
        } else {
            Log.w(
                TAG,
                "lock did not hold: wanted LTE=$wantLte SA=$wantSa NSA=$wantNsa " +
                    "got LTE=$gotLte SA=$gotSa NSA=$gotNsa",
            )
            Outcome(false, "The modem is not holding the band lock (it reports something else). Measurements will not be band-restricted.")
        }
    }

    private fun describe(lte: Set<Int>, nrSa: Set<Int>, nrNsa: Set<Int> = emptySet()): String =
        (
            lte.sorted().map { "B$it" } +
                nrSa.sorted().map { "n$it (SA)" } +
                nrNsa.sorted().map { "n$it (NSA)" }
            ).joinToString(", ")

    /** Restores the masks read before the first lock. Succeeds trivially when there is nothing to restore. */
    fun release(): Outcome {
        if (!pendingRestore) return Outcome(true, "No band lock was being held.")
        val snap = when (val r = client.read()) {
            is QmiNasClient.Snapshot.Failed -> return Outcome(false, "Could not release: ${r.reason} It clears on Airplane Mode or a restart.")
            is QmiNasClient.Snapshot.Ok -> r.result
        }
        val baseLte = getLongs(KEY_BASE_LTE)?.firstOrNull()
        val baseSa = getLongs(KEY_BASE_SA)
        val baseNsa = getLongs(KEY_BASE_NSA)
        val mode = snap.modePref ?: return Outcome(false, "Could not read the current mode, so nothing was restored.")

        if (baseLte != null) {
            client.write(QmiSelectionPreference.restoreLteArgs(baseLte))?.let {
                return Outcome(false, "Could not restore LTE bands: $it It clears on Airplane Mode or a restart.")
            }
        }
        if (baseSa != null && baseNsa != null) {
            client.write(QmiSelectionPreference.restoreNrSaArgs(mode, baseSa, baseNsa))?.let {
                return Outcome(false, "Could not restore NR bands: $it It clears on Airplane Mode or a restart.")
            }
        }
        clearBaseline()
        prefs.edit().remove(KEY_WANT_LTE).remove(KEY_WANT_SA).remove(KEY_WANT_NSA).apply()
        Log.i(TAG, "released")
        return Outcome(true, "Band lock released. Original band masks restored.")
    }
}
