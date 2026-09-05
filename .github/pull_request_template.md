## What changed, and why

<!-- The why matters more than the what; the diff already says the what. -->

## Definition of done

Per [`docs/PROCESS.md`](../docs/PROCESS.md) §5. Tick what holds; strike through with a reason what
does not apply.

- [ ] `./gradlew :app:testDebugUnitTest` passes locally, and CI is green
- [ ] Tested at the level the failure mode requires ([`PROCESS.md`](../docs/PROCESS.md) §6)
- [ ] **Observed doing the thing** — a human looked at it on the handset / read the rendered output
- [ ] Numbers checked against something independent — ground truth, OS telemetry, or a second
      measurement path. Not against expectation
- [ ] Record updated — `MASTER.md` for engineering narrative, `CHANGELOG.md` under `[Unreleased]`,
      `DEFECTS.md` for any defect

## How the numbers were verified

<!-- Name the independent source. "Tests pass" is not verification of a measurement: a test that
     passes on synthetic input tells you the function is right, only real output tells you the
     input was. Write "no measurement affected" if that is the case. -->

## Schema and version impact

- [ ] No change to the 73-column CSV schema
- [ ] Columns **appended** (MINOR — old sessions still read)
- [ ] Columns **reordered or redefined** (MAJOR — every recorded session becomes ambiguous; say why
      this is necessary)

## Defects

<!-- RFT-D### fixed or introduced. New defects go in docs/DEFECTS.md with severity by effect on a
     client deliverable, not by fix difficulty. -->
