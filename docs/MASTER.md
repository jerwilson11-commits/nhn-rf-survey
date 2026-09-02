# RF Test App — Master

Source of truth for the mobile drive-test / walk-test application. Update this file when scope,
platform, or architecture decisions change. Everything else in this folder derives from it.

Owner: Jeremy Wilson · Started: 2026-08-28

---

## 1. What this is

A field-test application for measuring 4G LTE and 5G NR radio and performance KPIs, geo-logging
them, and exporting them for reporting. Positioned as a lightweight, low-cost alternative to
TEMS Pocket / XCAL / QualiPoc for DAS, Private 5G, and CBRS acceptance and troubleshooting work.

Two audiences, in order:

1. **NHN Engineering & Consultants** — the instrument Jeremy uses on his own engagements.
2. **Commercial product** — published once proven in the field.

Working name: TBD.

---

## 2. Decisions locked 2026-08-28

| Decision | Choice | Why |
|---|---|---|
| Platform | **Android first, iOS companion later** | iOS exposes no RF KPIs to third-party apps. Android does, via public API, no root. |
| Purpose | Consulting tool first, commercial product later | De-risks store polish until the measurement core is proven. |
| Build | Jeremy + Claude, learning as we go | Zero marginal cost, full ownership, unlimited iteration. |
| Data | On-device capture + optional cloud sync | Local-first works offline in a basement DAS. Cloud is Phase 4. |

---

## 3. Platform reality — what can and cannot be measured

### iOS: RF KPIs are impossible

No public API for RSRP, RSRQ, SINR, RSSI, band, EARFCN/NR-ARFCN, PCI, CID, TAC, or neighbors.
`CTCarrier` is deprecated and stubbed on iOS 16+. Private APIs (`CTGetSignalStrength`, status-bar
internals) are App Store Guideline 2.5.1 rejections and are broken on current iOS regardless.

**Band lock and programmatic test-mode entry do not exist on iOS** — not privately, not with an
enterprise certificate, not under MDM. They require the modem diagnostic interface (Qualcomm DIAG),
which Apple exposes to no one outside Apple. Field Test Mode (`*3001#12345#*`) is a carrier-bundle
UI the user drives by hand; nothing programmatic hangs off it.

This is why TEMS Pocket, QualiPoc, and XCAL are Android-only for RF-layer data, and why
OpenSignal / Ookla on iOS report only throughput and a coarse network type.

### Android: RF KPIs are available, band lock is not

| KPI / Feature | Android public API | Notes |
|---|---|---|
| RSRP / RSRQ / RSSNR / CQI / RSSI (LTE) | `CellSignalStrengthLte` | RSSI added API 29 |
| SS-RSRP / SS-RSRQ / SS-SINR (NR) | `CellSignalStrengthNr` | API 29+ |
| Band, EARFCN / NR-ARFCN | `CellIdentityLte` / `CellIdentityNr` | `getBands()` API 30+ |
| PCI / CI / NCI / TAC / PLMN | `CellIdentity*` | |
| Timing advance | `CellSignalStrengthLte.getTimingAdvance()` | Serving cell only |
| Neighbor cells | `getAllCellInfo()` | Completeness is vendor-dependent |
| NSA / SA state | `NetworkRegistrationInfo.getNrState()` | API 30+ |
| **Band lock** | **Not available** | `setAllowedNetworkTypesForReason()` needs `MODIFY_PHONE_STATE`, a system permission |
| **Test mode entry** | **Not available** | Manual dial code, OEM-dependent |
| Throughput / latency / jitter / loss | Standard networking | Fully available |
| GPS track, map, export | Standard | Fully available |

**Band lock is off the table on both platforms** for any app that can be distributed normally.
Real drive-test kit gets it through vendor-firmware handsets (Samsung/Sony units flashed for TEMS)
or an external scanner. That is a hardware supply-chain problem, not a coding problem.

### Options if band lock becomes a hard requirement later

- Vendor-firmware handset plus an existing commercial tool — buy, do not build.
- External RF scanner over BLE/USB with a vendor SDK, app as the head unit.
- Rooted device with Qualcomm DIAG parsing — technically possible, not distributable, not supportable.

---

## 4. Scope

### v1 — the measurement core

Two coequal measurement modes sharing one pipeline: **cellular** and **Wi-Fi**.

- Cellular: continuous serving-cell and neighbor collection, LTE and NR, all identity and signal
  fields. NSA/SA state and RAT display, including the LTE anchor when NSA.
- Wi-Fi: connected-AP RSSI, BSSID/SSID, channel, band (2.4 / 5 / 6 GHz), channel width, link rate,
  802.11 standard, security; plus scan-result neighbor AP list with co-channel and adjacent-channel
  interference counts.
- GPS-tagged sample stream at a configurable rate, identical for both modes.
- Live numeric dashboard, RF-engineer layout, not a consumer "bars" UI.
- Session record/stop with named sessions: site, venue, sector, floor.
- CSV export, local file storage, share sheet out.

**Why Wi-Fi is first-class, not a detour:** the sampling loop, geo-tagging, storage schema, export
writers, map overlay, and threshold alarms are all radio-agnostic. Wi-Fi exercises every one of
them with no SIM required, so the pipeline is proven before the cellular collector plugs in. It
also aligns with the CWNA track, and a combined cellular-plus-Wi-Fi survey tool in one app is a
real differentiator — DAS venues need both surveyed anyway.

### v2 — field usability

- Map view with color-coded KPI overlay (RSRP / SINR / throughput) and plotted track.
- KML and GeoJSON export for Google Earth and GIS.
- DL / UL throughput test, latency, jitter, packet loss, DNS resolve time.
- Configurable pass/fail thresholds per KPI, visual and audible alarm on breach.
- Background collection via foreground service, screen-off logging.

### v3 — reporting

- Session summary statistics: min/max/mean/percentiles per KPI, per band, per cell.
- Indoor floorplan mode: load an image, tap-to-place points instead of GPS.
- PDF / XLSX acceptance report generation.

### v4 — platform

- Optional cloud sync, multi-tester campaigns, server-side heatmaps, client dashboards.
- iOS companion: speedtest, GPS logging, and viewing/reporting of Android-collected data.

### Explicitly out of scope

- Band lock, forced RAT selection, test-mode entry.
- Layer-3 message decode, RRC signaling, protocol trace.
- Anything requiring root.

---

## 5. Technical stack (proposed)

| Layer | Choice | Rationale |
|---|---|---|
| Language | Kotlin | Android standard, concise, coroutines fit the sampling loop |
| UI | Jetpack Compose | Modern, less boilerplate, easier to learn than XML layouts |
| Min SDK | **31 (Android 12)** | `TelephonyCallback` lands at 31. Dropping to 29 would force a parallel deprecated `PhoneStateListener` path for two OS versions that are nearly gone by 2026 — not worth the dual code path |
| Compile SDK | **37** | The only platform SDK installed. compileSdk and targetSdk differing is normal — compileSdk decides which APIs you can call, targetSdk decides which behavior changes apply |
| Target SDK | **36 for now, 37 later** | Test device runs Android 17 (API 37), but targeting 36 keeps API-37 behavior changes out of Phases 1–4. Bump deliberately as its own task — see `docs/android-17-impact-notes.md` |
| Local storage | **Streaming CSV** (Room deferred) | Changed 2026-08-31. Room means adding KSP and matching its version to Kotlin 2.2.10 under AGP 9, for a benefit not yet needed. Streaming appends are also better for a drive test: a session killed mid-run keeps every row already written, memory stays flat under Android 17's RAM limits, and the CSV is the deliverable so there is no export step to fail. Room returns when queries across sessions are actually needed — Phase 7 reporting or cloud sync |
| Location | Platform `LocationManager` | Changed 2026-08-31. Android 12+ ships a fused provider inside the platform, so no Play Services dependency. Raw `GPS_PROVIDER` preferred, fused as indoor fallback — a fused fix can be derived from Wi-Fi and cell, which would make a Wi-Fi survey partly circular. Every fix records which provider produced it |
| Map | **Self-drawn Compose Canvas** (MapLibre dropped) | Changed 2026-08-31. A tile basemap adds a dependency, a tile-source ToS and network dependence, and is useless for the actual use case — inside a DAS venue or basement a street map shows nothing and there is often no connectivity. The geographic view is the KML export opened in Google Earth. Equirectangular projection with longitude scaled by cos(latitude), one scale on both axes |
| Networking | OkHttp | Multi-stream throughput test, full control over timing |
| Speedtest server | **User-configurable URL**, Cloudflare default | The server is a field in the UI, not a constant. On a DAS / Private 5G job the meaningful test is against a server on the **venue LAN** — testing to the internet measures the client's backhaul and ISP, not the radio system under acceptance. Cloudflare's endpoints are the default for general drive test; a LAN-hosted LibreSpeed is the on-site workflow |
| Export | Hand-written CSV/KML/GeoJSON writers | No dependency, exact control over the column schema |

---

## 6. Roadmap

Resequenced 2026-08-28 so that no phase before 5 is blocked on SIM activation.

| Phase | Goal | Gate | Needs SIM |
|---|---|---|---|
| 0 | ✅ **Done 2026-08-31.** Dev environment, app builds and runs on the Pixel | App runs on the handset | No |
| 1 | ✅ **Done 2026-08-31.** Data model, permissions flow, **Wi-Fi collector**, live dashboard | Real RSSI / BSSID / channel / band on screen | No |
| 2 | ✅ **Done 2026-08-31.** GPS sampling loop, session record/stop, streaming CSV | A real walk produces a usable CSV | No |
| 3 | ✅ **Done 2026-08-31.** Session browser, track plot, KML / GeoJSON export, share sheet | Route and RSSI render correctly | No |
| 4 | ✅ **Done 2026-08-31.** Speedtest — DL / UL / latency / jitter / ICMP loss | Results within reason of Ookla over Wi-Fi | No |
| 5 | ✅ **Validated 2026-09-01** on live T-Mobile 5G SA. Cellular collector, band derivation, UI card, CSV columns | NR values match OS ground truth | Done |
| 6 | ✅ **Done 2026-08-31.** Foreground service, screen-off logging, thresholds and alarms | Survives a full site walk | No |
| 7a | ✅ **Done 2026-09-01.** Floorplan mode — indoor positioning by hand, waypoint labels | Indoor samples carry a usable position | No |
| 7b | Reporting — statistics, PDF / XLSX | A client-ready acceptance report comes out | No |
| 8a | ✅ **Done 2026-09-01.** MCP server, stdio transport, 5 tools over the session corpus | An agent answers an acceptance question directly from recorded sessions | No |
| 8b | ✅ **Done 2026-09-01.** Stateless `streamable-http` transport, OAuth 2.1 Resource Server | Hosted deployment per MCP 2026-07-28 | No |

The cellular collector moves to Phase 5 deliberately. By then the sampling loop, storage, export,
and map are all validated against real Wi-Fi data, so when the SIM arrives we debug one module
rather than ten simultaneously.

### Phase 7a — floorplan mode, 2026-09-01

Indoor positioning by hand, because GPS cannot do it. Inside a venue, accuracy collapses from the
±3 m measured on the driveway to tens of metres or no fix at all, and a coverage measurement that
cannot be placed on a plan is most of the way to useless.

Load a floorplan image, tap where you are. **The position is sticky**, carried by every subsequent
sample until moved or cleared — which matches how an indoor walk actually goes. A one-shot marker
would turn a thirty-second dwell into one located sample and twenty-nine orphans.

Design decisions:

- **Coordinates are normalised 0..1 to the image, not stored in pixels.** Pixel coordinates would
  be bound to the display size at the moment of capture and would break silently at a different
  zoom, on a different screen, or after re-export at another resolution.
- **Images are copied into app storage, not referenced by URI.** A picked `content://` URI is a
  revocable grant that breaks if the source moves or a cloud provider goes offline. A session whose
  floorplan cannot be reopened is a session whose positions mean nothing. The stored filename goes
  in the CSV so a session and its plan can be handed over together.
- **The canvas is sized to the image aspect ratio** so the bitmap exactly fills it. Letterboxing
  would make screen and image coordinates diverge, placing every tap slightly wrong — an error
  invisible on screen that would corrupt every position in the session.
- Dimensions are read with `inJustDecodeBounds`, header only. A resort floorplan can be a very
  large image and decoding pixels merely to learn the aspect ratio risks OutOfMemory under Android
  17's per-app RAM limits.

Four CSV columns added: `floorplan_id`, `floorplan_x`, `floorplan_y`, `waypoint`. Schema is now
**71 columns**.

**Verified on device 2026-09-01:** image imports through the picker, renders, and taps land where
the operator expects. That last point needed a human — the screen-to-image inverse transform is
exactly the kind of arithmetic that can be subtly wrong in a way no automated check would notice
and that would misplace every position in a session.

Note for future testing: pushing a file into `files/floorplans/` over `adb` creates that directory
owned by `shell`, which locks the app out of its own folder. Import through the picker instead.

### Phase 7a follow-on — indoor sessions are reviewable and analysable, 2026-09-01

Floorplan mode initially only *recorded*. A walk you cannot review afterwards produces no
deliverable, and a Margaritaville session will be almost entirely indoor — so the read path needed
the same treatment.

- **`SessionReader` keeps a row placeable by GPS *or* by floorplan.** It previously required a
  lat/lon, which would have silently discarded an entire indoor venue walk — precisely the case
  floorplan mode exists to handle. `latitudeDeg`/`longitudeDeg` are now nullable, and `hasGpsPosition`
  / `hasIndoorPosition` say which applies.
- **Sessions tab renders a past session back onto its floorplan**, points coloured by whichever
  radio was serving. If the image is missing from the device it says so plainly rather than showing
  an empty panel — the positions are still in the CSV and still valid, they just cannot be drawn.
- **KML and GeoJSON export GPS-located samples only.** They are geographic formats; forcing
  floorplan coordinates into them would fabricate geography. An indoor session now explains why its
  geographic export is sparse rather than appearing broken.
- **MCP `analyze_coverage` is KPI-aware.** Defaults are −75 dBm for Wi-Fi RSSI and −105 dBm for
  cellular RSRP, which differ by an order of magnitude — a Wi-Fi threshold applied to cellular data
  would fail essentially every sample ever recorded. `kpi="auto"` picks cellular where present.
- **Coverage holes carry an indoor position.** Outdoors a lat/lon; indoors a floorplan coordinate
  plus the nearest waypoint label. Without that, a venue walk would report holes with no position
  at all.
- Bucket distribution is suppressed for RSRP, since that scale is Wi-Fi-specific.

Regression checked: the existing Wi-Fi driveway session still resolves to `wifi_rssi` at −75 dBm
with 87.1% compliance and 2 holes — identical to before the change.

### Test suite — 2026-09-01

20 unit tests, all passing. The project had none until Phase 5.

- **BandMapping, 14 tests.** Verified against 3GPP TS 36.101 and TS 38.104. The only part of the
  cellular work provable without a SIM.
- **CsvSchemaTest, 5 tests.** Guards the Phase 2 defect directly: the row builder once emitted 29
  empty cellular cells against a 31-column header, shifting every later field by two. A runtime
  assertion caught it only after the app had written a session and crashed. These catch it at build
  time — including the all-null case, which is the one that actually shifted, and a check that
  locale-sensitive numbers use a decimal point rather than a comma.

What these do **not** catch is a transposition — RSRQ values written into the RSRP column would
pass every test. That is why the emission order sits directly beneath the column list in the source
rather than elsewhere in the file.

### Report presentation and dominance analysis — 2026-09-01

Built against a specific competitor deliverable (Wireless Data Collections, analysed in
`OneDrive/Documents/DAS/WDC/`) rather than against a general sense of "better".

**Added:** bordered title block; per-band and per-area statistics; sector dominance, overlap
percentage and per-cell best-server table; plot frame, colour legend, north arrow, scale bar
snapped to a round number of metres, and start/end markers.

**Deliberately absent: no north arrow or scale bar on floorplan plots.** The operator uploads a
plan image, not a georeferenced raster, so neither its orientation nor its scale is known to the
app. Drawing either would be an invention the reader cannot check. The plot says so in words.

**Defect found and fixed: null-as-zero in the neighbour JSON.** `cellNeighborsToJson` wrote
`"rsrp":${n.rsrpDbm ?: 0}`. A neighbour the modem reported without a level was serialised as
**0 dBm** — the strongest value in the file. Any best-server or dominance calculation built on top
of it would have ranked an unmeasured cell above every real one. Fixed to emit JSON null, parse
back as null, and exclude unlevelled cells from the analysis while reporting how many were
excluded.

This is **the same mistake as the GPS distance work**, where a missing Doppler velocity treated as
zero silently deleted an entire outbound leg. It is now the second occurrence, so it is worth
stating as a rule alongside the staleness lesson:

> **A missing measurement is not a zero. Zero is a measurement, and usually an extreme one.**

The difference this time is that it was caught by writing the consumer before shipping the
producer, not by a walk that produced numbers that could not be true.

**Honesty constraint carried into the report:** dominance from a handset is a **lower bound**. A
scanner decodes every cell on air simultaneously; a handset reports its serving cell plus whatever
partial neighbour list the modem chose to surface. Only cells in a sample's own measurement report
are counted — retained neighbours keep the live display readable but counting them would assert a
simultaneity never observed. The asymmetry is the useful part: **a handset finding overlap is
evidence; a handset finding none is not.**

**Tests: 31 → 49.** The one worth naming reproduces the competitor's exact 285-of-495 and pins the
result at 57.58, not 0.5757. Their sheet reports that figure as `0.5757575757575757` under a `%`
heading — a 100x understatement on the single metric that most drives DAS remediation.

**Not yet seen by a human: the rendered PDF.** The statistics are unit-tested; the layout is not,
and no test can tell whether the legend collides with the scale bar on a real page.

---

### Walk validation — 2026-09-02, indoor + outdoor, live T-Mobile 5G SA

431 samples, 7 m 36 s, 181 Indoor / 244 Outdoor / 6 before the first label. GPS: 430 of 431 fixes,
430 with velocity, median accuracy 3.95 m, provider `gps` throughout — **including indoors**, so
the indoor positions are genuine GNSS and not Wi-Fi-derived. Distance was not flagged approximate;
the pre-walk warning that it probably would be was wrong.

**Three open questions, all answered.**

**1. Does SS-SINR move while walking, or stay pinned?** It moves. 9 distinct values, 3–22 dB,
σ = 4.75, correlated with RSRP at only r = 0.40 — which is physically right, since SINR depends on
interference and not on signal alone. The stationary session was inconclusive, not broken.

But the update rate differs sharply from RSRP, and that is the finding worth keeping:

| Field | Changes in 431 samples | Median run | Longest run |
|---|---|---|---|
| `nr_ss_rsrp` | 88 | 5 samples | 12 samples |
| `nr_ss_sinr` | 19 | 5 samples | **99 samples** |
| `nr_ss_rsrq` | 19 | 12 samples | **120 samples** |

SINR held one value for 99 consecutive seconds while RSRP moved 88 times. **SS-SINR and SS-RSRQ are
live but not sample-synchronous with RSRP**, and a report that presents them side by side without
saying so implies a simultaneity the instrument does not deliver. This finally resolves the Phase 5
"pinned SINR" question: not a caching bug, a genuinely slower measurement.

**This is now stated in the report.** `SessionStats.cadence` counts transitions per field and the
methodology page carries a "Measurement cadence" note built from the session's own numbers — for
this walk: *RSRP changed 88 times, while SINR changed 19 times and held one value for 103 s; RSRQ
changed 19 times and held one value for 126 s.* The note only appears where a session actually
shows the disparity (quality-metric changes at most half of RSRP's), so a session where the fields
track one another does not carry a caveat that does not apply to it.

**2. Does mean dominant-sector count rise above 1.00 once moving?** Yes — **1.13, with 8.8%
overlap**, against exactly 1.00 and 0% stationary. Histogram: 392 samples with one dominant sector,
18 with two, 20 with three. The metric responds to motion, as it must.

**3. Indoor versus outdoor.** Median RSRP −103 dBm indoor against −96 dBm outdoor — **7 dB of
building penetration loss**, 6.2 dB by means. Threshold compliance at −105 dBm: 97.1% outdoor,
87.8% indoor. Plausible for light residential construction, and it is the number that sells a DAS.

**Incidental findings.**

The walk saw **17 cells across five carriers on four bands** — n71 at 622.95 MHz, n2/n25 at
1981.25, n4/n66 at 2136.15, and two n41 carriers at 2506.95 and 2606.55 MHz. `BandMapping` derived
all five correctly from the raster, validated against live data for the first time beyond n41.

**The serving cell never left n41**, despite an n71 neighbour being the strongest cell seen in 16
samples. The modem is being held on mid-band for capacity rather than following signal. Worth
knowing before reading any handset survey as if it tracked best server.

**Defect found: NR band ambiguity discarded on the neighbour path.** Two of the five channels map
to overlapping allocations, and the neighbour path resolved them silently with `firstOrNull()`,
labelling them n2 and n4 — the wrong choice for a US carrier both times. The serving-cell path had
always surfaced the ambiguity. **The one place it was dropped was the one place a client reads it.**
Fixed; label compacted to `n2/n25` because the old `n2 (or n25)` truncated to `n2 (or` in the
six-character band column, turning an honest ambiguity into apparent corruption.

**Area labelling worked on its first outing** and immediately earned itself: the coverage-hole table
now names where each hole was — four Indoor, one Outdoor — rather than printing coordinates.

**App output cross-checked against an independent implementation.** The dominance figures were
computed separately in Python straight from the CSV before the report was generated: 1.14 mean and
9.0% overlap over 431 rows against the app's 1.13 and 8.8% over 430. The difference is one row the
reader drops for having no fix, and it accounts for the gap exactly.

**Tests: 58 → 62.**

---

### Report render validation — 2026-09-01, first PDF anyone had looked at

The previous entry ended "not yet seen by a human: the rendered PDF". It was generated and read the
same day. **Two defects, neither reachable by reasoning about the code.**

**1. Detection counts larger than the sample count.** The per-cell table reported PCI 216 as
detected in **36 samples out of 28 analysed**. It was counting cell *observations*, not samples:
eight rows of that session report the same PCI on two channels simultaneously, so 29 rows produced
37 observations.

Fixing it exposed a bigger problem. **PCI is unique only within a carrier** — 504 values for LTE,
1008 for NR — so the same PCI on two channels is two different physical cells. Grouping by PCI
alone merges them silently. Cells are now keyed by **PCI and channel together**. The result is
better information, not merely correct information: the same session now shows PCI 216 and 865
present on two carriers, the second in 8 of 28 samples, which the merged view hid completely.

Worth noting the competitor's per-PCI columns carry the same ambiguity and nothing in their
deliverable resolves it.

**2. Explanatory text clipped at the right page edge.** Three paragraphs ran off the page —
including, with some irony, the one explaining why detection rate must be shown. They were passed
to a helper that does not wrap. `Ctx.para` now wraps on **measured width** (`Paint.measureText`)
rather than the 96-character guess the methodology page used. Verified programmatically: zero text
spans cross the right margin on any page.

**The lesson, and it is not a new one.** Every subsystem in this project has produced believable
wrong numbers on first contact with reality — Wi-Fi RSSI, Wi-Fi identity, GPS distance, cellular
SINR, and now the report layer. The report was the last one still unexamined, and it broke in
exactly the same way: **plausible output, wrong content.** Unit tests passed throughout, because
the tests pinned arithmetic that was correct on data the analysis was mis-assembling upstream.

> **A test that passes on synthetic input tells you the function is right. Only real output tells
> you the input was.**

**Tests: 49 → 54.** One states the property the broken version violated — detection rate can never
exceed 100% — and one reproduces the two-channel case from the real session.

---

### Cellular neighbour logging — 2026-09-01

Cellular neighbours were displayed but **never logged**. The schema had `wifi_neighbors_json` and
no cellular equivalent, so everything on the neighbour panel vanished when the sample was written.
Added `cell_neighbor_count` and `cell_neighbors_json`; schema is now **73 columns**.

**On the "bouncing" neighbours.** Weak cells near the detection floor appear and disappear between
measurement reports. This looks like the Wi-Fi partial-sweep problem and is not the same thing: a
Wi-Fi AP missing from a scan is an artefact, the AP is still there and accumulating is correct. A
cellular neighbour vanishing is usually **real** — it crossed the detection floor, and that is
itself information.

So retention is deliberately short — **10 s versus 60 s for Wi-Fi**, roughly 14 m at walking pace —
and exists only to stop the live display flickering. Every neighbour carries `age_ms`, so nothing
is fabricated: filter to `age_ms == 0` for only the cells in that sample's own report. Measured on
a stationary 24-sample session, retention held a median of one extra cell per sample, which
quantifies the churn.

### The staleness lesson, third occurrence — 2026-09-01

The `SignalStrength` fallback added for SS-SINR was itself measured pinning **SS-SINR to 18 and
SS-RSRQ to −10 across 24 consecutive samples** while SS-RSRP moved across four values. The
callback simply never fired. Replacing "missing" with "stale" is an improvement and still not
honest.

Switched to pulling `TelephonyManager.getSignalStrength()` at sample time, matching the Wi-Fi fix.
**This did not change the result** — SINR remained 18 across a fresh 29-sample session, so the
constancy is not caused by caching in this app.

Honest status: **inconclusive while stationary.** SINR and RSRQ are ratios and legitimately more
stable than RSRP, and SINR has been observed at 21, 17 and 18 across sessions, so it does move on
longer timescales. The pull is retained because it removes this app's caching as a variable.

**The walk decides it.** If SS-SINR stays pinned while SS-RSRP swings 20+ dB during a moving test,
it is stale and needs another source. If it tracks, it is real. This is the first thing to check in
tomorrow's session.

Three subsystems have now shown the same shape — Wi-Fi RSSI, cellular SINR, and the Wi-Fi
NetworkCallback identity fields. **A push cache answers "what did I last hear"; a pull answers
"what is true now".** Prefer the pull for anything volatile, and where a value must come from a
push, record its age.

### Phase 5 VALIDATION record — 2026-09-01, live T-Mobile 5G SA

SIM activated. First contact with a real network, at last.

**What was correct on first run** — unusual for this project, and worth recording:

```
5G SA · T-Mobile (310/260) · −94 dBm SS-RSRP
Band n41 · NR-ARFCN 501390 · 2507.0 MHz · PCI 206 · NCI 6592188719 · TAC 8517888
```

- **RAT discrimination correct.** Identified SA, not NSA, via the public-API path
  (`getDataNetworkType()` + display-info override) that replaced the `@SystemApi` approach the
  planning doc wrongly recommended.
- **Band maths correct against a live network.** NR-ARFCN 501390 x 5 kHz = 2506.95 MHz, displayed
  2507.0, inside n41 (2496–2690). Exactly what the unit tests predicted.
- **`Integer.MAX_VALUE` guards worked.** The raw dump carried `ssSinr = 2147483647`,
  `csiRsrp = 2147483647`. None reached the UI or the CSV.
- **Band derivation earned its place.** Every NR *neighbour* reported `mBands = []` — the modem
  supplies no band at all — yet the app showed n41 for each, derived from the ARFCN. This is
  precisely the case `BandMapping` exists for, confirmed live.
- Five to six NR neighbours reported, better than expected from an Exynos modem.

**Defect 1 — SS-SINR silently lost.** `getAllCellInfo()` returned `ssSinr = UNAVAILABLE` while the
`SignalStrength` callback carried `ssSinr = 21` for the same serving cell at the same instant. The
collector read only the former. This is the cellular twin of the Wi-Fi RSSI staleness bug, and SINR
is the KPI separating "strong signal" from "usable signal", so losing it silently is worse than
most alternatives. Fixed by overlaying `SignalStrength` for the **registered cell only** —
neighbours legitimately have no SINR and borrowing the serving value would fabricate a measurement.
Verified: app now reports 17 dB against a ground-truth 17 dB.

**Defect 2 — the modem contradicts itself.** A registered cell reported `mBands = [25]` while its
own NR-ARFCN mapped to 2606.55 MHz, which is n41; n25 spans 1930–1995 MHz. The neighbour list
showed the same PCI at ARFCN 396250 (1981.25 MHz, genuinely n25), so one of the two fields in that
record is stale or wrong and the handset does not say which.

Unresolvable from the device, so it is **not resolved** — it is surfaced. The band label carries a
warning marker and the UI explains that the band for that sample is unreliable. A report stating
"n25 at 2606 MHz" is internally incoherent and would destroy credibility faster than an admitted
uncertainty. The CSV keeps the plain band without the marker, because a symbol inside a categorical
field breaks grouping for analysis; the conflict stays derivable since `nr_arfcn` sits in the same
row.

Generalisable: **cross-check fields that should agree.** Band and channel number are derivable from
one another, so a mismatch is detectable — and detecting it is the difference between a tool that
reports what the modem said and one that reports what is true.

### Phase 5 build record — 2026-09-01 (written before validation; kept for the record)

Cellular collector built. **Nothing in it has met a live network.** The only part proven correct is
`BandMapping`, which is pure arithmetic and carries 14 unit tests.

What exists: `CellularCollector` (LTE and NR serving cell, neighbours, NSA/SA discrimination),
`BandMapping` (EARFCN and NR-ARFCN to band and centre frequency), `CellularCard`, the 31 cellular
CSV columns now populated, and `READ_PHONE_STATE` requested but not gated on.

**Band derivation is tested and correct.** 14 unit tests against 3GPP TS 36.101 table 5.7.3-1 and
TS 38.104 section 5.4.2.1. It exists because `getBands()` arrived at API 30 and is routinely empty —
the framework reports what the modem hands it, and the Exynos modem in the test handset is exactly
the kind that hands it nothing. The channel number is almost always present, so deriving the band
turns "unknown" into data precisely where the vendor path fails.

Two tests worth noting: NR-ARFCN 519000 resolves to 2595.0 MHz and n41 (the Margaritaville
question), and 1950 MHz is reported as ambiguous between n2 and n25 rather than guessed — the
channel number genuinely cannot resolve that overlap, and a confident wrong band in an acceptance
report is worse than an honest "or".

**A documentation error in this project, found by building against it.** The cellular API reference
originally recommended `ServiceState.getNetworkRegistrationInfo()` and
`NetworkRegistrationInfo.getNrState()` for NSA/SA discrimination. **Both are `@SystemApi`** and
unreachable from a normal app. It compiled-errored rather than failing at runtime, which was luck.

The working public-API path, now documented:

```
getDataNetworkType() == NETWORK_TYPE_NR                     -> 5G SA
getDataNetworkType() == NETWORK_TYPE_LTE && override is NR  -> 5G NSA
getDataNetworkType() == NETWORK_TYPE_LTE                    -> LTE
```

Neither signal suffices alone: `getDataNetworkType()` reports LTE under NSA because the device
genuinely is on the LTE anchor, and `getOverrideNetworkType()` is the marketing value that drives
the status-bar icon. Combined they are the best public discriminator available. The override is
logged separately so the icon can be compared against actual registration.

Generalisable lesson: **a planning document written from API documentation alone will contain
errors of this shape.** An API appearing in the docs is not proof an app can call it.

Separate `RsrpBucket` scale for cellular, deliberately not reusing the Wi-Fi `RssiBucket`. −70 dBm
is marginal on Wi-Fi and unremarkable on cellular; one shared scale would paint a healthy DAS red.

**Before the venue walk, in order:** SIM activates, local shakedown walk, cross-check serving RSRP /
RSRQ / SINR / band / PCI against Field Test Mode (`*3001#12345#*`), fix what that exposes. Only then
Margaritaville. Every prior collector produced believable wrong numbers on first contact with a real
network and none of them crashed while doing it.

### Phase 8b validation record — 2026-09-01

Stateless HTTP transport and OAuth 2.1 Resource Server. Both transports register tools from the
same `tools.register()`, so there is no per-transport tool code to drift.

**Statelessness verified, not assumed.** `stateless_http=True` only asserts a property of the code;
it holds because no tool retains anything between calls. Proof: a cold request carrying no prior
`initialize` and no session header succeeded on its own, and no `Mcp-Session-Id` was issued.

```
initialize                          -> 200, Mcp-Session-Id: (none)
tools/call, no init, no session id  -> 200, 4 sessions
analyze_coverage over HTTP          -> 87.1% compliance, 2 holes (matches stdio exactly)
```

**A role correction the blueprint blurred.** CIMD is an *Authorization Server* feature, not a
Resource Server one. Under CIMD a client hosts JSON metadata at a stable HTTPS URL and uses that
URL as its `client_id`, replacing the deprecated Dynamic Client Registration round-trip. The party
that fetches it is the AS during authorization; a Resource Server never sees a registration request
at all — only a token whose `client_id` claim happens to be a URL.

So the defensible claims are: Resource Server validating tokens from a CIMD-capable AS, handling
URL-shaped `client_id` values, and **deliberately not implementing an Authorization Server**. That
last is a decision — rolling your own OAuth AS is a known source of exploitable bugs, and real
deployments delegate to Auth0, Okta, Entra or Keycloak. The SDK offers `auth_server_provider` for
anyone who wants to; this does not use it.

Auth verified end to end:

```
no token    -> 401, WWW-Authenticate carries resource_metadata pointer
wrong token -> 401
valid token -> 200
RFC 9728 Protected Resource Metadata endpoint -> 200
```

Token validation pins algorithms (accepting the header's claim permits "alg":"none" and RS256->HS256
confusion) and checks audience against this server's own resource identifier per RFC 8707, so a
token minted for another resource cannot be replayed here.

Security posture: binds loopback by default and **refuses** to bind wider with no auth configured
rather than warning — an unauthenticated MCP server on 0.0.0.0 exposes every session file it can
read. The dev verifier requires two separate environment switches to construct.

Two SDK constraints found by hitting them:

1. `FastMCP` was renamed to `MCPServer` in SDK 2.x. The first implementation targeted 1.x and
   failed at import; migrated rather than pinning to a superseded version.
2. `token_verifier` cannot be supplied without `auth` settings. Reasonable — a server that
   validates tokens but publishes no Protected Resource Metadata gives a client no way to discover
   where to obtain one. This surfaced only because the dev-auth path was actually exercised; the
   auth code would otherwise have shipped untested.

### Phase 8a validation record — 2026-09-01

MCP server in `mcp-server/`, Python, MCP SDK 2.1.1, stdio transport. Verified with a real JSON-RPC
handshake against actual recorded sessions, not mocks:

```
initialize        -> server 'rf-test-app', protocol 2025-06-18
tools/list        -> 5 tools with correct JSON schemas
analyze_coverage  -> walk_baseline @ -75 dBm: 87.1% compliance, 2 holes
                     walk_baseline @ -70 dBm: 76.7% compliance, 1 hole
query_samples     -> speedtest filter: dl 192.896 / ul 39.64 Mbps at -38 dBm
error path        -> unknown session returns the error plus available sessions
```

Coverage-hole detection located the real thing: a 12-sample, 11.5 s run bottoming at −77 dBm at the
westernmost point of the driveway track, 6.0 m extent — the far end, furthest from the AP.

**Bucket distribution came out 50/18/24/24 — identical to the KML and GeoJSON exports.** That is a
third independent implementation of the same scale agreeing, which is the payoff for defining
`RssiBucket` once and mirroring it rather than reinventing it.

**The comparability caveat earned itself immediately.** Comparing the driveway walk against a
mostly stationary indoor session returned a +24 dB median delta and +12.9% compliance — which looks
like a dramatic network improvement and is almost entirely route artefact. The tool returns sample
counts, durations and an explicit caveat, and deliberately does not declare an improvement.

Architecture note: `session_store.py` carries all parsing and analysis with no MCP dependency;
`server.py` is a thin tool wrapper. The analysis is the valuable part and should not be reachable
only through a protocol.

SDK note: built against MCP Python SDK **2.x**, where `FastMCP` was renamed to `MCPServer`. The
first implementation targeted the 1.x API and failed at import; migrated rather than pinning to a
superseded version.

### Phase 6 validation record — 2026-08-31

Recording moved out of the Composable and into `RecordingService`, a foreground service with
`type="location"`. Measured on the Pixel 6 Pro:

| Test | Result |
|---|---|
| Survives tab switch | 11 rows -> 26 while sitting on the Sessions tab |
| Service type | `isForeground=true types=0x8` (LOCATION), notification with 1 action |
| Survives screen off | 26 -> 85 rows over 45 s with the screen off |
| Sample interval, screen off | 1.05 s mean, 2.0 s worst — no throttling |

Data quality with the screen off is the part that mattered, since rows being written is not the
same as rows being useful:

| Field | Screen ON | Screen OFF |
|---|---|---|
| wifi_rssi / bssid / channel / neighbour count | 100% | **100%** |
| lat / speed_mps / gps_accuracy_m | 96% | **100%** |
| Neighbour count, median | 31 | **32** |

Scan age cycled 0–29 s and RSSI kept varying, so scanning and the live-value query both continue
while dozing. Android does not throttle a foreground service with location type the way it
throttles a background app.

**Permission consequence worth remembering:** `ACCESS_BACKGROUND_LOCATION` is deliberately NOT
requested. A foreground service declared `type="location"` may access location while backgrounded
without it. That permission is for apps wanting location with no visible service, and asking for it
triggers a Play Store justification review and demo video for no benefit here.

`POST_NOTIFICATIONS` is requested but **not gated on** — without it the service still records, only
the notification and its Stop action are suppressed. Blocking every feature behind it would be
wrong. It needs its own request pass, because the combined request only fires when the *required*
permissions are missing, so an existing install would otherwise never be asked.

`onStartCommand` returns `START_NOT_STICKY` on purpose. Silently resuming after an OS kill would
append to a file the operator believes is finished, producing a session with an unexplained gap in
the middle.

Confirmed as correct behaviour, not a defect: `adb shell am start-foreground-service ... STOP` is
rejected with "Requires permission not exported from uid". The service is `exported="false"`, so
nothing outside the app can start or stop a recording.

**Verified by hand 2026-08-31:** the notification's "Stop and save" action ends the session and
finalises the file, and the threshold alarm fires — red breach card plus audible beep on the
5-second cooldown. Both needed manual test; neither could be driven reliably over adb against the
screen timeout.

Phase 6 is complete.

### Phase 4 validation record — 2026-08-31

Measured over Wi-Fi against speed.cloudflare.com:

```
Download 192.90 Mbps · Upload 39.64 Mbps
Latency 68.3 ms median (44.2 / 299.0) · Jitter 34.0 ms · Loss 0.0%
```

The result lands on exactly one CSV row — throughput is a point measurement, not a continuous one —
carrying the position and RF conditions it ran under:

```
note=speedtest, lat 26.0500xx, lon -80.1397xx, +/-4.5 m
TestAP-5G, -38 dBm, ch 161, 5 GHz, PHY tx 648 / rx 864 Mbps
```

That co-location is the point of the design. The Wi-Fi PHY rate dropped from 1134/1200 Mbps idle to
648/864 under load — rate adaptation a standalone speed-test app cannot show, sitting in the same
row as the throughput figure and the RSSI it was measured at.

Measurement decisions worth keeping:

- **TCP slow start is excluded.** The first 1.5 s of each transfer is discarded. A naive
  total-bytes/total-time figure understates throughput, and the faster the link the worse the error.
- **Parallel streams** — 4 down, 3 up. A single TCP stream is bounded by window/RTT, so on a
  high-bandwidth-delay link one connection cannot fill the pipe however fast the link is.
- **Upload sends random bytes, not zeroes.** A compressing middlebox or CDN would squash zeroes and
  report throughput the link cannot deliver.
- **Packet loss is ICMP or nothing.** `/system/bin/ping` works from the app on this device, so real
  loss is measured; where SELinux blocks it the field stays blank. HTTP request failures are never
  relabelled as packet loss — they are a different measurement and would put a wrong number in an
  acceptance report.

Two defects found and fixed:

1. **Cloudflare returns HTTP 403 above ~25 MB** (verified: 25 MB -> 200, 100 MB -> 403). The
   download requested 200 MB and got a 1-byte 403 body. Each stream now issues repeated 25 MB
   requests until the deadline.
2. **HTTP status was never checked, and stream failures were swallowed** by `runCatching`. That is
   why a 403 surfaced as an unexplained null rather than an error naming the cause. Status is now
   checked on both download and upload, and every stream failure is logged and propagated.

Schema note: `THROUGHPUT_COLUMNS` grew from 5 to 8 — `latency_min_ms`, `latency_max_ms` and
`speedtest_server` were added. Done now, before any file has left the building; the schema-stability
rule applies from first release onward.

### Phase 3 validation record — 2026-08-31

Session browser, track plot and exports, verified on the real driveway walk.

- **Track plot** renders the out-and-back correctly: strong green clustered at the office end,
  degrading through amber to orange at the far end of the driveway, both passes visible. Start and
  end markers distinguish an out-and-back from a one-way. Summary reads 116 rows / 116 geo-tagged /
  1:59 / RSSI −32 to −77 dBm (45 dB).
- **GeoJSON**: 117 features — 1 LineString of 116 vertices plus 116 Points. Coordinates correctly
  `[lon, lat]`. Full properties per point including `rssi_bucket` and `marker_color`.
- **KML**: well-formed, 6 styles, 117 placemarks. Colour conversion to KML's `aabbggrr` verified
  by hand — `ff327d2e` unpacks to RGB `2E7D32`, matching the app's green. All five buckets reverse
  correctly, which matters because getting it wrong swaps red and blue and renders strong signal
  as a problem area.
- **Cross-consistency**: KML style usage and GeoJSON bucket counts match exactly at 50 / 18 / 24 /
  24, and both derive from the same [RssiBucket] scale the on-screen plot uses. One session cannot
  tell three different stories.

Both exports are generated by reading the CSV back off disk rather than from an in-memory copy, so
what the client receives is provably built from the recorded bytes.

~~**Known limitation:** switching tabs stops an in-progress recording.~~ **Resolved in Phase 6** —
recording moved into `RecordingService`.

### Phase 2 validation record — 2026-08-31

Recorded live sessions on the Pixel 6 Pro and inspected the resulting files.

- 64-column CSV, every data row aligned to the header, ISO-8601 UTC timestamps at 1 Hz.
- Location, Wi-Fi and neighbour JSON populated; 38 columns correctly empty pending Phases 4 and 5.
- Recorder UI: row count, elapsed time, track distance; file lands in
  `Android/data/com.nhnengineering.rftest/files/sessions/`.

Four defects found and fixed. As in Phase 1, none of them crashed in a way that pointed at the
cause, and three would have quietly produced wrong numbers:

1. **Hardcoded blank-column count.** The row builder emitted 29 empty cellular cells against a
   31-column header. The assertion caught it — every row would otherwise have been shifted two
   columns. Fixed structurally: the schema is now built from named group lists and the row builder
   derives counts with `.size`, so header and row cannot disagree.
2. **`String.format` used the default locale.** On a comma-decimal locale this writes
   `26,050266`, splitting the field and corrupting the file — invisible on a US device. Now
   `Locale.US` explicitly.
3. **RSSI was frozen.** The `NetworkCallback` is push-based and fires on coarse capability changes,
   not per-RSSI update: 123 consecutive samples over 2m06s all read exactly −37 dBm while the OS
   reported −36/−37 concurrently. Fixed by reading volatile fields (RSSI, link rates, frequency)
   from a direct `getConnectionInfo()` query while keeping identity from the callback. After the
   fix, 48 samples spanned −33 to −38 and tracked the OS series. **This was the most serious bug
   found so far — RSSI is the primary KPI and it would have flatlined during a walk.**
4. **Track distance took four attempts and ended in a deliberate admission of uncertainty.**
   Ground truth: an 80 ft driveway (24.4 m), walked out and back — ~49 m. The app's own track
   independently derived 25.7 m one way, confirming the ground truth to within 5%.

   | Approach | Good-GPS walk | Degraded-GPS walk | Stationary |
   |---|---|---|---|
   | Position differencing, accuracy gate | 51 m (+4%) | — | **11 m in 69 s** |
   | Add Doppler speed gate >=0.5 m/s | 28 m (−45%) | — | 0 m |
   | Trapezoidal velocity integration | **44 m (−11%)** | 21 m (−57%) | 0 m |
   | Velocity, falling back to position when absent | 44 m (−11%) | 78 m (**+58%**) | 0 m |

   Position differencing fails because at 1 Hz a walking step is ~1 m while GPS accuracy is
   +/-6 m, so real movement is indistinguishable from jitter. Velocity integration — Doppler
   speed, the way GPS receivers actually do odometry — is immune to that and won on every metric
   until the second walk, where the receiver **stopped reporting velocity for 78 of 423 fixes**
   with accuracy degraded to +/-23 m. Treating those as zero lost the whole outbound leg; falling
   back to position differencing over the same stretch over-counted by 58%.

   **Neither number is defensible, so the app no longer picks one.** Velocity integration stands,
   fixes lacking velocity are counted, and when they exceed 10% the distance is shown as `~21 m`
   with `Distance*` and a note naming the percentage. Under-reporting a known amount beats
   inventing a plausible one — and this is a tool whose output goes into acceptance reports.

   Positions and RF data are unaffected by any of this; only the derived odometry is at issue.

Also measured, not a defect but a property worth knowing: the platform refreshes Wi-Fi RSSI roughly
every **3 seconds**. Logging at 1 Hz over-samples it, so the data carries ~3 s granularity
regardless of sample rate. Any report built on it should not imply finer time resolution.

### Phase 1 validation record — 2026-08-31

App reads live Wi-Fi on the Pixel 6 Pro, cross-checked against `adb shell cmd wifi status`:

| KPI | App | OS ground truth |
|---|---|---|
| SSID / BSSID | TestAP-5G / aa:bb:cc:dd:ee:11 | identical |
| RSSI | −40 dBm | −40 dBm |
| Frequency → band, channel | 5805 MHz → 5 GHz, **ch 161** | 5805 MHz |
| Channel width | 80 MHz | (matched via scan results) |
| Standard / security | 802.11ax / WPA3-SAE | 11ax / wpa3-sae |
| Tx / Rx / max Tx | 1134 / 1200 / 1200 Mbps | identical |
| Co-channel count | 1 | 1 AP confirmed on 5805 |
| Adjacent count | 0 | 5785 APs at −88, below the −85 floor |

Frequency-to-channel derivation is confirmed correct against hardware. Three real defects were found
and fixed in the process — all three are documented in the Wi-Fi API reference, and all three would
have silently corrupted exported data rather than crashing:

1. `WifiInfo.INVALID_RSSI` is `@hide`, not public API — build failure, caught at compile time.
2. `NetworkCallback` needs **`FLAG_INCLUDE_LOCATION_INFO`** or SSID/BSSID are blanked despite
   `ACCESS_FINE_LOCATION` being granted.
3. `getScanResults()` returns a **partial channel sweep**, not the full picture. Replacing the
   neighbour list wholesale made co-channel count read 0 with a co-channel AP at −37 dBm. Results
   now accumulate by BSSID with a 60 s retention window and per-AP age.

---

## 6a. Phase 8 — MCP server, and why it supersedes part of Phase 7


Phase 7 originally assumed a hardcoded PDF/XLSX report template. Exposing the session corpus as
Model Context Protocol tools is a better answer to the same problem: a fixed template answers the
questions we anticipated, while an agent over typed tools answers the ones a client actually asks
— "which sectors failed the −75 dBm threshold and by how much", "compare this walk against the
pre-tuning baseline".

It reads the same CSV files the app writes, through the same schema, so the server and the handset
cannot disagree about what a session contains — the same discipline that keeps the RSSI colour
scale defined once and shared by the plot, the KML and the QGIS style.

Two milestones: a local stdio server with the core tools, then optionally a stateless HTTP server
with CIMD registration per the MCP 2026-07-28 specification.

This phase also serves Jeremy's job search — it is the portfolio artifact for GSI AI-engineering
roles at Accenture and Deloitte. That is a genuine dual purpose, not a detour: the app gets the
better reporting architecture either way.

---

## 7. Open items

- ~~**Test handset**~~ — **resolved 2026-08-28: Google Pixel 6 Pro, updated to Android 17 (API 37).**
  Tensor G1 with a **Samsung Exynos 5123 modem** — not Qualcomm. Stock AOSP means framework
  behavior is textbook and ideal for development, but neighbor lists are sparse and NR SCG
  reporting under NSA is inconsistent. Build on it; validate field coverage on a Qualcomm Samsung
  before declaring any field unobtainable. See section 8 of the API reference.
- **SIM / cellular service — currently BLOCKING Phase 1 cellular validation.** Device is Wi-Fi
  only. With no SIM the modem does not register and `getAllCellInfo()` returns nothing usable, so
  no cellular KPI can be read or verified. Fastest unblock is an **eSIM data plan** — the Pixel 6
  Pro supports eSIM, so activation is minutes rather than shipping days.
- **Multi-carrier testing.** Pixel 6 Pro is one physical nano-SIM plus eSIM (DSDS — both
  registered, one data-active at a time). Enough to A/B two carriers by switching. Real
  multi-carrier benchmarking for client work eventually wants a SIM per major carrier.
- **NR SA validation** needs a network that actually runs SA — or a CBRS / Private 5G lab or client
  site. Most US macro 5G is still NSA on most bands.
- **Target SDK 37 migration** — deferred until after Phase 4. Needs `ACCESS_LOCAL_NETWORK` if the
  speedtest server is LAN-hosted, and a responsive dashboard for large screens. Scoped in
  `docs/android-17-impact-notes.md`.
- ~~**Version control**~~ — **local git initialised 2026-09-01**, first commit `608f04b`, 57 files
  on `main`. `.gitignore` correctly excludes `local.properties`, `build/` and `.gradle`.
  **Still to do:** push to a public GitHub repo, and decide where the design docs live (below).
- **Where the design docs live.** They are the portfolio differentiator — the engineering record is
  more compelling than the feature list — but they currently sit in OneDrive, outside the repo.
  Moving them in means one source of truth under version control; leaving them out means the repo
  reads as code-only. Copying them to both places recreates exactly the divergence problem that
  killed `src-staging/`. Decision needed before the repo goes public.
- **Product name**, and whether it carries NHN branding.
- **Reference tool** for validating our numbers in Phase 1 — Field Test Mode on the device, or an
  existing app such as Network Cell Info or NetMonster as a cross-check.
- Google Play developer account, $25 one-time — not needed until v1 ships.

---

## 8. Cross-domain notes

This tool is a credibility asset for both other tracks: a demonstrable 4G/5G KPI instrument
supports the Job Search Profile and is a differentiator for NHN's DAS / Private 5G / CBRS
positioning. Revisit `Consulting Business/` messaging once Phase 2 produces real field output.
