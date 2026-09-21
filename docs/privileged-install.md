# Privileged install (survey handset)

How the app gets `READ_PRECISE_PHONE_STATE` and `MODIFY_PHONE_STATE`, what that buys, and the one
step that silently undoes it.

Verified on the OnePlus 9 (LE2115), OxygenOS 14 build `LE2115_11.H.29_3290_202510271426`, rooted
with Magisk 29.0, on 2026-09-21.

## Why

Both permissions are `signature|privileged`. They are granted only to an app that is **installed
in `/system/priv-app`** *and* **named in a `privapp-permissions` XML on the same partition**.
Neither a normal install nor root alone is enough: root lets you place the files, but the grant
decision is made by the package manager at boot from those two facts.

## What it actually buys

| Surface | Result on this handset |
|---|---|
| `PhysicalChannelConfig` | Registers. `connectionStatus`, `networkType`, `frequencyRange` are real. `band`, `physicalCellId`, channel number and bandwidths are all UNKNOWN sentinels, under load, on NR SA. Vendor RIL does not populate them. |
| Carrier aggregation | The only evidence available. `ServiceState` reports `mCellBandwidths=[]` and `isUsingCarrierAggregation=false` regardless, so carrier count and primary/secondary role come from here or nowhere. |
| `setAllowedNetworkTypesForReason` | Callable, and **defeated by the vendor layer on this handset** — see below. |
| Band selection | Does not exist at any privilege level, on any handset. Modem NV / vendor diagnostic only. |

## The module

`/data/adb/modules/nhn_privapp/`

```
module.prop
system/priv-app/RFTestApp/RFTestApp.apk
system/etc/permissions/privapp-permissions-nhn.xml
```

The XML names every privileged permission the APK declares:

```xml
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="com.nhnengineering.rftest">
        <permission name="android.permission.READ_PRECISE_PHONE_STATE"/>
        <permission name="android.permission.MODIFY_PHONE_STATE"/>
    </privapp-permissions>
</permissions>
```

## The rule that will bite you

> **`adb install` breaks the privileged grant.**

Installing over the top creates a `/data/app` copy, which makes the package an
`UPDATED_SYSTEM_APP`. AOSP grants an updated system app a privileged permission *only if the
original system package also requested it*, and the recorded permission set for the disabled
system package is empty — `packages.xml` shows `<updated-package name="com.nhnengineering.rftest"
… />` self-closing, with no `<perms>` child.

Observed exactly this: with a `/data/app` update present, `READ_PRECISE_PHONE_STATE` was granted
(it had been in the original system APK) and `MODIFY_PHONE_STATE` was **not**. Removing the update
granted it immediately.

### Deploying a new build

```bash
# 1. build
./gradlew assembleDebug

# 2. replace the module's APK (NOT adb install)
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/new.apk
adb shell su -c '
  cp /data/local/tmp/new.apk /data/adb/modules/nhn_privapp/system/priv-app/RFTestApp/RFTestApp.apk
  chmod 644 /data/adb/modules/nhn_privapp/system/priv-app/RFTestApp/RFTestApp.apk
  chown 0:0 /data/adb/modules/nhn_privapp/system/priv-app/RFTestApp/RFTestApp.apk
  rm /data/local/tmp/new.apk'

# 3. reboot — the grant is decided at package scan, not at install
adb reboot
```

If you did `adb install` by mistake, `adb shell pm uninstall -k com.nhnengineering.rftest` removes
the update and reverts to the system copy. **`-k` keeps app data; without it you lose recorded
sessions**, which live in `/sdcard/Android/data/com.nhnengineering.rftest/files/sessions/`.

## Before every reboot: the bootloop check

`ro.control_privapp_permissions=enforce` on this handset. A privileged app declaring a privileged
permission that no allowlist covers **prevents boot**. So the allowlist XML must be updated
*before* the APK that declares the new permission — the deadline is the next reboot, not the next
install.

Check it like this. Do not use `pm list permissions`; it throws a NullPointerException partway
through on this build and returns a partial list that looks complete:

```bash
# what the installed package declares
adb shell dumpsys package com.nhnengineering.rftest \
  | sed -n '/requested permissions:/,/install permissions:/p'

# the protection level of each (this path works)
adb shell dumpsys package permission android.permission.MODIFY_PHONE_STATE
```

Every permission whose `prot=` contains `privileged` must appear in the XML. App-defined ones such
as `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` are `signature` and outside the check.

### If it does not boot

1. Hold **Volume Down** from the moment the OnePlus logo appears — Magisk core-only mode disables
   every module, including this one.
2. Failing that, `fastboot flash boot boot_oos14.img` (stock, sha256
   `5161456e3bc5b3b7b2b786db49b9a0b3512006cd1d8bfb574d54bb36298615a1`).

## Technology lock: works, and does nothing here

`MODIFY_PHONE_STATE` is granted and `setAllowedNetworkTypesForReason` is callable. On this handset
the write is accepted and then discarded:

```
calculatePreferredNetworkType: networkType = 840583      <- requested
OplusNetworkUtils: getOplusUserPreferredNetworkFromDb: nwMode = -1
OplusNetworkUtils: getNewPreferredNetworkMode, defaultNwMode = 33 newMode = 33
calculatePreferredNetworkType: networkType = 916479      <- back to everything
```

Nothing throws. OPlus recomputes the allowance from its own preferred-network store, which is
*not* `Settings.Global` — writing `oplus_user_preferred_network_mode4` there sticks, and the
telephony layer still reads `nwMode = -1`.

So on OxygenOS the app must not claim a lock it cannot hold. `TechnologyLock.lockHeld` decides by
observation and `TechnologyLockController.verify()` reports a failed lock as a finding about the
handset. A stock-Android device with no vendor override is the case where this is expected to
work, and it has not been tested on one — the Pixel 6 Pro is not rooted, so it has no privileged
install.
