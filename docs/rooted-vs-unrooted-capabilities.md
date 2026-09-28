# Rooted vs. Non-Rooted Capability Split

Written 2026-09-28. This is the reference for what the app does on a normal Play Store install
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

Each of these is invisible and inert on a non-rooted phone — the control is either absent or shows
a plain-language reason it can't run, never a crash:

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
