package com.nhnengineering.rftest.modem

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the vendor -> backend routing in [ModemBackends.forVendor] -- the pure half of the registry.
 * [QualcommBackend.availability] and [ModemBackends.current] touch `su` / `Build.*` at runtime and
 * are device-dependent, the same way the rest of this project's `su`-adjacent code isn't unit tested.
 *
 * These expectations also guard the capability gate: a vendor that maps to null here is reported as
 * WRONG_CHIPSET by [ProModem.capability], and a vendor that maps to a backend is gated on that
 * backend's availability instead. When a MediaTek or Exynos backend lands, its row here flips from
 * null to that backend -- and this test is where that intent gets recorded.
 */
class ModemBackendsTest {

    @Test
    fun `Qualcomm routes to the Qualcomm backend`() {
        assertSame(QualcommBackend, ModemBackends.forVendor(ModemChipset.Vendor.QUALCOMM))
    }

    @Test
    fun `an unrecognised modem is tried optimistically on the Qualcomm backend`() {
        assertSame(QualcommBackend, ModemBackends.forVendor(ModemChipset.Vendor.OTHER_OR_UNKNOWN))
    }

    @Test
    fun `MediaTek routes to the MediaTek backend`() {
        assertSame(MediaTekBackend, ModemBackends.forVendor(ModemChipset.Vendor.MEDIATEK))
    }

    @Test
    fun `MediaTek backend is gated off (no parser yet)`() {
        assertFalse(MediaTekBackend.VALIDATED)
        assertTrue(MediaTekBackend.availability() is BackendAvailability.Unsupported)
    }

    @Test
    fun `Exynos routes to the Exynos backend`() {
        assertSame(ExynosBackend, ModemBackends.forVendor(ModemChipset.Vendor.EXYNOS))
    }

    @Test
    fun `Exynos backend is gated off until hardware-validated`() {
        // While ExynosBackend.VALIDATED is false the backend reports Unsupported, so the capability
        // gate still resolves Exynos to WRONG_CHIPSET -- production behaviour is unchanged until a
        // real SDM capture is decoded on a rooted Shannon device. (Safe to call: the VALIDATED=false
        // path returns before any su probe.)
        assertFalse(ExynosBackend.VALIDATED)
        assertTrue(ExynosBackend.availability() is BackendAvailability.Unsupported)
    }

    @Test
    fun `Unisoc has no backend yet`() {
        assertNull(ModemBackends.forVendor(ModemChipset.Vendor.UNISOC))
    }

    @Test
    fun `the Qualcomm backend reports the Qualcomm vendor`() {
        assertSame(ModemChipset.Vendor.QUALCOMM, QualcommBackend.vendor)
    }
}
