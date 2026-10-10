# MediaTek modem backend — feasibility spike

**Status: desk research (reference-based). No on-device validation yet.** This is the Phase-1 spike
called for in `docs/multi-chipset-roadmap.md`. It answers the five research questions from open
references (MobileInsight, SCAT) and general platform knowledge, and flags precisely which claims are
**reference-derived** vs. **must be confirmed on a real rooted MediaTek device**. It follows the
project ground rules in `docs/modem-diag-access.md`: build from open references, never decompile a
competitor.

> **Hardware gap:** we currently have a **Pixel 6 Pro (Exynos/Shannon)** and Qualcomm devices only —
> **no MediaTek device.** So the RQ5 end-to-end validation below is blocked until a rooted MediaTek
> handset is sourced. The Pixel unblocks the *Exynos* spike instead; see that spike when written.

---

## Verdict (summary)

| Dimension | Verdict | Confidence |
|---|---|---|
| **Capture transport** (reach RRC/NAS/measurement logs) | **Feasible on root** via MediaTek's "MD log" diagnostic stream | Medium-high (MobileInsight supports it) |
| **Log parsing** → our model | **Moderate effort** — new MTK log-framing parser; then the existing `libnrrrc` + `TddProfile` pipeline is reused unchanged | Medium |
| **Control** (band / technology lock) | **Low confidence / partial** — no QMI equivalent; AT / EngineerMode / NV, all device- and firmware-fragile | Low |
| **No-root angle** | **Promising differentiator** — EngineerMode exposes RF info (and sometimes band selection) without root on many MTK devices | Low-medium (device-dependent, often gated on modern Android) |

**Bottom line:** the *capture + decode* path (the same payoff as Qualcomm SIB1/TDD and RRC OTA) is the
realistic first deliverable for a `MediaTekBackend`, gated on root, reusing the decoder we already
ship. **Control (band/tech lock) should be scoped separately and promised conservatively.** A no-root
"lite" read via EngineerMode is worth a short probe because of MediaTek's huge install base, but may be
blocked by modern Android permissions.

---

## RQ1 — Transport: what diagnostic channel exists, and does root suffice?

- MediaTek modems expose a diagnostic/logging channel commonly called **"MD log"** (modem log).
  **MobileInsight supports MediaTek diag on rooted Android**, which is the primary open reference that
  the transport is reachable at all.
- The channel is **not** QMI-over-QRTR and **not** Qualcomm DIAG — it is MediaTek's own framing, so
  none of our `Qrtr*` / `Qmi*` transport code applies. A new transport is required.
- **Root is expected to be required** to open the stream (same barrier as Qualcomm). Some MTK devices
  also need the **diagnostic port enabled** first (via a dialer/EngineerMode toggle and/or a USB
  "MTK port" mode) before the stream is accessible.

**Confirm on device:** the exact node/socket MobileInsight opens, whether `su` alone opens it or a
port-enable step is needed first, and whether SELinux on a current Android blocks it.

## RQ2 — Logs: which formats carry what we need, mapped to our model?

- **RRC OTA** (LTE RRC and **NR RRC**) is carried in MTK log packets → feed the raw UPER bytes to the
  existing `NrRrcDecoder` / `Sib1Parser` → `TddProfile`. This is the reuse that makes MediaTek worth
  doing: **once raw RRC bytes are in hand, our decoder pipeline is unchanged** (`libnrrrc` is
  chipset-agnostic).
- **NAS** (EMM / 5GMM) is available in the MTK log set.
- **Serving + neighbour measurements** are available; map them to the `ModemNeighbourSource` /
  measurement model.
- Each MTK log type has **its own packet header/framing**, so a new parser (analogous to
  `RrcOtaParser` / `NrMl1Parser`, but for MTK framing) is the real parsing work. MobileInsight's MTK
  message definitions are the reference for the framing and IEs.

## RQ3 — Control: is band / technology lock feasible, and how safe?

No QMI `SYSTEM_SELECTION_PREFERENCE` equivalent exists. Candidate mechanisms, all lower-confidence:

- **AT commands** over the modem's AT channel — proprietary `AT+E...`-style band/RAT commands exist on
  some MTK firmwares but are frequently absent, locked, or carrier-stripped. Device/firmware-specific.
- **EngineerMode "Band Mode" / network-type selection** — user-facing band restriction on **some** MTK
  devices, and notably **may not need root**. Device-dependent.
- **NV / EFS writes** — firmware-specific and risky; **out of scope** on safety grounds (our ground
  rules forbid firmware-write/root exploits).

**Reversibility:** prefer an EngineerMode-style change the user can undo, mirroring the Qualcomm
"until power cycle" discipline. **Expectation to set:** controlled band/tech lock may be **partial or
unavailable** across much of the MTK fleet — do not promise Qualcomm parity.

## RQ4 — No-root options worth surfacing?

- **EngineerMode** (`*#*#3646633#*#*`) surfaces serving-cell and neighbour info, and on some devices
  RSRP/RSRQ/SINR and **band selection** — **without root.** Surfacing even read-only RF detail on
  unrooted MediaTek devices would be a genuine differentiator given the install base.
- **Caveat:** much EngineerMode data lives only in its UI, not behind a stable public API/intent.
  Programmatic access historically used the hidden `com.mediatek.engineermode` intent or specific
  providers, which **modern Android increasingly blocks** for third-party apps. Treat as a short probe,
  not a promise.

## RQ5 — Validation (blocked: needs a rooted MediaTek device)

On a rooted MTK device, prove the whole path end-to-end — the same bar Qualcomm passes:
1. Open the MD-log stream (note any port-enable step).
2. Capture across an idle → connected transition.
3. Extract an NR `RRCReconfiguration` and a `SIB1`.
4. Decode via `libnrrrc` (`NrRrcDecoder`).
5. Confirm the resulting `TddProfile` matches the serving cell.

---

## Device / firmware caveats

- MTK diag support is **highly device- and firmware-fragile.** Target a chipset/firmware that
  MobileInsight already documents as working for the first implementation, rather than a random device.
- The **diagnostic port may need enabling**, and some carriers/OEMs lock it.
- **Root is required** for the capture path (this broadens the *rooted* fleet; it does not remove the
  root barrier). Non-root MediaTek users keep the **paste-import** path and all Field features today.

## Recommended implementation plan (after on-device validation)

1. `MediaTekBackend` registered in `ModemBackends` (currently returns `null` → `WRONG_CHIPSET`).
   `availability()` = rooted **and** MD-log node reachable.
2. `MtkLogTransport` (open/read the stream) + `MtkLogParser` (framing → raw RRC OTA bytes) →
   **reuse** `NrRrcDecoder` / `Sib1Parser` / `TddProfile` unchanged.
3. Neighbours from MTK measurement logs → shaped like `ModemNeighbourSource` output.
4. Control: EngineerMode-based band selection **where available**; otherwise report band/tech lock as
   unavailable on MTK with an honest `ModemFailureMessages` string — never a silent no-op.
5. Optional no-root "lite": surface EngineerMode RF reads **if** programmatically reachable on target
   firmware.

## Next step

Source a **rooted MediaTek handset with a MobileInsight-supported chipset** to run RQ1 + RQ5. Until
then this backend stays `null` in the registry and the capability gate honestly reports
`WRONG_CHIPSET` for MediaTek — while the decoder, no-root import, and Field features already serve
MediaTek users.
