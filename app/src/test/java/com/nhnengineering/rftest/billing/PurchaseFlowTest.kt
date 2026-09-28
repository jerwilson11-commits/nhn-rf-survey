package com.nhnengineering.rftest.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the one rule that actually matters here: every purchase gets acknowledged exactly once,
 * regardless of how many times it is observed. An unacknowledged purchase auto-refunds after
 * three days -- Google's side of that timer is not something this test can exercise, but "was
 * `acknowledge` called, with the right token, exactly when it needed to be" is, and that is the
 * whole integration bug this class exists to prevent.
 *
 * Exercises [PurchaseFlow.acknowledgeIfNeeded] with raw values rather than a real `Purchase`
 * object -- see that function's own doc for why (its JSON-backed getters throw "not mocked" on
 * the plain JVM unit-test classpath).
 */
class PurchaseFlowTest {

    private val ok = BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build()
    private val error = BillingResult.newBuilder()
        .setResponseCode(BillingClient.BillingResponseCode.ERROR)
        .setDebugMessage("boom")
        .build()

    @Test
    fun `an unacknowledged completed purchase gets acknowledged with its own token`() = runBlocking {
        var calledWith: String? = null
        val outcome = PurchaseFlow.acknowledgeIfNeeded(
            purchaseState = Purchase.PurchaseState.PURCHASED,
            isAcknowledged = false,
            purchaseToken = "the-real-token",
        ) { token -> calledWith = token; ok }

        assertEquals("the-real-token", calledWith)
        assertEquals(PurchaseFlow.AcknowledgeOutcomeKind.ACKNOWLEDGED, outcome.kind)
    }

    @Test
    fun `an already-acknowledged purchase is never acknowledged twice`() = runBlocking {
        var calls = 0
        val outcome = PurchaseFlow.acknowledgeIfNeeded(
            purchaseState = Purchase.PurchaseState.PURCHASED,
            isAcknowledged = true,
            purchaseToken = "tok-1",
        ) { calls++; ok }

        assertEquals(0, calls)
        assertEquals(PurchaseFlow.AcknowledgeOutcomeKind.ALREADY_ACKNOWLEDGED, outcome.kind)
    }

    @Test
    fun `a pending purchase is left alone, not acknowledged early`() = runBlocking {
        var calls = 0
        val outcome = PurchaseFlow.acknowledgeIfNeeded(
            purchaseState = Purchase.PurchaseState.PENDING,
            isAcknowledged = false,
            purchaseToken = "tok-1",
        ) { calls++; ok }

        assertEquals(0, calls)
        assertEquals(PurchaseFlow.AcknowledgeOutcomeKind.NOT_YET_PURCHASED, outcome.kind)
    }

    @Test
    fun `a failed acknowledgment call is reported, not swallowed`() = runBlocking {
        val outcome = PurchaseFlow.acknowledgeIfNeeded(
            purchaseState = Purchase.PurchaseState.PURCHASED,
            isAcknowledged = false,
            purchaseToken = "tok-1",
        ) { error }

        assertEquals(PurchaseFlow.AcknowledgeOutcomeKind.FAILED, outcome.kind)
        assertEquals("boom", (outcome as PurchaseFlow.AcknowledgeOutcome.Failed).reason)
    }
}
