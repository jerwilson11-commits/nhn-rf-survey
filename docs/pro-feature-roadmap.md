# Pro / Field feature roadmap — deep-modem features (Qualcomm rooted)

Planning doc for the advanced modem feature set. Focus: **Qualcomm-only rooted devices** for the deep
(DIAG/QMI) features. Researched 2026-10-11.

## Tiering rule (set 2026-10-11)

- **Root is mandatory → Pro tier.** Everything that needs the rooted DIAG/QMI path.
- **Everything else → Field tier.** No-root features (public-API analytics, standard test automation,
  voice-quality estimate, ERRCS coverage, CBRS band presence).

This has a deliberate side effect: it **enriches Field** (the bigger funnel / mass market) while making
**Pro a true root-DIAG moat**. Two features split across tiers as a natural free→paid upsell:
- **PCI analysis:** basic from the public API = Field; per-neighbour SINR/deep = Pro.
- **CBRS/private-5G:** band presence/validation = Field; slice S-NSSAI + NPN/SNPN (NAS decode) = Pro.

## Samsung Galaxy & Pro access — the real picture (corrected 2026-10-11)

The dominant US pro test phone is the **Samsung Galaxy S series**, used by TEMS/QualiPoc/Nemo/NSG.
Two different things, which must not be conflated:

- **Consumer bootloader rooting (Magisk/OEM-unlock)** *is* locked on US Snapdragon Samsung (and One UI
  8.0 removed bootloader unlock in all regions). But **this is NOT how the pros get modem access.**
- **Professional modem/DIAG access on Samsung = a Samsung-issued diagnostic "token" / authorization.**
  NSG's own docs confirm it: for S20/S21/S22 a **Samsung-issued token** recovers/enables the diag port,
  and the **token is issued by Samsung, not the app vendor.** Commercial tools pair a **validated
  device+firmware** with this access (Nemo also uses an external **NDM** hardware module; POLQA rides on
  it). **A small vendor (QTRUN/NSG) already obtains these tokens — so it's attainable at our size.**

**Implication — this reverses the earlier "Samsung excludes Pro" conclusion:**
- Pro on the dominant Samsung fleet is a **Samsung partner / diagnostic-token path** (business
  development), not a dead end → potentially the **single biggest lever for Pro's addressable market**,
  and it likely also unlocks the **Exynos/Shannon** work (NSG needs a Samsung token for Exynos too).
- Still available regardless: Pro on the **rootable-Qualcomm fleet** (OnePlus, Motorola, Xiaomi/POCO,
  Nothing, older Qualcomm Pixels) via normal root.

**Uncertain — research with Samsung directly:** does the token grant diag on a stock/locked device or
is root still involved? Which models/firmware? Open to US entities / small vendors? Terms/NDA/cost? The
replicable enrollment path QTRUN used. **ACTION: pursue "obtain Samsung diagnostic-access authorization"
as a high-priority business-dev track.**

**DIAG verification device:** OnePlus 9 = SD888 / X60 (2021); current Samsung S = SD 8 Gen 3 / 8 Elite,
X75/X80. DIAG log-packet field offsets vary by modem generation, so verify A-metrics on a **recent
rootable Snapdragon** (e.g. a current Motorola Edge, see `docs/test-device-research.md`) matching that
modem gen before shipping. OnePlus 9 is fine to start (it's on hand).

## Competitive frame

- **Network Signal Guru (NSG, QTRUN)** — the direct rooted-Qualcomm competitor: full L2/L3/SIP decode,
  band lock, RF optimization. Needs root + Qualcomm. **~$50/mo ($690/yr).** Powerful but dense UI; no
  survey/coverage/public-safety/CBRS verticals, no client reporting.
- **TEMS Pocket ~$699+, TEMS Investigation ~$1199+, Nemo Handy** (needs external NDM module; **POLQA**
  voice license tied to the hardware module) — the $thousands commercial league.
- **Our wedge:** match NSG's depth (A/B/D) at **$29.99**, and win on **UX, analytics-that-answer (C),
  client-ready reporting, and the survey/coverage/public-safety/CBRS verticals NSG lacks.**

## Scoring

`Score = average(Effort*, Value, RF-Eng demand, Field demand)`, each 1–5, one decimal. **Effort inverted**
(low effort boosts score): S=5.0, S–M=4.0, M=3.5, M–L=2.5, L=1.5. Value ★→1–5; demand H=5/MH=4/M=3/LM=2/L=1.

**▶ LEAD (business priority): F2 — Public-safety / ERRCS enhancements · Field · 3.9**

| # | Feature | Tier | Effort | Value | RF | Field | Score |
|---|---|---|---|---|---|---|---|
| B5 | Mobility/retainability event log (HO, RLF+cause, RACH) | Pro | M | 5 | H | H | 4.6 |
| C3 | Automated "why did it drop" root-cause | Pro | M | 5 | H | H | 4.6 |
| C1 | PCI confusion/collision + mod-3/30 | Field→Pro | S | 4 | H | M | 4.3 |
| A1 | Per-CC carrier aggregation + EN-DC/CA events | Pro | M | 5 | H | M | 4.1 |
| A2 | MCS / BLER / CQI per carrier | Pro | M | 5 | H | M | 4.1 |
| F1 | CBRS/private-5G: band validation / slice S-NSSAI, NPN | Field→Pro | M | 4 | MH | H | 4.1 |
| A5 | Timing Advance→distance, PHR/Tx power | Pro | S–M | 3 | H | MH | 4.0 |
| C2 | Overshoot + interference/pilot-pollution | Pro | S–M | 4 | H | M | 4.0 |
| C4 | Area RF-audit scoring → report | Field | S–M | 4 | M | H | 4.0 |
| B1 | Full RRC log + decode (beyond SIB1) | Pro | M/L | 5 | H | M | 3.9 |
| B2 | Full NAS decode + cause codes | Pro | M | 4 | H | M | 3.9 |
| E2 | VoLTE/VoNR MOS (E-model estimate) | Field | M | 4 | M | H | 3.9 |
| F2 | Public-safety / ERRCS enhancements (LEAD) | Field | M | 4 | M | H | 3.9 |
| B3 | Measurement-report decode | Pro | S–M | 4 | H | LM | 3.8 |
| A4 | NR beam-level (SSB beam + per-beam RSRP) | Pro | L | 5 | H | M | 3.6 |
| D1 | GSMTAP PCAP → Wireshark | Pro | M | 5 | H | L | 3.6 |
| E1 | Scripted test sequences | Field | M–L | 4 | M | H | 3.6 |
| A3 | MIMO rank/layers | Pro | M–L | 4 | H | LM | 3.4 |
| B4 | SIB2–SIBx decode | Pro | M | 3 | MH | M | 3.4 |
| E3 | Benchmark / A-B mode | Field | M | 3 | MH | M | 3.4 |
| D2 | QMDL export → QCAT | Pro | S | 3 | MH | L | 3.3 |

## Recommended build order

1. **F2** (lead, business/consulting) — Field, extends ERRCS grid. **IN PROGRESS:** first increment
   shipped 2026-10-11 — **FirstNet retainability & continuity** (`report/FirstNetContinuity.kt`): over a
   walk, counts on-FirstNet %, fell-to-commercial, weak-FirstNet (coverage holes), lost-FirstNet
   events + longest on-FirstNet run, surfaced in the PDF Track-B section. Unit-tested. (Automation is
   Track-B/FirstNet only — LMR stays manual by physics.) Remaining F2 options: acceptance report +
   survey workflow; donor-signal check mode.
2. **B5 + C3** (4.6) — RLF/HO event log → root-cause. **IN PROGRESS:** derived v1 shipped 2026-10-11
   — `report/MobilityEvents.kt`: from the recorded samples, detects serving-cell changes, NR→LTE
   fallbacks, off-FirstNet, and service losses, and root-causes each loss (missed-handover via a
   stronger same-band neighbour vs coverage hole); rendered in a PDF "Mobility and retainability"
   section. Unit-tested. **Follow-up (hardware-gated):** true modem-internal RLF/RACH/HO DIAG log
   codes (needs a confirmed capture-capable device; our OnePlus 9 streams zero packets on 0xB823).
3. **C1** (4.3) — nearly free; Field basic + Pro deep. **DONE 2026-10-11** — `report/PciConflicts.kt`:
   co-channel PCI conflicts, gated on mod-3 (DL RS/PSS) with mod-6 (LTE CRS) and mod-30 (LTE UL RS)
   flags (both are subsets of mod-3), min-level-margin for severity; rendered in a PDF "PCI planning
   conflicts" section. Works on public-API or modem neighbours. Unit-tested. True collision/confusion
   (needs per-neighbour NCI) deliberately not claimed.
4. **A1 + A2** (4.1) — the marquee Pro depth that matches NSG. Verify on a modem-gen-matched Snapdragon.
5. **F1** (4.1) — CBRS/private-5G, consulting niche.
6. Then C2/C4/A5, B1/B2, D1, and the rest by score.

## DIAG log-code references (for A, to confirm on real captures)

Layouts vary by chipset/modem version — confirm against QCAT / MobileInsight / CellNinja per device:
- LTE CA metrics: `0xB184`. NR5G MAC PDSCH Status: `0xB887` (MCS/RB/CRC→BLER), MAC DL TB Report `0xB886`.
- NR5G MAC CSF Report: `0xB8A7` (CQI). NR5G ML1 Searcher Meas DB Update Ext: `0xB97F` (SINR). NR5G LL1
  Serving SNR: `0xB8D8` (RSRP). MIMO rank / SSB beam index: spread across ML1/LL1 — needs a capture dig.
- Export (D): GSMTAP PCAP via SCAT/QCSuper references; 5G needs the Wireshark NR dissector plugin.

## ⏰ Revisit reminder

- **POLQA licensing** — using the free E-model MOS estimate (E2) for now. Revisit the cost/benefit of a
  licensed **POLQA** perceptual voice-quality engine (tied to hardware in Nemo) once Pro has traction.
