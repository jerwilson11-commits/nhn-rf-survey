package com.nhnengineering.rftest.modem

/**
 * Wording for the modem-transport failure reasons shown in [com.nhnengineering.rftest.ui.CellularCard]
 * and the band-lock/technology-lock controls -- kept as pure functions, separate from
 * [ModemChipset]'s detection and the impure transport classes ([QmiNasClient], [ModemNeighbourSource],
 * [ModemNrStream]), so the message-selection logic itself is unit testable without a device.
 *
 * The distinction that matters: "not rooted" and "rooted, but this modem was never Qualcomm" used
 * to produce the identical message, and it picked "not rooted" -- wrong and confusing on an
 * already-rooted Samsung/MediaTek/Unisoc phone. These functions take the already-known facts
 * (whether `su` worked, what chipset was detected) and choose honest wording for each case.
 */
object ModemFailureMessages {

    /** [QmiNasClient]: the Network Access Service could not be reached over QRTR at all. */
    fun nasUnreachable(rooted: Boolean, vendor: ModemChipset.Vendor): String = when {
        !rooted -> "Not rooted. Talking to the modem over QMI needs a rooted handset; " +
            "everything else in the app works without it."
        vendor == ModemChipset.Vendor.QUALCOMM -> "Root is available, but this Qualcomm modem's " +
            "Network Access Service did not answer over QRTR (it may not be registered yet). " +
            "The modem cannot be asked right now."
        else -> "Root is available, but this phone's modem doesn't look Qualcomm-based " +
            "(detected: ${vendor.label}), so the QMI transport band lock and technology lock " +
            "depend on isn't present here. This is a Qualcomm-only feature."
    }

    /** [ModemNeighbourSource]: same NAS-unreachable case, phrased for reading neighbours. */
    fun neighboursNasUnreachable(rooted: Boolean, vendor: ModemChipset.Vendor): String = when {
        !rooted -> "Not rooted. Reading neighbours from the modem needs a rooted handset; " +
            "everything else in the app works without it."
        vendor == ModemChipset.Vendor.QUALCOMM -> "Root is available, but this Qualcomm modem's " +
            "Network Access Service isn't listed on QRTR yet, so neighbours cannot be read from " +
            "it right now."
        else -> "Root is available, but this phone's modem doesn't look Qualcomm-based " +
            "(detected: ${vendor.label}), so it isn't listed on QRTR. Reading neighbours directly " +
            "from the modem needs the Qualcomm QMI transport, which isn't present on this hardware."
    }

    /**
     * The follow-up `su` call failed after an earlier one in the same request already proved root
     * works (resolving the NAS address). Root loss mid-flight -- a Magisk grant revoked, most
     * likely -- not the ordinary "no root" case, so it must not reuse that wording.
     */
    fun rootLostMidRequest(): String =
        "Root access could not be used to run the modem helper, though it worked a moment ago. " +
            "Try again."

    /**
     * [ModemNrStream]: the DCI helper ran (root worked) but reported an error. `libdcilogger.so`
     * only works by `dlopen`-ing a Qualcomm vendor library, so on non-Qualcomm hardware this is
     * almost always that library not existing -- add chipset context only then; a genuine Qualcomm
     * protocol error needs no decoration.
     */
    fun decorateHelperError(raw: String, vendor: ModemChipset.Vendor): String =
        if (vendor == ModemChipset.Vendor.QUALCOMM) {
            raw
        } else {
            "$raw (this phone's modem doesn't look Qualcomm-based -- detected: ${vendor.label} -- " +
                "and this feature is Qualcomm-only)"
        }
}
