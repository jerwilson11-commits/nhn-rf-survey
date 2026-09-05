# Defect Register

Every defect that reached a commit, with the guard that stops it recurring.

Severity is assigned by effect on a client deliverable, not by fix difficulty — see
[`PROCESS.md`](PROCESS.md) §8. **S1 is the project's characteristic failure: a plausible wrong
number that reaches a report.** A crash is usually S3, because it announces itself.

Status: `FIXED` · `MITIGATED` (best available answer, limitation stated in output) · `OPEN`

---

## Recurrence — the reason this register exists

Individually these were nineteen bugs. Grouped by root cause they are **five mistakes made
repeatedly**, which is only visible from a register:

| Root cause | Occurrences | Standing rule |
|---|---|---|
| **Stale cache read as current** | D001, D011, D012 — Wi-Fi RSSI, Wi-Fi identity, cellular SINR | A push cache answers "what did I last hear"; a pull answers "what is true now" |
| **Missing measurement treated as zero** | D003, D006 | A missing measurement is not a zero |
| **Privileged API assumed public** | D007 (three occurrences within itself) | `dumpsys` runs as shell; what it prints is not what an app may read |
| **Output never looked at** | D009, D014, D015, D016 | A test that passes on synthetic input tells you the function is right; only real output tells you the input was |
| **Ambiguity resolved silently** | D006, D014, D019 | Where the data does not decide, the output must say so — never pick one and present it as fact |

Every subsystem in this project has produced believable wrong numbers on first contact with
reality: Wi-Fi RSSI, Wi-Fi identity, GPS distance, cellular SINR, the report layer, and the laptop
map. **Assume the next one will too.**

---

## Register

### RFT-D001 — Wi-Fi RSSI frozen at a stale value
**S1 · FIXED · Phase 1**

123 consecutive samples read exactly −37 dBm while the OS reported values varying between −36 and
−37 over the same window. Android's `NetworkCallback` is push-based and fires on coarse capability
changes, not per-RSSI update, so the cached value can be minutes stale.

*Found by* sampling the OS in parallel (`adb shell cmd wifi status`) and comparing series.
*Fix:* split the sources — identity from the callback, volatile numerics from a direct query.
*Guard:* pull-at-sample-time for anything volatile; where a value must come from a push, record its
age.

### RFT-D002 — Interference counts silently read zero
**S1 · FIXED · Phase 1**

Co-channel count reported 0 with a co-channel AP sitting at −37 dBm. `getScanResults()` returns
only the most recent scan and the OS routinely sweeps a subset of channels — observed AP counts of
22, then 13, then 4, then 14, in one location, stationary, within two minutes.

A zero meaning "not observed this sweep" is indistinguishable from a zero meaning "clean channel" —
the worst available failure mode for a tool whose output goes in a report.

*Fix:* accumulate observations by BSSID with a retention window; stamp every neighbour with its own
age.

### RFT-D003 — Track distance, four attempts, ending in a refusal
**S1 · MITIGATED · Phase 2**

Position differencing with an accuracy gate read 28 m against 49 m of tape-measured ground truth
(−45%). Removing the gate recovered the walk but accumulated 11 m of phantom distance in 69 seconds
standing still. Integrating Doppler velocity — how GPS receivers actually do odometry — got within
−11%. A second walk then showed the receiver dropping velocity entirely for 78 of 423 fixes, where
treating null as zero lost the whole outbound leg and falling back to position differencing
over-counted by 58%.

*Why it is MITIGATED, not FIXED:* neither number is defensible. The app reports the figure it can
compute, counts the fixes it could not, and marks the total approximate with the percentage.
**Under-reporting a known amount beats inventing a plausible one.**

*Note:* the 2026-09-02 walk saw 430 of 431 fixes carry velocity and was not flagged approximate.
The mitigation is correct; it simply did not need to fire.

### RFT-D004 — Locale-dependent CSV corruption
**S2 · FIXED · Phase 2**

`String.format("%.6f", lat)` on a comma-decimal locale emits `26,0500` — splitting the field and
shifting every subsequent column. Invisible on a US device, silently destroys every file elsewhere.

*Guard:* `CsvSchemaTest` asserts locale-sensitive numbers use a decimal point.

### RFT-D005 — CSV column count drift
**S2 · FIXED · Phase 2**

The row builder emitted 29 empty cellular cells against a 31-column header, shifting every later
field by two. A runtime assertion caught it only after the app had written a session and crashed.

*Fix:* blank counts derived from the named column lists with `.size`, so header and row are
structurally unable to disagree.
*Guard:* `CsvSchemaTest`, 5 tests, including the all-null case — which is the one that actually
shifted.

*Known gap:* a transposition is still not caught. RSRQ values written into the RSRP column would
pass every test. This is why the emission order sits directly beneath the column list in the source
rather than elsewhere in the file.

### RFT-D006 — Null-as-zero in the cellular neighbour JSON
**S1 · FIXED · 2026-09-01**

`cellNeighborsToJson` wrote `"rsrp":${n.rsrpDbm ?: 0}`. A neighbour the modem reported without a
level was serialised as **0 dBm — the strongest value in the file.** Any best-server or dominance
calculation built on top of it would have ranked an unmeasured cell above every real one.

*Fix:* emit JSON null, parse back as null, exclude unlevelled cells from the analysis, and report
how many were excluded.
*Found by* writing the consumer before shipping the producer — not by a walk producing impossible
numbers, which is how [D003](#rft-d003--track-distance-four-attempts-ending-in-a-refusal) was
found.

Second occurrence of the same mistake as D003. This is what produced the standing rule.

### RFT-D007 — The `@SystemApi` trap
**S3 · FIXED · 2026-09-02**

`PhysicalChannelConfig` and `LinkCapacityEstimate` both appear in `dumpsys` and both require
`READ_PRECISE_PHONE_STATE`, a privileged permission. The second was recorded as "confirmed
available" *because dumpsys printed it*. Third occurrence of this reasoning error.

*Cost:* planning time, not wrong data — hence S3.
*Guard:* standing rule. Verify against the permission an app can hold, never against what shell can
print.

### RFT-D008 — A regression caused by adding one listener
**S1 · FIXED · 2026-09-02**

All listeners on a single `TelephonyCallback` share one registration, so adding a privileged
listener made the whole registration throw. The app kept running and **silently lost NSA/SA
detection and its `SignalStrength` fallback.**

*Fix:* a privileged listener is registered separately, so its failure cannot take working ones
down.

### RFT-D009 — Clipped ARFCN
**S1 · FIXED · 2026-09-02**

The KPI tile rendered 521310 as "52131". Compose clips without an ellipsis by default, so it was
not a visibly truncated number on screen — it was **a different, plausible one**, feeding the
frequency and GSCN derivations.

*Found* only by finally looking at the screen, after three UI commits had gone in unverified while
the phone was locked.
*Guard:* definition of done requires a human to observe anything with a visual surface on the
handset ([`PROCESS.md`](PROCESS.md) §5.3).

### RFT-D010 — A patch that reported success while doing nothing
**S3 · FIXED · 2026-09-02**

A scripted edit searched for `out =` at a call site that passes positionally, matched nothing,
changed nothing, and printed a success message. Build passed, tests passed, and the new report
section simply never appeared.

*Guard:* a scripted patch that cannot find its target must fail loudly. Tooling defect, not product
— recorded because it cost a full verification cycle.

### RFT-D011 — SS-SINR silently lost
**S1 · FIXED · 2026-09-01 (Phase 5 validation)**

`getAllCellInfo()` returned `ssSinr = UNAVAILABLE` while the `SignalStrength` callback carried
`ssSinr = 21` for the same serving cell at the same instant.

### RFT-D012 — Staleness, third occurrence
**S1 · FIXED · 2026-09-01**

The `SignalStrength` fallback added for [D011](#rft-d011--ss-sinr-silently-lost) was itself
measured pinning **SS-SINR to 18 and SS-RSRQ to −10 across 24 consecutive samples** while SS-RSRP
moved across four values. The callback never fired. Replacing "missing" with "stale" is an
improvement and still not honest.

*Fix:* pull `TelephonyManager.getSignalStrength()` at sample time, matching the Wi-Fi fix.

*Resolution of the underlying question:* the pull did not change the result, leaving it inconclusive
while stationary. The 2026-09-02 walk settled it — SS-SINR changed 19 times against RSRP's 88 and
held one value for 99 consecutive samples, so **the constancy is genuine, not cached.** SS-SINR and
SS-RSRQ are live but not sample-synchronous with RSRP, and the report now states the per-field
cadence wherever a session shows the disparity.

### RFT-D013 — Cellular neighbours displayed but never logged
**S2 · FIXED · 2026-09-01**

The schema had `wifi_neighbors_json` and no cellular equivalent, so everything on the neighbour
panel vanished when the sample was written.

*Fix:* added `cell_neighbor_count` and `cell_neighbors_json`. Schema 71 → 73 columns.

### RFT-D014 — Detection counts larger than the sample count
**S1 · FIXED · 2026-09-01 (first PDF read by a human)**

The per-cell table reported PCI 216 as detected in **36 samples out of 28 analysed.** It counted
cell *observations*, not samples: eight rows report the same PCI on two channels simultaneously, so
29 rows produced 37 observations.

Fixing it exposed a larger problem. **PCI is unique only within a carrier** — 504 values for LTE,
1008 for NR — so the same PCI on two channels is two different physical cells, and grouping by PCI
alone merges them silently.

*Fix:* cells keyed by **PCI and channel together.** The result is better information, not merely
correct information — the same session now shows PCI 216 and 865 present on two carriers, which the
merged view hid completely.
*Guard:* a test stating the property the broken version violated (detection rate can never exceed
100%), and one reproducing the two-channel case from the real session.

### RFT-D015 — Report text clipped at the right page edge
**S3 · FIXED · 2026-09-01**

Three explanatory paragraphs ran off the page — including, with some irony, the one explaining why
detection rate must be shown. They were passed to a helper that does not wrap.

*Fix:* `Ctx.para` wraps on measured width (`Paint.measureText`) rather than the 96-character guess
the methodology page used.
*Guard:* verified programmatically — zero text spans cross the right margin on any page.

### RFT-D016 — Satellite imagery never appeared on the laptop live view
**S3 · FIXED · 2026-09-02**

The tile route served correctly and the trail drew, so nothing looked broken. The page divided tile
indices by `2^z` before passing them to a projection that already scales from zoom z — every tile
landed sub-pixel at the origin.

*Why it survived:* the Kotlin map on the handset does the same projection correctly. **Two
independent implementations of one projection, only one of them checked** — the phone was verified
visually and the laptop assumed.

### RFT-D017 — Operator Stop recorded as a network failure
**S2 · FIXED · 2026-09-02**

A throughput burst still transferring when the operator pressed Stop was written into the data as a
failure: `down: StandaloneCoroutine was cancelled`. **A failure at the wrong address, in
unreadable language, in a client deliverable.**

*Fix:* cancellation is rethrown rather than recorded.

### RFT-D018 — Walk bursts rate-limited by the public endpoint
**S3 · FIXED · 2026-09-02**

Two parallel download streams per walk burst tripped the public endpoint's limit — eight
consecutive HTTP 429s across a morning walk, every download failing.

*Fix:* one stream per walk burst; eight bursts, eight complete results (107–57 Mbps) the same
afternoon.
*Real answer:* NHN's own endpoint on the venue LAN, which is the meaningful test for a DAS job
anyway.

### RFT-D019 — NR band ambiguity discarded on the neighbour path
**S1 · FIXED · 2026-09-02**

Two of five observed channels map to overlapping 3GPP allocations. The neighbour path resolved them
silently with `firstOrNull()`, labelling them n2 and n4 — **the wrong choice for a US carrier both
times.** The serving-cell path had always surfaced the ambiguity. **The one place it was dropped was
the one place a client reads it.**

*Fix:* ambiguity carried into neighbour labels, compacted to `n2/n25` — the previous `n2 (or n25)`
truncated to `n2 (or` in the six-character band column, turning an honest ambiguity into apparent
corruption.

---

## Open

None. [D003](#rft-d003--track-distance-four-attempts-ending-in-a-refusal) is `MITIGATED` and is
expected to stay that way — the limitation is in the GNSS receiver, and the app states it in output.

## Known limitations — not defects

Recorded here so they are not re-raised as bugs.

- **Neighbour list completeness is chipset-dependent.** Developed on a Pixel 6 Pro (Exynos 5123),
  which has textbook framework behaviour but sparse neighbour reporting. Validate field coverage on
  a Qualcomm handset before declaring any field unobtainable.
- **Handset dominance is a lower bound.** A scanner decodes every cell on air simultaneously; a
  handset reports its serving cell plus whatever partial neighbour list the modem chose to surface.
  **A handset finding overlap is evidence; a handset finding none is not.**
- **Band lock and test-mode entry are not implemented and will not be.** See
  [`MASTER.md`](MASTER.md) §3.
- **No north arrow or scale bar on floorplan plots.** The operator uploads a plan image, not a
  georeferenced raster, so neither orientation nor scale is known. Drawing either would be an
  invention the reader cannot check.
