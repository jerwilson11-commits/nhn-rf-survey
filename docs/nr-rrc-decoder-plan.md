# On-device NR RRC decoder for worldwide TDD SIB1/RRCReconfiguration capture — plan

Scoped 2026-10-03. The goal is **full auto-capture of the TDD UL/DL configuration** for field
users, worldwide, FR1 first (n41/n48/n77/n78 and the rest of sub-6 TDD), FR2 later. T-Mobile is only
the development network. This document covers the **decoder** — the long pole — not the capture
orchestration that consumes it (see "Handoff" at the end).

## Why a decoder is the long pole

The capture envelope already works: `modem/RrcOtaParser.kt` decodes the `0xB821` (NR RRC OTA) DIAG
log as far as PCI / NR-ARFCN / PDU-type and hands off the raw ASN.1 **UPER** body (payload offset
23). Today that body is decoded **off-device with pycrate**. Everything else for auto-capture already
exists — band discovery, band-lock for any NR band (`QmiSelectionPreference` masks are 8×u64 = 512
bits, so every band incl. FR2 is encodable), SA mode-pref, and the tested field-derivation parser
(`profile/Sib1Parser.kt`). The missing middle is an **on-device ASN.1 UPER decoder** that turns the
captured bytes into the fields `Sib1Parser` already knows how to derive from.

## What the on-device verification established (2026-10-03, OnePlus 9 LE2115)

Captured `0xB0C0` (LTE RRC) + `0xB821` (NR RRC OTA) during an NSA EN-DC n41 session and decoded with
pycrate (`RRCNR.NR_RRC_Definitions`, `RRCLTE.EUTRA_RRC_Definitions`):

- **NSA gives the TDD config with no SIB1 and no band-lock.** The SCG-add is an NR
  `RRCReconfiguration` carrying `tdd-UL-DL-ConfigurationCommon` (refSCS 30 kHz; pattern1 DL3/6sym,
  UL2/4sym, `dl-UL-TransmissionPeriodicity-v1530 ms3`; pattern2 ms2, DL4) — **byte-identical to the
  SA-SIB1 n41 profile** captured earlier.
- It appears in **both** logs: embedded in the LTE `0xB0C0` message as `nr-SecondaryCellGroupConfig`
  (669-byte octet string), **and** as the same bytes in the `0xB821` NR packet on n41 (PCI 216,
  ARFCN 501390).
- The `0xB821` body starts at **the same validated payload offset 23** `RrcOtaParser` already uses
  and decodes standalone as NR `RRCReconfiguration`. **So NSA does not require cracking the LTE
  `0xB0C0` container** — `0xB821` is sufficient on its own.
- **PDU-type enum drifts by version:** this SCG reconfig arrived as PDU-type **9**, not the
  documented `4` (the NSA run showed 8/9/10/25). The decoder must not fully trust that byte.

Net: both decode roots (SIB1 and RRCReconfiguration) are **NR-RRC only**. The LTE 38.331 grammar is
optional corroboration, not on the critical path.

## Toolchain: asn1c → C → arm64 `.so` (in-process JNI)

Decoding is pure computation on bytes — **no root** — so this is an in-process `System.loadLibrary`
library, not an `su`-run helper like `libdcilogger.so`.

- **Compiler: `asn1c`.** Proven for 3GPP RRC (OAI, srsRAN), real unaligned-UPER support, fits the
  existing NDK/JNI pattern. Rejected: JVM UPER codegen (jASN1 is BER/DER only — none mature for
  UPER); Rust/rasn (new toolchain, no decisive gain). pycrate stays as the **cross-check oracle**,
  not shipped.
- **ASN.1 source: TS 38.331 Rel-17** (public 3GPP text). asn1c honors extension markers, so a
  newer grammar reads older messages and tolerates newer operators' added IEs — a Rel-15 decoder
  would break where a network includes later extensions. Needed for *worldwide*.
- **Build:** run asn1c once in `tools/asn1/` (mirrors `tools/diag/`), **check in the generated C**
  (CI needs only the NDK, not asn1c), compile via Gradle `externalNativeBuild` + CMake into
  `libnrrrc.so`. This is a deliberate departure from the single-file `build_*.sh` helper scripts —
  justified by the size of the generated tree. ABIs: **arm64-v8a** (field), plus **x86_64** so
  JVM/emulator tests can load it.

## JNI surface (tiny)

```kotlin
object NrRrcDecoder {
    external fun decodeTdd(pduKind: Int, uper: ByteArray): String?  // JSON, or null on failure
}
```

Native side: asn1c-decode as the given root, navigate to the shared node, emit a small stable JSON
of only the needed leaves.

- **Shared extraction, written once.** Both roots converge on the same asn1c type
  (`TDD_UL_DL_ConfigCommon_t` under `ServingCellConfigCommon`). Only the navigation differs:
  - `BCCH-DL-SCH-Message` → SIB1 → `servingCellConfigCommon`
  - `RRCReconfiguration` → `secondaryCellGroup`/`spCellConfig` → `reconfigurationWithSync` →
    `spCellConfigCommon`
- **JSON leaves:** `refSCS`, pattern1 `{periodicity, v1530, dlSlots, dlSyms, ulSlots, ulSyms}`,
  pattern2 `{…}`, `ssb-PositionsInBurst`, `ssb-periodicity`, band, PLMN.
- **Don't trust the PDU-type byte.** Use it as a hint; attempt decode as the plausible root(s) and
  accept whichever decodes cleanly and yields the expected IEs. Immune to per-firmware enum drift.

## Pipeline

```
RrcOtaParser (envelope: pci/arfcn/pduType/rawUper @off 23)
   └─ NrRrcDecoder.decodeTdd(root, uper) → JSON          [NEW native]
        └─ Kotlin adapter → Sib1Parser.Result            [reuse derivations]
             └─ TddProfile.withSib1(...)                  [unchanged]
```

- Extend `RrcOtaParser.PduType` (add 9 → DL-DCCH/RRCReconfiguration; keep 2 → SIB1). Envelope
  offsets unchanged (verified).
- **Refactor `Sib1Parser` to split extract from derive.** The derivation half (slot-string
  `DDDSU…`, combined periodicity, `ssbPositionOf`, pattern2 handling — the logic the 466 tests
  cover, incl. the v1530 fix) is fed by the native JSON front-end. The existing text front-end stays
  for the **paste/upload** path (MediaTek/Exynos and any non-root device), unchanged.

## Testing / validation

- **JVM unit tests** with recorded fixtures: UPER hex → expected `TddProfile`. Seed with the two
  real vectors we have (n41 SIB1; the 2026-10-03 NSA RRCReconfiguration).
- **asn1c vs pycrate cross-check** (dev-time harness): decode a corpus with both, diff; pycrate is
  the oracle. This is how asn1c's UPER is trusted across constructs.
- **On-device instrumented smoke test**: load the real `.so`, decode a known capture, assert.
- **Worldwide corpus**: add n48/n77/n78 and non-US captures as vectors as they arrive.

## Risks

- **APK size**: a full-38.331 `.so` may be 1–5 MB — acceptable; dead-strip where the linker allows.
- **asn1c UPER edge cases** — mitigated by the pycrate cross-check on real data.
- **Malformed-input safety** in native code — wrap JNI, always free asn1c structures, fuzz briefly.
- **38.331 ASN.1 compiling cleanly** under asn1c may need minor mechanical fixups — budget it in the
  spike.

## Phasing

1. **Spike (biggest unknown):** asn1c compiles Rel-17 NR RRC and decodes the two captured vectors to
   the needed fields on a host, cross-checked against pycrate.
2. **JNI + wiring:** `libnrrrc.so`, `NrRrcDecoder`, `PduType` 9, `Sib1Parser` extract/derive split +
   JSON front-end.
3. **Tests:** fixtures, cross-check harness, on-device smoke.
4. **Capture hook:** auto-decode a captured SIB1/RRCReconfiguration → save `TddProfile`. This is the
   seam into the auto-capture orchestration.

## Handoff: the auto-capture orchestration (separate workstream)

Sits on top of this decoder: discover TDD ARFCNs from the cells in view → for each, NSA
(traffic-trigger, no force) or SA-only band (force via `BandLockController`) → capture `0xB821` →
`NrRrcDecoder` → `TddProfile` → restore. Qualcomm-root devices get auto-capture; MediaTek/Exynos use
the upload/paste path into the same decoder+derivation. Candidate-driven (only bands seen on site),
FR1 first.

## References

- `docs/modem-diag-access.md` — DIAG/QMI access, the `0xB821` envelope layout, band-lock proof,
  and the OnePlus 9's inability to hold SA n41 (why NSA is this handset's working n41 route).
- `profile/Sib1Parser.kt`, `profile/TddProfile.kt` — the derivation logic to reuse.
- `modem/RrcOtaParser.kt` — the capture envelope the decoder plugs into.
