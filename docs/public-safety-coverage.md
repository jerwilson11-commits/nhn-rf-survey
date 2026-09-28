# Public Safety Coverage (ERRCS / ERCES)

Scoped and built 2026-09-28. See `C:\Users\jerwi\.claude\plans\mutable-jingling-squid.md` for the
full approved scoping plan this implementation followed.

## The finding that shaped this feature

ERRCS (Emergency Responder Radio Coverage System, per NFPA 72 / IFC 510) was flagged throughout
this project's own competitive research as a strong candidate feature — code-mandated, annually
re-tested, no incumbent tool covers it. Before building anything, this session researched the
actual code requirements rather than assuming them, and found something that reshapes the feature
significantly:

**Strict ERRCS is Land Mobile Radio (LMR) only, and a phone cannot measure it — a hard hardware
floor, not a software gap.** Traditional ERRCS coverage is VHF (150–174 MHz), UHF (450–512 MHz), or
700/800 MHz P25 digital/analog voice — the fire department's actual portable radio system. This
uses a completely different RF front end and protocol than a phone's 3GPP cellular or Wi-Fi radios.
Same category as this project's existing "no SNR" and "no spectrum analysis" findings elsewhere.
Real-world ERRCS testing uses a dedicated tuned signal meter, with readings logged roughly every 10
feet along paths emergency responders would use.

**But there is a real, measurable exception: FirstNet, Band 14.** NFPA 1225 — the newer, broader
standard some AHJs have adopted — introduces ERCES and permits cellular-based public-safety
coverage, specifically AT&T's FirstNet network on 3GPP Band 14/n14, to count toward compliance.
**Whether a given building's AHJ accepts this substitution is jurisdiction-specific and must never
be assumed** — traditional LMR remains the default/dominant requirement everywhere until confirmed
otherwise for a specific project. Band 14 is already recognized in this app's own band mapping,
labeled "700 FirstNet" for both LTE and NR (`BandMapping.kt`). A FirstNet-provisioned device
registering on Band 14 is measured by the exact same `SignalStrength` pipeline this app already
uses for every other band — no new radio-access code was needed for this track.

Signal thresholds are genuinely inconsistent across sources: −95 dBm downlink is the one figure
every source agrees on, but the required coverage percentage varies — IFC 510 is commonly cited at
95% general areas, while NFPA 72 sources are commonly cited at 90% general / 99% critical. Shipping
one hardcoded number as "the" requirement would be a wrong figure landing in a life-safety
compliance document, so the thresholds are configurable (`PublicSafetyThresholds`), not hardcoded.

## Two tracks, never merged into one number

**Track A — manual entry**, for real LMR-based ERRCS testing. The technician reads a dBm value off
their own tuned meter, taps a point on a floorplan (`Safety` tab), and enters the reading plus an
area classification (general/critical) and a free-text system/frequency label. This app never
measures or verifies the reading — it ingests it, the same way it already ingests a pasted NSG SIB1
decode. Structurally: `model/PublicSafetyCoverage.kt` (`ErrcsGridPoint`, `errcsCompliance()`),
`session/ErrcsGridStore.kt` (JSONL, mirrors `ProfileStore.kt`), `ui/ErrcsGridScreen.kt` (reuses
`FloorplanScreen`'s canvas).

**Track B — FirstNet Band 14, auto-measured.** Reuses the existing continuous-recording pipeline
unchanged for capture. The operator classifies the current area (general/critical) live via a
sticky control in Setup (mirrors `areaLabel`/`floor`), written to a new `errcs_area_class` CSV
column and to `RecordingState.errcsAreaClass`. At report time, `SessionStats.publicSafetyCoverage()`
filters the session to samples whose serving cell was Band 14 (LTE, written as `"B14"`) or n14 (NR,
written as `"n14"`, possibly joined with other NR bands as `"n41/n14"`), then computes compliance
per area class against the configured thresholds. A non-Band-14 sample is excluded regardless of
its signal strength — the one bug here that would actually be dangerous is silently counting a
commercial-band reading toward a public-safety compliance number, and it is covered by a dedicated
regression test (`PublicSafetyCoverageStatsTest.kt`).

**Naming matters and must not overclaim.** Both tracks report as "Public safety coverage," with
Track B's section explicitly stating it measures FirstNet/Band 14 under NFPA 1225's ERCES
framework — not a substitute for LMR-based ERRCS testing where the AHJ requires strict LMR.

## Report

Both tracks get their own sub-section in the PDF report (`PdfReportGenerator.kt`, "Public safety
coverage" section, right after the per-area breakdown), each with track-specific methodology
language. A session with data on only one track omits the other's sub-section entirely rather than
showing a misleading zero. A session with Band 14 samples that were never classified into an area
says so explicitly rather than silently omitting the section.

## What is NOT verified

**Track B has not been end-to-end verified against a real Band 14/n14 signal.** Neither test device
(OnePlus 9, Pixel 6 Pro) currently has an active FirstNet-provisioned SIM — the Pixel 6 Pro
supports Band 14 in hardware but has no active FirstNet service. The capture path is the same
`SignalStrength` pipeline already validated for every other band, and the `cellBand` string format
(`"B14"` / `"n14"`) is confirmed by reading `SessionReader.kt`/`SessionCsvWriter.kt` directly rather
than assumed, but a live device has never actually registered on Band 14 while this app was
recording. Track A needs no live radio state at all and is fully testable now — verified on-device
(data entry, floorplan tap-to-point, JSONL round-trip).

Unit-tested: both tracks' compliance math against synthetic data (`PublicSafetyCoverageTest.kt` for
Track A, `PublicSafetyCoverageStatsTest.kt` for Track B), including the Band-14-exclusion
regression test and a null-percentage-not-zero test for an untested area class.
