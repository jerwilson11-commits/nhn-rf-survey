# Android 17 (API 37) — Impact on This Project

Checked against the official behavior-change pages 2026-08-28, after the Pixel 6 Pro took the
Android 17 update.

**Headline: nothing in Android 17 changes location permissions, Wi-Fi scanning, `NEARBY_WIFI_DEVICES`,
telephony APIs, `getAllCellInfo()`, or foreground services.** The measurement path is untouched, and
the collector code written for API 31+ stands as-is.

Four changes land near enough to matter.

---

## 1. `ACCESS_LOCAL_NETWORK` — matters at Phase 4

New runtime permission. Apps **targeting API 37** must hold it to exchange any traffic with devices
on the local network: mDNS discovery, raw sockets, LAN device connections. Optional in Android 16,
mandatory in 17.

Why it hits us: the Phase 4 speedtest is planned against a **self-hosted LibreSpeed server**. Run
that server on the internet and nothing changes. Run it **on-site on the LAN** — which is exactly
what you want for DAS and Private 5G acceptance, because it isolates the RAN from internet backhaul
and removes the ISP from the measurement — and the permission becomes required.

Mitigating detail: `ACCESS_LOCAL_NETWORK` sits in the **`NEARBY_DEVICES` permission group**, the
same group as `NEARBY_WIFI_DEVICES`, which we already request for Wi-Fi scanning. A user who has
granted that will not be prompted a second time.

Action: add to the manifest when we target 37. Not needed while targeting 36.

## 2. Target SDK decision — stay at 36 for now

Behavior changes gated on `targetSdk` do not apply while we target 36, even running on an Android 17
device. That is normal, supported, and reduces moving parts during Phases 1–4.

| Setting | Value | Note |
|---|---|---|
| `minSdk` | 31 | Unchanged — `TelephonyCallback` |
| `compileSdk` | 36 | Safe regardless of which platform SDKs Android Studio has |
| `targetSdk` | **36** | Bump to 37 deliberately, as its own task, once Phase 4 is done |

The 37 migration is tracked in the Master file's open items. Doing it later is a scoped afternoon;
doing it now would front-load two unrelated problems into the learning curve.

## 3. Adaptive layout enforcement — a UI constraint, not a blocker

Apps targeting 37 lose the ability to lock orientation or aspect ratio on large screens
(smallest-width > 600 dp). Phones are unaffected. Tablets would be.

Not a problem, and arguably a nudge in the right direction: a drive-test dashboard wants a landscape
layout anyway — wide numeric readouts and a map side by side beat a portrait column. Design the
dashboard responsive from the start and this costs nothing later.

## 4. RAM-based app memory limits — applies to all apps, already mitigated

Android 17 imposes device-RAM-based memory ceilings on all apps regardless of target. Exceeding one
shows as `"MemoryLimiter:AnonSwap"` in `ApplicationExitInfo.getDescription()`.

Relevant because a long walk test generates a lot of samples. We are already on the right side of
this: samples stream into **Room/SQLite** rather than accumulating in an in-memory list. Keep it
that way — never hold a whole session in RAM to build a CSV. Stream the export from a database
cursor.

---

## Not relevant, recorded so we do not re-check

- MessageQueue lock-free rewrite, and static final fields becoming unmodifiable — both only break
  apps using reflection into framework internals. We do not.
- SMS OTP 3-hour delay, Contact Picker, background audio hardening, touchpad pointer capture,
  cross-profile loopback blocking, keystore key limits — no overlap with this app.
- **Bluetooth RFCOMM:** for apps targeting 37, `InputStream.read()` on a `BluetoothSocket` now
  returns −1 on a closed or dropped connection instead of its previous behavior. Irrelevant now;
  becomes relevant only if the external-RF-scanner path in Master section 3 is ever pursued.

---

## Sources

- [Behavior changes: apps targeting Android 17+](https://developer.android.com/about/versions/17/behavior-changes-17)
- [Behavior changes: all apps](https://developer.android.com/about/versions/17/behavior-changes-all)
