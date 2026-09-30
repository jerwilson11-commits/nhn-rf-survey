# Rooted vs. Non-Rooted Capability Split

Written 2026-09-28. Updated 2026-09-30: chipset-vendor framing added alongside the root framing.
This is the reference for what the app does on a normal Play Store install
versus what it additionally does when the phone underneath it happens to be rooted. It exists
because the project's earlier planning docs (`MASTER.md`, the competitive analysis, the roadmap)
were written before the root-gated work below existed and, in places, said the opposite of what's
true now — see the correction at the top of `MASTER.md`'s "Explicitly out of scope" section.

The one rule that makes this split safe to ship: **every root-gated feature is optional, detected
at request time, and fails gracefully.** No code path in `MainActivity` or any controller calls
`su` eagerly or in a constructor — root is only ever touched when the user presses a specific
button for a specific feature, and every such control carries an `unavailableReason` that hides or
disables it cleanly when root isn't there. A user who installs from the Play Store onto a stock,
unrooted phone never sees a root prompt, a crash, or degraded core functionality. Root adds a layer
on top; it is never a prerequisite for the app being useful.

As of 2026-09-30, the failure messages these controls show also distinguish "not rooted" from
"rooted, but this modem isn't Qualcomm-based" — see `modem/ModemChipset.kt` — so a rooted
non-Qualcomm phone is told the truth rather than being told to root a phone that already is.

## Works fully on every install — no root required

This is the baseline every Play Store user gets, and it's already a complete, competitive product
on its own:

- **Cellular measurement**: RSRP/RSRQ/SINR via the `SignalStrength` callback surface (not the
  `CellInfo` surface, which returns `UNAVAILABLE` on this platform and blinds competitor apps to
  SINR specifically), serving-cell and neighbour-cell tracking, LTE/NR technology and band
  identification from public `TelephonyManager`/`CellInfo` APIs.
- **Wi-Fi measurement**: signal strength, channel, BSS Load beacon IE parsing for channel
  utilization and station count (no free competitor parses this — it's what separates "coverage
  exists" from "capacity is available"), roaming/BSSID-transition analysis, heatmap rendering.
  Legacy-rate reporting is complete; HT/VHT/HE/EHT rate-set parsing is the one open Wi-Fi gap (see
  below), not a root limitation — it's unparsed on every device, rooted or not.
- **Throughput testing.**
- **Floorplan-based surveys and heatmap overlays.**
- **Export**: PDF reports, CSV, KML, GeoJSON, GeoPackage.
- **The TDD profile library**: storing, matching, and reporting vendor-supplied or previously
  captured TDD slot configurations (pattern, periodicity, DL/UL slot and symbol counts, SSB
  position) for a site. Entering a profile by hand or importing one someone else captured needs no
  root — only *capturing* a fresh one from the air does (see below).
- **The MCP server** exposing the app's data to other tools.

## Root-gated, optional bonuses

Each of these is invisible and inert on a non-rooted phone, **or on a rooted phone whose modem
isn't Qualcomm-based** — the mechanisms below (QRTR/QMI, DIAG log streaming) are Qualcomm
vendor-specific, not part of the Android platform. The control is either absent or shows a
plain-language reason it can't run, never a crash:

- **Band lock** (LTE and NR) — set via QMI band-preference TLVs on the modem directly. Not the
  same mechanism as the public `setAllowedNetworkTypesForReason` framework API (which doesn't reach
  band-level granularity); this is a rooted transport underneath a feature no public API can offer
  at all, on any Android phone at any privilege level.
- **Technology lock**, including NR-SA-only and NR-NSA-only modes — same QMI transport.
- **VoNR enable/disable** — a carrier-config override, again requiring root to reach the
  system service that holds it.
- **Capturing a new TDD/SIB1 profile from a live cell** — reading the modem's DIAG log stream to
  decode RRC SIB1 (TDD pattern, SSB position, periodicity) requires root on every Android device
  ever made, because SIB1 never crosses the modem-to-application-processor boundary through any
  documented, non-root API. This is a hard platform boundary, not a permission this app could ask
  for. What root-free users get instead: the same profile library, populated by hand or by
  importing someone else's capture (including one taken with a dedicated tool like NSG).
- **NR neighbour-cell detail beyond what `CellInfo` exposes**, read the same way as SIB1 capture.

## Why these are Qualcomm-specific, not just root-gated

QRTR (`AF_QIPCRTR`, resolved through `/vendor/bin/qrtr-lookup`), QMI NAS (the message set band
lock, technology lock, and VoNR override all ride on), and DIAG log streaming (`/vendor/lib64/
libdiag.so`, which SIB1 capture and modem-sourced neighbour/serving-cell detail depend on) are all
Qualcomm baseband mechanisms. None has a public equivalent on Samsung Exynos/Shannon, MediaTek, or
Unisoc basebands — this isn't a permission gap or a root gap on those chipsets, it's a different,
undocumented, vendor-specific IPC/diagnostic surface entirely. The app now detects this
(`modem/ModemChipset.kt`) and states it correctly rather than blaming root.

## Chipset parity for other vendors — not attempted in this pass

Each of the following is an independent, open-ended, vendor-specific reverse engineering effort
with no guarantee of success — the same character as this project's already-parked Qualcomm
PCI/cell-lock research, which stalled because no public documentation of the needed DIAG command
bytes exists anywhere. None of these is scoped for implementation here; each is a future research
question. **Priority order as of 2026-09-30: MediaTek first, then Exynos, then Unisoc** — reversed
from an earlier assumption that Samsung's larger market share made it the obvious first target.

- **MediaTek** (research first) — an unverified third-party comparison (not this project's own
  research; no command has been confirmed against real hardware) claims MediaTek exposes band lock,
  ARFCN lock, and PCI lock natively through its CCCI virtual serial AT interface (`/dev/ttyC0` or
  `/dev/radio/ptty*`, commands like `AT+EPBSE` for band selection and a built-in `ChannelLock`
  subsystem) and unthrottled neighbour-cell reads via engineering AT commands (`AT+ECELL`,
  `AT+ECINFO`) — i.e., no DIAG-style binary reverse engineering needed at all for the features that
  matter most to this app (band/PCI lock, neighbours). The same source claims SIB1 capture is the
  hard part on MediaTek (needs a firmware-specific proprietary database, `MDDB_InfoCustomAppCat_*.EDB`,
  to interpret the CCCI trace stream via MediaTek's own Catcher tool) — but this app already has a
  no-root fallback for SIB1 (manual entry/import into the TDD profile library), so a MediaTek gap
  there costs less than a gap in band/PCI lock or neighbours would. If the AT-command claims hold up
  on a real device, MediaTek could reach parity on this app's core features with substantially less
  effort than Qualcomm's own DIAG reverse engineering took. Needs a rooted MediaTek Dimensity test
  device to confirm before any of this is trusted.
- **Exynos/Shannon** — is there any documented or reverse-engineerable IPC mechanism on Samsung's
  Shannon baseband equivalent to QRTR/QMI for band/technology selection? Is SIB1/TDD data reachable
  through any channel at all? Samsung's own CP/RIL debug interfaces are far less publicly
  documented than Qualcomm's DIAG. The same unverified comparison above claims ARFCN/PCI locking is
  "near impossible" on Shannon and neighbour reads require reversing an undocumented binary stream
  (SCAT covers 2G/3G/4G, only partial 5G NR on Pixel) — if true, Exynos is the weaker payoff of the
  two for this app's core RF drive-test features, even though it may be the easier of the two for
  SIB1/RRC capture specifically (SCAT already provides a baseline there).
- **Unisoc** — the least-documented of the three publicly; likely the lowest-priority, highest-risk
  research target of the group.

## The Play Store risk this used to create, and how it was fixed

Until 2026-09-28, the root-gated features above worked by bundling compiled native binaries in
`assets/`, copying them out, `chmod +x`-ing them, and executing them as root on request. That
shape — an app that ships executables and runs them with elevated privilege — resembles what
automated malware scanning looks for, even though every use here was disclosed, optional, and
user-initiated.

Fixed by shipping the three helpers (`libqmilock.so`, `libqmihelper.so`, `libdcilogger.so`) as
ordinary native libraries under `app/src/main/jniLibs/`, so Android's own installer places and
marks them executable at install time — nothing in this app's own code writes or elevates a fresh
executable after install anymore. Verified on a Pixel 6 Pro as an ordinary (non-privileged) install
and confirmed working end to end on the OnePlus 9's privileged test install; see
`docs/modem-diag-access.md`'s 2026-09-28 entry for the full verification and the one platform
gotcha it took to get there (modern Android does not extract native libraries to disk by default;
`packaging.jniLibs.useLegacyPackaging = true` in `app/build.gradle.kts` opts back in).
