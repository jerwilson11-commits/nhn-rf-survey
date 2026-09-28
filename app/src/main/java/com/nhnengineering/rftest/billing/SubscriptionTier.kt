package com.nhnengineering.rftest.billing

/**
 * What the current subscription grants. Ordered so `tier >= FIELD` reads naturally at a call site.
 *
 * There is no FREE tier -- every feature in this app requires at least [FIELD]. See
 * `docs/play-billing.md` for why: a deliberate departure from the "generous free tier" strategy in
 * this project's earlier roadmap, made once the roadmap's own paid-tier feature set (ERRCS mode,
 * multi-device, cloud sync, scanner ingest) turned out not to be built yet, leaving nothing for a
 * mid-priced tier to sell except what this project's own competitive research already identified as
 * the professional market's real paid/free line: root-gated diagnostics.
 */
enum class SubscriptionTier {
    /** No active subscription. Every screen is paywalled. */
    NONE,

    /** Everything in the app except the root-gated diagnostic tools. */
    FIELD,

    /** Field, plus band lock, technology lock, VoNR control, live SIB1/TDD decode, NR neighbours. */
    PRO,
    ;

    val grantsField: Boolean get() = this >= FIELD
    val grantsPro: Boolean get() = this >= PRO
}
