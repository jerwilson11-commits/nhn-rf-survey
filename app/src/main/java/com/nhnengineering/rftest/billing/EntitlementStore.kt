package com.nhnengineering.rftest.billing

import android.content.Context

/**
 * Persists the last-known tier so the UI has an immediate answer on cold start, before the first
 * `BillingClient` round-trip completes -- without this, an already-subscribed user would see the
 * paywall for a beat on every launch. Same `SharedPreferences` precedent as `BandLockController` /
 * `TechnologyLockController`, not `ProfileStore`'s JSONL pattern: this is scalar state, not a
 * user-authored collection.
 *
 * This is a cache, not the source of truth. [EntitlementRepository] always re-verifies against
 * `BillingClient` on connect and overwrites whatever is stored here -- a stale "PRO" surviving an
 * actual cancellation is wrong in the generous direction, which is why it is only ever trusted for
 * the few hundred milliseconds before the real answer arrives, never beyond it.
 */
class EntitlementStore(context: Context) {

    private companion object {
        const val PREFS = "entitlement"
        const val KEY_TIER = "tier"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var cachedTier: SubscriptionTier
        get() = prefs.getString(KEY_TIER, null)?.let {
            runCatching { SubscriptionTier.valueOf(it) }.getOrNull()
        } ?: SubscriptionTier.NONE
        set(value) {
            prefs.edit().putString(KEY_TIER, value.name).apply()
        }
}
