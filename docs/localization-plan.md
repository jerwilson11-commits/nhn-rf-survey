# Localization / language support — readiness assessment & plan

**Goal:** reach more non-English-speaking customers. Researched 2026-10-11.

## Current state: zero localization infrastructure

- `res/values/strings.xml` holds **1 string**; there are **0 `stringResource()` calls**.
- **178+ hardcoded English `Text("…")`** literals in the Compose UI alone, plus more in the **PDF
  report** text, the **What's New**, and the **modem failure messages**.
- No `values-<lang>/` locale resources.

So the app is **100% hardcoded English**. Full in-app localization means a two-step effort: (1)
**externalize** every user-facing string into `strings.xml` with `stringResource()`, then (2)
**translate** per language. Step 1 is a sizeable one-time refactor; step 2 repeats per language.

## Key insight: the highest-ROI step is NOT in-app translation

For *acquiring* non-English customers, **localize the Play Store listing first** — it's far cheaper,
needs **no code changes**, and is what a non-English speaker sees *before* installing:

- Translate the **store listing** (title, short + full description, What's New) and ideally the
  **screenshot caption overlays** into target languages, in Play Console → per-language listings.
- This directly lifts discovery and install conversion in those markets. A localized *listing* with an
  English *app* still converts far better than an English listing, and many RF engineers read English
  technical UI fine.

**Recommendation: do store-listing localization first (days, no code), measure uptake by language in
Play Console, then invest in in-app localization for the languages that actually convert.**

## Important nuance for *this* app: much of the text shouldn't be translated

A large share of the on-screen text is **technical RF terminology** — band names (n41, B14), protocol
terms (RSRP, RSRQ, SINR, RRC, SIB1, ERRCS, NFPA, CBRS), and identifiers — which are **English/standard
across the industry and must stay as-is**. This cuts both ways:
- **Less to translate** than 178 strings implies — the translatable surface is UI chrome, instructions,
  buttons, report headings, and error messages, not the technical nouns.
- **Machine translation is risky here** — it will mangle RF terms. Translations need **expert review**
  by someone who knows both the language and RF, or a glossary of do-not-translate terms.

## Recommended target languages (validate against Play Console country data)

Ordered by likely RF/telecom-market payoff:
1. **Spanish** — Spain + Latin America (large telecom/DAS market).
2. **Portuguese (Brazil)** — big, active cellular/telecom market.
3. **French** — France, Canada, francophone Africa.
4. **German** — strong engineering market.
5. Then, per Play data: **Japanese, Korean, Simplified Chinese** (major telecom), **Arabic** (Gulf — note
   RTL layout work), **Indonesian / Hindi** (large volume, more price-sensitive).

Start with **1–2** (Spanish + Portuguese-BR) and expand on evidence.

## Phased plan

1. **Store-listing localization (first, cheap, no code).** Translate listing + What's New for Spanish +
   Portuguese-BR; add to Play Console. Watch install/country stats.
2. **In-app externalization (one-time refactor).** Move all user-facing strings to `strings.xml` +
   `stringResource()`; build a **do-not-translate glossary** of RF terms; keep `%s`/`%d` placeholders
   for formatted strings. This is good practice regardless and unblocks every future language.
3. **Translate in-app for the proven languages**, with expert/RF-aware review.
4. **Handle the hard parts:** the **PDF report** text is generated in code (needs localized resources or
   a locale parameter); **number/unit formatting** (decimal separators, dBm stays dBm); **RTL** if Arabic
   is added; test layouts for text expansion (German/French run ~30% longer).

## Effort estimate (rough)

- Store-listing localization (per language): **hours** (translation + Console entry).
- In-app externalization refactor: **multi-day** (178+ UI strings + report + messages, carefully).
- Per-language in-app translation + review: **1–2 days** each with an RF-aware reviewer.

## Bottom line

**Start with localized store listings** for Spanish + Portuguese-BR — cheapest path to more non-English
customers, no code risk. Treat full in-app localization as a scheduled refactor (externalize once, then
translate the languages that convert), with expert review because of the RF terminology.
