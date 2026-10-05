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

## 2026-10-04 enhancements

Four enhancements, all offline-testable (no live radio needed), built after confirming the original
feature above was already shipped. Order and scope agreed with Jeremy.

1. **Live "FirstNet · Band 14" badge.** `CellularSample.onFirstNetBand14` was only surfaced in the
   Setup area-class control and the report. A shared `FirstNetBand14Badge` composable
   (`ui/CellularCard.kt`) now shows it at a glance in the FieldDashboard hero and the cellular card
   whenever the serving or strongest-observed cell is Band 14 (LTE) or n14 (NR). It identifies the
   band only — it does not assert an AHJ accepts the Band 14 substitution (that stays here, in the
   Safety-tab reporting).

2. **Grid method (NFPA "20-square").** `errcsGridCompliance()` / `errcsGridCell()` grade occupied
   grid *squares* rather than individual readings: a square passes only if every reading in it passes
   (worst-case), and compliance is passing squares over tested squares. The by-point figure is kept
   alongside it — neither is assumed, because AHJs differ on which they require. `FloorplanCanvas`
   gained an optional `GridOverlay` drawn in the plan's own pan/zoom space (squares lock to the floor
   as it zooms); the Safety tab has a grid on/off switch and per-floorplan rows/cols steppers, and
   squares shade green/red/unshaded by verdict. Dimensions persist per floorplan in
   `session/ErrcsGridConfigStore.kt` (4×5 default = 20 squares), and the PDF report aggregates
   grid-method compliance across the session's floors (each floor on its own saved grid).

3. **Two-way (inbound/outbound).** `ErrcsGridPoint.signalDbm` is now explicitly the outbound/downlink
   (talk-out) reading, and an optional `inboundDbm` carries the uplink/talk-in reading. ERRCS
   requires two-way coverage, so a point with an inbound reading passes only if BOTH directions clear
   the dBm floor. A missing inbound is "not measured", never a failure.

4. **DAQ.** `ErrcsGridPoint.daq` (TSB-88 1.0–5.0) is recorded optionally, and
   `PublicSafetyThresholds.minDaq` (null by default) grades it. A point fails on DAQ only when a
   threshold is set AND a DAQ value was recorded — recording DAQ without a threshold keeps it as
   documentation without changing pass/fail. Common objectives surfaced in the UI: DAQ 3.0 (ERRCS
   acceptance), DAQ 3.4 (TSB-88 wide-area P25).

5. **Designate critical areas (paint squares).** Critical vs general is really an *area* the AHJ
   designates (fire command center, fire pump rooms, interior exit stairs/passageways, elevators +
   lobbies, standpipe cabinets, sprinkler sectional valves, areas of refuge, + anything else the AHJ
   names) — not a per-reading judgement. The Safety tab now has a **Add reading / Mark critical**
   mode switch: in Mark mode, tapping a grid square toggles it as a critical area (blue outline).
   Readings whose location falls in a designated area are graded **critical** regardless of their own
   tag (`ErrcsGridPoint.effectiveAreaClass` — designated areas are authoritative and never downgraded;
   the per-point tag can still raise a reading to critical *outside* a designated area). Critical
   areas are stored per floorplan as **normalised regions** (`session/ErrcsCriticalAreaStore.kt`), not
   grid-cell indices, so a designation survives the tester changing the sampling grid. The add-reading
   dialog pre-selects and locks Critical when the tap lands in a designated area, point/grid
   compliance and the PDF report all grade by the effective class, and `passesAs(class, thresholds)`
   applies the correct dBm floor for the effective class. Workflow fit: on a design-validation or
   annual-certification walk the critical areas are already AHJ-approved (often marked in the iBwave
   design / PDF), so the tester just paints the squares to match before walking.

6. **Coverage area + grid-within (Phase A).** The NFPA grid must cover the floor, not the whole PDF
   page. The operator traces the floor outline as a polygon, corner by corner, with the crosshair (the
   georeference-style aim-then-confirm — more accurate than finger taps). Shared per floorplan
   (`model/CoverageArea.kt`, `session/CoverageAreaStore.kt`, `ui/CoverageAreaEditor.kt`) and offered on
   **both** the P. Safety tab (grid method) and the Plan tab (cellular). The grid method became
   square-centric and coverage-aware (`errcsGridSquares`/`errcsGridCompliance`): grids lay over the
   polygon's bounding box, only squares whose centre is inside the polygon are testable, and compliance
   is graded over **all** testable squares (an untested square counts against — a valid 20-grid result)
   with `testedPct`/`complete` reporting how much has been walked (recommendations 1 & 2). Because the
   ~20-grid minimum applies to the floor and not the bounding box, saving a coverage area **auto-fits**
   the grid (`fitErrcsGrid`) so ≥ 20 squares land inside the polygon; a "Fit ~20 grids" button and an
   "only N inside" caution keep it enforced, while the Cols/Rows steppers still override. The PDF adds
   "Within the traced coverage area, x dBm was met in x% of samples" (indoor samples inside the polygon
   only).

7. **Georeference sizing (Phase B).** When a floor is georeferenced, the coverage polygon yields real
   numbers. A similarity transform scales area by `metresPerPixel²`, so floor area (m² → ft²) is the
   polygon's pixel area × `metresPerPixel²` (`coverageAreaSquareMetres`), and each grid square's real
   size (`errcsGridCellSize`) is checked against the **NFPA 80-ft maximum grid dimension**. Surfaced in
   a "Floor sizing" card on the P. Safety tab (floor area ft²/m², per-grid ft, an 80-ft warning) and in
   the report (floor/building ft² across floors, and an 80-ft flag per floor). All sizing maths is pure
   and unit-tested (`CoverageAreaSizingTest.kt`); the georeference itself comes from
   `BuildingStore.geoReferenceFor`.

8. **Multiple coverage regions per floor.** A floor can hold more than one closed area (a main
   building plus a detached outbuilding that are separate on this floor, connected only on another —
   seen on the Margaritaville floor-2 page). Coverage is a **list of `CoverageRegion`** per floorplan
   (`session/CoverageAreaStore.kt`, region-per-line, migrates the old single-polygon format), each with
   its own polygon and its own grid. Each region is auto-fit to ≥ 20 grids and graded on its own grid;
   the floor rolls them up (`errcsFloorGridCompliance`) and sums their areas — so a small outbuilding
   gets its own adequate sample rather than being swamped by a floor-wide grid. The P. Safety tab lists
   regions (add / select-to-edit-its-grid / delete); `FloorplanCanvas` draws several polygons and grid
   overlays at once.

9. **Auto-detect the outline (`map/CoverageOutlineTrace.kt`, pure + unit-tested).** iBwave exports draw
   the coverage boundary as a bold closed loop in an engineer-chosen colour. "Detect" lets the operator
   tap that line; the tracer samples the colour, isolates the connected loop at the tap, fills it, traces
   the outer contour (Moore-neighbor) and simplifies (Douglas–Peucker) to a polygon. A detached
   neighbour is a separate component and is left alone; a grey tap or a page-engulfing fill is rejected.
   `map/CoverageOutlineDetector.kt` wraps it for Android bitmaps (off-thread, downscaled). Best-effort —
   the result is an editable/deletable region, never committed blind.

10. **Shared floorplan picker + map-at-top.** `ui/FloorplanPickerCard.kt` (import, "✓ georeferenced"
    marker, Use, Delete) is shared by the Plan and P. Safety tabs so they can't drift. The P. Safety tab
    now renders the selected floor's map at the top, with the picker and thresholds below.

**Preliminary, not a certification.** This output is a **preliminary coverage assessment** — confirm
requirements with the AHJ; it is not an AHJ-submittable certified report.

**Backward compatibility:** the new `ErrcsGridPoint` fields are optional and the store's parser reads
a missing key as null, so grid points written before this change load unchanged (covered by a
regression test in `ErrcsGridStoreTest.kt`).

**Testing status unchanged for Track B.** These enhancements are all Track A (manual ingestion) plus
the band badge; none required a live Band 14/n14 signal. Track B's live-signal validation is still
open and still blocked on an active FirstNet (or commercial AT&T, which also uses Band 14) SIM —
that remains the one outstanding verification for the public-safety feature as a whole.

Unit-tested additions: inbound two-way grading, DAQ grading (threshold-and-value gating),
grid-cell bucketing with far-edge clamp, worst-case square pass/fail, null-not-zero for an untested
class under the grid method, and round-trips for both `ErrcsGridStore` (incl. old-format records) and
`ErrcsGridConfigStore`.
