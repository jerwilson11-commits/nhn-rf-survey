# RF Test App — Master

Source of truth for the mobile drive-test / walk-test application. Update this file when scope,
platform, or architecture decisions change. Everything else in this folder derives from it.

Owner: Jeremy Wilson · Started: 2026-08-28

---

## 1. What this is

A field-test application for measuring 4G LTE and 5G NR radio and performance KPIs, geo-logging
them, and exporting them for reporting. Positioned as a lightweight, low-cost alternative to
TEMS Pocket / XCAL / QualiPoc for DAS, Private 5G, and CBRS acceptance and troubleshooting work.

Two audiences, in order:

1. **NHN Engineering & Consultants** — the instrument Jeremy uses on his own engagements.
2. **Commercial product** — published once proven in the field.

Working name: TBD.

---

## 2. Decisions locked 2026-08-28

| Decision | Choice | Why |
|---|---|---|
| Platform | **Android first, iOS companion later** | iOS exposes no RF KPIs to third-party apps. Android does, via public API, no root. |
| Purpose | Consulting tool first, commercial product later | De-risks store polish until the measurement core is proven. |
| Build | Jeremy + Claude, learning as we go | Zero marginal cost, full ownership, unlimited iteration. |
| Data | On-device capture + optional cloud sync | Local-first works offline in a basement DAS. Cloud is Phase 4. |

---

## 3. Platform reality — what can and cannot be measured

### iOS: RF KPIs are impossible

No public API for RSRP, RSRQ, SINR, RSSI, band, EARFCN/NR-ARFCN, PCI, CID, TAC, or neighbors.
`CTCarrier` is deprecated and stubbed on iOS 16+. Private APIs (`CTGetSignalStrength`, status-bar
internals) are App Store Guideline 2.5.1 rejections and are broken on current iOS regardless.

**Band lock and programmatic test-mode entry do not exist on iOS** — not privately, not with an
enterprise certificate, not under MDM. They require the modem diagnostic interface (Qualcomm DIAG),
which Apple exposes to no one outside Apple. Field Test Mode (`*3001#12345#*`) is a carrier-bundle
UI the user drives by hand; nothing programmatic hangs off it.

This is why TEMS Pocket, QualiPoc, and XCAL are Android-only for RF-layer data, and why
OpenSignal / Ookla on iOS report only throughput and a coarse network type.

### Android: RF KPIs are available, band lock is not

| KPI / Feature | Android public API | Notes |
|---|---|---|
| RSRP / RSRQ / RSSNR / CQI / RSSI (LTE) | `CellSignalStrengthLte` | RSSI added API 29 |
| SS-RSRP / SS-RSRQ / SS-SINR (NR) | `CellSignalStrengthNr` | API 29+ |
| Band, EARFCN / NR-ARFCN | `CellIdentityLte` / `CellIdentityNr` | `getBands()` API 30+ |
| PCI / CI / NCI / TAC / PLMN | `CellIdentity*` | |
| Timing advance | `CellSignalStrengthLte.getTimingAdvance()` | Serving cell only |
| Neighbor cells | `getAllCellInfo()` | Completeness is vendor-dependent |
| NSA / SA state | `NetworkRegistrationInfo.getNrState()` | API 30+ |
| **Band lock** | **Not available** | `setAllowedNetworkTypesForReason()` needs `MODIFY_PHONE_STATE`, a system permission |
| **Test mode entry** | **Not available** | Manual dial code, OEM-dependent |
| Throughput / latency / jitter / loss | Standard networking | Fully available |
| GPS track, map, export | Standard | Fully available |

**Band lock is off the table on both platforms** for any app that can be distributed normally.
Real drive-test kit gets it through vendor-firmware handsets (Samsung/Sony units flashed for TEMS)
or an external scanner. That is a hardware supply-chain problem, not a coding problem.

### Options if band lock becomes a hard requirement later

- Vendor-firmware handset plus an existing commercial tool — buy, do not build.
- External RF scanner over BLE/USB with a vendor SDK, app as the head unit.
- Rooted device with Qualcomm DIAG parsing — technically possible, not distributable, not supportable.

---

## 4. Scope

### v1 — the measurement core

Two coequal measurement modes sharing one pipeline: **cellular** and **Wi-Fi**.

- Cellular: continuous serving-cell and neighbor collection, LTE and NR, all identity and signal
  fields. NSA/SA state and RAT display, including the LTE anchor when NSA.
- Wi-Fi: connected-AP RSSI, BSSID/SSID, channel, band (2.4 / 5 / 6 GHz), channel width, link rate,
  802.11 standard, security; plus scan-result neighbor AP list with co-channel and adjacent-channel
  interference counts.
- GPS-tagged sample stream at a configurable rate, identical for both modes.
- Live numeric dashboard, RF-engineer layout, not a consumer "bars" UI.
- Session record/stop with named sessions: site, venue, sector, floor.
- CSV export, local file storage, share sheet out.

**Why Wi-Fi is first-class, not a detour:** the sampling loop, geo-tagging, storage schema, export
writers, map overlay, and threshold alarms are all radio-agnostic. Wi-Fi exercises every one of
them with no SIM required, so the pipeline is proven before the cellular collector plugs in. It
also aligns with the CWNA track, and a combined cellular-plus-Wi-Fi survey tool in one app is a
real differentiator — DAS venues need both surveyed anyway.

### v2 — field usability

- Map view with color-coded KPI overlay (RSRP / SINR / throughput) and plotted track.
- KML and GeoJSON export for Google Earth and GIS.
- DL / UL throughput test, latency, jitter, packet loss, DNS resolve time.
- Configurable pass/fail thresholds per KPI, visual and audible alarm on breach.
- Background collection via foreground service, screen-off logging.

### v3 — reporting

- Session summary statistics: min/max/mean/percentiles per KPI, per band, per cell.
- Indoor floorplan mode: load an image, tap-to-place points instead of GPS.
- PDF / XLSX acceptance report generation.

### v4 — platform

- Optional cloud sync, multi-tester campaigns, server-side heatmaps, client dashboards.
- iOS companion: speedtest, GPS logging, and viewing/reporting of Android-collected data.

### Explicitly out of scope

- Band lock, forced RAT selection, test-mode entry.
- Layer-3 message decode, RRC signaling, protocol trace.
- Anything requiring root.

---

## 5. Technical stack (proposed)

| Layer | Choice | Rationale |
|---|---|---|
| Language | Kotlin | Android standard, concise, coroutines fit the sampling loop |
| UI | Jetpack Compose | Modern, less boilerplate, easier to learn than XML layouts |
| Min SDK | **31 (Android 12)** | `TelephonyCallback` lands at 31. Dropping to 29 would force a parallel deprecated `PhoneStateListener` path for two OS versions that are nearly gone by 2026 — not worth the dual code path |
| Compile SDK | **37** | The only platform SDK installed. compileSdk and targetSdk differing is normal — compileSdk decides which APIs you can call, targetSdk decides which behavior changes apply |
| Target SDK | **36 for now, 37 later** | Test device runs Android 17 (API 37), but targeting 36 keeps API-37 behavior changes out of Phases 1–4. Bump deliberately as its own task — see `docs/android-17-impact-notes.md` |
| Local storage | **Streaming CSV** (Room deferred) | Changed 2026-08-31. Room means adding KSP and matching its version to Kotlin 2.2.10 under AGP 9, for a benefit not yet needed. Streaming appends are also better for a drive test: a session killed mid-run keeps every row already written, memory stays flat under Android 17's RAM limits, and the CSV is the deliverable so there is no export step to fail. Room returns when queries across sessions are actually needed — Phase 7 reporting or cloud sync |
| Location | Platform `LocationManager` | Changed 2026-08-31. Android 12+ ships a fused provider inside the platform, so no Play Services dependency. Raw `GPS_PROVIDER` preferred, fused as indoor fallback — a fused fix can be derived from Wi-Fi and cell, which would make a Wi-Fi survey partly circular. Every fix records which provider produced it |
| Map | **Self-drawn Compose Canvas** (MapLibre dropped) | Changed 2026-08-31. A tile basemap adds a dependency, a tile-source ToS and network dependence, and is useless for the actual use case — inside a DAS venue or basement a street map shows nothing and there is often no connectivity. The geographic view is the KML export opened in Google Earth. Equirectangular projection with longitude scaled by cos(latitude), one scale on both axes |
| Networking | OkHttp | Multi-stream throughput test, full control over timing |
| Speedtest server | **User-configurable URL**, Cloudflare default | The server is a field in the UI, not a constant. On a DAS / Private 5G job the meaningful test is against a server on the **venue LAN** — testing to the internet measures the client's backhaul and ISP, not the radio system under acceptance. Cloudflare's endpoints are the default for general drive test; a LAN-hosted LibreSpeed is the on-site workflow |
| Export | Hand-written CSV/KML/GeoJSON writers | No dependency, exact control over the column schema |

---

## 6. Roadmap

Resequenced 2026-08-28 so that no phase before 5 is blocked on SIM activation.

| Phase | Goal | Gate | Needs SIM |
|---|---|---|---|
| 0 | ✅ **Done 2026-08-31.** Dev environment, app builds and runs on the Pixel | App runs on the handset | No |
| 1 | ✅ **Done 2026-08-31.** Data model, permissions flow, **Wi-Fi collector**, live dashboard | Real RSSI / BSSID / channel / band on screen | No |
| 2 | ✅ **Done 2026-08-31.** GPS sampling loop, session record/stop, streaming CSV | A real walk produces a usable CSV | No |
| 3 | ✅ **Done 2026-08-31.** Session browser, track plot, KML / GeoJSON export, share sheet | Route and RSSI render correctly | No |
| 4 | ✅ **Done 2026-08-31.** Speedtest — DL / UL / latency / jitter / ICMP loss | Results within reason of Ookla over Wi-Fi | No |
| 5 | **Cellular collector** plugged into the proven pipeline | LTE/NR values match Field Test Mode | **Yes** |
| 6 | ✅ **Done 2026-08-31.** Foreground service, screen-off logging, thresholds and alarms | Survives a full site walk | No |
| 7 | Reporting — statistics, floorplan mode, PDF / XLSX | A client-ready acceptance report comes out | No |
| 8a | ✅ **Done 2026-09-01.** MCP server, stdio transport, 5 tools over the session corpus | An agent answers an acceptance question directly from recorded sessions | No |
| 8b | ✅ **Done 2026-09-01.** Stateless `streamable-http` transport, OAuth 2.1 Resource Server | Hosted deployment per MCP 2026-07-28 | No |

The cellular collector moves to Phase 5 deliberately. By then the sampling loop, storage, export,
and map are all validated against real Wi-Fi data, so when the SIM arrives we debug one module
rather than ten simultaneously.

### Phase 8b validation record — 2026-09-01

Stateless HTTP transport and OAuth 2.1 Resource Server. Both transports register tools from the
same `tools.register()`, so there is no per-transport tool code to drift.

**Statelessness verified, not assumed.** `stateless_http=True` only asserts a property of the code;
it holds because no tool retains anything between calls. Proof: a cold request carrying no prior
`initialize` and no session header succeeded on its own, and no `Mcp-Session-Id` was issued.

```
initialize                          -> 200, Mcp-Session-Id: (none)
tools/call, no init, no session id  -> 200, 4 sessions
analyze_coverage over HTTP          -> 87.1% compliance, 2 holes (matches stdio exactly)
```

**A role correction the blueprint blurred.** CIMD is an *Authorization Server* feature, not a
Resource Server one. Under CIMD a client hosts JSON metadata at a stable HTTPS URL and uses that
URL as its `client_id`, replacing the deprecated Dynamic Client Registration round-trip. The party
that fetches it is the AS during authorization; a Resource Server never sees a registration request
at all — only a token whose `client_id` claim happens to be a URL.

So the defensible claims are: Resource Server validating tokens from a CIMD-capable AS, handling
URL-shaped `client_id` values, and **deliberately not implementing an Authorization Server**. That
last is a decision — rolling your own OAuth AS is a known source of exploitable bugs, and real
deployments delegate to Auth0, Okta, Entra or Keycloak. The SDK offers `auth_server_provider` for
anyone who wants to; this does not use it.

Auth verified end to end:

```
no token    -> 401, WWW-Authenticate carries resource_metadata pointer
wrong token -> 401
valid token -> 200
RFC 9728 Protected Resource Metadata endpoint -> 200
```

Token validation pins algorithms (accepting the header's claim permits "alg":"none" and RS256->HS256
confusion) and checks audience against this server's own resource identifier per RFC 8707, so a
token minted for another resource cannot be replayed here.

Security posture: binds loopback by default and **refuses** to bind wider with no auth configured
rather than warning — an unauthenticated MCP server on 0.0.0.0 exposes every session file it can
read. The dev verifier requires two separate environment switches to construct.

Two SDK constraints found by hitting them:

1. `FastMCP` was renamed to `MCPServer` in SDK 2.x. The first implementation targeted 1.x and
   failed at import; migrated rather than pinning to a superseded version.
2. `token_verifier` cannot be supplied without `auth` settings. Reasonable — a server that
   validates tokens but publishes no Protected Resource Metadata gives a client no way to discover
   where to obtain one. This surfaced only because the dev-auth path was actually exercised; the
   auth code would otherwise have shipped untested.

### Phase 8a validation record — 2026-09-01

MCP server in `mcp-server/`, Python, MCP SDK 2.1.1, stdio transport. Verified with a real JSON-RPC
handshake against actual recorded sessions, not mocks:

```
initialize        -> server 'rf-test-app', protocol 2025-06-18
tools/list        -> 5 tools with correct JSON schemas
analyze_coverage  -> walk_baseline @ -75 dBm: 87.1% compliance, 2 holes
                     walk_baseline @ -70 dBm: 76.7% compliance, 1 hole
query_samples     -> speedtest filter: dl 192.896 / ul 39.64 Mbps at -38 dBm
error path        -> unknown session returns the error plus available sessions
```

Coverage-hole detection located the real thing: a 12-sample, 11.5 s run bottoming at −77 dBm at the
westernmost point of the driveway track, 6.0 m extent — the far end, furthest from the AP.

**Bucket distribution came out 50/18/24/24 — identical to the KML and GeoJSON exports.** That is a
third independent implementation of the same scale agreeing, which is the payoff for defining
`RssiBucket` once and mirroring it rather than reinventing it.

**The comparability caveat earned itself immediately.** Comparing the driveway walk against a
mostly stationary indoor session returned a +24 dB median delta and +12.9% compliance — which looks
like a dramatic network improvement and is almost entirely route artefact. The tool returns sample
counts, durations and an explicit caveat, and deliberately does not declare an improvement.

Architecture note: `session_store.py` carries all parsing and analysis with no MCP dependency;
`server.py` is a thin tool wrapper. The analysis is the valuable part and should not be reachable
only through a protocol.

SDK note: built against MCP Python SDK **2.x**, where `FastMCP` was renamed to `MCPServer`. The
first implementation targeted the 1.x API and failed at import; migrated rather than pinning to a
superseded version.

### Phase 6 validation record — 2026-08-31

Recording moved out of the Composable and into `RecordingService`, a foreground service with
`type="location"`. Measured on the Pixel 6 Pro:

| Test | Result |
|---|---|
| Survives tab switch | 11 rows -> 26 while sitting on the Sessions tab |
| Service type | `isForeground=true types=0x8` (LOCATION), notification with 1 action |
| Survives screen off | 26 -> 85 rows over 45 s with the screen off |
| Sample interval, screen off | 1.05 s mean, 2.0 s worst — no throttling |

Data quality with the screen off is the part that mattered, since rows being written is not the
same as rows being useful:

| Field | Screen ON | Screen OFF |
|---|---|---|
| wifi_rssi / bssid / channel / neighbour count | 100% | **100%** |
| lat / speed_mps / gps_accuracy_m | 96% | **100%** |
| Neighbour count, median | 31 | **32** |

Scan age cycled 0–29 s and RSSI kept varying, so scanning and the live-value query both continue
while dozing. Android does not throttle a foreground service with location type the way it
throttles a background app.

**Permission consequence worth remembering:** `ACCESS_BACKGROUND_LOCATION` is deliberately NOT
requested. A foreground service declared `type="location"` may access location while backgrounded
without it. That permission is for apps wanting location with no visible service, and asking for it
triggers a Play Store justification review and demo video for no benefit here.

`POST_NOTIFICATIONS` is requested but **not gated on** — without it the service still records, only
the notification and its Stop action are suppressed. Blocking every feature behind it would be
wrong. It needs its own request pass, because the combined request only fires when the *required*
permissions are missing, so an existing install would otherwise never be asked.

`onStartCommand` returns `START_NOT_STICKY` on purpose. Silently resuming after an OS kill would
append to a file the operator believes is finished, producing a session with an unexplained gap in
the middle.

Confirmed as correct behaviour, not a defect: `adb shell am start-foreground-service ... STOP` is
rejected with "Requires permission not exported from uid". The service is `exported="false"`, so
nothing outside the app can start or stop a recording.

**Verified by hand 2026-08-31:** the notification's "Stop and save" action ends the session and
finalises the file, and the threshold alarm fires — red breach card plus audible beep on the
5-second cooldown. Both needed manual test; neither could be driven reliably over adb against the
screen timeout.

Phase 6 is complete.

### Phase 4 validation record — 2026-08-31

Measured over Wi-Fi against speed.cloudflare.com:

```
Download 192.90 Mbps · Upload 39.64 Mbps
Latency 68.3 ms median (44.2 / 299.0) · Jitter 34.0 ms · Loss 0.0%
```

The result lands on exactly one CSV row — throughput is a point measurement, not a continuous one —
carrying the position and RF conditions it ran under:

```
note=speedtest, lat 26.0500xx, lon -80.1397xx, +/-4.5 m
TestAP-5G, -38 dBm, ch 161, 5 GHz, PHY tx 648 / rx 864 Mbps
```

That co-location is the point of the design. The Wi-Fi PHY rate dropped from 1134/1200 Mbps idle to
648/864 under load — rate adaptation a standalone speed-test app cannot show, sitting in the same
row as the throughput figure and the RSSI it was measured at.

Measurement decisions worth keeping:

- **TCP slow start is excluded.** The first 1.5 s of each transfer is discarded. A naive
  total-bytes/total-time figure understates throughput, and the faster the link the worse the error.
- **Parallel streams** — 4 down, 3 up. A single TCP stream is bounded by window/RTT, so on a
  high-bandwidth-delay link one connection cannot fill the pipe however fast the link is.
- **Upload sends random bytes, not zeroes.** A compressing middlebox or CDN would squash zeroes and
  report throughput the link cannot deliver.
- **Packet loss is ICMP or nothing.** `/system/bin/ping` works from the app on this device, so real
  loss is measured; where SELinux blocks it the field stays blank. HTTP request failures are never
  relabelled as packet loss — they are a different measurement and would put a wrong number in an
  acceptance report.

Two defects found and fixed:

1. **Cloudflare returns HTTP 403 above ~25 MB** (verified: 25 MB -> 200, 100 MB -> 403). The
   download requested 200 MB and got a 1-byte 403 body. Each stream now issues repeated 25 MB
   requests until the deadline.
2. **HTTP status was never checked, and stream failures were swallowed** by `runCatching`. That is
   why a 403 surfaced as an unexplained null rather than an error naming the cause. Status is now
   checked on both download and upload, and every stream failure is logged and propagated.

Schema note: `THROUGHPUT_COLUMNS` grew from 5 to 8 — `latency_min_ms`, `latency_max_ms` and
`speedtest_server` were added. Done now, before any file has left the building; the schema-stability
rule applies from first release onward.

### Phase 3 validation record — 2026-08-31

Session browser, track plot and exports, verified on the real driveway walk.

- **Track plot** renders the out-and-back correctly: strong green clustered at the office end,
  degrading through amber to orange at the far end of the driveway, both passes visible. Start and
  end markers distinguish an out-and-back from a one-way. Summary reads 116 rows / 116 geo-tagged /
  1:59 / RSSI −32 to −77 dBm (45 dB).
- **GeoJSON**: 117 features — 1 LineString of 116 vertices plus 116 Points. Coordinates correctly
  `[lon, lat]`. Full properties per point including `rssi_bucket` and `marker_color`.
- **KML**: well-formed, 6 styles, 117 placemarks. Colour conversion to KML's `aabbggrr` verified
  by hand — `ff327d2e` unpacks to RGB `2E7D32`, matching the app's green. All five buckets reverse
  correctly, which matters because getting it wrong swaps red and blue and renders strong signal
  as a problem area.
- **Cross-consistency**: KML style usage and GeoJSON bucket counts match exactly at 50 / 18 / 24 /
  24, and both derive from the same [RssiBucket] scale the on-screen plot uses. One session cannot
  tell three different stories.

Both exports are generated by reading the CSV back off disk rather than from an in-memory copy, so
what the client receives is provably built from the recorded bytes.

~~**Known limitation:** switching tabs stops an in-progress recording.~~ **Resolved in Phase 6** —
recording moved into `RecordingService`.

### Phase 2 validation record — 2026-08-31

Recorded live sessions on the Pixel 6 Pro and inspected the resulting files.

- 64-column CSV, every data row aligned to the header, ISO-8601 UTC timestamps at 1 Hz.
- Location, Wi-Fi and neighbour JSON populated; 38 columns correctly empty pending Phases 4 and 5.
- Recorder UI: row count, elapsed time, track distance; file lands in
  `Android/data/com.nhnengineering.rftest/files/sessions/`.

Four defects found and fixed. As in Phase 1, none of them crashed in a way that pointed at the
cause, and three would have quietly produced wrong numbers:

1. **Hardcoded blank-column count.** The row builder emitted 29 empty cellular cells against a
   31-column header. The assertion caught it — every row would otherwise have been shifted two
   columns. Fixed structurally: the schema is now built from named group lists and the row builder
   derives counts with `.size`, so header and row cannot disagree.
2. **`String.format` used the default locale.** On a comma-decimal locale this writes
   `26,050266`, splitting the field and corrupting the file — invisible on a US device. Now
   `Locale.US` explicitly.
3. **RSSI was frozen.** The `NetworkCallback` is push-based and fires on coarse capability changes,
   not per-RSSI update: 123 consecutive samples over 2m06s all read exactly −37 dBm while the OS
   reported −36/−37 concurrently. Fixed by reading volatile fields (RSSI, link rates, frequency)
   from a direct `getConnectionInfo()` query while keeping identity from the callback. After the
   fix, 48 samples spanned −33 to −38 and tracked the OS series. **This was the most serious bug
   found so far — RSSI is the primary KPI and it would have flatlined during a walk.**
4. **Track distance took four attempts and ended in a deliberate admission of uncertainty.**
   Ground truth: an 80 ft driveway (24.4 m), walked out and back — ~49 m. The app's own track
   independently derived 25.7 m one way, confirming the ground truth to within 5%.

   | Approach | Good-GPS walk | Degraded-GPS walk | Stationary |
   |---|---|---|---|
   | Position differencing, accuracy gate | 51 m (+4%) | — | **11 m in 69 s** |
   | Add Doppler speed gate >=0.5 m/s | 28 m (−45%) | — | 0 m |
   | Trapezoidal velocity integration | **44 m (−11%)** | 21 m (−57%) | 0 m |
   | Velocity, falling back to position when absent | 44 m (−11%) | 78 m (**+58%**) | 0 m |

   Position differencing fails because at 1 Hz a walking step is ~1 m while GPS accuracy is
   +/-6 m, so real movement is indistinguishable from jitter. Velocity integration — Doppler
   speed, the way GPS receivers actually do odometry — is immune to that and won on every metric
   until the second walk, where the receiver **stopped reporting velocity for 78 of 423 fixes**
   with accuracy degraded to +/-23 m. Treating those as zero lost the whole outbound leg; falling
   back to position differencing over the same stretch over-counted by 58%.

   **Neither number is defensible, so the app no longer picks one.** Velocity integration stands,
   fixes lacking velocity are counted, and when they exceed 10% the distance is shown as `~21 m`
   with `Distance*` and a note naming the percentage. Under-reporting a known amount beats
   inventing a plausible one — and this is a tool whose output goes into acceptance reports.

   Positions and RF data are unaffected by any of this; only the derived odometry is at issue.

Also measured, not a defect but a property worth knowing: the platform refreshes Wi-Fi RSSI roughly
every **3 seconds**. Logging at 1 Hz over-samples it, so the data carries ~3 s granularity
regardless of sample rate. Any report built on it should not imply finer time resolution.

### Phase 1 validation record — 2026-08-31

App reads live Wi-Fi on the Pixel 6 Pro, cross-checked against `adb shell cmd wifi status`:

| KPI | App | OS ground truth |
|---|---|---|
| SSID / BSSID | TestAP-5G / aa:bb:cc:dd:ee:11 | identical |
| RSSI | −40 dBm | −40 dBm |
| Frequency → band, channel | 5805 MHz → 5 GHz, **ch 161** | 5805 MHz |
| Channel width | 80 MHz | (matched via scan results) |
| Standard / security | 802.11ax / WPA3-SAE | 11ax / wpa3-sae |
| Tx / Rx / max Tx | 1134 / 1200 / 1200 Mbps | identical |
| Co-channel count | 1 | 1 AP confirmed on 5805 |
| Adjacent count | 0 | 5785 APs at −88, below the −85 floor |

Frequency-to-channel derivation is confirmed correct against hardware. Three real defects were found
and fixed in the process — all three are documented in the Wi-Fi API reference, and all three would
have silently corrupted exported data rather than crashing:

1. `WifiInfo.INVALID_RSSI` is `@hide`, not public API — build failure, caught at compile time.
2. `NetworkCallback` needs **`FLAG_INCLUDE_LOCATION_INFO`** or SSID/BSSID are blanked despite
   `ACCESS_FINE_LOCATION` being granted.
3. `getScanResults()` returns a **partial channel sweep**, not the full picture. Replacing the
   neighbour list wholesale made co-channel count read 0 with a co-channel AP at −37 dBm. Results
   now accumulate by BSSID with a 60 s retention window and per-AP age.

---

## 6a. Phase 8 — MCP server, and why it supersedes part of Phase 7


Phase 7 originally assumed a hardcoded PDF/XLSX report template. Exposing the session corpus as
Model Context Protocol tools is a better answer to the same problem: a fixed template answers the
questions we anticipated, while an agent over typed tools answers the ones a client actually asks
— "which sectors failed the −75 dBm threshold and by how much", "compare this walk against the
pre-tuning baseline".

It reads the same CSV files the app writes, through the same schema, so the server and the handset
cannot disagree about what a session contains — the same discipline that keeps the RSSI colour
scale defined once and shared by the plot, the KML and the QGIS style.

Two milestones: a local stdio server with the core tools, then optionally a stateless HTTP server
with CIMD registration per the MCP 2026-07-28 specification.

This phase also serves Jeremy's job search — it is the portfolio artifact for GSI AI-engineering
roles at Accenture and Deloitte. That is a genuine dual purpose, not a detour: the app gets the
better reporting architecture either way.

---

## 7. Open items

- ~~**Test handset**~~ — **resolved 2026-08-28: Google Pixel 6 Pro, updated to Android 17 (API 37).**
  Tensor G1 with a **Samsung Exynos 5123 modem** — not Qualcomm. Stock AOSP means framework
  behavior is textbook and ideal for development, but neighbor lists are sparse and NR SCG
  reporting under NSA is inconsistent. Build on it; validate field coverage on a Qualcomm Samsung
  before declaring any field unobtainable. See section 8 of the API reference.
- **SIM / cellular service — currently BLOCKING Phase 1 cellular validation.** Device is Wi-Fi
  only. With no SIM the modem does not register and `getAllCellInfo()` returns nothing usable, so
  no cellular KPI can be read or verified. Fastest unblock is an **eSIM data plan** — the Pixel 6
  Pro supports eSIM, so activation is minutes rather than shipping days.
- **Multi-carrier testing.** Pixel 6 Pro is one physical nano-SIM plus eSIM (DSDS — both
  registered, one data-active at a time). Enough to A/B two carriers by switching. Real
  multi-carrier benchmarking for client work eventually wants a SIM per major carrier.
- **NR SA validation** needs a network that actually runs SA — or a CBRS / Private 5G lab or client
  site. Most US macro 5G is still NSA on most bands.
- **Target SDK 37 migration** — deferred until after Phase 4. Needs `ACCESS_LOCAL_NETWORK` if the
  speedtest server is LAN-hosted, and a responsive dashboard for large screens. Scoped in
  `docs/android-17-impact-notes.md`.
- ~~**Version control**~~ — **local git initialised 2026-09-01**, first commit `608f04b`, 57 files
  on `main`. `.gitignore` correctly excludes `local.properties`, `build/` and `.gradle`.
  **Still to do:** push to a public GitHub repo, and decide where the design docs live (below).
- **Where the design docs live.** They are the portfolio differentiator — the engineering record is
  more compelling than the feature list — but they currently sit in OneDrive, outside the repo.
  Moving them in means one source of truth under version control; leaving them out means the repo
  reads as code-only. Copying them to both places recreates exactly the divergence problem that
  killed `src-staging/`. Decision needed before the repo goes public.
- **Product name**, and whether it carries NHN branding.
- **Reference tool** for validating our numbers in Phase 1 — Field Test Mode on the device, or an
  existing app such as Network Cell Info or NetMonster as a cross-check.
- Google Play developer account, $25 one-time — not needed until v1 ships.

---

## 8. Cross-domain notes

This tool is a credibility asset for both other tracks: a demonstrable 4G/5G KPI instrument
supports the Job Search Profile and is a differentiator for NHN's DAS / Private 5G / CBRS
positioning. Revisit `Consulting Business/` messaging once Phase 2 produces real field output.
