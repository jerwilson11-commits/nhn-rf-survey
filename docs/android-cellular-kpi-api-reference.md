# Android RF KPI API Reference

Field-by-field mapping from the KPIs we need to the Android APIs that provide them. This is the
technical backbone of Phase 1. Everything here is public API — no root, no vendor SDK, no
reflection.

---

## 1. Entry points

There are two ways to get radio data, and we use both.

### Push — `TelephonyCallback` (API 31+) / `PhoneStateListener` (deprecated, pre-31)

Register callbacks and the framework pushes updates as they change. Lower power, no polling, but
update cadence is decided by the platform and can be sparse when idle.

Relevant callback interfaces:

| Interface | Gives us |
|---|---|
| `TelephonyCallback.CellInfoListener` | Full `List<CellInfo>` — serving plus neighbors |
| `TelephonyCallback.SignalStrengthsListener` | `SignalStrength` — serving cell only, but updates fast |
| `TelephonyCallback.ServiceStateListener` | `ServiceState` — registration, NR state, roaming |
| `TelephonyCallback.DisplayInfoListener` | `TelephonyDisplayInfo` — the NSA/NR-Advanced override |

### Pull — `TelephonyManager.getAllCellInfo()`

Explicit request for the current cell list. **Throttled to roughly one call per second**, and
Android 10+ blocks it outright when the app is in the background without a foreground service.
Requires `ACCESS_FINE_LOCATION`.

**Design decision:** run a coroutine sampling loop at a user-configurable rate (default 1 s),
calling `getAllCellInfo()`, and merge in the latest pushed `SignalStrength` / `ServiceState`
snapshots. That gives a deterministic sample rate for the CSV while still catching fast changes.

---

## 2. LTE fields

`CellInfoLte` splits into identity and signal strength.

### `CellIdentityLte`

| Method | KPI | Min API | Notes |
|---|---|---|---|
| `getCi()` | Cell Identity (ECI, 28-bit) | 17 | `Integer.MAX_VALUE` if unknown. eNB ID = `ci >> 8`, Cell ID = `ci & 0xFF` |
| `getPci()` | Physical Cell ID, 0–503 | 17 | |
| `getTac()` | Tracking Area Code | 17 | |
| `getEarfcn()` | E-UTRA ARFCN | 24 | Derive band from this if `getBands()` is unavailable |
| `getBandwidth()` | Channel BW in kHz | 28 | e.g. 20000 = 20 MHz |
| `getBands()` | **Band number array** | 30 | Preferred source of band. Empty on API 29 |
| `getMccString()` / `getMncString()` | PLMN | 28 | String form preserves leading zeros — always use this, not the deprecated int form |
| `getOperatorAlphaLong()` | Network name | 28 | |

### `CellSignalStrengthLte`

| Method | KPI | Range | Min API | Notes |
|---|---|---|---|---|
| `getRsrp()` | **RSRP** dBm | −140 to −44 | 26 | The primary coverage KPI |
| `getRsrq()` | **RSRQ** dB | −20 to −3 | 26 | Quality / loading indicator |
| `getRssnr()` | **SINR** dB | −20 to +30 | 26 | Reported in 0.1 dB units on some devices — validate against Field Test Mode |
| `getCqi()` | Channel Quality Indicator | 0–15 | 26 | Frequently unavailable |
| `getRssi()` | **RSSI** dBm | −113 to −51 | 29 | Often absent; can be estimated from RSRP + 10·log10(12·N_RB) |
| `getTimingAdvance()` | TA | 0–1282 | 17 | Serving cell only. Distance ≈ TA × 78.125 m |
| `getLevel()` | 0–4 bars | 17 | Consumer abstraction — display only, never log as a KPI |

**Unavailable values come back as `Integer.MAX_VALUE` or `CellInfo.UNAVAILABLE`, not null and not
zero.** Every getter needs a guard. A raw `Integer.MAX_VALUE` written into a CSV as an RSRP is the
single most likely bug in Phase 1.

---

## 3. 5G NR fields

`CellInfoNr` — added API 29. Note the awkward API: `getCellIdentity()` returns the base
`CellIdentity` type, so it must be cast to `CellIdentityNr`.

### `CellIdentityNr`

| Method | KPI | Min API | Notes |
|---|---|---|---|
| `getNci()` | NR Cell Identity, 36-bit | 29 | Returns `long`. gNB ID length is operator-configured, typically 22–32 bits |
| `getPci()` | Physical Cell ID, 0–1007 | 29 | Wider range than LTE |
| `getTac()` | Tracking Area Code | 29 | |
| `getNrarfcn()` | **NR-ARFCN** | 29 | |
| `getBands()` | **NR band array** | 30 | n41, n77, n78, n71, n260, n261 etc. |
| `getMccString()` / `getMncString()` | PLMN | 29 | |

### `CellSignalStrengthNr`

| Method | KPI | Range | Notes |
|---|---|---|---|
| `getSsRsrp()` | **SS-RSRP** dBm | −140 to −44 | SSB-based — the primary NR coverage KPI |
| `getSsRsrq()` | **SS-RSRQ** dB | −43 to 20 | |
| `getSsSinr()` | **SS-SINR** dB | −23 to 40 | |
| `getCsiRsrp()` | CSI-RSRP dBm | | CSI-RS based. Frequently unavailable |
| `getCsiRsrq()` | CSI-RSRQ dB | | |
| `getCsiSinr()` | CSI-SINR dB | | |

All are API 29+. `getCsiCqiReport()` and `getTimingAdvanceMicros()` exist at API 31+ but are
sparsely implemented.

---

## 4. NSA vs SA — the part that trips people up

In 5G **NSA**, the device is registered on LTE (the anchor) with an NR secondary cell group added.
Two consequences:

1. `getAllCellInfo()` reports the **LTE anchor** as the registered cell. The NR SCG often does not
   appear at all, or appears with identity but no signal values. Coverage varies wildly by chipset
   and vendor — this is the single biggest source of "why doesn't my app show 5G" confusion.
2. To know NSA is even active, read the NR state rather than the cell list.

### Reading NR state

```
val ss: ServiceState = telephonyManager.serviceState
val nri = ss.getNetworkRegistrationInfo(
    NetworkRegistrationInfo.DOMAIN_PS,
    AccessNetworkConstants.TRANSPORT_TYPE_WWAN
)
val nrState = nri?.nrState   // NONE / RESTRICTED / NOT_RESTRICTED / CONNECTED
```

`NR_STATE_CONNECTED` means NSA is actively carrying data. `NOT_RESTRICTED` means the network
advertises NR availability but no SCG is added. API 30+.

### Distinguishing SA

If `nri.accessNetworkTechnology == TelephonyManager.NETWORK_TYPE_NR`, the device is registered
**standalone** on NR. If it is `NETWORK_TYPE_LTE` and `nrState == CONNECTED`, it is NSA. This is
the correct discriminator — the UI "5G" icon is not.

### `TelephonyDisplayInfo` (API 30+)

`getOverrideNetworkType()` returns the marketing-layer override the status bar uses:
`OVERRIDE_NETWORK_TYPE_NR_NSA`, `NR_ADVANCED` (mmWave or high-band), `LTE_CA`, `LTE_ADVANCED_PRO`,
`NONE`. Useful for a "what the user sees" column, and it is how we detect carrier aggregation
without CA-specific APIs, which do not exist for third-party apps.

---

## 5. Carrier aggregation

There is **no public API listing component carriers**. Partial signals available:

- `getAllCellInfo()` sometimes returns multiple non-registered `CellInfoLte` entries on the same
  PLMN with different EARFCNs — these are candidate SCells, not guaranteed.
- `TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA` indicates CA is active but not the count or
  the bands.
- `CellIdentityLte.getBandwidth()` gives the PCell bandwidth only.

Log what is observable and be honest in the UI that CA detail requires vendor tooling.

---

## 6. Permissions

Manifest:

```
android.permission.ACCESS_FINE_LOCATION
android.permission.ACCESS_COARSE_LOCATION
android.permission.ACCESS_BACKGROUND_LOCATION
android.permission.READ_PHONE_STATE
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_LOCATION
android.permission.INTERNET
android.permission.ACCESS_NETWORK_STATE
android.permission.POST_NOTIFICATIONS
```

Gotchas:

- `ACCESS_FINE_LOCATION` is what unlocks cell identity. Coarse alone returns redacted identity
  fields. Without it, `getAllCellInfo()` returns an empty list on Android 10+.
- `ACCESS_BACKGROUND_LOCATION` must be requested **separately and after** foreground location is
  already granted. It opens the system settings page — it cannot be granted in a normal dialog.
- Android 14+ requires `foregroundServiceType="location"` on the service element and a matching
  permission, or the service throws at start.
- Play Store submission requires a written justification and a demo video for background location.
  Not an issue while sideloading for consulting use.

---

## 7. Sample record schema (CSV v1 draft)

One row per sample. Nulls written as empty, never as `Integer.MAX_VALUE`.

```
timestamp_utc, session_id, seq,
lat, lon, alt_m, gps_accuracy_m, speed_mps, bearing_deg,
rat, nr_state, override_network_type, is_roaming,
mcc, mnc, operator,
lte_ci, lte_enb_id, lte_pci, lte_tac, lte_earfcn, lte_band, lte_bw_khz,
lte_rsrp, lte_rsrq, lte_rssnr, lte_rssi, lte_cqi, lte_ta,
nr_nci, nr_pci, nr_tac, nr_arfcn, nr_band,
nr_ss_rsrp, nr_ss_rsrq, nr_ss_sinr, nr_csi_rsrp, nr_csi_rsrq, nr_csi_sinr,
neighbor_count, neighbors_json,
dl_mbps, ul_mbps, latency_ms, jitter_ms, loss_pct,
note
```

`neighbors_json` keeps a compact array of `{rat, pci, earfcn|arfcn, rsrp, rsrq}` so the flat schema
stays readable in Excel while neighbor detail survives for analysis.

---

## 8. Device dependency — expect variance

Field completeness is not uniform, and it is set by **two independent things**: the modem, which
decides what data exists, and the Android build, which decides how faithfully the framework
surfaces it. A device can be good at one and bad at the other.

| | Modem data | Framework fidelity |
|---|---|---|
| Qualcomm + AOSP-like build | Best | Best |
| **Pixel 6–9 (Exynos 5123 modem, stock AOSP)** | **Middling** | **Best** |
| Samsung US flagships (Qualcomm, One UI) | Best | Good — richest neighbor lists |
| MediaTek midrange | Weakest | Variable |

Specific to the **Pixel 6 Pro** — our test device:

- Framework behavior is textbook. Stock AOSP means `getAllCellInfo()`, `CellSignalStrengthNr`, and
  `NetworkRegistrationInfo.getNrState()` behave exactly as documented. Excellent for development.
- **Neighbor cell lists are sparse.** Pixels historically report few LTE neighbors and often zero
  NR neighbors. Do not treat an empty neighbor list as a bug until cross-checked on another handset.
- NR SCG reporting under NSA is inconsistent on the Exynos 5123 — identity may appear without
  signal values, or the SCG may not appear at all. Section 4 applies in full.
- CQI is usually unavailable. Timing advance is usually present on LTE.

Conclusion: the Pixel 6 Pro is the right device to *build* on and the wrong device to *conclude*
from. Validate field coverage on a Qualcomm Samsung before deciding a field is unobtainable.

Other OEM quirk worth knowing: some devices return stale `getAllCellInfo()` results until a
`requestCellInfoUpdate()` call forces a refresh.

Phase 1 must therefore include a **capability probe screen** that shows which fields the current
handset actually populates. This is not optional polish — it is how we avoid chasing bugs that are
really vendor gaps, and it becomes a genuine product feature.
