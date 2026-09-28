package com.nhnengineering.rftest.billing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ordering `grantsField`/`grantsPro` depend on -- there is no free tier, so a wrong
 * ordering here would either lock out a paying Field subscriber or silently hand out Pro features
 * (band lock, technology lock) to someone who never paid for them.
 */
class SubscriptionTierTest {

    @Test
    fun `NONE grants neither tier`() {
        assertFalse(SubscriptionTier.NONE.grantsField)
        assertFalse(SubscriptionTier.NONE.grantsPro)
    }

    @Test
    fun `FIELD grants field but not pro`() {
        assertTrue(SubscriptionTier.FIELD.grantsField)
        assertFalse(SubscriptionTier.FIELD.grantsPro)
    }

    @Test
    fun `PRO grants both -- it is Field plus the root-gated tools, not a separate purchase`() {
        assertTrue(SubscriptionTier.PRO.grantsField)
        assertTrue(SubscriptionTier.PRO.grantsPro)
    }
}
