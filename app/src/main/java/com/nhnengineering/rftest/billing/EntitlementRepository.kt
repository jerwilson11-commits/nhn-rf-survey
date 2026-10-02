package com.nhnengineering.rftest.billing

import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The two subscription product IDs configured in Play Console. Owning [PRO_PRODUCT_ID] implies
 * [FIELD_PRODUCT_ID]-level access too -- they are base plans in one subscription group, not
 * independent purchases, so a user only ever holds one active subscription at a time and Play
 * handles the upgrade/downgrade path natively. See `docs/play-billing.md`.
 */
const val FIELD_PRODUCT_ID = "field_tier"
const val PRO_PRODUCT_ID = "pro_tier"

/**
 * Owns the [BillingClient] connection and exposes the current entitlement as a
 * [StateFlow]-backed singleton, mirroring [com.nhnengineering.rftest.service.RecordingState] --
 * no DI framework exists in this app, and none is needed here either.
 *
 * Client-side entitlement only, deliberately, for v1: this app has no backend, so the tier is
 * whatever [BillingClient]'s local purchase state says, never verified server-side against the
 * Google Play Developer API. See `docs/play-billing.md` for the tradeoff this accepts.
 *
 * A process-scoped singleton object, not a class instantiated per-screen like the collectors --
 * the billing connection is expensive to open and must be a single source of truth for every
 * screen's paywall check, so it is opened once in [init] and never per-composable.
 */
object EntitlementRepository {

    private const val TAG = "Entitlement"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tier = MutableStateFlow(SubscriptionTier.FREE)

    /** [SubscriptionTier.FREE] until the first real answer arrives, unless [EntitlementStore]
     *  had a cached one -- see [init]. The app is fully usable at FREE, so starting here means a
     *  new install is never blocked waiting on the first BillingClient round-trip. */
    val tier: StateFlow<SubscriptionTier> = _tier.asStateFlow()

    /** True until the first connection attempt (success or failure) has completed, so the UI can
     *  distinguish "still checking" from "checked, and there is genuinely no subscription" --
     *  the same three-state idiom [com.nhnengineering.rftest.cellular.TechnologyLockController]
     *  already uses for `unavailableReason`. */
    private val _checking = MutableStateFlow(true)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private var store: EntitlementStore? = null
    private var client: BillingClient? = null
    private var initialized = false

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            scope.launch { handlePurchases(purchases) }
        }
    }

    /** Call once, from [android.app.Application.onCreate] or the first composition of
     *  [com.nhnengineering.rftest.MainActivity] -- idempotent, so a second call from a
     *  configuration change is harmless. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true

        // Debug builds only, never release: without this, the app is unusable for local
        // development the moment this gate ships, since there is no free tier and no Play
        // Console subscription product exists to test-purchase yet. A release build always
        // takes the real BillingClient path below, unconditionally.
        if (com.nhnengineering.rftest.BuildConfig.DEBUG) {
            _tier.value = SubscriptionTier.PRO
            _checking.value = false
            Log.i(TAG, "debug build: entitlement forced to PRO, BillingClient not connected")
            return
        }

        val appContext = context.applicationContext
        val entitlementStore = EntitlementStore(appContext)
        store = entitlementStore
        // The cached tier is shown immediately, before the connection even opens, so an
        // already-subscribed user never sees a paywall flash on a cold launch. It is overwritten
        // the moment the real answer arrives, in either direction.
        _tier.value = entitlementStore.cachedTier

        val billingClient = BillingClient.newBuilder(appContext)
            .setListener(purchasesUpdatedListener)
            .enableAutoServiceReconnection()
            .build()
        client = billingClient

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch { refresh() }
                } else {
                    Log.w(TAG, "billing setup failed: ${result.debugMessage}")
                    _checking.value = false
                }
            }

            override fun onBillingServiceDisconnected() {
                // enableAutoServiceReconnection() handles retry; nothing to do here beyond what
                // the library already does.
            }
        })
    }

    /** Re-queries active subscriptions and updates [tier]. Safe to call any time the connection
     *  is up -- e.g. when the app returns to the foreground after a purchase made elsewhere. */
    suspend fun refresh() {
        val billingClient = client ?: return
        val result = billingClient.queryPurchasesAsyncSuspend(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
        )
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            handlePurchases(result.purchasesList)
        }
        _checking.value = false
    }

    private suspend fun handlePurchases(purchases: List<Purchase>) {
        val billingClient = client
        var resolved = SubscriptionTier.FREE
        for (purchase in purchases) {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            val grants = when {
                PRO_PRODUCT_ID in purchase.products -> SubscriptionTier.PRO
                FIELD_PRODUCT_ID in purchase.products -> SubscriptionTier.FIELD
                else -> SubscriptionTier.FREE
            }
            if (grants > resolved) resolved = grants
            if (billingClient != null) {
                PurchaseFlow.acknowledgeIfNeeded(
                    purchaseState = purchase.purchaseState,
                    isAcknowledged = purchase.isAcknowledged,
                    purchaseToken = purchase.purchaseToken,
                ) { token -> billingClient.acknowledgePurchaseSuspend(token) }
            }
        }
        _tier.value = resolved
        store?.cachedTier = resolved
        _checking.value = false
    }

    /** Product details for the two subscription products, fetched fresh each time
     *  [com.nhnengineering.rftest.ui.PaywallScreen] is shown -- offers (price, trial) can change
     *  server-side and this is not data worth caching across a session. */
    suspend fun queryProductDetails(): Map<String, com.android.billingclient.api.ProductDetails> {
        val billingClient = client ?: return emptyMap()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(FIELD_PRODUCT_ID, PRO_PRODUCT_ID).map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                },
            )
            .build()
        val result = billingClient.queryProductDetails(params)
        return result.productDetailsList.orEmpty().associateBy { it.productId }
    }

    /** Exposed so [PurchaseFlow] can launch the billing sheet without this object needing to know
     *  about `Activity` at all. */
    fun billingClientOrNull(): BillingClient? = client
}

/** [BillingClient.queryPurchasesAsync] has no KTX suspend extension bundled as of 9.1.0 with the
 *  exact overload this app needs, so this wraps the callback form directly -- the same pattern
 *  the KTX `queryProductDetails` extension uses internally. */
private suspend fun BillingClient.queryPurchasesAsyncSuspend(
    params: QueryPurchasesParams,
) = suspendCancellableCoroutine { cont ->
    queryPurchasesAsync(params) { result, purchases ->
        cont.resume(com.android.billingclient.api.PurchasesResult(result, purchases))
    }
}

private suspend fun BillingClient.acknowledgePurchaseSuspend(purchaseToken: String): BillingResult =
    suspendCancellableCoroutine { cont ->
        val params = com.android.billingclient.api.AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
        acknowledgePurchase(params) { result -> cont.resume(result) }
    }
