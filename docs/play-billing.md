# Play Billing Integration

Scoped and approved 2026-09-28; steps 1-4 below built and pushed the same day. Original scoping
conversation covered a full re-derivation of the tier structure against this project's own
competitive research before landing here — see `docs/MASTER.md` and
`Competitive Analysis - Android RF Apps.md` (private) for that reasoning if it needs revisiting.

## Confirmed structure

**One free tier + two paid tiers, monthly, cancel anytime — no annual plan for v1.**
Updated 2026-10-01: a **Free tier was added**, reversing the earlier "everything paywalled" v1
decision below once it proved fatal for public discovery/trial (a new install could do nothing
without paying). The free tier is the acquisition wedge; the paid tiers hold the professional
deliverables.

- **Free — $0.** Live measurement (cellular/Wi-Fi KPIs, verdict, map, spot check), session
  recording, **raw-CSV** export, **manual throughput/speed test**, **manual video + voice QoE**, and
  walk-throughput logging during a recording. Always non-rooted.
- **Field — $9/month.** Free, plus the **PDF acceptance report**, the pro exports (**KML, GeoJSON,
  GeoPackage, iBwave CSV**), **floorplan mode**, **cell lock watch**, **Automation**
  (looped/scripted testing), and **threshold alarms**.
- **Pro — $39/month.** Field, plus every root-gated tool already built.

A **7-day free-trial offer on both paid base plans** (configured in Play Console, not in code) lets
someone try Field/Pro before paying, on top of the permanent Free tier. Recommended; configure in
Console.

### Gating model (code)
`MainActivity` no longer blocks the whole app — it renders at the **Free floor** and gates paid
features individually. A gated control calls `billing/UpgradePrompt.open()`, which `MainActivity`
observes to overlay the upgrade screen (`PaywallScreen`, now reachable with a "keep using free"
close). Gated touchpoints: the Plan tab (floorplan), `SessionsScreen`'s report + four pro exports
(raw CSV stays free), and `WifiDashboard`'s Automation card, Thresholds/alarms card, and
lock-watch entry. `SubscriptionTier` is now `FREE | FIELD | PRO` and "no active subscription"
resolves to `FREE`.

---

### Superseded: original v1 "no free tier" decision (kept for history)

**Two paid tiers, monthly, cancel anytime — no free tier, no annual plan, for v1.**

- **Field — $9/month.** Every feature the app has today except the root-gated diagnostic tools.
- **Pro — $39/month.** Field, plus every root-gated tool already built.

No functionality is available without an active subscription. This is a deliberate reversal of the
earlier "generous free tier is the wedge" strategy that ran through this project's prior roadmap
and competitive-analysis docs — noted here plainly so it's visible as an intentional decision, not
something to silently re-litigate later. A **free trial period on both base plans** (recommended:
7 days, configured in Play Console, not in code) is the substitute mechanism for letting someone
try the app before paying, given there's no permanent free tier to serve that role. Recommended,
not yet confirmed or configured.

## Why this boundary, not the original roadmap's

The original roadmap's tier *feature* boundaries (written 2026-09-02) assumed a free tier and
assumed Pro would sell features that don't exist yet (ERRCS mode, multi-device multi-carrier, cloud
sync, scanner ingest). With no free tier, the Field/Pro split collapses onto a boundary this
project's own competitive research already validated from the professional market's actual pricing
behavior:

- **NSG's own pricing draws exactly this line.** *"Forcing — band and technology lock — is
  paywalled; signalling decode is not"* in NSG's model. This session built exactly that class of
  feature (band lock, technology lock, VoNR control, live SIB1/TDD decode, NR neighbour reads), all
  root-gated — the established professional market's own root/non-root boundary, already built here.
- **No advertising, at any tier, ever** — unchanged.

## Tier definitions

**Field — $9/month, cancel anytime.** Everything currently built that isn't root-gated: full
cellular + Wi-Fi capture (SINR, channel utilization, HT/VHT/HE rates, capacity modeling),
CSV/KML/GeoJSON/GeoPackage export, the PDF report, floorplan mode, threshold alarms, and
per-engagement-type report templates (DAS acceptance / Wi-Fi survey framing — ERRCS-specific
compliance templates wait for ERRCS mode itself).

**Pro — $39/month, cancel anytime.** Field, plus every root-gated tool already built: band lock,
technology lock, VoNR control, live SIB1/TDD decode, NR neighbour reads over QMI. ERRCS/NFPA mode,
multi-device multi-carrier, cloud sync, and scanner ingest fold into Pro as each ships later.

**Enterprise.** Unchanged — negotiated directly, outside Play Billing entirely.

Prices, trial length, and terms are Play Console configuration, changeable anytime without a code
or app-store update.

## Architecture

**No backend, client-side entitlement only, for v1.** `BillingClient`'s local purchase state is
checked directly on-device; no receipt validation against the Google Play Developer API. Real but
modest risk: a rooted device (this app's own core audience) could theoretically fake local
entitlement state. Accepted for now, revisitable if piracy becomes a real problem.

Mirrors two patterns already established elsewhere in this codebase:

- The `unavailableReason: String?` tri-state idiom (checking / available / unavailable-with-reason)
  that `TechnologyLockController`/`ModemNeighbourSource` already use, consumed via `LaunchedEffect`
  + `Dispatchers.IO` in `WifiDashboard.kt` exactly as tech-lock availability already is.
- A `StateFlow`-based singleton (`EntitlementRepository`), mirroring `RecordingState` — no DI
  framework exists in this app, and none is needed here either.

**Files:**
- `billing/SubscriptionTier.kt` — `NONE | FIELD | PRO`, ordered so `tier >= FIELD` reads naturally.
- `billing/EntitlementRepository.kt` — owns the `BillingClient` connection, exposes
  `tier: StateFlow<SubscriptionTier>` and `checking: StateFlow<Boolean>`. Product IDs:
  `field_tier`, `pro_tier` — one subscription group in Play Console (base plans on the same two
  products), not independent purchases, so Play handles upgrade/downgrade proration natively.
- `billing/EntitlementStore.kt` — caches the last-known tier in `SharedPreferences` (matching
  `BandLockController`'s precedent) so the UI has an answer before the first `BillingClient`
  round-trip completes.
- `billing/PurchaseFlow.kt` — launches the billing sheet, and separately, `acknowledgeIfNeeded` --
  the acknowledge-within-3-days logic (an unacknowledged purchase auto-refunds), tested against raw
  purchase-state values rather than a real `Purchase` object, since `Purchase`'s JSON-backed
  getters throw "not mocked" on the plain JVM unit-test classpath.
- `ui/PaywallScreen.kt` — shown full-screen from `MainActivity` when there's no active
  subscription, the same way a missing permission already blocks the tab content.

**Gating:** `MainActivity` gates the whole app behind `tier.grantsField`, once, at the top level.
`WifiDashboard.kt`'s technology-lock and band-lock controls add `tier == PRO` alongside their
existing root check — available requires both.

**Debug builds bypass billing entirely** (`EntitlementRepository.init`, gated on
`BuildConfig.DEBUG`): tier is forced to `PRO` and `BillingClient` is never connected. Added after
scoping, not part of the original plan -- without it the app is unusable for local development
and testing the moment this gate ships, since there is no free tier and no Play Console
subscription product exists yet to test-purchase against. A release build always takes the real
`BillingClient` path, unconditionally.

## Known gap

`ModemNeighbourSource` (NR neighbour reads) and `ModemNrStream` (live SIB1/TDD decode) are wired
into `CellularCollector`'s passive data pipeline, not a toggled UI control like band/technology
lock. They are **not yet tier-gated** — a rooted device gets this data regardless of subscription
tier today. Closing this means touching `CellularCollector` itself, deferred rather than rushed
into the core collection pipeline without separate review.

## Remaining (Play Console side, not code)

1. Create the `field_tier` and `pro_tier` subscription products, each with a monthly base plan and
   (if the trial recommendation above is accepted) a free-trial offer.
2. Add a license tester account so test purchases don't charge real money.
3. End-to-end verification with a real (test) purchase — blocked on the app listing existing in
   Console, which is blocked on account verification finishing.
