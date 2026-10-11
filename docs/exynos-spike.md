# Exynos / Samsung Shannon modem backend — feasibility spike

**Status: RQ1 confirmed on-device (Pixel 6 Pro, unrooted); backend code WRITTEN from the SCAT reference and gated OFF; RQ5 (live capture) needs a rootable Shannon device.** The Phase-3 spike from
`docs/multi-chipset-roadmap.md`. Answers the five research questions from open references (primarily
**SCAT**, which supports Samsung's **SDM** diagnostic format) and platform knowledge, marking what is
**reference-derived** vs. **must be confirmed on a real rooted device**. Follows the ground rules in
`docs/modem-diag-access.md`: build from open references, never decompile a competitor.

> **Two distinct sub-targets — do not conflate them:**
> 1. **Retail international Samsung Galaxy (Exynos)** — the better-documented SDM path.
> 2. **Google Pixel / Tensor** — a Samsung Shannon modem integrated through Google's `cpif` driver;
>    the **less-documented, higher-risk** path.
>
> We have a **Pixel 6 Pro (Tensor G1 = Shannon g5123b)**, so our on-device validation targets sub-target
> **(2)** — the harder one. A retail Exynos Galaxy would validate the easier (1); we don't have one.
>
> **RQ5 blocked on our hardware (2026-10-10):** our Pixel 6 Pro is a **Verizon** unit — OEM unlocking
> is **greyed out and permanent** (Verizon is the one carrier that ships Pixels with a permanently
> locked bootloader). It **cannot be rooted**, so the capture path (open `/dev/umts_dm0`, SDM, decode)
> is blocked on this device. RQ5 needs a **rootable** Exynos target instead — a Google-Store (unlocked)
> Pixel, or, easier per this spike, a retail **international Exynos Samsung Galaxy** (unlockable).

---

## Verdict (summary)

| Dimension | Verdict | Confidence |
|---|---|---|
| **Capture transport** (reach RRC/NAS/measurement logs) | **Plausible on root** via Samsung **SDM** (SCAT); **Pixel/Tensor transport is the main risk** | Medium (retail Exynos) / Low-medium (Pixel) |
| **Log parsing** → our model | **Moderate** — new **SDM**-framing parser; then `libnrrrc` + `TddProfile` reused unchanged | Medium |
| **Control** (band / technology lock) | **Low confidence** — proprietary AT / NV; Samsung ServiceMode is UI-only; worse on Pixel | Low |
| **No-root angle** | **Weak on Pixel** (no Samsung ServiceMode); ServiceMode dialer codes exist on retail Exynos Samsung but are UI-only | Low |

**Bottom line:** the *capture + decode* path is plausible and reuses our decoder, but **the Pixel's
Shannon diagnostic transport is the real unknown** and more uncertain than MediaTek's MD log. Expect a
Pixel-specific transport investigation to dominate the effort. Control (band/tech lock) is the weakest
dimension across all of Exynos/Shannon — scope it separately and promise little. No-root is weak on
Pixel specifically.

---

## RQ1 — Transport: what diagnostic channel exists, and does root suffice?

- Samsung Exynos modems ("Shannon", e.g. s5123/s5300) expose a **Diagnostic Monitor (DM)** stream;
  **SCAT supports the Samsung SDM** log format, which is the primary open reference that the stream is
  reachable and parseable.
- **Retail Exynos Galaxy:** classically reached by enabling a **USB DM / diagnostic port** (via a
  SysDump / secret dialer path or a service call) and reading the modem's serial/char node (historically
  `/dev/umts_*`-style). Root typically required, plus an explicit port-enable step on many models.
- **Pixel / Tensor:** the Shannon modem is bridged by Google's **`cpif`** kernel driver, **not** the
  retail Samsung stack, so the retail `/dev/umts_*` + SysDump approach does **not** transfer directly.
  Diagnostic access on Tensor is sparsely documented and community results are partial. **This is the
  item to establish first on the Pixel:** which node/socket carries the diag stream, and whether root
  alone opens it.
- **Root is required** either way (same barrier as Qualcomm/MediaTek).

### On-device findings — Pixel 6 Pro (raven, gs101), unrooted, 2026-10-10

Read-only reconnaissance over adb (no root, no writes) **confirmed the transport exists and is
root/system-gated**, turning this RQ from reference-derived to device-confirmed:

- **Modem:** `gsm.version.baseband = g5123b-…` → Samsung **Shannon g5123b**. SoC = Google **Tensor
  gs101**, `ro.soc.manufacturer = Google`.
- **The DM diagnostic node is `/dev/umts_dm0`** — char device `489,4`, owner **`system:system`**,
  mode `crw-rw----`. This is the Samsung **DM (Diagnostic Monitor)** channel SCAT reads SDM from. Its
  permissions mean **a normal app cannot open it — root (or the `system`/`radio` context) is
  required**, confirming the root barrier.
- **`cpif` driver confirmed:** `/dev/logbuffer_cpif` present; the modem IPC is exposed as
  `umts_ipc0/1`, `umts_rfs0`, `umts_boot0`, `umts_router` (all `radio`/`system`) and `oem_ipc0..7`
  (`radio:radio`). So Tensor uses the Samsung `cpif` stack, **not** QRTR/QMI — none of our Qualcomm
  transport applies, as expected.
- **SELinux is `Enforcing`**, so even with root, opening `umts_dm0` likely needs an SELinux allowance
  (e.g. a permissive domain / Magisk policy) — a real RQ5 caveat.
- **Classifier fix made as a result:** Tensor reports `SOC_MANUFACTURER = "Google"` (not "Samsung")
  and the board codename `raven` matched no vendor regex, so `ModemChipset.classify()` was returning
  `OTHER_OR_UNKNOWN` for Pixels — which would route a Pixel to the Qualcomm backend instead of a
  future `ExynosBackend`. Fixed: `"Google"` → `EXYNOS`, plus `gs\d{3}`/`zuma`/`tensor` codename
  fallbacks, with tests.

**Still needs root (RQ5):** opening `/dev/umts_dm0`, capturing SDM, and decoding a real RRC packet.

**Original confirm list (for reference):** enumerate modem/diag nodes, whether `su` opens the diag
stream, and whether a port-enable step is needed. *(Read-only reconnaissance on an already-rooted
device — no firmware writes, no root exploits.)* — the node enumeration above is done; the `su`-open
test remains.

## RQ2 — Logs: which formats carry what we need, mapped to our model?

- **Samsung SDM** carries **RRC (LTE/NR)**, **NAS**, and **measurement** logs. SCAT's SDM parser is the
  reference for the framing and message IDs.
- **RRC OTA → reuse:** extract the raw UPER RRC bytes (SIB1 / `RRCReconfiguration`) from SDM and feed
  `NrRrcDecoder` / `Sib1Parser` → `TddProfile` — **unchanged**, exactly as Qualcomm and MediaTek do.
- **Serving + neighbour measurements** → shape like `ModemNeighbourSource` output.
- A new **SDM-framing parser** (analogous to `RrcOtaParser`, but for SDM containers) is the parsing
  work. Tensor firmware may use a newer/variant SDM dialect than SCAT's retail baseline — verify.

## RQ3 — Control: is band / technology lock feasible, and how safe?

Lowest-confidence dimension.

- **Retail Exynos Samsung:** proprietary **AT commands** and **NV/EFS** govern band/RAT, but are
  firmware-specific and largely undocumented. **ServiceMode** (`*#0011#`, `*#2263#` "band selection")
  exposes some control but is **UI-only** and unreliable to drive programmatically.
- **Pixel/Tensor:** no Samsung ServiceMode; band/RAT control is even murkier. Treat as **likely
  unavailable** at first.
- **NV/EFS writes are out of scope** on safety grounds (ground rules forbid firmware-write/root
  exploits).
- **Expectation to set:** band/tech lock is likely **unavailable on Exynos/Shannon** in the first
  backend — report it honestly via `ModemFailureMessages`, never a silent no-op. Capture-only is still
  a strong deliverable (SIB1/TDD + RRC OTA on a Pixel is itself valuable and currently impossible).

## RQ4 — No-root options worth surfacing?

- **Retail Exynos Samsung:** ServiceMode dialer codes (`*#0011#`, `*#197328640#`) surface RF detail —
  but **UI-only**, not a stable programmatic API.
- **Pixel:** **no Samsung ServiceMode**; nothing meaningful beyond the Android public APIs the **Field
  tier already uses**. So on our actual hardware, the no-root angle is weak — the honest message for an
  unrooted Pixel stays "use the Field features + the paste-import path."

## RQ5 — Validation (needs the rooted Pixel 6 Pro connected)

On the rooted Pixel 6 Pro, prove the path end-to-end — the Qualcomm bar:
1. Locate/enable the Shannon diag transport (RQ1 — the crux on Tensor).
2. Capture across an idle → connected transition.
3. Extract an NR `RRCReconfiguration` and a `SIB1` from the SDM stream.
4. Decode via `libnrrrc` (`NrRrcDecoder`).
5. Confirm the `TddProfile` matches the serving cell.

---

## Device / firmware caveats

- **Pixel/Tensor ≠ retail Exynos Samsung.** The `cpif` transport is the divergence; don't assume SCAT's
  retail SDM transport works unchanged on Tensor.
- **Diag port enabling** and carrier/OEM locks apply.
- **Root required**, and Tensor root (unlock + Magisk-patched boot) is its own setup — confirm the
  Pixel 6 Pro is actually rooted before budgeting RQ5.
- SDM **dialect drift** across firmwares may need parser tolerance.

## Implementation status (code written 2026-10-11, from the SCAT reference, gated OFF)

Written from the SCAT Samsung-SDM format (clean-room Kotlin, not SCAT's GPL code), **unit-tested on
synthetic frames, not yet run on hardware:**

1. ✅ **`ExynosBackend`** registered in `ModemBackends` (EXYNOS → `ExynosBackend`). `availability()`
   returns `Unsupported` while `ExynosBackend.VALIDATED = false`, so the capability gate **still
   reports `WRONG_CHIPSET`** in production — flip `VALIDATED` after a real capture decodes. The real
   rooted + `/dev/umts_dm0`-present check is written behind the flag.
2. ✅ **`SdmParser`** — outer SDM framing (`0x7F … header … 0x7E`, `'<HBHHBBBL'`), command groups/ids,
   and the LTE RRC-OTA inner header (`'<BBH'` → raw RRC PDU). Round-trip + resync + partial-frame
   tests pass. Feeds the existing `NrRrcDecoder` / `Sib1Parser` / `TddProfile` unchanged.
   ⚠️ The **NR RRC inner container** layout is still a TODO (`nrRrcPayloadUnparsed` returns the raw
   payload) — needs `sdmcommonparser` cross-checked against a real NR capture.
3. ✅ **`SdmTransport`** — byte-stream → frame → NR/LTE RRC dispatch (`processStream`, unit-tested with
   split reads). ⚠️ `openDiagStream` (the `su` open of `/dev/umts_dm0` under SELinux Enforcing) and the
   **item-select enable handshake** are the two parts deliberately left for on-device work (RQ5) and
   currently return "not yet enabled".
4. ◻️ Neighbours from SDM measurement logs → `ModemNeighbourSource`-shaped output — not started.
5. ◻️ Control: **likely report band/tech lock unavailable** on Exynos/Shannon initially, with an
   honest message — revisit only if a safe AT path is proven.
6. ◻️ No-root: nothing to add on Pixel beyond existing Field features.

**To turn it on:** on a rootable Shannon device, finish `openDiagStream` + the item-select sequence,
confirm the NR inner container, decode one real SIB1/RRCReconfiguration through `libnrrrc`, then flip
`ExynosBackend.VALIDATED = true`.

## Open question for sequencing

If broad **retail international Samsung (Exynos)** reach matters more than Pixel specifically, a retail
Exynos Galaxy is the **easier, better-documented** first target than Tensor. We have a Pixel, so we can
start validating the harder Tensor path now — but the retail path may be the higher-ROI build. Worth a
product call before committing the backend.

## Next step

RQ1 is done (transport confirmed: `/dev/umts_dm0` over cpif, root-gated). **RQ5 is blocked for lack of
a rootable Exynos device** — our only one is the Verizon-locked Pixel 6 Pro (see the banner). To
proceed to a real `ExynosBackend`, source a **rootable** target:

- a **Google-Store (unlocked) Pixel** (Tensor/Shannon — the path we already have RQ1 for), or
- a retail **international Exynos Samsung Galaxy** — the **better-documented SDM path** and likely the
  higher-ROI first build.

Until then this backend stays `null` in the registry and the capability gate honestly reports
`WRONG_CHIPSET` for Exynos — while the decoder, no-root import, and Field features already serve
Exynos/Pixel users. The `ModemBackend` seam means adding the backend is a one-line registration once a
device and an SDM parser exist.
