# Changelog

All notable changes to the RF Test App, its MCP server and its speedtest server.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning follows
[`PROCESS.md`](PROCESS.md) §3 — **appending a CSV column is MINOR; reordering or redefining one is
MAJOR**, because every recorded session in the corpus becomes ambiguous.

Defect references are register IDs from [`DEFECTS.md`](DEFECTS.md).

---

## [Unreleased]

Nothing yet. Next release is **v0.10.0**, target 2026-09-25 — see [`ROADMAP.md`](ROADMAP.md) §4.

### Added
- Product development process: [`ROADMAP.md`](ROADMAP.md), [`CHANGELOG.md`](CHANGELOG.md),
  [`DEFECTS.md`](DEFECTS.md) and [`PROCESS.md`](PROCESS.md), each owning one question exclusively so
  no fact is maintained in two places.
- Defect register backfilled with 19 entries, grouped by the five recurring root causes.
- GitHub issue and pull request templates carrying the definition of done.

---

## [0.9.0] — target 2026-09-11 · not yet tagged

Baseline release. Draws a line under continuous development from 2026-08-28 to 2026-09-04 —
44 commits, 73 source files, 219 unit tests — so subsequent work is measurable against it.

Nothing in this section is new work; it is the first inventory of what already exists.

### Added — measurement

- **Wi-Fi collector.** Serving-AP RSSI, BSSID/SSID, channel, band (2.4 / 5 / 6 GHz), channel width,
  802.11 standard, security, Tx/Rx link rates. Neighbour AP list with co-channel and
  adjacent-channel interference counts computed from **spectral overlap** — centre frequency and
  channel width, not channel-number comparison — so wide-channel overlap is counted and 2.4 GHz
  partial overlap is not missed.
- **Cellular collector (LTE / NR).** Serving-cell and neighbour collection: RSRP, RSRQ, RSSNR, CQI,
  SS-RSRP, SS-RSRQ, SS-SINR, PCI, CI/NCI, TAC, PLMN, timing advance, NSA/SA state. Validated
  2026-09-01 on live T-Mobile 5G SA.
- **Band derivation from ARFCN**, against 3GPP TS 36.101 and TS 38.104. Earns its place: every NR
  neighbour reported `mBands = []` on the test handset, yet the app labels each correctly. NR band
  ambiguity is carried into the label (`n2/n25`) rather than resolved silently.
- **GSCN and SSB layout derivation**, per band, distinguishing carriers by per-sector planning.
- **Position.** Platform `LocationManager`, GPS preferred with the fused provider as indoor
  fallback. Every fix records which provider produced it, because a fused fix may be derived from
  Wi-Fi and cell — which would make a Wi-Fi survey partly circular.
- **Floorplan mode.** Load a plan image, tap to place position. Coordinates normalised 0..1 to the
  image rather than stored in pixels, so they survive a different zoom, screen or re-export.
  Position is sticky, matching how an indoor walk actually goes.
- **Throughput.** DL/UL across parallel streams, latency (min/median/max), jitter, ICMP packet
  loss. TCP slow start excluded; upload sends random bytes so a compressing middlebox cannot
  inflate the result.
- **Walk throughput** — bursts during a moving session, and **spot check** for stationary
  assessment.
- **Carrier bandwidth and GPS fix age** recorded per sample.
- **TDD and SSB configuration profiles**, with cross-check of the observed layout against the
  recorded profile.

### Added — recording and storage

- **Streaming 73-column CSV**, one row per sample, flushed per row so a session killed mid-run
  keeps everything already written. Cellular columns were carried from the start, written empty,
  so a file recorded before the cellular collector landed stays directly comparable with one
  recorded after.
- **Foreground recording service** — sampling continues through backgrounding and screen lock.
- **Configurable pass/fail thresholds** per KPI with visual and audible alarms.
- **Session naming** by site, venue, sector, floor and area, with operator-entered floor labels.

### Added — export

- KML for Google Earth, GeoJSON for QGIS, with ready-made QGIS styles in [`qgis/`](../qgis/).
- **GeoPackage** export — the one format gap against the free field.
- **iBwave CSV** export for RF measurement sessions.
- One colour-scale definition feeds the handset plot, the KML and the QGIS symbology, so a session
  cannot tell three different stories.

### Added — reporting

- **PDF acceptance report**: bordered title block, per-band and per-area statistics, sector
  dominance, overlap percentage, per-cell best-server table, plot frame, colour legend, north
  arrow, scale bar snapped to a round number of metres, start/end markers.
- **Measurement cadence note**, built from the session's own numbers, stated wherever a session
  shows SS-SINR and SS-RSRQ changing at most half as often as RSRP — so the report never implies a
  simultaneity the instrument does not deliver.
- **Configuration profile section.**
- **Plain-language verdict** and a stabiliser that steadies the displayed verdict without ever
  touching the recorded measurement.
- Deliberately absent: **no north arrow or scale bar on floorplan plots**, because the plan is an
  image and not a georeferenced raster.

### Added — interfaces

- **Live view served to a connected laptop** during a walk, with satellite imagery and trail.
- Satellite map on the handset.
- **MCP server** over the session corpus — 5 typed tools, stdio transport, reading the same CSV
  through the same schema the app writes, so server and handset cannot disagree about what a
  session contains.
- **MCP stateless `streamable-http` transport with OAuth 2.1 Resource Server**, per the MCP
  2026-07-28 specification.
- **NHN speedtest endpoint** — because on a DAS job the meaningful test is against a server on the
  venue LAN, not the internet.

### Added — verification

- 219 unit tests, from zero before Phase 5. Weighted toward the project's actual failure mode:
  measurement arithmetic, schema structure, and standards-derived tables.
- **CI** — `testDebugUnitTest` on every push, one run per branch with superseded runs cancelled.

### Fixed

Nineteen defects, detailed in [`DEFECTS.md`](DEFECTS.md). The ones that changed the architecture:

- **RFT-D001** — Wi-Fi RSSI frozen at a stale value for 123 consecutive samples. Sources split:
  identity from the push callback, volatile numerics from a direct query.
- **RFT-D002** — interference counts silently zero, because `getScanResults()` returns one sweep of
  a subset of channels. Observations now accumulate by BSSID with a retention window and per-
  neighbour age.
- **RFT-D005** — 29 empty cellular cells written against a 31-column header, shifting every later
  field by two. Blank counts now derive from the column lists with `.size`.
- **RFT-D006** — an unlevelled cellular neighbour serialised as `0 dBm`, the strongest value in the
  file, which would have ranked an unmeasured cell above every real one in dominance analysis.
- **RFT-D008** — adding one privileged `TelephonyCallback` listener made the whole registration
  throw; the app kept running and silently lost NSA/SA detection.
- **RFT-D012** — third occurrence of stale-cache-read-as-current, this time in the `SignalStrength`
  fallback. Resolved by pulling at sample time; the underlying SINR constancy was later proven
  genuine by a moving walk.
- **RFT-D014** — per-cell detection counts exceeding the sample count, because observations were
  counted as samples and PCI is unique only within a carrier. Cells are now keyed by PCI **and**
  channel, which surfaced two carriers the merged view had hidden.
- **RFT-D019** — NR band ambiguity discarded on the neighbour path, mislabelling two US carrier
  bands in the one place a client reads them.

### Changed

- **Live screen rebuilt as a field instrument** rather than a settings page — the cards had been
  stacked in the order they were written.
- Track distance reports the figure it can compute, counts the fixes it could not, and marks the
  total approximate with the percentage, rather than emitting a defensible-looking wrong number
  (**RFT-D003**).
- Operator Stop during a throughput burst is no longer recorded as a network failure
  (**RFT-D017**).
- Walk bursts cut from two download streams to one, clearing eight consecutive HTTP 429s
  (**RFT-D018**).
- Engineering documentation moved into the repository, identifying data scrubbed.

### Known limitations

Stated in full in [`DEFECTS.md`](DEFECTS.md). In short: neighbour completeness is chipset-dependent;
handset dominance is a lower bound, so **finding overlap is evidence and finding none is not**; band
lock and test-mode entry are not implemented and will not be; track distance is approximate where
GPS supplies no velocity, and says so.

---

[Unreleased]: https://github.com/jerwilson11-commits/nhn-rf-survey/compare/v0.9.0...HEAD
[0.9.0]: https://github.com/jerwilson11-commits/nhn-rf-survey/releases/tag/v0.9.0
