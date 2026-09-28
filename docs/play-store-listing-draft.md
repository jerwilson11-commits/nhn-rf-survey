# Play Store Listing — Draft Copy

Drafted 2026-09-28 for the initial submission. Not final until you've reviewed it — the app name,
short description, and full description all count toward Play's ranking signals and are expensive
to meaningfully change later (existing installs, reviews, and search history attach to the
listing), so this is worth a real read before it goes in.

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

• Export everything. PDF client reports, CSV for your own analysis, and KML/GeoJSON/GeoPackage for
  GIS tools survey teams already use. A session is never locked into the app.

FREE TO START

The core survey — Wi-Fi and cellular signal, capacity analysis, roaming, heatmaps, and every export
format — works fully for free. Field and Pro tiers add extended session length, advanced reporting,
and (on supported rooted hardware only, entirely optional) direct radio diagnostics for engineers
who need them: band locking for controlled testing, and real-time frame-level decode.

WHO THIS IS FOR

RF and DAS engineers doing venue and building surveys. Network consultants who need a client-ready
report, not a screenshot. IT teams diagnosing a Wi-Fi capacity problem a signal meter can't see.
Anyone who has ever wanted an honest answer for why "full bars" still feels slow.

No account required to use the free tier. No ads.
```

(Roughly 2,700 characters — well under the 4,000 limit, leaving room to add screenshots-referenced
callouts or tighten later based on what actually converts.)

## Notes for whoever reviews this before submission

- "SINR... platform's own signal callback" and "channel utilization... broadcast by the access
  point itself" are both factual claims this session verified against the actual code and against
  real devices — not marketing copy assembled without checking. If either capability changes, this
  copy needs to change with it.
- The Free/Field/Pro tier language matches the pricing sketch in the private roadmap doc, but Play
  Billing isn't implemented yet (see the conversation this file came out of) — this description
  should not go live before purchasing actually works, or it's advertising a feature that doesn't
  exist.
- No specific pricing figures are quoted here on purpose, since those aren't finalized and Play
  Console enforces its own pricing display separately from the description text.
