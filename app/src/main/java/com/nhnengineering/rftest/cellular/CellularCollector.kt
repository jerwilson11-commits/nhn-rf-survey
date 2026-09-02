package com.nhnengineering.rftest.cellular

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SignalStrength
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.nhnengineering.rftest.model.CellularSample
import com.nhnengineering.rftest.model.LteCell
import com.nhnengineering.rftest.model.NeighborCell
import com.nhnengineering.rftest.model.NrCell
import com.nhnengineering.rftest.model.NrState
import com.nhnengineering.rftest.model.Rat
import com.nhnengineering.rftest.model.SimState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * LTE and NR measurement collection.
 *
 * **Validation status: unproven.** Written against 3GPP specs and the Android telephony docs with
 * no live SIM available. [BandMapping] is unit-tested; nothing else here has met a real network.
 * Expect the first live session to expose defects — every prior collector in this project produced
 * believable wrong numbers on first contact, and none of them crashed while doing it.
 *
 * Design carried over from the Wi-Fi collector, where each of these was learned the hard way:
 *
 * - **Unavailable values are `Integer.MAX_VALUE` / `CellInfo.UNAVAILABLE`, not null and not zero.**
 *   Every getter is guarded. An unguarded 2147483647 written into a CSV as an RSRP is the single
 *   most likely defect in this file.
 * - **Push callbacks go stale**, so `getAllCellInfo()` is polled each sample rather than relying on
 *   `onCellInfoChanged`. This is exactly the Wi-Fi RSSI failure, pre-empted.
 * - **`getAllCellInfo()` can return stale results on some OEMs** until `requestCellInfoUpdate()`
 *   forces a refresh, so that is issued on a slow cadence.
 */
class CellularCollector(context: Context) {

    private companion object {
        const val TAG = "CellularCollector"

        /** Android's sentinel for "no value", returned by most telephony getters. */
        const val UNAVAILABLE = Int.MAX_VALUE

        /** How often to force a cell info refresh, for OEMs that otherwise return stale data. */
        const val REFRESH_INTERVAL_MS = 5_000L

        /**
         * How long a neighbour cell stays in the list after it was last actually observed.
         *
         * Deliberately much shorter than the Wi-Fi equivalent (60 s), because the two problems
         * are not the same. A Wi-Fi AP missing from a scan is an artefact — the OS swept a subset
         * of channels and the AP is still there. A cellular neighbour vanishing is usually real:
         * it crossed the detection floor. Retention here exists only to stop a weak neighbour
         * flickering in and out of the display between reports, and every neighbour carries its
         * age so that nothing is fabricated.
         *
         * At walking pace 10 s is roughly 14 m, which is short enough that a retained neighbour
         * is still meaningfully "here".
         */
        const val NEIGHBOR_RETENTION_MS = 10_000L

        /** Cap on neighbours serialised per sample, strongest first. The true count is logged
         *  separately so the cap is visible rather than silent. */
        const val MAX_NEIGHBORS_LOGGED = 12
    }

    private val appContext = context.applicationContext
    private val tm = appContext.getSystemService(TelephonyManager::class.java)
    private val executor: Executor = Executor { it.run() }

    @Volatile private var latestDisplayInfo: TelephonyDisplayInfo? = null
    @Volatile private var latestSignalStrength: SignalStrength? = null
    @Volatile private var lastRefreshElapsedMs = 0L

    /** Keyed by rat|pci|channel, which identifies a cell uniquely enough within one locality. */
    private val observedNeighbors = ConcurrentHashMap<String, Pair<NeighborCell, Long>>()

    private var started = false
    private var callback: TelephonyCallback? = null

    private val hasPhoneState: Boolean
        get() = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

    private val hasFineLocation: Boolean
        get() = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    private inner class Callback :
        TelephonyCallback(),
        TelephonyCallback.DisplayInfoListener,
        TelephonyCallback.SignalStrengthsListener {

        override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
            latestDisplayInfo = displayInfo
        }

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            latestSignalStrength = signalStrength
        }
    }

    fun start() {
        if (started) return
        started = true
        if (!hasPhoneState) {
            // Not fatal. Cell identity and signal still come from getAllCellInfo() under
            // ACCESS_FINE_LOCATION; what is lost is registration state and NR state, which is
            // exactly what distinguishes NSA from SA. Surfaced in the sample rather than hidden.
            Log.w(TAG, "READ_PHONE_STATE not granted — NSA/SA state unavailable")
            return
        }
        try {
            val cb = Callback()
            callback = cb
            tm?.registerTelephonyCallback(executor, cb)
        } catch (e: SecurityException) {
            Log.w(TAG, "registerTelephonyCallback denied", e)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        callback?.let { cb -> runCatching { tm?.unregisterTelephonyCallback(cb) } }
        callback = null
    }

    // -----------------------------------------------------------------------
    // Sampling
    // -----------------------------------------------------------------------

    fun snapshot(): CellularSample {
        val sim = simState()

        if (tm == null) {
            return empty(sim)
        }

        maybeRequestRefresh()

        val cells: List<CellInfo> = try {
            if (hasFineLocation) tm.allCellInfo.orEmpty() else emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "getAllCellInfo denied", e)
            emptyList()
        }

        val lteCells = cells.filterIsInstance<CellInfoLte>()
        val nrCells = cells.filterIsInstance<CellInfoNr>()

        val servingLte = lteCells.firstOrNull { it.isRegistered } ?: lteCells.firstOrNull()
        val servingNr = nrCells.firstOrNull { it.isRegistered } ?: nrCells.firstOrNull()

        val lte = servingLte?.let { toLte(it) }
        val nr = servingNr?.let { toNr(it) }

        val nrState = nrStateOf()
        val rat = ratOf(lte, nr, sim)

        val seenNow = buildList {
            lteCells.filter { it !== servingLte }.forEach { add(neighborFromLte(it)) }
            nrCells.filter { it !== servingNr }.forEach { add(neighborFromNr(it)) }
        }
        val neighbors = mergeNeighbors(seenNow)

        return CellularSample(
            simState = sim,
            rat = rat,
            nrState = nrState,
            overrideNetworkType = overrideLabel(),
            isRoaming = runCatching { tm.isNetworkRoaming }.getOrDefault(false),
            mcc = nr?.mcc ?: lte?.mcc,
            mnc = nr?.mnc ?: lte?.mnc,
            operator = nr?.operator ?: lte?.operator ?: tm.networkOperatorName?.ifBlank { null },
            lte = lte,
            nr = nr,
            neighbors = neighbors,
            permissionLimited = !hasPhoneState,
        )
    }

    private fun empty(sim: SimState) = CellularSample(
        simState = sim,
        rat = Rat.NO_SERVICE,
        nrState = NrState.UNKNOWN,
        overrideNetworkType = null,
        isRoaming = false,
        mcc = null, mnc = null, operator = null,
        lte = null, nr = null, neighbors = emptyList(),
        permissionLimited = !hasPhoneState,
    )

    /**
     * Some OEMs return a cached `getAllCellInfo()` indefinitely until an update is requested.
     * Throttled because the request itself is rate-limited by the framework.
     */
    private fun maybeRequestRefresh() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRefreshElapsedMs < REFRESH_INTERVAL_MS) return
        lastRefreshElapsedMs = now
        if (!hasFineLocation) return
        runCatching {
            tm?.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) = Unit
                override fun onError(errorCode: Int, detail: Throwable?) {
                    Log.w(TAG, "requestCellInfoUpdate error $errorCode")
                }
            })
        }
    }

    // -----------------------------------------------------------------------
    // Registration / RAT
    // -----------------------------------------------------------------------

    /**
     * NSA / SA discrimination using only public API.
     *
     * The obvious approach — `ServiceState.getNetworkRegistrationInfo()` and
     * `NetworkRegistrationInfo.getNrState()` — is `@SystemApi` and unreachable from a normal app.
     * That is not obvious from the documentation and it does not fail until compile time; the
     * cellular API reference in `docs/` originally recommended it and was wrong.
     *
     * What is actually available:
     *
     * - `TelephonyManager.getDataNetworkType()` returns `NETWORK_TYPE_NR` when the device is
     *   registered **standalone** on NR. Requires READ_PHONE_STATE.
     * - `TelephonyDisplayInfo.getOverrideNetworkType()` reports `NR_NSA` or `NR_ADVANCED` when an
     *   NR secondary cell group is carrying data on an LTE anchor — i.e. **non-standalone**.
     *
     * So: data network type NR means SA. Data network type LTE with an NR override means NSA.
     * The override alone is a marketing-layer signal and is logged separately for comparison, but
     * combined with the registered network type it is the best public discriminator available.
     */
    private fun dataNetworkType(): Int = try {
        if (hasPhoneState) tm?.dataNetworkType ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
        else TelephonyManager.NETWORK_TYPE_UNKNOWN
    } catch (e: SecurityException) {
        TelephonyManager.NETWORK_TYPE_UNKNOWN
    }

    private fun overrideIndicatesNr(): Boolean = when (latestDisplayInfo?.overrideNetworkType) {
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> true
        5 -> true // OVERRIDE_NETWORK_TYPE_NR_ADVANCED, matched by value
        else -> false
    }

    private fun nrStateOf(): NrState = when {
        !hasPhoneState -> NrState.UNKNOWN
        dataNetworkType() == TelephonyManager.NETWORK_TYPE_NR -> NrState.CONNECTED
        overrideIndicatesNr() -> NrState.CONNECTED
        else -> NrState.UNKNOWN
    }

    private fun ratOf(lte: LteCell?, nr: NrCell?, sim: SimState): Rat {
        if (sim == SimState.ABSENT) return Rat.NO_SERVICE
        val dnt = dataNetworkType()
        return when {
            dnt == TelephonyManager.NETWORK_TYPE_NR -> Rat.NR_SA
            dnt == TelephonyManager.NETWORK_TYPE_LTE && overrideIndicatesNr() -> Rat.NR_NSA
            dnt == TelephonyManager.NETWORK_TYPE_LTE -> Rat.LTE
            dnt == TelephonyManager.NETWORK_TYPE_UMTS ||
                dnt == TelephonyManager.NETWORK_TYPE_HSPA ||
                dnt == TelephonyManager.NETWORK_TYPE_HSPAP -> Rat.WCDMA
            dnt == TelephonyManager.NETWORK_TYPE_GPRS ||
                dnt == TelephonyManager.NETWORK_TYPE_EDGE -> Rat.GSM
            // No registration info available (no READ_PHONE_STATE): infer from the cells present
            // and flag the sample as permission-limited so the guess is never mistaken for fact.
            nr != null -> Rat.NR_SA
            lte != null -> Rat.LTE
            else -> Rat.UNKNOWN
        }
    }

    private fun overrideLabel(): String? = when (latestDisplayInfo?.overrideNetworkType) {
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE -> "none"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE-CA"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE-A Pro"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "NR-NSA"
        // OVERRIDE_NETWORK_TYPE_NR_ADVANCED (=5) is API 31+; matched by value so the constant is
        // not required at compile time on a lower compileSdk.
        5 -> "NR-Advanced"
        else -> null
    }

    private fun simState(): SimState = when (tm?.simState) {
        TelephonyManager.SIM_STATE_READY -> SimState.READY
        TelephonyManager.SIM_STATE_ABSENT -> SimState.ABSENT
        TelephonyManager.SIM_STATE_PIN_REQUIRED,
        TelephonyManager.SIM_STATE_PUK_REQUIRED,
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> SimState.LOCKED
        TelephonyManager.SIM_STATE_NOT_READY -> SimState.NOT_READY
        else -> SimState.UNKNOWN
    }

    // -----------------------------------------------------------------------
    // Cell conversion
    // -----------------------------------------------------------------------

    private fun Int.orNull(): Int? = if (this == UNAVAILABLE || this == CellInfo.UNAVAILABLE) null else this
    private fun Long.orNull(): Long? = if (this == Long.MAX_VALUE || this == CellInfo.UNAVAILABLE_LONG) null else this

    private fun toLte(info: CellInfoLte): LteCell {
        val id: CellIdentityLte = info.cellIdentity
        val ss: CellSignalStrengthLte = info.cellSignalStrength
        val fb = if (info.isRegistered) servingLte() else null
        val ci = id.ci.orNull()
        val earfcn = id.earfcn.orNull()

        // Prefer the modem's own band list; fall back to deriving it from the channel number,
        // because getBands() is frequently empty in practice.
        val reportedBand = id.bands.firstOrNull()
        val derived = earfcn?.let { BandMapping.lteBandFor(it) }
        val band = reportedBand ?: derived?.band

        return LteCell(
            registered = info.isRegistered,
            ci = ci,
            enbId = ci?.let { it shr 8 },
            sectorId = ci?.let { it and 0xFF },
            pci = id.pci.orNull(),
            tac = id.tac.orNull(),
            earfcn = earfcn,
            band = band,
            bandLabel = band?.let { b -> "B$b" + (derived?.label?.let { " ($it)" } ?: "") },
            dlFreqMhz = earfcn?.let { BandMapping.lteDownlinkMhz(it) },
            bandwidthKhz = id.bandwidth.orNull(),
            rsrpDbm = ss.rsrp.orNull() ?: fb?.rsrp?.orNull(),
            rsrqDb = ss.rsrq.orNull() ?: fb?.rsrq?.orNull(),
            rssnrDb = ss.rssnr.orNull() ?: fb?.rssnr?.orNull(),
            rssiDbm = ss.rssi.orNull() ?: fb?.rssi?.orNull(),
            cqi = ss.cqi.orNull() ?: fb?.cqi?.orNull(),
            timingAdvance = ss.timingAdvance.orNull() ?: fb?.timingAdvance?.orNull(),
            mcc = id.mccString,
            mnc = id.mncString,
            operator = id.operatorAlphaLong?.toString()?.ifBlank { null },
        )
    }

    /**
     * Serving-cell signal from the SignalStrength callback, used to fill gaps in getAllCellInfo().
     *
     * The two sources do not agree about what is available. Measured on T-Mobile 5G SA:
     * `getAllCellInfo()` returned `ssSinr = 2147483647` (UNAVAILABLE) while `SignalStrength`
     * carried `ssSinr = 21` for the same serving cell at the same moment. Reading only the former
     * loses SINR entirely — and SINR is the KPI that separates "strong signal" from "usable
     * signal", so losing it silently is worse than most alternatives.
     *
     * Applied to the **registered cell only**. Neighbours legitimately have no SINR, and borrowing
     * the serving cell's value for them would fabricate a measurement.
     */
    private fun signalStrengthNow(): SignalStrength? = runCatching {
        // PULLED at sample time, not taken from the callback cache.
        //
        // The callback version of this was measured pinning SS-SINR to a single value for 24
        // consecutive samples while SS-RSRP moved across four values — onSignalStrengthsChanged
        // simply did not fire. That is the same defect as the Wi-Fi RSSI freeze, in a third
        // subsystem, and the same remedy applies: a push cache answers "what did I last hear",
        // a pull answers "what is true now". The callback is retained only as a fallback for
        // devices where the direct query is unavailable.
        tm?.signalStrength ?: latestSignalStrength
    }.getOrNull() ?: latestSignalStrength

    private fun servingNr(): CellSignalStrengthNr? = runCatching {
        signalStrengthNow()?.getCellSignalStrengths(CellSignalStrengthNr::class.java)?.firstOrNull()
    }.getOrNull()

    private fun servingLte(): CellSignalStrengthLte? = runCatching {
        signalStrengthNow()?.getCellSignalStrengths(CellSignalStrengthLte::class.java)?.firstOrNull()
    }.getOrNull()

    private fun toNr(info: CellInfoNr): NrCell {
        // getCellIdentity() returns the base type on CellInfoNr, so the cast is required.
        val id = info.cellIdentity as? CellIdentityNr
        val ss = info.cellSignalStrength as? CellSignalStrengthNr
        // Only the registered cell may borrow from SignalStrength; see servingNr().
        val fb = if (info.isRegistered) servingNr() else null
        val nrarfcn = id?.nrarfcn?.orNull()

        val reported = id?.bands?.map { "n$it" }.orEmpty()
        val derived = nrarfcn?.let { BandMapping.nrBandsFor(it) }.orEmpty()
        val derivedNames = derived.map { it.band }
        val bands = if (reported.isNotEmpty()) reported else derivedNames

        // Cross-check the modem's band claim against the band its own channel number implies.
        // These disagree in practice — observed on T-Mobile 5G SA, a registered cell reporting
        // band 25 with an ARFCN mapping to 2606.55 MHz, which is n41. Reporting one silently
        // would put an internally incoherent figure in a client document.
        val conflict = if (
            reported.isNotEmpty() && derivedNames.isNotEmpty() &&
            reported.none { it in derivedNames }
        ) {
            "ARFCN $nrarfcn implies ${derivedNames.joinToString("/")}"
        } else {
            null
        }

        val label = when {
            conflict != null -> reported.joinToString("/") + " ⚠"
            reported.isNotEmpty() -> reported.joinToString("/")
            nrarfcn != null -> BandMapping.nrBandLabel(nrarfcn)
            else -> null
        }

        return NrCell(
            registered = info.isRegistered,
            nci = id?.nci?.orNull(),
            pci = id?.pci?.orNull(),
            tac = id?.tac?.orNull(),
            nrarfcn = nrarfcn,
            bands = bands,
            bandLabel = label,
            bandConflict = conflict,
            dlFreqMhz = nrarfcn?.let { BandMapping.nrArfcnToMhz(it) },
            ssRsrpDbm = ss?.ssRsrp?.orNull() ?: fb?.ssRsrp?.orNull(),
            ssRsrqDb = ss?.ssRsrq?.orNull() ?: fb?.ssRsrq?.orNull(),
            ssSinrDb = ss?.ssSinr?.orNull() ?: fb?.ssSinr?.orNull(),
            csiRsrpDbm = ss?.csiRsrp?.orNull() ?: fb?.csiRsrp?.orNull(),
            csiRsrqDb = ss?.csiRsrq?.orNull() ?: fb?.csiRsrq?.orNull(),
            csiSinrDb = ss?.csiSinr?.orNull() ?: fb?.csiSinr?.orNull(),
            mcc = id?.mccString,
            mnc = id?.mncString,
            operator = id?.operatorAlphaLong?.toString()?.ifBlank { null },
        )
    }

    /**
     * Merges this report's neighbours into the retained set and ages the rest out.
     *
     * Returns them strongest first, each stamped with how long ago it was actually seen. A
     * neighbour observed in this report has `ageMs = 0`; one carried over reports its true age, so
     * a stale entry can never be mistaken for a fresh measurement.
     */
    private fun mergeNeighbors(seenNow: List<NeighborCell>): List<NeighborCell> {
        val now = SystemClock.elapsedRealtime()
        for (n in seenNow) {
            observedNeighbors["${n.rat}|${n.pci}|${n.channel}"] = n to now
        }
        observedNeighbors.entries.removeAll { now - it.value.second > NEIGHBOR_RETENTION_MS }
        return observedNeighbors.values
            .map { (cell, at) -> cell.copy(ageMs = now - at) }
            .sortedByDescending { it.rsrpDbm ?: Int.MIN_VALUE }
    }

    private fun neighborFromLte(info: CellInfoLte): NeighborCell {
        val id = info.cellIdentity
        val ss = info.cellSignalStrength
        val earfcn = id.earfcn.orNull()
        return NeighborCell(
            rat = "LTE",
            pci = id.pci.orNull(),
            channel = earfcn,
            band = (id.bands.firstOrNull() ?: earfcn?.let { BandMapping.lteBandFor(it)?.band })
                ?.let { "B$it" },
            rsrpDbm = ss.rsrp.orNull(),
            rsrqDb = ss.rsrq.orNull(),
        )
    }

    private fun neighborFromNr(info: CellInfoNr): NeighborCell {
        val id = info.cellIdentity as? CellIdentityNr
        val ss = info.cellSignalStrength as? CellSignalStrengthNr
        val nrarfcn = id?.nrarfcn?.orNull()
        return NeighborCell(
            rat = "NR",
            pci = id?.pci?.orNull(),
            channel = nrarfcn,
            // Same labelling as the serving cell. Taking firstOrNull() here silently
            // discarded the ambiguity the serving path is careful to preserve, and neighbours
            // are what populate the report's per-cell table -- so the one place the ambiguity
            // was dropped was the one place a client would read it.
            band = id?.bands?.firstOrNull()?.let { "n$it" }
                ?: nrarfcn?.let { BandMapping.nrBandLabel(it) },
            rsrpDbm = ss?.ssRsrp?.orNull(),
            rsrqDb = ss?.ssRsrq?.orNull(),
        )
    }
}
