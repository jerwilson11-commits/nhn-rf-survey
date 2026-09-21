package com.nhnengineering.rftest.cellular

import android.telephony.TelephonyManager

/**
 * Holding the radio on one access technology for the length of a measurement.
 *
 * ## What this is for
 *
 * A survey that lets the handset roam across technologies measures the handset's preferences as
 * much as it measures the site. Asking "what does LTE look like here" is only answerable if the
 * phone can be held on LTE while the question is asked. The same applies to the harder one this
 * project has been chasing: **does this site have usable n41 coverage at all**, which cannot be
 * answered by a phone that camps on n25 and never looks.
 *
 * ## What it can and cannot do
 *
 * This selects a *technology*, never a band. Android exposes no band selection at any privilege
 * level -- that lives in modem NV and is reachable only through vendor diagnostic channels. So
 * [Technology.NR_ONLY] forces NR rather than LTE; it cannot force n41 rather than n25.
 *
 * The one indirect lever worth knowing: NSA is not a technology of its own, it is NR anchored on
 * LTE. Removing LTE from the mask therefore removes NSA as a possibility, so a handset that keeps
 * NR service under [Technology.NR_ONLY] is necessarily on standalone NR. See [forcesStandalone].
 *
 * ## Why it cannot add capability
 *
 * The modem's effective allowance is the bitwise AND across every reason the framework tracks
 * (user preference, carrier config, power saving). Writing one reason can only ever narrow what
 * the radio may use. Asking for a technology the carrier config already excludes changes nothing,
 * which is why [TechnologyLockController.lock] reports what actually took effect rather than
 * what was requested.
 *
 * ## Why REASON_USER
 *
 * Of the two reasons this SDK exposes, `REASON_CARRIER` fights the carrier configuration and is
 * reset underneath the app whenever that config reloads. `REASON_USER` is the slot Settings' own
 * network-type picker writes. That choice has a deliberate safety property: a lock this app
 * somehow fails to release is visible and clearable by hand from Settings, on a phone that would
 * otherwise be sitting in a drawer with no service and no obvious cause.
 *
 * Requires `MODIFY_PHONE_STATE` (`signature|privileged`), so this is available only on a
 * privileged install. [TechnologyLockController.unavailableReason] is how callers find out,
 * and refusal is the normal case.
 */
object TechnologyLock {

    /** LTE, including the carrier-aggregated variant the framework tracks separately. */
    private const val LTE_ALL =
        TelephonyManager.NETWORK_TYPE_BITMASK_LTE or TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA

    /** Pre-LTE circuit and packet technologies, kept together as one fallback group. */
    private const val LEGACY_ALL =
        TelephonyManager.NETWORK_TYPE_BITMASK_GSM or
            TelephonyManager.NETWORK_TYPE_BITMASK_GPRS or
            TelephonyManager.NETWORK_TYPE_BITMASK_EDGE or
            TelephonyManager.NETWORK_TYPE_BITMASK_UMTS or
            TelephonyManager.NETWORK_TYPE_BITMASK_HSPA or
            TelephonyManager.NETWORK_TYPE_BITMASK_HSDPA or
            TelephonyManager.NETWORK_TYPE_BITMASK_HSUPA or
            TelephonyManager.NETWORK_TYPE_BITMASK_HSPAP or
            TelephonyManager.NETWORK_TYPE_BITMASK_TD_SCDMA

    /**
     * A technology the radio can be held on.
     *
     * [warning] is non-null where the choice can plausibly cost the handset its service, and is
     * meant to be shown before the lock is applied rather than after it has taken effect.
     */
    enum class Technology(
        val label: String,
        val mask: Long,
        val warning: String? = null,
    ) {
        NR_ONLY(
            "5G NR only",
            TelephonyManager.NETWORK_TYPE_BITMASK_NR,
            "Removes the LTE anchor, so NSA is no longer possible. If this site has no " +
                "standalone NR, the handset will lose data service until the lock is released.",
        ),
        LTE_ONLY(
            "LTE only",
            LTE_ALL,
            "5G will not be used, including as an aggregated carrier.",
        ),
        NR_AND_LTE(
            "5G + LTE",
            TelephonyManager.NETWORK_TYPE_BITMASK_NR or LTE_ALL,
        ),
        LTE_AND_LEGACY(
            "LTE + 3G/2G",
            LTE_ALL or LEGACY_ALL,
        ),
        ;

        /** True where holding this technology necessarily means standalone NR. */
        val forcesStandalone: Boolean get() = forcesStandalone(mask)
    }

    /**
     * Whether a mask can only be satisfied by standalone NR.
     *
     * NSA carries NR traffic on an LTE anchor, so it needs both bits. NR present with LTE absent
     * leaves SA as the only way to hold NR service -- which makes this the closest thing the
     * platform offers to an SA/NSA test, without any band control at all.
     */
    fun forcesStandalone(mask: Long): Boolean =
        mask and TelephonyManager.NETWORK_TYPE_BITMASK_NR != 0L && mask and LTE_ALL == 0L

    /**
     * What a modem allowance actually permits, given every reason in force.
     *
     * The framework ANDs the reasons together, so this is the arithmetic that decides whether a
     * requested lock means anything. A request for NR against a carrier config that excludes NR
     * yields zero, not NR.
     */
    fun effective(masks: List<Long>): Long =
        if (masks.isEmpty()) 0L else masks.reduce { a, b -> a and b }

    /**
     * Human-readable technologies in a mask, widest first.
     *
     * Deliberately not a bit dump: this goes in front of an engineer reading a report, where
     * "5G NR, LTE" is checkable against what they saw and `0x8`&#8203;`1000` is not.
     */
    fun describeMask(mask: Long): String {
        if (mask == 0L) return "none"
        val parts = buildList {
            if (mask and TelephonyManager.NETWORK_TYPE_BITMASK_NR != 0L) add("5G NR")
            if (mask and LTE_ALL != 0L) add("LTE")
            if (mask and LEGACY_ALL != 0L) add("3G/2G")
            if (mask and TelephonyManager.NETWORK_TYPE_BITMASK_IWLAN != 0L) add("IWLAN")
        }
        return if (parts.isEmpty()) "other" else parts.joinToString(", ")
    }

    /**
     * Whether a requested technology survives contact with the other reasons in force.
     *
     * Returns the technologies that would actually be available. An empty result is the case
     * worth catching before applying anything: the lock would leave the radio with nothing.
     */
    fun previewLock(requested: Long, otherReasons: List<Long>): Long =
        effective(listOf(requested) + otherReasons)

    /**
     * Whether an allowance observed after a write actually reflects the lock that was asked for.
     *
     * ## Why a write is not evidence
     *
     * `setAllowedNetworkTypesForReason` returning without throwing means the framework accepted
     * the value, not that the radio is holding it. Measured on the OnePlus 9 (OOS 14,
     * 2026-09-21): the requested mask is applied and then recomputed straight back to the
     * handset default a moment later, by a vendor layer that derives the allowance from its own
     * preferred-network store rather than from the framework value:
     *
     * ```
     * calculatePreferredNetworkType: networkType = 840583      <- what was asked for
     * OplusNetworkUtils: getOplusUserPreferredNetworkFromDb: nwMode = -1
     * OplusNetworkUtils: getNewPreferredNetworkMode, defaultNwMode = 33 newMode = 33
     * calculatePreferredNetworkType: networkType = 916479      <- back to everything
     * ```
     *
     * Nothing throws. An implementation that trusted the call would report "holding LTE only"
     * over a handset sitting on 5G NR, and every reading taken under that claim would be
     * mislabelled. This project has shipped that exact shape of defect once already, in the
     * rate-limit backoff, where the displayed text was right and the machine-readable half was
     * silently absent. So the lock is confirmed by observation or it is not claimed.
     *
     * ## The test
     *
     * A lock holds when the radio is left with something, and with nothing outside what was
     * asked for. Narrower than requested is fine and expected -- the carrier's own reason is
     * ANDed in. Anything *wider* means the request did not survive.
     *
     * The revert is not instant, so this must be read a few seconds after the write rather than
     * immediately; an immediate read sees the accepted value and reports a lock that is about to
     * evaporate.
     */
    fun lockHeld(requested: Long, observed: Long): Boolean =
        observed != 0L && observed and requested.inv() == 0L
}
