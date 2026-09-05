# Roadmap

What is planned, in which release, by when, and what has to be true for it to count as done.

Owner: Jeremy Wilson · Revised: 2026-09-05 · Next revision: at `v0.10.0`

Phases 0–8b are complete and their engineering record stays in [`MASTER.md`](MASTER.md) §6.
Everything forward of 2026-09-05 lives here. Versioning and cadence rules are in
[`PROCESS.md`](PROCESS.md).

---

## 1. Where the project is today

44 commits · 73 Kotlin source files · 219 unit tests · 73-column session schema · validated on live
T-Mobile 5G SA, indoor and outdoor.

**Shipping capability:** Wi-Fi and cellular collectors, GPS and floorplan positioning, streaming
CSV, KML / GeoJSON / GeoPackage / iBwave export, throughput and latency, foreground recording with
thresholds and alarms, PDF reporting with dominance analysis, laptop live view, and an MCP server
over the session corpus with OAuth 2.1.

**What that is not yet:** a released product. Nothing is versioned, nothing is tagged, and the tool
has not produced a deliverable for a paying client. That is what the ladder below is for.

## 2. Release ladder

| Version | Theme | Target | Gate — the one thing that closes it |
|---|---|---|---|
| **v0.9.0** | Baseline — tag what exists | **2026-09-11** | A tagged build installs on the handset, records a session, and produces a PDF, with the version visible in the app |
| **v0.10.0** | Reporting complete | **2026-09-25** | A full acceptance deliverable — PDF **and** XLSX — generated from one real walk with no manual assembly |
| **v0.11.0** | Servers trustworthy | **2026-10-09** | MCP and speedtest servers have automated tests in CI, and the MCP server reads every session in the corpus |
| **v1.0.0-rc1** | Field candidate | **2026-10-23** | A full dry-run engagement: survey a venue end to end, produce the client pack, list every defect it exposed |
| **v1.0.0** | First engagement release | **2026-11-06** | **A deliverable from this tool has gone to a paying client** |
| **v1.1.0** | Platform currency | **2026-12-04** | targetSdk 37, running clean on Android 17 |
| **v1.2.0** | Spectrum module | **2027-01-29** | A geo-tagged sweep the modem cannot see, cross-validated against the app's own Wi-Fi collector |
| **v2.0.0** | Multi-tester platform | unscheduled | Two testers' sessions merge into one campaign view |

Dates are **release-train Fridays** on a two-week cadence. Scope moves out of a release; the date
does not move ([`PROCESS.md`](PROCESS.md) §4). A gate that cannot be met stops the release, and the
reason is recorded here.

---

## 3. v0.9.0 — Baseline · target 2026-09-11

Nothing new. The point is to draw a line under nine days of continuous development and make the
next nine measurable against it.

| # | Item | Done when |
|---|---|---|
| 0.9-1 | Adopt versioning: `versionCode`/`versionName` set, scheme documented | Done 2026-09-05 — `app/build.gradle.kts` carries `0.9.0` / `versionCode` 9, governed by [`PROCESS.md`](PROCESS.md) §3 |
| 0.9-2 | Version and build date visible in the app and stamped into the PDF footer and CSV header | A session file identifies the build that wrote it — **remaining work for this release** |
| 0.9-3 | Backfill [`CHANGELOG.md`](CHANGELOG.md) from the 44-commit history | Done 2026-09-05 — every shipped capability appears under `0.9.0` |
| 0.9-4 | Backfill [`DEFECTS.md`](DEFECTS.md) | Done 2026-09-05 — 19 entries |
| 0.9-5 | Tag `v0.9.0` on `main` | Tag exists, CI green on that commit |
| 0.9-6 | Reconcile [`MASTER.md`](MASTER.md) §7 open items against reality | Done 2026-09-05 — the SIM item had read "currently BLOCKING" for four days after Phase 5 validated on a live network |

**Why 0.9 and not 1.0:** the version number is a claim about field-proven-ness, and this tool has
not yet carried an engagement. See the `v1.0.0` gate.

## 4. v0.10.0 — Reporting complete · target 2026-09-25

The reporting layer is the deliverable. It is also the subsystem with the most recent S1s
([D014](DEFECTS.md#rft-d014--detection-counts-larger-than-the-sample-count),
[D015](DEFECTS.md#rft-d015--report-text-clipped-at-the-right-page-edge)) and the one where a wrong
number reaches a client fastest.

| # | Item | Done when | Est. |
|---|---|---|---|
| 0.10-1 | **XLSX export** — per-sample sheet, summary statistics, per-band and per-cell tables | Opens in Excel with correct types; numbers reconcile against the PDF from the same session | 3 d |
| 0.10-2 | Report cover configuration — client, site, engineer, date, logo | An operator produces a branded report without editing code | 1 d |
| 0.10-3 | Reconciliation test: PDF and XLSX from one session agree on every shared figure | A test fails if the two renderers ever diverge | 1 d |
| 0.10-4 | Threshold profiles per engagement type (DAS acceptance, CBRS, Wi-Fi) | Thresholds are selected, not retyped, and the profile is named in the report | 1 d |
| 0.10-5 | Read the rendered XLSX as a human before release | Confirmed by observation, per [`PROCESS.md`](PROCESS.md) §5.3 | 0.5 d |

**Risk:** the report layer has broken on first contact with reality every time. Budget a full
render-and-read cycle, not a test run — *a test that passes on synthetic input tells you the
function is right; only real output tells you the input was.*

## 5. v0.11.0 — Servers trustworthy · target 2026-10-09

`mcp-server/` and `speedtest-server/` are ~750 lines of Python with **no automated tests at all**,
against 219 for the app. The MCP server is the portfolio artifact and reads client data.

| # | Item | Done when | Est. |
|---|---|---|---|
| 0.11-1 | Pytest suite over `session_store.py` and `tools.py` | Coverage of every tool, including the empty-corpus and malformed-row cases | 3 d |
| 0.11-2 | Extend CI to run the Python suite alongside the Android tests | One workflow, both suites, green | 0.5 d |
| 0.11-3 | Corpus compatibility test — server reads every session ever recorded | A schema addition cannot silently break the reader | 1 d |
| 0.11-4 | `analyze_coverage` KPI-selection regression pinned | The Wi-Fi/cellular threshold confusion (−75 vs −105 dBm) cannot return | 0.5 d |
| 0.11-5 | Speedtest server: NHN endpoint documented and deployable from the repo | A venue LAN instance stands up from `speedtest-server/README.md` alone | 1 d |

## 6. v1.0.0-rc1 — Field candidate · target 2026-10-23

A dry run of a real engagement, treated as a test of the whole system rather than of any feature.

| # | Item | Done when |
|---|---|---|
| rc1-1 | Survey one venue end to end — indoor and outdoor, cellular and Wi-Fi | A complete session corpus for a real site |
| rc1-2 | Produce the full client pack from that corpus with no manual assembly | PDF, XLSX, KML, GeoPackage, all self-consistent |
| rc1-3 | Log every defect the run exposes in [`DEFECTS.md`](DEFECTS.md) | The register grows — if it does not, the dry run was not adversarial enough |
| rc1-4 | Cross-check one headline figure against an independent implementation | As done for dominance on 2026-09-02: Python straight from the CSV, agreeing to within an explained row |
| rc1-5 | Fix every S1 and S2 the run exposes | Register shows none open |

**This is the release that decides the 1.0 date.** If rc1 exposes an S1, `v1.0.0` moves and the
reason is written here.

## 7. v1.0.0 — First engagement release · target 2026-11-06

**Gate: a deliverable produced by this tool has gone to a paying client.** Not a demo, not a
portfolio piece — a report someone made a decision on.

| # | Item | Done when |
|---|---|---|
| 1.0-1 | Product name decided, and whether it carries NHN branding | Named in the app, the report and the repo |
| 1.0-2 | Every S1/S2 in the register closed or explicitly accepted with the limitation stated in output | Register reviewed and signed off |
| 1.0-3 | Operator documentation — a field procedure someone other than the author can follow | A second person records a valid session unaided |
| 1.0-4 | Release build configuration reviewed (`optimization.enable` is currently `false`) | A release APK is built, installed and exercised |
| 1.0-5 | Signing keys generated and stored securely | Reproducible signed build |

**Not in 1.0:** Google Play distribution. The $25 developer account is not needed until the tool is
distributed beyond NHN, and store review adds a constraint the consulting use does not need.

## 8. v1.1.0 — Platform currency · target 2026-12-04

Deferred since Phase 4 and scoped in [`android-17-impact-notes.md`](android-17-impact-notes.md).
It becomes urgent the moment a venue LAN speedtest endpoint is the normal case.

| # | Item | Done when |
|---|---|---|
| 1.1-1 | `targetSdk` 36 → 37 | Builds and runs clean on Android 17 |
| 1.1-2 | `ACCESS_LOCAL_NETWORK` permission for LAN-hosted speedtest | A venue-LAN test works on API 37 |
| 1.1-3 | Responsive dashboard for large screens | Usable on a tablet under API 37 orientation enforcement |
| 1.1-4 | Multi-carrier A/B via DSDS SIM switching | One walk, two carriers, comparable output |
| 1.1-5 | Validate neighbour coverage on a Qualcomm handset | Confirms which sparse fields are Exynos-specific, not Android-wide |

## 9. v1.2.0 — Spectrum module · target 2027-01-29

**HackRF One as an RX-only accessory.** Scoped 2026-09-05 after evaluation; the reasoning is in
[`MASTER.md`](MASTER.md) §3.

The modem can only report on what it is attached to. A spectrum sweep sees energy no handset
can — uplink noise, PIM, external interferers, CBRS occupancy, and RF-layer confirmation that a
remote is actually radiating.

| # | Item | Done when |
|---|---|---|
| 1.2-1 | USB-OTG transport and sweep capture | A geo-tagged sweep is recorded alongside a normal session |
| 1.2-2 | Sweeps stored as their own artifact stream, joined by timestamp + position | The 73-column schema is unchanged — a 1024-bin sweep is not a CSV row |
| 1.2-3 | Derived scalars folded into the existing envelope — channel power, noise floor, occupancy % | Map, export, thresholds and alarms work on them with no new plumbing |
| 1.2-4 | Cross-validation against `WifiCollector` | A 2.4 GHz sweep and the app's own AP list agree on what is on air |
| 1.2-5 | Per-band calibration table, versioned, or absolute power is not reported | Uncalibrated output is exported as relative/occupancy only, labelled as such |

**Explicitly excluded:** transmit, and per-PCI RSRP scanning. Transmit on licensed spectrum is not
ours to do, and conducted CW testing is better served by a calibrated bench source. Per-PCI
scanning is a different instrument class — 8-bit dynamic range and no preselector will not survive
a −40 dBm serving cell in a venue. [`MASTER.md`](MASTER.md) §3 already concludes *buy, do not
build* for scanner-class capability.

**Why it sits at 1.2 and not sooner:** it adds an NDK/JNI dependency, breaking the project's
zero-dependency posture, and it must not compete with the reporting work that turns the tool into
revenue.

## 10. v2.0.0 — Multi-tester platform · unscheduled

Deliberately undated. Scheduling it before `v1.0.0` earns revenue would be planning the wrong
problem.

Cloud sync · multi-tester campaigns · server-side heatmaps · client dashboards · iOS companion for
speedtest, GPS logging and viewing Android-collected data.

---

## 11. Sprint schedule

One week, Monday to Sunday. Release cut on the Friday closing every second sprint.

| Sprint | Dates | Focus | Closes |
|---|---|---|---|
| S1 | 2026-09-07 → 09-13 | Versioning, changelog backfill, tag | **v0.9.0** (Fri 09-11) |
| S2 | 2026-09-14 → 09-20 | XLSX export, report configuration | |
| S3 | 2026-09-21 → 09-27 | Reconciliation tests, render-and-read | **v0.10.0** (Fri 09-25) |
| S4 | 2026-09-28 → 10-04 | Python test suite, CI extension | |
| S5 | 2026-10-05 → 10-11 | Corpus compatibility, speedtest server docs | **v0.11.0** (Fri 10-09) |
| S6 | 2026-10-12 → 10-18 | Dry-run venue survey | |
| S7 | 2026-10-19 → 10-25 | Defect burn-down from the dry run | **v1.0.0-rc1** (Fri 10-23) |
| S8 | 2026-10-26 → 11-01 | Naming, operator docs, release build | |
| S9 | 2026-11-02 → 11-08 | S1/S2 closure, signing | **v1.0.0** (Fri 11-06) |

## 12. Risks

| Risk | Effect | Response |
|---|---|---|
| **No paying engagement lands by early November** | `v1.0.0`'s gate cannot be met by definition | Ship `v0.11.x` and hold at rc. Do **not** relabel a dry run as the gate — the version number is the claim |
| **The dry-run survey exposes an S1** | 1.0 slips | Expected, not exceptional. Every subsystem has produced believable wrong numbers on first contact. rc1 exists to absorb this |
| **Solo capacity** | Everything competes with consulting delivery | Two-week trains with scope moved out, never dates moved back. A short release is a release |
| **Reporting is the highest-value and highest-risk area** | An S1 here reaches a client fastest | Every report change gets a human render-and-read, not a test run |
| **Chipset-specific behaviour concluded as Android-wide** | Fields wrongly declared unobtainable | 1.1-5 validates on a Qualcomm handset before any such conclusion is recorded |
| **Spectrum module absorbs attention early** | Reporting slips, revenue slips | Held at 1.2, behind the 1.0 gate, deliberately |
