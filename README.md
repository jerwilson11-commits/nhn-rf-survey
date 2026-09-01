# RF Test App

An Android field-measurement instrument for wireless survey work — Wi-Fi today, 4G LTE and 5G NR
next. Records geo-tagged RF and performance KPIs, maps them, and exports to formats that open
directly in Google Earth and QGIS.

Built as a lightweight alternative to TEMS Pocket, XCAL and QualiPoc for DAS, Private 5G and CBRS
acceptance and troubleshooting work.

Kotlin · Jetpack Compose · minSdk 31 · no third-party dependencies beyond the Compose BOM.

---

## Why this repository is worth reading

The feature list is unremarkable. What is worth your time is [`docs/MASTER.md`](docs/MASTER.md) —
the engineering record, which documents **ten defects found and fixed, most of which produced
plausible wrong numbers rather than crashing.**

That is the actual difficulty in instrumentation work. A tool that crashes tells you it is broken.
A tool that confidently reports −37 dBm forever does not, and its output ends up in an acceptance
report a client makes decisions on.

Four representative examples, each with its diagnostic signature and structural fix in the record:

**RSSI was frozen.** 123 consecutive samples read exactly −37 dBm while the OS reported values
varying between −36 and −37 over the same window. Android's `NetworkCallback` is push-based and
fires on coarse capability changes, not per-RSSI update, so the cached value can be minutes stale.
Caught by sampling the OS in parallel and comparing series. Fixed by splitting the sources —
identity from the callback, volatile numerics from a direct query.

**Interference counts silently read zero.** Co-channel count reported 0 with a co-channel AP
sitting at −37 dBm, because `getScanResults()` returns only the most recent scan and the OS
routinely sweeps a subset of channels. Observed AP counts of 22, then 13, then 4, then 14, in one
location, stationary, within two minutes. A zero meaning "not observed this sweep" is
indistinguishable from a zero meaning "clean channel" — the worst available failure mode for a
tool whose output goes in a report. Fixed by accumulating observations by BSSID with a retention
window, and stamping every neighbour with its own age.

**Track distance took four attempts and ended in a refusal.** Position differencing with an
accuracy gate read 28 m against 49 m of tape-measured ground truth (−45%); removing the gate
recovered the walk but accumulated 11 m of phantom distance in 69 seconds standing still.
Integrating Doppler velocity — how GPS receivers actually do odometry — got within −11%. Then a
second walk showed the receiver dropping velocity entirely for 78 of 423 fixes, where treating
null as zero lost the whole outbound leg and falling back to position differencing over-counted by
58%. Neither number was defensible, so the app reports the figure it can compute, counts the fixes
it could not, and marks the total approximate with the percentage. **Under-reporting a known
amount beats inventing a plausible one.**

**Locale-dependent CSV corruption.** `String.format("%.6f", lat)` on a comma-decimal locale emits
`26,0500` — splitting the field and shifting every subsequent column. Invisible on a US device,
silently destroys every file elsewhere.

---

## Capabilities

**Wi-Fi** — serving AP RSSI, BSSID/SSID, channel, band (2.4/5/6 GHz), channel width, 802.11
standard, security, Tx/Rx link rates. Neighbour AP list with co-channel and adjacent-channel
interference counts computed from **spectral overlap** (centre frequency and channel width), not
channel-number comparison — so wide-channel overlap is counted and 2.4 GHz partial overlap is not
missed.

**Position** — platform `LocationManager`, GPS preferred with the platform fused provider as
indoor fallback. Every fix records which provider produced it, because a fused fix may be derived
from Wi-Fi and cell, which would make a Wi-Fi survey partly circular.

**Sessions** — streamed to a 67-column CSV, one row per sample, flushed per row so a session
killed mid-run keeps everything already written.

**Throughput** — DL/UL across parallel streams, latency (min/median/max), jitter, and ICMP packet
loss. TCP slow start is excluded from the calculation; upload sends random bytes so a compressing
middlebox cannot inflate the result. Server URL is user-configurable, because on a DAS job the
meaningful test is against a server on the venue LAN, not the internet.

**Mapping and export** — track plot with RSSI colour coding, KML for Google Earth, GeoJSON for
QGIS. Ready-made QGIS styles in [`qgis/`](qgis/). One colour scale definition feeds the handset
plot, the KML and the QGIS symbology, so a session cannot tell three different stories.

**Background recording** — a foreground service keeps sampling through backgrounding and screen
lock, with configurable pass/fail thresholds and visual/audible alarms.

---

## Architecture notes

```
model/      Radio-agnostic sample types, RSSI scale, thresholds
wifi/       WifiCollector — scan accumulation, redaction handling
location/   LocationCollector — provider selection and fix ageing
session/    CSV writer and reader, KML and GeoJSON exporters
speedtest/  Throughput, latency, jitter, ICMP loss
service/    Foreground recording service and shared state
ui/         Compose screens
```

The sample envelope is deliberately radio-agnostic. The sampling loop, storage, export, map and
alarms are all shared, so adding the cellular collector changes one module rather than ten.

The CSV schema carries the cellular columns already, written empty, so a file recorded today stays
directly comparable with one recorded after that collector lands. Column groups are named lists and
blank counts are derived with `.size` — a hardcoded count drifted from the header once, and the fix
was to make the two structurally unable to disagree.

---

## Status

| Phase | Status |
|---|---|
| Environment, Wi-Fi collector, live dashboard | done |
| GPS sampling, sessions, CSV | done |
| Track plot, KML / GeoJSON export | done |
| Throughput, latency, jitter, ICMP loss | done |
| Foreground service, thresholds, alarms | done |
| **Cellular collector (LTE / NR)** | pending — needs an active SIM |
| Reporting and MCP server over the session corpus | planned |

---

## Known limitations, stated plainly

- **Band lock and test-mode entry are not implemented and will not be.** Neither is possible on
  Android for a normally distributed app — they require system permissions or vendor firmware.
  [`docs/MASTER.md`](docs/MASTER.md) section 3 covers what is and is not reachable, and the
  alternatives.
- **iOS is not planned as a measurement platform.** It exposes no RF KPIs to third-party apps at
  all. This is why the project is Android-first.
- Neighbour list completeness is chipset-dependent. Developed on a Pixel 6 Pro (Exynos modem),
  which has textbook framework behaviour but sparse neighbour reporting — good to build on, wrong
  to conclude from.
- Track distance is approximate where GPS supplies no velocity, and says so.

---

## Building

Android Studio, or:

```bash
./gradlew assembleDebug
```

Requires JDK 17+ (Android Studio's bundled JBR works), Android SDK platform 36 or later. See
[`docs/dev-environment-setup.md`](docs/dev-environment-setup.md).

---

## Development approach

Built with Claude Code as the development environment, using version-controlled project context
files to constrain agent behaviour, and validating every measurement against either ground truth or
independent OS telemetry (`adb shell cmd wifi status`, `dumpsys location`) rather than against
expectation. The defect record above is the output of that discipline — none of those ten were
found by the code failing.

---

*NHN Engineering & Consultants — RF / DAS / Private 5G / CBRS advisory.*
