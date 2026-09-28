package com.nhnengineering.rftest.billing

import android.app.Activity
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.ProductDetails

/**
 * Launches the Play billing sheet, and separately, the one piece of this integration worth
 * testing in isolation: acknowledgment.
 *
 * **Every purchase must be acknowledged within three days of the charge, or Google auto-refunds
 * it.** This is the single most common Play Billing integration bug -- easy to get right on the
 * happy path (acknowledge immediately after [PurchasesUpdatedListener] fires) and easy to miss on
 * the path that actually causes refunds: a purchase that completed while the app was killed, or
 * whose acknowledgment call itself failed, and is never revisited. [acknowledgeIfNeeded] is called
 * from both paths in [EntitlementRepository] -- the listener callback and the periodic
 * [EntitlementRepository.refresh] -- for exactly that reason: acknowledgment is not a one-time
 * side effect of a fresh purchase, it is a property every observed purchase must eventually have,
 * however it was observed.
 */
object PurchaseFlow {

    /**
     * Acknowledges a purchase if it needs it, and only if it needs it.
     *
     * Takes the three raw fields rather than a `Purchase` object on purpose: `Purchase`'s getters
     * parse JSON internally via `org.json.JSONObject`, which is stubbed to throw on the plain JVM
     * unit-test classpath ("not mocked") with no real Android device or Robolectric behind it --
     * this project's own established convention (see `BeaconElements`'s "no Android types" design)
     * is to keep logic like this testable on the JVM directly rather than fight that stub. The one
     * real `Purchase` object involved lives in [com.nhnengineering.rftest.billing.EntitlementRepository],
     * which unpacks it into these three values before calling here.
     *
     * @param acknowledge Calls the real `BillingClient.acknowledgePurchase`, injected rather than
     *   taking a `BillingClient` directly so this logic is testable against a fake with no real
     *   billing connection -- see `PurchaseFlowTest`.
     */
    suspend fun acknowledgeIfNeeded(
        purchaseState: Int,
        isAcknowledged: Boolean,
        purchaseToken: String,
        acknowledge: suspend (purchaseToken: String) -> BillingResult,
    ): AcknowledgeOutcome {
        if (purchaseState != Purchase.PurchaseState.PURCHASED) {
            // PENDING means the charge has not completed yet (e.g. a payment method awaiting
            // confirmation) -- acknowledging it now would be premature, and Play does not permit
            // it. Nothing to do until the state changes and this purchase is observed again.
            return AcknowledgeOutcome.NotYetPurchased
        }
        if (isAcknowledged) {
            return AcknowledgeOutcome.AlreadyAcknowledged
        }
        val result = acknowledge(purchaseToken)
        return if (result.responseCode == com.android.billingclient.api.BillingClient.BillingResponseCode.OK) {
            AcknowledgeOutcome.Acknowledged
        } else {
            AcknowledgeOutcome.Failed(result.debugMessage)
        }
    }

    enum class AcknowledgeOutcomeKind { NOT_YET_PURCHASED, ALREADY_ACKNOWLEDGED, ACKNOWLEDGED, FAILED }

    sealed interface AcknowledgeOutcome {
        val kind: AcknowledgeOutcomeKind

        object NotYetPurchased : AcknowledgeOutcome {
            override val kind = AcknowledgeOutcomeKind.NOT_YET_PURCHASED
        }

        object AlreadyAcknowledged : AcknowledgeOutcome {
            override val kind = AcknowledgeOutcomeKind.ALREADY_ACKNOWLEDGED
        }

        object Acknowledged : AcknowledgeOutcome {
            override val kind = AcknowledgeOutcomeKind.ACKNOWLEDGED
        }

        data class Failed(val reason: String) : AcknowledgeOutcome {
            override val kind = AcknowledgeOutcomeKind.FAILED
        }
    }

    /** The offer token identifies a specific base plan + offer (e.g. "pro-monthly" with or
     *  without a trial); [ProductDetails.getSubscriptionOfferDetails] lists what is actually
     *  available to this user, which [com.nhnengineering.rftest.ui.PaywallScreen] resolves before
     *  calling this. */
    fun launch(activity: Activity, productDetails: ProductDetails, offerToken: String): BillingResult? {
        val client = EntitlementRepository.billingClientOrNull() ?: return null
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(offerToken)
                        .build(),
                ),
            )
            .build()
        return client.launchBillingFlow(activity, params)
    }
}
