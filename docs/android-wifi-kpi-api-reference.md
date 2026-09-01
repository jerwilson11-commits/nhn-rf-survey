# Android Wi-Fi KPI API Reference

Companion to the cellular API reference. Wi-Fi is a first-class measurement mode, not scaffolding —
see the Master file, section 4.

Target: API 31+ (Android 12), test device Pixel 6 Pro (Wi-Fi 6E capable, so 6 GHz is in scope).

---

## 1. Connected AP — the deprecation trap

`WifiManager.getConnectionInfo()` is **deprecated as of API 31**. It still compiles, and most
tutorials online still use it, but on modern Android it can return redacted or stale values.

The correct modern path is through `ConnectivityManager`:

```
val request = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .build()

connectivityManager.registerNetworkCallback(request, object : NetworkCallback() {
    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        val info = caps.transportInfo as? WifiInfo   // <- the live WifiInfo
    }
})
```

**Redaction rule — two conditions, both required.** `transportInfo` returns a WifiInfo with SSID and
BSSID stripped (`"<unknown ssid>"` and `02:00:00:00:00:00`) unless **both** hold:

1. The app has `ACCESS_FINE_LOCATION` granted at runtime, **and**
2. The callback was constructed with **`FLAG_INCLUDE_LOCATION_INFO`** (API 31+):

```
object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) { ... }
```

Condition 2 is the one that will cost you an afternoon. Permission alone is not enough — without the
flag the platform blanks SSID and BSSID unconditionally, silently, with no exception and nothing in
logcat.

**The diagnostic signature:** every non-location field is perfect — RSSI, frequency, security type,
Wi-Fi standard, link rates all correct — and exactly two fields are empty. That asymmetry means the
flag, not the permission. If the permission were missing you would also get an empty scan list.

Confirmed on the Pixel 6 Pro 2026-08-31: with permission granted but no flag, the app read
−40 dBm / 5805 MHz / WPA3-SAE / 11ax correctly while showing "(not associated)" and a blank BSSID.
Adding the flag fixed both immediately. Restarting the process did **not** help, which rules out the
plausible-sounding theory that redaction is snapshotted at callback registration time.

### The callback's RSSI goes stale — query directly for volatile fields

`onCapabilitiesChanged` is push-based and fires on coarse capability changes, **not** on every RSSI
update. The WifiInfo you are holding can therefore be minutes old.

Measured on the Pixel 6 Pro, stationary:

| Source | Result over the same window |
|---|---|
| Callback WifiInfo, 123 samples over 2m06s | **−37 dBm, every single sample** |
| `adb shell cmd wifi status`, sampled concurrently | −36 / −37, varying |
| After switching to a direct query, 48 samples | −33 to −38, tracking the OS series |

RSSI is the primary Wi-Fi KPI. Frozen, it would flatline during a walk — the exact moment the
number matters — while looking entirely plausible on screen.

**Fix: split the sources by volatility.**

- **Identity** — SSID, BSSID, security, standard — from the callback, whose
  `FLAG_INCLUDE_LOCATION_INFO` path is the reliable way to get un-redacted values.
- **Volatile numerics** — RSSI, Tx/Rx link rates, frequency — from a direct
  `wifiManager.connectionInfo` call at sample time. Deprecated as of API 31, but it is a *pull*
  rather than a *push*, so it returns the current value. There is no non-deprecated pull.

### RSSI updates about every 3 seconds

Related, and worth knowing before anyone reads too much into a 1 Hz log: the platform refreshes
Wi-Fi RSSI roughly every 3 seconds. Sampling faster does not produce finer data — it produces
repeated values, visible as runs of three identical readings at 1 Hz. Log at whatever rate suits
the workflow, but do not let a report imply sub-3-second time resolution on this KPI.

### `WifiInfo` fields

| Method | KPI | Min API | Notes |
|---|---|---|---|
| `getRssi()` | **RSSI** dBm | 1 | The primary Wi-Fi coverage KPI. Typically −30 (excellent) to −90 (unusable) |
| `getBssid()` | **BSSID** | 1 | AP radio MAC. Requires fine location |
| `getSSID()` | SSID | 1 | Returned wrapped in quotes — strip them. Requires fine location |
| `getFrequency()` | Center freq MHz | 21 | Derive channel and band from this |
| `getLinkSpeed()` | Tx link rate Mbps | 1 | Negotiated PHY rate, not throughput |
| `getTxLinkSpeedMbps()` / `getRxLinkSpeedMbps()` | Directional link rate | 29 | More useful than `getLinkSpeed()` |
| `getMaxSupportedTxLinkSpeedMbps()` | Ceiling | 29 | Good for a "headroom" indicator |
| `getWifiStandard()` | **802.11 generation** | 30 | Returns `ScanResult.WIFI_STANDARD_*` |
| `getCurrentSecurityType()` | Security | 31 | WPA2 / WPA3 / OWE etc. |

**No channel width on `WifiInfo`.** The connected AP's channel width is only exposed on
`ScanResult`. To report it, match the connected BSSID against the latest scan results. Worth doing
— width is central to any Wi-Fi capacity conversation.

---

## 2. Neighbor APs — `getScanResults()`

`WifiManager.getScanResults()` returns the last scan's AP list. This is the Wi-Fi analogue of
`getAllCellInfo()`, and unlike the cellular case it is genuinely rich — which is part of why Wi-Fi
is a good pipeline test.

### `ScanResult` fields

| Field | KPI | Notes |
|---|---|---|
| `SSID` / `BSSID` | Identity | |
| `level` | RSSI dBm | Note it is `level`, not `rssi` — a classic mix-up |
| `frequency` | Center freq MHz | |
| `channelWidth` | **20 / 40 / 80 / 160 / 80+80 / 320 MHz** | `CHANNEL_WIDTH_*` constants |
| `centerFreq0` / `centerFreq1` | Segment centers | `centerFreq1` only for 80+80 |
| `capabilities` | Security string | e.g. `[WPA2-PSK-CCMP][ESS]` |
| `wifiStandard` | 802.11 generation | API 30+ |
| `timestamp` | Microseconds since boot | Age of the observation |

`CHANNEL_WIDTH_320MHZ` exists for 802.11be at API 34+.

### Scan results are a PARTIAL sweep — never replace the list, accumulate it

`getScanResults()` returns the results of the **most recent scan only**, and the OS routinely sweeps
a subset of channels rather than all of them. Consecutive calls legitimately return wildly different
AP counts with no error and nothing logged.

Measured on the Pixel 6 Pro within a two-minute window, stationary, in one location:

| Observation | AP count |
|---|---|
| `cmd wifi list-scan-results` at 09:33 | 22 |
| Our app, replacing the list wholesale | 13 |
| Our app, a minute later, same method | **4** |
| `cmd wifi list-scan-results` at 09:37 | 14 |
| Our app, accumulating with 60 s retention | **39** |

Two consequences, both serious for a survey tool:

1. The neighbour set collapses and expands at random, which looks like the radio environment
   changing when nothing changed.
2. **Derived counts silently read zero.** Co-channel count reported 0 while a co-channel AP was
   sitting at −37 dBm, purely because that particular sweep missed it. A zero that means "not
   observed this sweep" is indistinguishable from a zero that means "clean channel" — the worst
   possible failure mode for a tool whose output goes in an acceptance report.

**Fix: merge by BSSID and age out.** Keep a map keyed by BSSID holding the ScanResult and the
elapsed-realtime it was last seen; evict past a retention window. Retention is a genuine trade-off —
long retention gives a stable picture when stationary but keeps APs you have already walked past, so
every neighbour also carries its own `ageMs` and the UI surfaces it once it exceeds 15 s. Current
default is 60 s.

This is also why `cmd wifi list-scan-results` shows more APs than a single `getScanResults()` call:
the OS keeps its own accumulated cache. We are reproducing that behavior deliberately.

### Scan throttling — the thing that will confuse you first

Android 9+ throttles foreground apps to **4 `startScan()` calls per 2-minute window**. Exceeding it
makes `startScan()` return `false` and `getScanResults()` return the stale previous list, silently.
For a walk test at one sample per second, that is a hard ceiling.

Mitigations, in order:

1. **For development:** Developer options → **Wi-Fi scan throttling** → off. This is the single most
   useful setting on the test device. Note it is a dev-only fix — it does not exist for users.
2. **In production:** decouple the two rates. Sample connected-AP KPIs (RSSI, link rate) at the full
   1 Hz — those come from the network callback and are not throttled. Refresh the *neighbor* list on
   its own slower cadence, ~1 per 30 s, and timestamp it so the CSV shows the neighbor data's true
   age rather than implying it is fresh.
3. Register a `BroadcastReceiver` for `SCAN_RESULTS_AVAILABLE_ACTION` and consume results whenever
   any app on the device triggers a scan — free refreshes.

This split-rate design is exactly why the sample record carries a separate timestamp for neighbor
data.

---

## 3. Frequency, channel, and band derivation

Android gives frequency in MHz; channel and band are ours to compute.

| Band | Frequency range MHz | Channel formula |
|---|---|---|
| 2.4 GHz | 2401–2495 | `(freq − 2407) / 5`; channel 14 = 2484 |
| 5 GHz | 5150–5895 | `(freq − 5000) / 5` |
| 6 GHz | 5925–7125 | `(freq − 5950) / 5`; 5935 is the special case, channel 2 |

The Pixel 6 Pro is Wi-Fi 6E, so 6 GHz will appear in scans wherever it is deployed. Do not assume
`frequency > 5000` means 5 GHz — that bug silently mislabels every 6 GHz AP.

---

## 3a. Verified reference sample — use as a unit test

Captured from the Pixel 6 Pro on 2026-08-28 via `adb shell cmd wifi status`, before any of our code
ran. Good regression fixture for `WifiFrequency`, since it is ground truth from the OS itself.

| Field | Device reported | Our derivation |
|---|---|---|
| SSID | `TestAP-5G` | — |
| BSSID | `aa:bb:cc:dd:ee:11` | — |
| Frequency | 5805 MHz | `bandOf` → **5 GHz**, `channelOf` → **161** |
| RSSI | −39 dBm | — |
| Security type | `4` | `SECURITY_TYPE_SAE` → **WPA3-SAE** — matches the dump's `wpa3-sae` |
| Standard | `11ax` | `WIFI_STANDARD_11AX` → **Wi-Fi 6** |
| Tx / Rx link | 1134 / 1200 Mbps | — |
| Max supported Tx / Rx | 1200 / 1200 Mbps | — |

Note 5805 MHz is UNII-3, **not** 6 GHz — exactly the case a naive `frequency > 5000` check gets
wrong. Worth keeping a 6 GHz fixture alongside this one once a 6E AP is available to test against.

Useful during development: `adb shell cmd wifi status` gives ground truth to check our readings
against without instrumenting the app, and `adb shell cmd wifi list-scan-results` does the same for
the neighbour list.

## 4. Permissions

Adds to the cellular set:

```
android.permission.ACCESS_WIFI_STATE
android.permission.CHANGE_WIFI_STATE
android.permission.NEARBY_WIFI_DEVICES     (API 33+)
```

- `ACCESS_FINE_LOCATION` is still what unlocks SSID and BSSID, exactly as with cell identity.
- `CHANGE_WIFI_STATE` is required to call `startScan()` — it does not actually change any state.
- `NEARBY_WIFI_DEVICES` (API 33+) can substitute for location when scanning, using
  `usesPermissionFlags="neverForLocation"`. We do **not** use that flag: this app genuinely derives
  location, so the honest declaration is location permission plus `NEARBY_WIFI_DEVICES` without it.
- Location Services must be **on** at the OS level, not merely granted, or scan results come back
  empty. A frequent and confusing failure — the capability probe should check this explicitly.

---

## 5. Wi-Fi columns in the unified sample schema

Appended to the schema in the cellular reference, section 7. One row per sample, both radios,
empty where not applicable.

```
wifi_ssid, wifi_bssid, wifi_rssi, wifi_freq_mhz, wifi_channel, wifi_band,
wifi_width_mhz, wifi_standard, wifi_security,
wifi_tx_mbps, wifi_rx_mbps, wifi_max_tx_mbps,
wifi_neighbor_count, wifi_cochannel_count, wifi_adjacent_count,
wifi_neighbors_json, wifi_scan_age_ms
```

`wifi_cochannel_count` and `wifi_adjacent_count` are computed, not read — the count of other
observed BSSIDs on the same channel and on overlapping channels above a usable RSSI floor. Those
two derived numbers are what actually diagnose a bad Wi-Fi deployment, and no consumer app reports
them.

`wifi_scan_age_ms` is the age of the neighbor data at sample time, per the split-rate design in
section 2. Never imply neighbor data is fresher than it is.
