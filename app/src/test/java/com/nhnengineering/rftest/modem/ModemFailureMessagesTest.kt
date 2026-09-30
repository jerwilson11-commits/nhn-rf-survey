package com.nhnengineering.rftest.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the message-selection logic: "not rooted" and "rooted, but this modem isn't Qualcomm" used
 * to collapse into one message that always blamed root, which was wrong on an already-rooted
 * Samsung/MediaTek/Unisoc phone. These tests are the regression guard for that specific bug.
 */
class ModemFailureMessagesTest {

    @Test
    fun `nasUnreachable when not rooted blames root regardless of vendor`() {
        val notRooted = ModemFailureMessages.nasUnreachable(rooted = false, vendor = ModemChipset.Vendor.EXYNOS)
        assertTrue(notRooted.startsWith("Not rooted."))
    }

    @Test
    fun `nasUnreachable when rooted on Qualcomm does not mention chipset`() {
        val msg = ModemFailureMessages.nasUnreachable(rooted = true, vendor = ModemChipset.Vendor.QUALCOMM)
        assertTrue(msg.contains("Network Access Service"))
        assertFalse(msg.contains("doesn't look Qualcomm-based"))
    }

    @Test
    fun `nasUnreachable when rooted on non-Qualcomm names the detected vendor`() {
        val msg = ModemFailureMessages.nasUnreachable(rooted = true, vendor = ModemChipset.Vendor.EXYNOS)
        assertTrue(msg.contains("doesn't look Qualcomm-based"))
        assertTrue(msg.contains("Samsung Exynos / Shannon"))
        assertFalse(msg.startsWith("Not rooted."))
    }

    @Test
    fun `neighboursNasUnreachable mirrors the same three cases`() {
        assertTrue(
            ModemFailureMessages.neighboursNasUnreachable(rooted = false, vendor = ModemChipset.Vendor.MEDIATEK)
                .startsWith("Not rooted."),
        )
        val qualcomm = ModemFailureMessages.neighboursNasUnreachable(rooted = true, vendor = ModemChipset.Vendor.QUALCOMM)
        assertFalse(qualcomm.contains("doesn't look Qualcomm-based"))
        val mediatek = ModemFailureMessages.neighboursNasUnreachable(rooted = true, vendor = ModemChipset.Vendor.MEDIATEK)
        assertTrue(mediatek.contains("doesn't look Qualcomm-based"))
        assertTrue(mediatek.contains("MediaTek"))
    }

    @Test
    fun `rootLostMidRequest never blames missing root -- the exact bug this fixes`() {
        val msg = ModemFailureMessages.rootLostMidRequest()
        assertFalse(msg.contains("Not rooted"))
        assertFalse(msg.contains("needs a rooted handset"))
        assertTrue(msg.contains("worked a moment ago"))
    }

    @Test
    fun `decorateHelperError passes Qualcomm errors through unchanged`() {
        val raw = "dlopen failed: library not found"
        assertEquals(raw, ModemFailureMessages.decorateHelperError(raw, ModemChipset.Vendor.QUALCOMM))
    }

    @Test
    fun `decorateHelperError adds chipset context for non-Qualcomm`() {
        val raw = "dlopen failed: library not found"
        val decorated = ModemFailureMessages.decorateHelperError(raw, ModemChipset.Vendor.UNISOC)
        assertTrue(decorated.contains(raw))
        assertTrue(decorated.contains("Unisoc"))
    }
}
