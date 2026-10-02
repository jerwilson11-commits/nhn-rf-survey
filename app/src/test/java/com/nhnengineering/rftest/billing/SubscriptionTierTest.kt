package com.nhnengineering.rftest.billing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the tier ordering the whole gating model depends on: FREE is the floor, and the `grants*`
 * helpers read as thresholds. A reorder of the enum would silently flip what Free users can reach.
 */
class SubscriptionTierTest {

    @Test
    fun `tiers are ordered free below field below pro`() {
        assertTrue(SubscriptionTier.FREE < SubscriptionTier.FIELD)
        assertTrue(SubscriptionTier.FIELD < SubscriptionTier.PRO)
    }

    @Test
    fun `free grants neither field nor pro`() {
        assertFalse(SubscriptionTier.FREE.grantsField)
        assertFalse(SubscriptionTier.FREE.grantsPro)
    }

    @Test
    fun `field grants field but not pro`() {
        assertTrue(SubscriptionTier.FIELD.grantsField)
        assertFalse(SubscriptionTier.FIELD.grantsPro)
    }

    @Test
    fun `pro grants both`() {
        assertTrue(SubscriptionTier.PRO.grantsField)
        assertTrue(SubscriptionTier.PRO.grantsPro)
    }
}
