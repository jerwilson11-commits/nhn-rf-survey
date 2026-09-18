package com.nhnengineering.rftest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreWalkCheckTest {

    private fun inputs(
        locationPermission: Boolean = true,
        locationServicesOn: Boolean = true,
        hasGpsFix: Boolean = true,
        floorplanSelected: Boolean = false,
        scanThrottleDisabled: Boolean = true,
        phoneStatePermission: Boolean = true,
        simPresent: Boolean = true,
        batteryPct: Int? = 85,
        storageWritable: Boolean = true,
    ) = PreWalkCheck.Inputs(
        locationPermission, locationServicesOn, hasGpsFix, floorplanSelected,
        scanThrottleDisabled, phoneStatePermission, simPresent, batteryPct, storageWritable,
    )

    private fun statusOf(checks: List<PreWalkCheck.Check>, label: String) =
        checks.single { it.label == label }.status

    @Test
    fun `a ready handset blocks nothing`() {
        val checks = PreWalkCheck.evaluate(inputs())

        assertTrue(PreWalkCheck.clear(checks))
        assertTrue(checks.all { it.status == PreWalkCheck.Status.OK })
    }

    @Test
    fun `no GPS and no floorplan blocks, because every sample would be discarded`() {
        // The case that produced a full CSV and an empty report.
        val checks = PreWalkCheck.evaluate(inputs(hasGpsFix = false, floorplanSelected = false))

        assertEquals(
            PreWalkCheck.Status.BLOCK,
            statusOf(checks, "Somewhere to put the samples"),
        )
        assertFalse(PreWalkCheck.clear(checks))
    }

    @Test
    fun `a floorplan is enough on its own, which is the indoor case`() {
        val checks = PreWalkCheck.evaluate(inputs(hasGpsFix = false, floorplanSelected = true))

        assertEquals(PreWalkCheck.Status.OK, statusOf(checks, "Somewhere to put the samples"))
        assertTrue(PreWalkCheck.clear(checks))
    }

    @Test
    fun `location services off blocks even when the permission is granted`() {
        // Granting the permission is not sufficient, and the failure is silent.
        val checks = PreWalkCheck.evaluate(inputs(locationServicesOn = false))

        assertEquals(PreWalkCheck.Status.BLOCK, statusOf(checks, "Location services"))
    }

    @Test
    fun `scan throttling warns rather than blocks`() {
        // A cellular-only walk is still worth taking with throttling on.
        val checks = PreWalkCheck.evaluate(inputs(scanThrottleDisabled = false))

        assertEquals(PreWalkCheck.Status.WARN, statusOf(checks, "Wi-Fi scan throttling"))
        assertTrue(PreWalkCheck.clear(checks))
    }

    @Test
    fun `missing phone state blocks with a SIM and only warns without one`() {
        assertEquals(
            PreWalkCheck.Status.BLOCK,
            statusOf(
                PreWalkCheck.evaluate(inputs(phoneStatePermission = false, simPresent = true)),
                "Phone state permission",
            ),
        )
        assertEquals(
            PreWalkCheck.Status.WARN,
            statusOf(
                PreWalkCheck.evaluate(inputs(phoneStatePermission = false, simPresent = false)),
                "Phone state permission",
            ),
        )
    }

    @Test
    fun `a low battery warns`() {
        val checks = PreWalkCheck.evaluate(inputs(batteryPct = 12))

        assertEquals(PreWalkCheck.Status.WARN, statusOf(checks, "Battery"))
        assertTrue("a low battery is the operator's call", PreWalkCheck.clear(checks))
    }

    @Test
    fun `unknown battery is not a warning`() {
        // Not knowing is not the same as being low, and a spurious warning teaches people to
        // ignore the panel.
        assertEquals(
            PreWalkCheck.Status.OK,
            statusOf(PreWalkCheck.evaluate(inputs(batteryPct = null)), "Battery"),
        )
    }

    @Test
    fun `unwritable storage blocks`() {
        assertFalse(PreWalkCheck.clear(PreWalkCheck.evaluate(inputs(storageWritable = false))))
    }
}
