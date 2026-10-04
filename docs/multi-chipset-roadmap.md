# Multi-chipset modem support — roadmap & research plan

**Status: roadmap / research-first. No engineering committed yet.** This is the plan for *investigating*
what's reachable on non-Qualcomm modems; the per-vendor implementation plans come out of the spikes
below, the same way `docs/nr-rrc-decoder-plan.md` came out of its spike.

## Why this exists

Today the deep modem features are **Qualcomm-only**. They ride **QMI over QRTR** (band lock, technology
lock, VoNR, neighbour cells via `GET_CELL_LOCATION_INFO`) and **Qualcomm DIAG** (SIB1/TDD decode, NAS/RRC
OTA capture, NR ML1 measurements) — interfaces Exynos and MediaTek simply don't have. We want to extend
the deep features to more chipsets, but only after establishing, per vendor, what is actually possible.

## The two layers (what's vendor-specific vs already shared)

| Layer | Chipset-specific? | Status |
|---|---|---|
| Diagnostic **transport** (how to reach RRC/NAS/measurement logs) | **Yes** — per vendor | Qualcomm only (QRTR/DIAG) |
| Log **packet parsing** → our model | **Yes** — per vendor format | Qualcomm only |
| **Control** (band/technology lock) | **Yes** — per vendor | Qualcomm only (QMI selection pref) |
| **ASN.1 decode** (SIB1 / RRCReconfiguration → TDD config) | **No — chipset-agnostic** | Done (`libnrrrc`), reused by any vendor |
| Field features, public-API KPIs, Wi-Fi/GPS, floorplan, QoE, throughput | **No** | Work on every device |
| **No-root import** (paste raw UPER hex → decode) | **No** | Done — serves non-Qualcomm users today |

So per-vendor work = **transport + log parser (+ optional control)**. Once raw RRC bytes are in hand, the
existing decoder and `TddProfile` pipeline are reused unchanged.

## Important nuance: US Samsung is Snapdragon

A rooted **US Samsung (S24, etc.) is Qualcomm and already works today.** **Exynos** matters mainly for
**international Samsung and Google Pixel/Tensor** (Tensor uses a Samsung "Shannon" modem). Prioritise
accordingly — "Samsung support" in the US is largely already covered.

## Reference tools (open-source; do not decompile competitors)

Per the project ground rules (`docs/modem-diag-access.md`), build from open references:
- **MobileInsight** — Qualcomm **and MediaTek** diag on rooted Android.
- **SCAT** (Signaling Collection and Analysis Tool) — Qualcomm, **Samsung SDM**, some MediaTek.
- **QCSuper**, **libqmi** — Qualcomm (already used).

These document the transports and packet formats needed to implement our own backends.

## Per-vendor landscape (to confirm in research)

### MediaTek (likely first — global reach)
- **Rooted:** "MD log" / MTK diagnostic stream (MobileInsight supports it). RRC/NAS/measurements reachable.
- **No-root angle worth checking:** **EngineerMode** (`*#*#3646633#*#*`) exposes some RF info and band
  selection on many MTK devices without root — a potential differentiator, device-dependent.
- Common on budget and international devices.

### Exynos / Samsung Shannon (international Samsung, Pixel/Tensor)
- **Rooted:** Samsung **SDM** (Diagnostic Monitor) carries RRC/NAS/measurement logs (SCAT supports it).
- **Control (band/tech lock):** murkier — AT commands / NV, device- and firmware-specific. Treat as a
  separate, lower-confidence research item.

### Unisoc and others
- Low priority; niche/budget. Research only if a real test fleet needs it.

## Research questions (per vendor spike)

For each vendor, on a **real rooted device of that chipset**, answer:
1. **Transport:** what diagnostic channel exists (char device / socket / DM mode / EngineerMode), and does
   root suffice to open it?
2. **Logs:** which log formats carry what we need — RRC OTA (→ SIB1 / RRCReconfiguration for TDD), NAS,
   serving/neighbour measurements? Map each to our existing model.
3. **Control:** is band/technology lock feasible (AT? EngineerMode? NV?), and how reversible/safe?
4. **No-root options:** anything exposed without root (e.g. MTK EngineerMode) worth surfacing?
5. **Validation:** capture a real sample and decode it through the existing `libnrrrc` pipeline end to end.

Deliverable per spike: a feasibility verdict + a per-vendor implementation plan (transport, parser,
control scope, device/firmware caveats).

## Architecture when we build

Abstract a **`ModemBackend`** interface — `capture(logCodes) → stream`, `neighbours()`, `bandLock()`,
`technologyLock()`, etc. — with today's `QualcommBackend` plus future `MediaTekBackend` / `ExynosBackend`.
`ModemChipset.classify()` already routes by vendor; `ProModem.capability()` already gates the UI. The
decoder, `Sib1Parser`/`TddProfile` pipeline, and UI stay shared, so features light up per vendor as
backends land, and `ProModemCapability`/`ModemFailureMessages` keep telling the user honestly what their
device can and can't do.

## Honest expectations

- **Root is still required** for raw diagnostic logging on every vendor — this broadens the *rooted*
  fleet, it does not remove the root barrier. Non-root users keep the import path and Field features.
- Each backend is **real R&D (weeks), device- and firmware-fragile.** Phase it; don't promise parity.
- The **no-root import already covers** non-Qualcomm users for TDD decode in the interim.

## Phasing

1. **Research spikes** (this plan) — MediaTek first, then Exynos/Shannon.
2. **Build the highest-payoff backend** (likely MediaTek: global reach + possible no-root EngineerMode).
3. **Exynos/Shannon backend** (Pixel + international Samsung).
4. **Unisoc/others** only on demonstrated demand.
