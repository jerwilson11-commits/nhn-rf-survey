# Development Process

How work is planned, tracked, versioned and released on this project.

Owner: Jeremy Wilson · Adopted: 2026-09-05

---

## 1. Why this exists

Through Phase 8b the project ran as continuous development against `docs/MASTER.md`. That produced
good engineering — 44 commits, 219 unit tests, ten defects found and fixed — but it cannot answer
three questions a product needs to answer:

1. **What version is in the field, and what is in it?**
2. **When will a given capability be done, and what has to be true for it to count as done?**
3. **Is a defect found in the field already known, already fixed, or new?**

The engineering record answers *what happened*. This process answers *what is committed to*.

## 2. Document roles — one owner per question

The failure mode to avoid is the one that killed `src-staging/`: the same fact maintained in two
places, diverging silently. Each document below owns its question exclusively. Where another
document needs that fact, it **links** rather than restates.

| Document | Owns | Shape |
|---|---|---|
| [`MASTER.md`](MASTER.md) | Architecture decisions and the engineering record — what was built, what broke, why the fix took the shape it did | Append-only narrative |
| [`ROADMAP.md`](ROADMAP.md) | What is planned, in what release, by when, and the gate that closes it | Forward-looking, revised at each release |
| [`CHANGELOG.md`](CHANGELOG.md) | What shipped in each released version | Append-only, one section per version |
| [`DEFECTS.md`](DEFECTS.md) | Every defect: identity, severity, status, root cause, and the guard that stops recurrence | Register, entries updated in place |
| **This file** | How the above are kept true | Revised when the process changes |

`MASTER.md` §6 retains the Phase 0–8b history because that is engineering record. Everything
forward of today lives in `ROADMAP.md`.

## 3. Versioning

**`MAJOR.MINOR.PATCH`**, applied to the Android app, with `versionCode` a monotonic integer that
never decreases and never repeats.

| Increment | When | Example |
|---|---|---|
| **MAJOR** | The measurement contract changes in a way that makes old sessions non-comparable, or a stored format stops being readable | A CSV schema change that reorders or redefines existing columns |
| **MINOR** | New measurement capability, new export format, new screen — additive, old sessions still read | GeoPackage export; iBwave CSV export |
| **PATCH** | Defect fix or correction with no new capability | A KPI tile clipping its value |

**Appending a column to the CSV schema is MINOR, not MAJOR** — the schema is designed to grow at
the end, and `SessionReader` tolerates it. Reordering or redefining one is MAJOR, because every
recorded session in the corpus becomes ambiguous.

Pre-`1.0.0` means: proven on the bench and on validation walks, not yet proven on a paid
engagement. See the `v1.0.0` gate in [`ROADMAP.md`](ROADMAP.md).

The version lives in exactly one place — `app/build.gradle.kts` `defaultConfig` — and is read from
there by anything that needs to display it.

### Server components

`mcp-server/` and `speedtest-server/` version independently of the app, same scheme. The MCP
server's compatibility obligation is to the **CSV schema**, not to the app version: it must read
any session the app has ever written.

## 4. Cadence

- **Sprint: one week, Monday to Sunday.** Scope is set Monday, reviewed Sunday.
- **Release train: two weeks.** A version is cut on the Friday closing every second sprint.
- A release cut with unfinished scope ships without it. **The date moves scope out, not the date.**
  The exception is a gate item — if a gate cannot be met, the release does not go out, and
  `ROADMAP.md` records why.

## 5. Definition of done

A work item is done when **all** of the following hold. Anything less is in progress, regardless of
how complete the code looks.

1. **It builds.** `./gradlew :app:testDebugUnitTest` passes locally and in CI.
2. **It is tested at the level its failure mode requires** — see §6.
3. **It has been observed doing the thing.** For anything with a visual or on-device surface, a
   human has looked at it on the handset. Three UI commits went in unverified behind a locked phone
   and produced [`RFT-D009`](DEFECTS.md); "the build passed" is not observation.
4. **Its numbers were checked against something independent** — ground truth, OS telemetry
   (`adb shell cmd wifi status`, `dumpsys location`), or a second measurement path. Never against
   expectation.
5. **The record is updated.** Engineering narrative to `MASTER.md`; user-visible change to
   `CHANGELOG.md` under `[Unreleased]`; any defect to `DEFECTS.md`.
6. **It is committed and pushed**, with a message that says what changed and why.

## 6. Test obligation by failure mode

The project's ten defects were overwhelmingly **wrong numbers, not crashes**. Test effort is
allocated to match that, not evenly.

| Failure mode | Obligation |
|---|---|
| Arithmetic on measurements — scales, conversions, statistics, distance | Unit test with a hand-computed expected value, including the boundary and the null case |
| Schema and serialisation — CSV, JSON, GeoPackage, KML, GeoJSON, iBwave | Structural test that makes header and row **unable to disagree**, plus an all-null row and a comma-decimal locale |
| Standards-derived tables — band mapping, ARFCN, GSCN, SSB layout | Test against the citation (3GPP TS 36.101, TS 38.104), with the clause referenced in the test |
| Platform API behaviour — staleness, permissions, callback registration | Cannot be unit tested. Requires on-device validation against OS telemetry, recorded in `MASTER.md` |
| Rendering — PDF layout, Compose tiles, map plots | Cannot be unit tested. Requires a human looking at the output |

Where a class of failure cannot be caught by a test, say so in the code near where it lives, and
name the validation that does catch it.

## 7. Branching and review

- `main` is the trunk and stays releasable.
- Work happens on a branch, one branch per work item, named for the item.
- Every branch opens a pull request. CI (`.github/workflows/android-ci.yml`) runs the unit tests on
  every push and reports on the PR.
- A PR merges when CI is green and the definition of done in §5 is met.
- Release tags are `v<MAJOR>.<MINOR>.<PATCH>` on `main`.

## 8. Defect handling

Any defect that reached a commit gets an entry in [`DEFECTS.md`](DEFECTS.md), including ones fixed
within the hour. The register exists to make **recurrence visible** — staleness has now appeared
three times, null-as-zero twice, and the `@SystemApi` trap three times. A defect found in a
delivered report is a different class of problem from one found on the bench, and the register is
what lets that pattern be seen at all.

Severity is assigned by **what the defect does to a client deliverable**, not by how hard it was to
fix:

| Severity | Meaning |
|---|---|
| **S1** | Produces a plausible wrong number that reaches a report. The project's characteristic failure. Fix before anything else. |
| **S2** | Loses or corrupts recorded data, or makes a session unreadable. |
| **S3** | Visible malfunction — crash, missing feature, wrong display — that an operator would notice. |
| **S4** | Cosmetic or internal, no effect on recorded data or reported numbers. |

A crash is usually S3. A frozen RSSI reading is S1. **The one that does not announce itself is the
severe one.**

## 9. Standing rules

Earned from defects, restated here because each has recurred. Each links to the entry that produced
it.

> **A missing measurement is not a zero.** Zero is a measurement, and usually an extreme one.
> — [`RFT-D003`](DEFECTS.md), [`RFT-D006`](DEFECTS.md)

> **A cached value is not a current value.** Push-based platform callbacks fire on state changes,
> not on measurement updates. — [`RFT-D001`](DEFECTS.md)

> **`dumpsys` runs as shell. A value it prints is not a value an app may read.**
> — [`RFT-D007`](DEFECTS.md)

> **A scripted patch that cannot find its target must fail loudly.** Reporting success while
> changing nothing passes every build and every test. — [`RFT-D010`](DEFECTS.md)

> **Under-reporting a known amount beats inventing a plausible one.** Where a figure cannot be
> computed defensibly, report what can be computed, count what could not, and label the result.
> — [`RFT-D003`](DEFECTS.md)

## 10. Reviewing this process

Reviewed at each MINOR release. If a step here was skipped and nothing broke, it is ceremony and
should be cut. If a defect got through, the question is which step above would have caught it, and
whether that step exists.
