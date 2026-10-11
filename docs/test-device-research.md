# Test-device sourcing research — rootable, tri-carrier, full-band rigs

**Goal:** acquire test hardware for Site Survey Pro that is **rootable** (bootloader-unlockable — the
hard lesson from the Verizon-locked Pixel), **multi-SIM / tri-carrier** (Verizon + AT&T + T-Mobile),
and supports **B14 / n14** (FirstNet 700), **B41 / n41** (T-Mobile 2.5 GHz), and **CBRS (B48 / n48,
3.5 GHz)** — **one device per chipset** we want to support fully (Qualcomm, MediaTek, Exynos/Shannon),
plus an **Android tablet** for large-screen software functional testing.

Researched 2026-10-11. Band/unlock facts move fast and vary by exact model/variant — **verify each
pick against the checklist at the bottom before buying.**

---

## The core tension (read this first)

Three goals fight each other:

1. **Rootable** → favors *unlocked / non-carrier* units, and brands with an official unlock (Google
   non-carrier, Motorola, Xiaomi/POCO). **US carrier models are usually locked** — Verizon Pixels and
   **all US-Snapdragon Samsung** cannot be unlocked.
2. **Full US bands (B14/n14 + CBRS)** → favors *US-market* devices. B14/n14 (FirstNet) and CBRS
   (B48/n48) are US-specific and mostly absent from international units.
3. **A specific chipset** → narrows the field hard, especially for MediaTek.

**Qualcomm and Exynos/Shannon can satisfy all three. MediaTek effectively cannot** — see below.

---

## Qualcomm — ✅ feasible: **Motorola Edge (2025 / 2026, unlocked US model)**

- **Bands:** Phone Scoop confirms Edge 2024 **and** 2025 carry 5G **n14, n41, n48** and LTE **B14, B41,
  B48 (CBRS)** — the full set. Edge 2026 (XT2609-2) is **FirstNet Ready** (so B14 is real, not just
  listed).
- **Root:** Motorola offers an **official bootloader unlock** (unlock key → `fastboot oem unlock`) on
  eligible models; XDA confirms even a *T-Mobile-locked* Edge 2024 was bootloader-unlockable and rooted.
  **Buy the "unlocked / Made-for-US" XT model and confirm its eligibility on Motorola's unlock site.**
- **SIM:** Dual-SIM (1 physical nano + eSIM) on 2024/2025.
- **Caveats:** verify the **exact XT model number** supports B14 + B48 (Phone Scoop's band-48 labels are
  inconsistent across pages). Avoid a *carrier-financed* unit (carrier SIM-lock is separate from
  bootloader unlock, but complicates things).
- **Note on our existing Qualcomm rig:** the rooted **OnePlus 9 (LE2115)** is the *global* variant and
  almost certainly **lacks B14 and CBRS** — fine for Qualcomm *modem-feature* work (QMI/DIAG already
  work there), but not for testing those specific US bands. The Moto Edge is the band-complete Qualcomm
  rig; keep the OnePlus 9 as the secondary Qualcomm dev device.

## Exynos / Shannon — ✅ feasible and highest value: **Google-Store (unlocked) Pixel 9 Pro (or newer)**

- **Why it's the best single buy:** it is simultaneously the **Exynos/Shannon chipset target** (Tensor =
  Samsung Shannon modem — we already have its diag transport mapped: `/dev/umts_dm0` over cpif) **and** a
  **band-complete, all-carrier** device.
- **Bands:** global Pixel 9 Pro lists 5G **n14, n41, n48** and LTE **B14, B41, TDD 41/48 (CBRS)**. Pixels
  are FirstNet-certified and ship essentially one global hardware SKU with all bands.
- **Root:** non-carrier Pixels unlock cleanly (enable OEM unlocking → `fastboot flashing unlock` →
  Magisk-patch **boot.img** on Pixel 6-class, **init_boot.img** on Pixel 7+). **MUST be bought
  unlocked from the Google Store — NOT Verizon (permanently locked, as we just proved) and not a
  carrier-financed unit.**
- **SIM:** nano-SIM + eSIM, and newer Pixels support **multiple eSIM profiles** — good for holding
  several carriers.
- **Caveat:** unlocking a Pixel 9 bootloader reportedly disables some Pixel-AI features — irrelevant for
  a test rig.

## MediaTek — ⚠️ effectively infeasible for *full bands*; decouple the goals

This requirement is the problem child, for two independent reasons:

1. **Rooting modern Dimensity is hard.** Unofficial unlock is "unfeasible" on current MediaTek SoCs
   (they require an OEM-signed Download Agent that isn't distributed); MTKClient covers mostly *older*
   chips. Only OEMs with an **official** unlock (Xiaomi/POCO, with a waiting period) are practical.
2. **MediaTek devices don't carry US public-safety/CBRS bands.** Dimensity phones are international /
   budget-US; **B14/n14 (FirstNet) and CBRS (B48/n48) are essentially absent**, and none are FirstNet-
   certified.

**Recommendation — split the MediaTek goal in two:**
- **To develop the `MediaTekBackend` (MD-log capture/decode):** buy a **rootable Dimensity device with a
  MobileInsight-supported chip** (a Xiaomi/POCO Dimensity via official unlock, or a Motorola with a
  Dimensity SoC — Motorola is the one brand selling MediaTek in the US *and* offering an unlock). Accept
  that it **will not** have B14/CBRS to test those bands on MediaTek.
- **To test B14/n14/CBRS behavior:** do it on the Qualcomm (Moto Edge) and Exynos (Pixel) rigs, which do
  carry those bands. Band-specific testing is chipset-independent at the app/measurement level.
- Treat "fully support MediaTek including B14/CBRS" as **not achievable with current hardware** and set
  expectations accordingly.

## Android tablet — ⚠️ rootable + full-band tablet doesn't really exist; use a non-root tablet

- **Best large-screen functional-test tablet:** **Samsung Galaxy Tab S10 FE 5G (SM-X528U, US)** —
  FirstNet Ready, AT&T 5G mid-band + C-band. But **US Snapdragon Samsung tablets have a
  permanently-locked bootloader** (no OEM-unlock toggle; paid "unlock" services are unreliable and keep
  getting patched). So it is a **non-root** device — good for the **Free/Field-tier UI, reports,
  floorplan/large-screen workflows, and the bands it carries**, not for the root-gated Pro modem tools.
- Our existing **Tab S7 FE 5G (SM-T738U)** is the same story (US Snapdragon Samsung, non-root) and
  already covers this role — a new tablet is optional unless you want newer bands/C-band.
- **If a *rooted* tablet is truly needed:** options are poor — an *international Exynos* Galaxy Tab is
  unlockable but tends to lack US B14/CBRS; rugged/enterprise tablets (e.g. Honeywell RT710) list
  n41/n48/B14 but are locked. Realistically, **do rooted/modem testing on the phones** and keep the
  tablet for non-root, large-screen software testing.

---

## Cross-cutting: SIM & carrier logistics

- **"Tri-carrier" in practice:** no mainstream US phone has 2 physical SIM slots **and** these bands.
  Plan on **1 physical SIM + eSIM(s)** and swap/activate carrier profiles (Pixel's multi-eSIM helps).
  Keep one active SIM per carrier and rotate, or keep three cheap test lines.
- **Verizon note:** modern unlocked Pixels/Motos do work on Verizon 5G/LTE, but **never buy the Verizon
  *variant*** — buy the unlocked model and add a Verizon SIM/eSIM.

## Pre-purchase verification checklist (do this per exact model number)

1. **Bootloader unlock eligibility** — Motorola unlock site / Google (non-carrier) / Xiaomi unlock. Not a
   carrier-locked or Verizon unit.
2. **Bands on the *exact* variant** — cross-check B14, n14, B41, n41, **B48/n48 (CBRS)** on GSMArena +
   Kimovil + the carrier's device page. Variant/model-number matters.
3. **FirstNet certification** (for B14/n14) — FirstNet's certified-devices list.
4. **CBRS authorization** — the **OnGo Alliance FCC Authorized End-User Device** database / FCC OET ID.
5. **Chipset** confirmed (Qualcomm vs Tensor/Shannon vs Dimensity) via spec sheet.

## Recommended buy order (best coverage per dollar)

1. **Google-Store unlocked Pixel 9 Pro (or newer)** — covers **Exynos/Shannon chipset + all bands +
   all carriers + root** in one device. Unblocks the Exynos RQ5 work immediately.
2. **Motorola Edge (unlocked US, 2025/2026)** — band-complete **Qualcomm** rig with root + dual-SIM.
3. **Rootable Dimensity device** (Xiaomi/POCO or US Motorola-Dimensity) — **MediaTek backend
   development only**; no B14/CBRS.
4. **Tablet** — keep the existing non-root Tab S7 FE 5G (or add a Tab S10 FE 5G) for large-screen,
   non-root functional testing. Not rootable.

## Sources
- FirstNet certified devices & Band 14 — firstnet.com (certified-devices list, device help).
- Bootloader unlocking overview (Google easy on non-carrier; US Snapdragon Samsung unavailable; modern
  MediaTek unfeasible) — en.wikipedia.org/wiki/Bootloader_unlocking.
- Motorola Edge 2024/2025/2026 bands & FirstNet & unlock — phonescoop.com, gsmarena.com, Motorola unlock
  flow, XDA Edge-2024 bootloader/root thread.
- Pixel 9 Pro bands — phonearena.com & kimovil.com frequency checker; Pixel bootloader-unlock guides &
  the Android Central note on AI-feature side effects.
- Samsung Tab S10 FE 5G (SM-X528U) FirstNet listing — firstnet.com; US-Snapdragon-Samsung locked
  bootloader — thecustomdroid.com, XDA.
- CBRS device verification — OnGo Alliance FCC Authorized End-User Device database; pentenetworks.com
  CBRS device list.
