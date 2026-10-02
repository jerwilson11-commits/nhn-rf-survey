package com.nhnengineering.rftest.billing

/**
 * What the current subscription grants. Ordered so `tier >= FIELD` reads naturally at a call site.
 *
 * [FREE] is the floor -- no subscription grants it, and the whole app is usable at that level minus
 * the paid features, so the public can install and try it. See `docs/play-billing.md` for the split.
 * This restores the "free tier is the acquisition wedge" strategy after the v1 "everything paywalled"
 * decision proved fatal for public discovery/trial.
 */
enum class SubscriptionTier {
    /** No active subscription. Live measurement, recording, raw-CSV export, manual throughput and
     *  video/voice QoE are available; the paid features below are gated. */
    FREE,

    /** Free, plus the PDF report, pro exports (KML/GeoJSON/GeoPackage/iBwave), floorplan mode, cell
     *  lock watch, automation, and threshold alarms. */
    FIELD,

    /** Field, plus band lock, technology lock, VoNR control, live SIB1/TDD decode, NR neighbours. */
    PRO,
    ;

    val grantsField: Boolean get() = this >= FIELD
    val grantsPro: Boolean get() = this >= PRO
}
