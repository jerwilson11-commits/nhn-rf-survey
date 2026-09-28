# Play Store Listing — Draft Copy

Drafted 2026-09-28 for the initial submission, revised the same day after Play Billing was
scoped and built with a different structure than this copy originally assumed (see below). Not
final until you've reviewed it — the app name, short description, and full description all count
toward Play's ranking signals and are expensive to meaningfully change later (existing installs,
reviews, and search history attach to the listing), so this is worth a real read before it goes in.

## App name (Play Console "App name" field, 30 chars max)

```
Site Survey Pro: WiFi+Cellular
```

(30 characters exactly. The installed app's home-screen label is the shorter "Site Survey Pro" —
already set in `strings.xml`.)

## Short description (80 chars max, shown under the title in search results)

```
Professional WiFi & cellular site survey: signal, capacity, coverage analysis
```

(77 characters.)

## Full description (4,000 chars max)

```
See what every other Wi-Fi and cellular survey app misses.

Site Survey Pro measures both radios properly, in one tool — not a Wi-Fi analyzer with cellular
bolted on, or a signal meter that stops at bars. Built for RF engineers, network consultants, and
anyone who needs to prove what's actually happening at a site, not just what a bar graph implies.

WHAT MAKES THIS DIFFERENT

• Real SINR, not just RSRP. Most Wi-Fi/cellular apps on this store can't read cellular SINR at all
  on modern Android — they show you signal strength and nothing about interference. Site Survey Pro
  reads it directly from the platform's own signal callback, the one surface that actually carries
  it.

• Capacity, not just coverage. Every free Wi-Fi app shows signal strength. Almost none show channel
  utilization — the fraction of airtime actually busy, broadcast by the access point itself. A site
  at -55 dBm everywhere with 85% channel utilization has a capacity problem no signal reading will
  ever catch. Site Survey Pro reads it, and turns it into an actual capacity estimate: how much
  headroom is left, and roughly how much per device if it's shared evenly.

• Real PHY rate ceilings. Every AP's advertised "legacy" rate tops out at 54 Mbps, even on Wi-Fi 6
  hardware — the real numbers live in separate parts of the beacon almost nothing decodes. Site
  Survey Pro reads the actual HT/VHT/HE capability a nearby access point advertises, so a report
  reflects what the hardware can really do.

• Full LTE and 5G detail. Serving and neighbor cell tracking, NSA/SA state, band and channel
  identification, RSRP/RSRQ/SINR/CQI, and carrier aggregation detail where the device exposes it.

• Wi-Fi roaming and heatmap analysis. Track BSSID transitions across a walk, and visualize coverage
  and signal directly on an uploaded floorplan.

• Public safety coverage evidence. Log FirstNet Band 14/n14 signal against NFPA 1225 ERCES
  thresholds, classified by area (general/critical) as you walk. A useful supplement to a survey —
  not a substitute for dedicated LMR-based ERRCS testing, which needs equipment no phone has.

• Export everything. PDF client reports, CSV for your own analysis, and KML/GeoJSON/GeoPackage for
  GIS tools survey teams already use. A session is never locked into the app.

TWO PLANS, NO ADS, EVER

Field unlocks the complete survey toolkit: Wi-Fi and cellular signal, capacity analysis, roaming,
heatmaps, threshold alarms, the client-ready PDF report, and every export format (CSV, KML,
GeoJSON, GeoPackage).

Pro adds direct radio diagnostics for engineers on supported rooted hardware: band and technology
locking for controlled testing, live signalling decode, and neighbour-cell reads over the modem
diagnostic interface.

Both are monthly subscriptions, cancel anytime.

WHO THIS IS FOR

RF and DAS engineers doing venue and building surveys. Network consultants who need a client-ready
report, not a screenshot. IT teams diagnosing a Wi-Fi capacity problem a signal meter can't see.
Anyone who has ever wanted an honest answer for why "full bars" still feels slow.

No ads, ever.
```

(Well under the 4,000 limit, leaving room to add screenshots-referenced callouts or tighten later
based on what actually converts.)

## Notes for whoever reviews this before submission

- "SINR... platform's own signal callback" and "channel utilization... broadcast by the access
  point itself" are both factual claims this session verified against the actual code and against
  real devices — not marketing copy assembled without checking. If either capability changes, this
  copy needs to change with it.
- **Revised 2026-09-28, same day as the first draft.** The original draft assumed a free tier
  ("free to start... no account required"). Play Billing shipped with a different, later-confirmed
  structure instead: **no free tier at all** — the whole app is behind Field ($9/mo) or Pro
  ($39/mo), see `docs/play-billing.md`. The original copy would have been a false claim about the
  shipped product; this revision matches what the app actually does today rather than what an
  earlier roadmap draft assumed it would do.
- No specific pricing figures are quoted here on purpose — those live in Play Console's own pricing
  configuration, not the description text, and change there without a listing edit.
- No free-trial language is included. `docs/play-billing.md` recommends a 7-day trial on both base
  plans but it is **not yet configured in Play Console** — do not add trial language to this copy
  until it is actually live, or this becomes the same class of false claim as the free tier was.
- **Public safety coverage bullet added 2026-09-28**, after discussing whether "Public Safety"
  should go in the app *name* instead. Deliberately kept out of the 30-char title: the title is
  already full with the app's actual primary positioning (WiFi+Cellular), and Track B only measures
  FirstNet Band 14/n14 — not strict LMR ERRCS, which is what most searchers using that term actually
  need and which no phone can measure. Putting "Public Safety" in the title would draw exactly that
  audience and then disappoint them. The description bullet gets the ERRCS/NFPA 1225/FirstNet
  keyword discoverability without that risk, and matches the same non-overclaiming framing as
  `docs/public-safety-coverage.md` and the PDF report itself.
