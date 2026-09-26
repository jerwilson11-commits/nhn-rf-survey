# Reaching the modem directly — spike result

Where the modem's diagnostic and control channels actually are on the OnePlus 9. OxygenOS 14,
rooted, Snapdragon 888 (SM8350). Established 2026-09-22.

## Why this matters

Three things the app cannot do through Android resolve to modem access:

| Need | Why Android can't | What modem access gives |
|---|---|---|
| Neighbour cells | `getAllCellInfo()` returns the serving cell alone on this handset, across all three surfaces | `QMI_NAS_GET_CELL_LOCATION_INFO`, which carries the neighbour lists |
| Technology lock | `setAllowedNetworkTypesForReason` is accepted, then recomputed away by `OplusNetworkUtils` | `QMI_NAS_SET_SYSTEM_SELECTION_PREFERENCE`, below the vendor framework layer |
| Band lock | No API at any privilege level, on any handset | The band-preference TLVs of that same message; proven, see below |

## Correction: the MHI pipe is not the modem

An earlier version of this document identified `/dev/mhi_1103_00.01.00_pipe_4` as the modem's DIAG
channel, on the strength of `/vendor/bin/diag-router` holding it open and bridging it to USB. That
was wrong, and the experiment below is how it was caught.

`1103` is a PCI device ID. The device tree resolves it:

```
/sys/bus/pci/devices/0000:01:00.0   vendor=0x17cb  device=0x1103
/sys/bus/mhi/devices/               1103_00.01.00_DIAG, _IPCR, _LOOPBACK
```

`17cb:1103` is the **WCN6855 Wi-Fi/Bluetooth combo**, attached over PCIe. Its MHI children include
a DIAG channel, which is what `pipe_4` is. The SM8350's X60 modem is *integrated* and is not on the
PCIe bus at all, so it was never going to be reachable there.

### The experiment that disproved it

Cellular DIAG requests were sent to `pipe_4` and nothing ever came back:

- `DIAG_VERNO_F` (0x00) HDLC-framed as `00 78 f0 7e`, and `DIAG_EXT_BUILD_ID_F` (0x7C) as `7c 93 49 7e`
- the same two commands unframed, in case HDLC was applied only at the USB boundary
- with `diag-router` running, and with it `SIGSTOP`ed so it could not consume the reply
- with `sys.usb.config` left at `mtp,adb`, and switched to `diag,adb` so the bridge was active
- a passive read with no request at all

Every combination: **0 bytes**. The writes were accepted by the driver (`0+1 records out, 4 bytes`),
which is exactly the trap — a write to a live channel belonging to the wrong processor succeeds and
is silently discarded.

The CRC-16/X-25 implementation was checked against the standard `"123456789"` → `0x906E` vector
before use, and the framing independently matches the well-known DIAG version-request frame, so the
silence was not a framing bug.

## Where the modem actually is: QRTR

The modem speaks **QRTR** (Qualcomm IPC Router), visible as `soc:modem.IPCRTR` on the rpmsg bus.
There is no `soc:modem.DIAG` channel and no `diagchar` driver on this kernel — which is why no
character device was ever going to work.

`/vendor/bin/qrtr-ns` runs at boot, and the vendor ships `qrtr-lookup`, which enumerates the
registered services. As root:

```
Service Version Instance Node  Port
   4097     N/A        1    0    34  DIAG service (MODEM:CMD)
   4097     N/A        3    0    38  DIAG service (MODEM:DCI_CMD)
      3       1        0    0    86  Network Access Service
      2       1        0    0    98  Device Management Service
```

So both paths exist, over QRTR sockets (`AF_QIPCRTR`, family 42):

- **DIAG service 4097** — `MODEM:CMD` and `MODEM:DCI_CMD`. This is how `diag-router` reaches the
  modem; its dozens of open sockets are QRTR, not character devices.
- **QMI NAS, service 3** — the Network Access Service.

`qrtr-lookup` returning this at all is proof that a userspace process with root can talk QRTR here.

## Recommended target: QMI NAS, not raw DIAG

QMI NAS is the better first target of the two, for all three needs:

- `QMI_NAS_GET_CELL_LOCATION_INFO` (0x0043) returns serving and neighbour cell lists.
- `QMI_NAS_SET_SYSTEM_SELECTION_PREFERENCE` (0x0033) carries both RAT preference and band
  preference TLVs — technology lock *and* band lock in one message.

It is a request/response protocol with typed TLVs, fully documented by **libqmi** (open source), so
no log masks have to be constructed and no log-packet formats reverse-engineered. Raw DIAG remains
the richer channel for continuous measurement logging, and is the sensible second step.

## Confirmed working: QMI NAS over QRTR

`tools/diag/qmi_probe.c` sends one QMI request over an `AF_QIPCRTR` socket and dumps the response.
Built with `tools/diag/build_qmi_probe.sh`, run as root. Against NAS at node 0 port 86 with
`QMI_NAS_GET_CELL_LOCATION_INFO` (0x0043):

```
bound as node 1 port 18110
sending 7 bytes: 00 01 00 43 00 00 00
received 55 bytes from node 0 port 86
QMI header: type=0x02 (response) txn=1 msg_id=0x0043 len=48
  TLV 0x02 result: SUCCESS (result=0 error=0)
  TLV 0x2e: da 0b 06 00
  TLV 0x2f: 13 00 62 81 f9 00 17 c0 ec 88 01 00 00 00 a1 03 92 ff a4 fc 69 00
  TLV 0x32: 33 31 30 32 36 30            ("310260")
```

Over QRTR there is no QMUX header and no QMI_CTL client-id allocation: the socket's port is the
client, so a request is just the 7-byte QMI header plus TLVs.

### Verified against an independent source

The decode was checked against `dumpsys telephony.registry` read at the same moment, rather than
taken on plausibility:

| Field | Where in the response | QMI | Android |
|---|---|---|---|
| NR ARFCN | TLV 0x2e, u32 LE | 396250 | 396250 |
| NCI | TLV 0x2f bytes 6..13, u64 LE | 6592184343 | 6592184343 |
| TAC | TLV 0x2f bytes 3..5, u24 **BE** | 8517888 | 8517888 |
| PCI | TLV 0x2f bytes 14..15, u16 LE | 929 | 929 |
| PLMN | TLV 0x32, ASCII | 310260 | T-Mobile 310-260 |

Note TAC is big-endian where the surrounding fields are little-endian.

The three s16 values at bytes 16..21 read −110, −860 and 105 at 0.1-unit scale, consistent with
RSRQ −11.0 dB, RSRP −86.0 dBm and SNR 10.5 dB. The last of those changed between two consecutive
runs (105 then 100), which is what a live measurement does. These three are the least certain part
of the decode and should be confirmed against libqmi's field order before anything reports them.

## Technology lock over QMI: works, in three seconds

`QMI_NAS_SET_SYSTEM_SELECTION_PREFERENCE` (0x0033) with the RAT mode preference TLV does what the
framework API could not. The radio moved from NR SA to LTE on the first poll:

```
sending 16 bytes: 00 01 00 33 00 09 00 11 02 00 10 00 17 01 00 00
  TLV 0x02 result: SUCCESS
  t+3s  getRilDataRadioTechnology=14(LTE)
```

- **TLV 0x11** — RAT mode preference, u16 bitmask. Read back as `0x005F` on this handset, which
  decodes as CDMA-1x | HRPD | GSM | UMTS | LTE | NR with TD-SCDMA absent, and matches the modem RAF
  logged by the framework (`modemRafBitMask` → `UMTS|EvDo|1xRTT|LTE|GSM|LTE_CA|NR`) — an
  independent confirmation of the bit assignment. Bits: 1<<0 CDMA-1x, 1<<1 HRPD, 1<<2 GSM,
  1<<3 UMTS, 1<<4 LTE, 1<<5 TD-SCDMA, 1<<6 NR. LTE only is `0x0010`.
- **TLV 0x17** — change duration, u8. `00` = until power cycle, `01` = permanent. Always write
  `00` from a tool: it makes a reboot the backstop for anything that goes wrong.

Contrast with the framework path, which is accepted and then recomputed away by `OplusNetworkUtils`
within a second. QMI sits under that layer, so the vendor framework does not get a vote. Restoring
`0x005F` afterwards put the handset straight back onto NR SA n25.

This is the technology lock the app wants. It also means `TechnologyLockController.verify()` stays
exactly as valuable: on this handset the framework route silently fails, and only observation
distinguishes the two.

## Neighbour cells: confirmed, on LTE

With the radio held on LTE, `GET_CELL_LOCATION_INFO` returns the neighbour TLVs that never appear
on NR SA. Three samples, four seconds apart:

| | EARFCN | PCI | RSRP | RSRQ | RSSI |
|---|---|---|---|---|---|
| serving (TLV 0x13) | 1300 | 114 | −89.8 dBm | −9.7 dB | −65.0 dBm |
| neighbour (TLV 0x14) | 1000 | 288 | −92.5 dBm | −14.0 dB | −69.0 dBm |
| neighbour (TLV 0x14) | 1000 | 368 | −102.1 dBm | −18.0 dB | −73.7 dBm |

PCI 288 is the cell previously observed by hand on LTE B2 in this project, which corroborates the
decode from outside the tool.

### TLV layouts, and how far to trust them

Both structures consume **exactly** their declared length, which is the check that makes a
byte-offset decode credible rather than merely plausible.

`TLV 0x13`, LTE intra-frequency — 19 fixed bytes then a cell array:

```
u8  ue_in_idle
u8[3] plmn (BCD, nibble-swapped: 13 00 62 -> 310-260)
u16 tracking_area_code
u32 global_cell_id
u16 earfcn
u16 serving_cell_id
u8  cell_reselection_priority, s_non_intra_search, serving_cell_low_threshold, s_intra_search
u8  cell_count
  per cell (10 bytes): u16 pci, s16 rsrq, s16 rsrp, s16 rssi, s16 cell_selection_rx_level
```

`TLV 0x14`, LTE inter-frequency — a frequency array, each with its own cell array:

```
u8  ue_in_idle
u8  frequency_count
  per frequency: u16 earfcn, u8 priority, u8 high_threshold, u8 low_threshold, u8 cell_count
    per cell (10 bytes): same shape as above
```

All signal values are 0.1-unit scaled integers.

**Open: the serving EARFCN.** 1000 and 2300 map to Bands 2 and 4, which are T-Mobile's. The serving
cell's 1300 maps to Band 3, which this operator does not use in the US. Either the field is
something other than a plain EARFCN or the mapping needs care, so nothing should report a *band*
derived from it until that is resolved. The neighbour EARFCNs are not in doubt.

## Band lock: proven on the OnePlus 9 (2026-09-26)

The `GET_SYSTEM_SELECTION_PREFERENCE` (0x0034) response carries the band masks, and
`SET_SYSTEM_SELECTION_PREFERENCE` (0x0033) writes them:

- **TLV 0x15**, 8 bytes: LTE band preference (bands 1-64). Written with TLV 0x15 in a SET.
- **TLV 0x23** (get) / **0x24** (set), 32 bytes: extended LTE band preference.
- **TLV 0x2c** (get) / **0x2f** (set), 64 bytes: NR5G SA band preference. Eight u64 words, bit N of
  word K is band 64K+N+1 (n66 and n71 are bits 1 and 6 of word 1).
- **TLV 0x2d** (get) / **0x30** (set), 64 bytes: NR5G NSA band preference.

Decoded baseline on this handset: SA n1 n2 n3 n5 n7 n20 n25 n28 **n41** n48 n66 n71 n77 n78; LTE
B1-5,7,8,12,13,17-20,25,26,28,30,32,38-41,46,48 (no B66/B71 in the base mask, although the radio has
camped on B66 during a walk).

### What was tried, and what the modem said

| Write | Result |
|---|---|
| `15:` = Band 4 only (`0800000000000000`) + `17:00` | **Accepted.** Serving cell moved from EARFCN 1000 (B2, PCI 288) to EARFCN 2350 (B4, PCI 114) and stayed. Restoring `15:df180fabe0a10000` moved it back. |
| `15:` = 0 with `24:` band 66 only | Error 48 (InvalidArgument): the base LTE mask cannot be all zero. |
| `24:` zeros beside a non-zero `15:` | Error 48. |
| `24:` with any bit in word 1 (B66) | Error 48. Bands above 64 cannot be selected this way. |
| `2f:` (n41 only) alone | Error 17 (MissingArgument). |
| `2f:` + `30:` (NSA as read) + `17:00`, no `11:` | Error 17 (MissingArgument). |
| `11:5f00` + `2f:` (n41 only) + `30:` (NSA as read) + `17:00` | **Accepted.** SA mask read back as n41 only; the phone left SA n25 for LTE (n41 is not reachable at the test desk). Restoring the SA mask brought NR back. |
| `11:5f00` + `2f:` **all zero** + `30:` (NSA as read) + `17:00` | **Accepted** (unlike the all-zero LTE mask). SA mask read back empty. The phone left SA n25 for LTE B2. Under a 25 MB download the carrier list was LTE PrimaryServing + NR SecondaryServing (EN-DC = NSA); the NR carrier vanished when traffic stopped. Restoring the SA mask brought SA n25 back. |

**5G NSA only (hand-verified 2026-09-26):** mode `0x5F` with an empty SA mask leaves NR reachable
only through an LTE anchor. In idle it looks like plain LTE with a 5G indicator; the NR leg exists
only while data flows, so verification must be made under traffic (physical channel configs) or by
reading the SA mask back, not from an idle snapshot.

In the app as "5G NSA only" (`TechnologyLock.Technology.NSA_ONLY`), written as the baseline mode
with the SA mask emptied. Exercised through the UI on 2026-09-26: masks read back as expected;
under a 25 MB download the carriers were LTE primary + NR secondary; switching straight to "5G SA
only" gave the SA mask back before narrowing the mode and the phone returned to SA n25; Automatic
restored mode, SA and NSA masks exactly. NSA-only and an NR band lock both need the SA mask, so the
UI refuses to hold both at once rather than let two baselines restore each other wrongly.

So NR SA band lock needs Mode Preference (0x11) in the same request. It must be the value already
in force, or the write changes technology as a side effect.

### Not possible over this interface

A specific EARFCN or ARFCN. QMI NAS carries band masks, not channel lists. Channel-level control
would be a separate DIAG/NV investigation, not yet started.

### App implementation

`QmiSelectionPreference` (mask codec and write builders), `QmiNasClient` (shared root transport),
`BandLock` (validation and the recorded label), `BandLockController` (baseline capture, write,
read-back, restore). Restores write back the bit patterns that were read. Every write uses change
duration `00`. The controller's read-back confirms what the modem is allowed to use; the report
separately checks the bands the session actually saw against the label.

Observed 2026-09-26: a GET issued immediately after a SET can still return the old value, so
release messages report what was written, not an instant read-back.

## Which carrier profile is the modem running? (PDC, 2026-09-26)

The PDC service (QMI service 36) reports the modem's selected software configuration. It answers
in two steps: a request returns only a result code, and the data arrives as a later *indication*
to a client that registered for indications on the same socket. `qmilock` cannot do that (one
request, one reply, and the app depends on that), so `tools/diag/qmiseq.c` is a research tool that
sends several requests on one socket and keeps listening.

    qmiseq 0 <pdc-port> 4000 20 10:01 -- 22 01:01000000 10:01000000

Message ids and TLVs are from libqmi's `qmi-service-pdc.json`: Register 0x20 (TLV 0x10 enable
reporting), Get Selected Config 0x22, List Configs 0x24, Get Config Info 0x28. Config type 1
returned a config; type 0 returned error 16, so 1 is the software type here (the enum values are
not in the JSON; this was found by trying both).

Result on the OnePlus 9 (LE2115, OOS 14, T-Mobile SIM 310/260):

- Active software config: **Commercial-TMO**, version 0x0a010511, 20-byte id `cea020c8...ba4a`,
  chosen from 25 stored profiles.
- Android's framework carrier config is also T-Mobile's: `additional_nr_advanced_bands_int_array =
  [41]`, the 5G+ icon for mmWave, NSA and SA both available.
- `ro.oplus.image.my_carrier.type` is `empty`: OnePlus's own carrier-overlay layer is unbranded.
  What a T-Mobile (LE2117) image puts in that layer is not known and was not extracted.

So the modem and the framework are already T-Mobile-configured on a global LE2115. The "international
config" explanation for the phone staying on B2/n25 does not hold at the modem level.

## NSA n41 at the test desk (2026-09-26)

Question: can the phone reach n41, and does restricting the NSA mask (TLV 0x30) control it?
Method: NSA-only (mode 0x5F, empty SA mask), a 40-60 MB download over the cellular interface, and
`dci_logger` on log 0xB97F. The NR leg's channel was identified from the packets: ARFCN x 5 kHz
below 3 GHz, and n41 (2496-2690 MHz) is ARFCN 499200-537999. The framework cannot say: the
secondary carrier's channel and bandwidth come back unknown.

| NSA mask (SA empty in all three) | NR leg under load |
|---|---|
| n41 only | ARFCN 501390 (2506.95 MHz, **n41**) in 49 of 49 packets |
| full baseline (control) | ARFCN 501390 in 51 of 51 packets; ARFCN 393422 (n25) once |
| n25 only | **no NR leg**: 0 packets, LTE-only carriers, data still flowed |

Reading it:

- **n41 is reachable at this desk, in NSA.** The network adds an n41 NR leg to the LTE anchor (B2).
  The control shows it does so without any band lock, so the n41-only result on its own proved
  nothing about the mask.
- **The mask does control the NR leg**: excluding n41 removed it. Whether the network would have
  added n25 had it been allowed is not shown; n25 was only ever seen once, as a measurement.
- **Why the phone looked stuck on n25:** in baseline (Automatic) it camps on *standalone* n25.
  That is a preference for SA, not an inability to reach n41. "5G NSA only" in the app already gives
  the n41 leg; no band mask is needed.
- The NR leg exists only while data flows. Idle, it is plain LTE.

Unexplained: once, before a write, the SA mask read `[66]` instead of the full list restored
minutes earlier by the app's release. Nothing this project wrote in between should have changed it.
It read the full list again after being rewritten and held for 90 s. A vendor layer rewriting the
mask is a possibility, not a finding.

Practical: the cellular interface number changes when the data network is re-created (rmnet_data1,
then rmnet_data2 after a mode/band write), so a load test must look it up each time.

## The handset's own 5G mode setting (2026-09-26)

Developer options > Networking > "5G network mode" (Automatic / NSA / NSA + SA) is stored in
`Settings.System user_nr_mode`. Observed by cycling it while polling the QMI masks and the radio:

| Selection | user_nr_mode | Radio |
|---|---|---|
| Automatic | 3 | NR standalone |
| NSA | 1 | LTE (NSA) within about 3 s |
| NSA + SA | 0 | NR standalone |

Choosing an option did **not** change the QMI mode preference (stayed 0x5F) or any band mask, so
OnePlus implements it on a path this project has not found. Wi-Fi off/on changed neither the SA mask
nor the radio, so the `disable_sa_when_wfc = true` preference in the phone app is not what holds
this handset off standalone.

Earlier the same day the SA mask read `[66]`, then `[]`, without this project writing it, and
the menu options had no effect while it was empty (the phone stayed NSA in all three). It did not
recur once the full mask was restored, in any of the observations above, so the writer is
unidentified. One candidate, not established: OnePlus's "smart 5G" policy
(`Settings.System oplus.radio.smart5g_sa_cfg`: `sa_pref_prohibit_t0=120`,
`sa_pref_prohibit_stage_num=5`, `irat_pingpong_restrain=true`) suppresses SA preference in growing
stages after LTE/NR ping-pong, which repeated mode and mask changes during testing could provoke.
If the phone sticks on NSA, read the SA mask first (`qmilock ... 0034`, TLV 0x2c): empty means
something wrote it, not that SA is unavailable.

## Voice over 5G standalone (VoNR) on this handset (2026-09-26)

State observed on the OnePlus 9 (LE2115, OOS 14) camped on SA NR with a T-Mobile SIM:

- IMS is registered (`isImsRegistered = true`) with `getImsRegistrationTechnology = 0`, which is
  LTE (0 = LTE, 1 = IWLAN, 2 = cross-SIM, 3 = NR). So voice registers on LTE even while data is on
  NR standalone. The status bar shows VoLTE, consistent with that.
- The IMS PDN (`T-Mobile IMS` APN, `rmnet_data3`) is up with network type NR, so a 5G data path for
  IMS exists. Registration is what is on LTE.
- VoNR is off in three places: `persist.radio.is_vonr_enabled_0 = false`, and in OnePlus's own
  T-Mobile carrier config (`oplus_carrier_name = us-tmobile`, loaded from the default carrier app)
  `vonr_enabled_bool = false` with `vonr_setting_visibility_bool = false`, so there is no user toggle.
  The same block enables VoLTE and Wi-Fi calling, and sets `carrier_oplus_auto_nr_mode = 3`
  (matches `user_nr_mode` 3 = Automatic).
- A vendor `OplusVonrDetector` reacts to service-state changes. The carrier config also carries
  `carrier_vonr_backoff = true` and `carrier_vonr_call_fail_threshold = 1`, i.e. VoNR is dropped
  after one failed call when it is on.

### Trial: enabling VoNR with a non-persistent override

`cmd phone cc` is refused on this build even as root (carrier-config override commands need a
debuggable build), so `tools/diag/VonrCfg.java` calls `ICarrierConfigLoader.overrideConfig` over
Binder as root (MODIFY_PHONE_STATE is satisfied by uid 0). It requests a non-persistent override,
so a reboot clears it. Build: javac against android.jar, then d8; run with
`CLASSPATH=vonrcfg.dex app_process /system/bin VonrCfg <subId> show|set|clear`. An earlier version
that bootstrapped a system Context via `ActivityThread.systemMain()` was killed by the system;
going straight to the service avoids that.

- Setting `persist.radio.is_vonr_enabled_0` by hand did nothing (registration stayed LTE), and the
  system writes that property itself. It is a result, not the lever.
- Overriding `vonr_enabled_bool = true` (and the setting visibility) was picked up immediately:
  `OplusVoNrSwitchBase onUpdateVoNrStateDone enabled=true isSuccess=true`,
  `NAS-VonrBackoffIssue vonrSupportByCfg = true`, and the system then set the property to `true`.
- Idle IMS registration technology still read 0 (LTE) afterwards, so that reading is not a VoNR
  indicator here.
- One ~20 s voice call on SA NR: connected, clean audio per the user, ended user-terminated with
  no error, `needsBackoff = false`, and the serving NR channel (ARFCN 427230) never changed during
  the call. No fallback to LTE was seen, which is what VoNR (as opposed to EPS fallback) looks like.

Limits: one call, one cell, one day. The carrier config drops VoNR after a single failed call
(`carrier_vonr_call_fail_threshold = 1`), so the vendor logic would switch it off again on its own
if a call fails.

### Made persistent (owner's decision, 2026-09-26)

After the test call the owner asked for VoNR to stay on across reboots, so `VonrCfg ... setp`
requests a persistent override. It is stored as
`/data/user_de/0/com.android.phone/files/carrierconfig-com.android.carrierconfig-override-<iccid>-310260-1.xml`
and contains only `vonr_enabled_bool = true` and `vonr_setting_visibility_bool = true`. It is
tied to the SIM (the file name carries the ICCID and PLMN).

To undo it: `VonrCfg <subId> clear` (removes both the persistent and non-persistent overrides), or
delete that file and reboot. The vendor's own backoff still applies: after one failed VoNR call
the carrier config switches VoNR off by itself, so a call failure does not need manual cleanup to
stop being a problem. If calls start failing on 5G standalone, run `clear` first.

## Why the status bar says VoLTE, and why SA n41 is not seen (2026-09-26)

**Status bar.** OnePlus's SystemUI (`/system_ext/priv-app/SystemUI/SystemUI.apk`, OOS 14) contains
`stat_signal_volte*` and `stat_signal_vowifi*` drawables and no VoNR drawable; the string "vonr" appears
nowhere in its code. The phone therefore cannot display VoNR: it draws VoLTE for IMS voice generally,
including on 5G standalone. The label is not evidence for or against VoNR. The evidence is behaviour:
the test call kept the same NR channel throughout.

**SA n41.** With the SA mask limited to n41 (mode 0x5F, NSA mask full) the phone stayed on LTE
(EARFCN 650) for 75 s, idle. The modem's NR measurement log (0xB97F) meanwhile showed n41 cells at
ARFCN 501390 (2506.95 MHz): PCI 206 at a median -91.7 dBm, RSRQ -10.4 dB; PCI 216 at -99.9 dBm; PCI 673
at -114 dBm. Signal is therefore not why standalone n41 is not chosen: PCI 206 is as strong as the
standalone cells the phone does camp on. The same PCI 206 is the NR leg in NSA (LTE B2 anchor). The
header of those packets marks PCI 206 as the NR "serving" cell even while the phone is on LTE.

Not established: whether the n41 cell offers SA access at all (SIB1 access flags), which is the
deciding fact. Captured NR RRC OTA (0xB821, 3 packets) and LTE RRC OTA (0xB0C0, 34 packets) during the
attempt, but the payloads are raw ASN.1 and were not decoded. Decoding SIB1 of PCI 206 (cell access
and SA/NSA indications) is the next step; a wider survey at other locations is the other.

Log format note: in 0xB97F the u16 at offset 36 has a flag in its high byte; the cell count is the low
byte only (e.g. 515 = 0x0203 is 3 cells), which is what makes `length - 64 == count * 60` hold.

## SA n41 on PCI 206: what is and is not established (2026-09-26)

New fact from the owner: an iPhone at the same desk is camped on **standalone n41, PCI 206, 100 MHz**.
So the network does offer SA on that cell (ARFCN 501390); the OnePlus is the one not taking it.

Established (measured):

- With mode 0x5F and the SA mask limited to n41, the phone stayed on LTE for 75 s (idle).
- With mode NR-only (0x40) and SA limited to n41, the phone was OUT_OF_SERVICE for the full 200 s and
  never camped. (An 80 s run earlier was too short: the phone was still on its n66 cell for the first
  ~50 s, so it proved nothing.)
- The cell is strong, PCI 206 about -90 dBm, RSRQ -10 dB, so level is not the reason.
- During the 200 s run the modem did receive broadcast messages from PCI 206 (NR RRC OTA 0xB821: a
  pdu-1 message of 27 bytes and a pdu-2 message of 156 bytes, both with PCI 206 / ARFCN 501390) and then
  made no connection attempt: no connected-mode messages from PCI 206 followed, while the n66 cell
  (PCI 929) produced several during its connected period.
- Packet header (0xB821 payload): PCI is u16 at offset 7, NR-ARFCN u32 at offset 9, PDU type at
  offset 16, and the ASN.1 begins at offset 24 (offset 20..23 carries length + 1).

Not established, and explicitly withdrawn: any decoded MIB or SIB1 content. `pycrate` (RRCNR) decodes
those bytes, but the result was checked against ground truth and failed: it reports the working n66
cell as `cellBarred: barred` and swaps the n66/n41 subcarrier spacings. The bit alignment of the logged
MIB/SIB1 is therefore unresolved, and a MIB decode cannot be validated by re-encoding because any 24
bits are a valid MIB. Do not quote cellBarred, SCS or SIB1 fields from this capture.

Next step that would settle it: calibrate the decoder on a cell known to work (capture the MIB and
SIB1 of the n66 cell PCI 929 while the phone re-selects it, find the alignment that decodes cleanly
and consistently), then apply it to PCI 206.

## Ground rules

Work from the open-source references: **libqmi** for QMI NAS, and QCSuper / SCAT / MobileInsight
for DIAG framing and log codes. Do not decompile a competitor's application — both a legal problem
and unnecessary given those references.

## Product consequence

QRTR access needs **root**, not the privileged install. These features are therefore permanently
outside a Play Store build, and per the decision of 2026-09-22 they are capability-gated inside the
one app, appearing only where the channel is reachable.

## In the app

Wired in behind the root gate on 2026-09-22.

- `tools/diag/qmihelper.c` — transport only: one QMI request over QRTR, reply printed as
  `OK <hex>` or `ERR <reason>`. Built with `tools/diag/build_qmi_probe.sh` and checked in as
  `app/src/main/assets/qmihelper-arm64-v8a` (8.6 KB, arm64 only). It is a prebuilt in the repo
  rather than a Gradle native build because the app deploys as a *system* app, where APK native
  libraries are not extracted the way `nativeLibraryDir` assumes. Source and build script sit
  beside it so it is reproducible.
- `modem/QmiCellParser.kt` — all decoding, in Kotlin, unit-tested against the captures above.
- `modem/ModemNeighbourSource.kt` — unpacks the helper into the app's files dir, runs it through
  `su` on a private thread at a throttled cadence, and caches the result. Magisk shows a Superuser
  prompt on the first run.
- Neighbours carry `CellSource.MODEM`, the session CSV gains `cell_neighbor_source`, and
  `SessionStats` gained `MEASURED_NONE` so a report can distinguish a measured absence of
  neighbours from an unmeasurable one.

### The trap this nearly walked into

On 5G NR SA the modem answers `GET_CELL_LOCATION_INFO` with SUCCESS and NR serving-cell TLVs that
this build does not decode. The first on-device session recorded `cell_neighbor_source=modem` with
zero neighbours on all 36 samples — which `SessionStats` would have promoted to `MEASURED_NONE`,
printing "No neighbour cells are present here… that is a measurement" for a site nobody measured.

`QmiCellParser.Result.lteInfoPresent` now records whether the response carried LTE cell TLVs at
all, and a read that decoded nothing about the current RAT does not count as availability. Decoding
the NR neighbour TLVs is the obvious next step; until then the app says so rather than implying a
measurement.

## NR neighbours: where they are not, and where they must be

Established 2026-09-22, after the LTE neighbour work landed.

### QMI NAS does not carry them

On 5G NR SA, `GET_CELL_LOCATION_INFO` (0x0043) returns four TLVs and no cell list of any kind:

```
TLV 0x02  result SUCCESS
TLV 0x2e  4 bytes   NR ARFCN
TLV 0x2f  22 bytes  NR serving cell (NCI, PCI, TAC, signal)
TLV 0x32  6 bytes   "310260"
```

Two other read-only NAS messages were checked and carry no cell arrays either: `GET_SYS_INFO`
(0x004D) returns 23 small fixed-size system-info TLVs, and `GET_SIG_INFO` (0x004F) returns serving
signal only. So there are no "NR neighbour TLVs" waiting to be decoded — the service does not
expose them on this modem, and no amount of parser work changes that.

### DIAG command/response does work

Service 4097 `MODEM:CMD` at node 0 port 34 answers. Framing over QRTR is
`7E 01 <len:u16le> <payload> 7E`, and the modem accepted a bare command and an HDLC-framed one
identically:

```
req 00  ->  "Mar 18 2025" "03:17:46" "Jul 25 2024" "11:00:00" "lahaina"
req 7c  ->  "MPSS.HI.4.3.c4-00234-LC_ALL_PACK-1.27136.146"
```

`lahaina` is Qualcomm's codename for the SM8350, so this is unambiguously the right processor —
the check the MHI mistake earlier in this document did not get.

### But log configuration is refused on that port

`DIAG_LOG_CONFIG_F` (0x73) with the retrieve-ranges operation comes back as
`13 73 00 00 00 01 00 00 00`: error code `0x13` followed by a verbatim echo of the request. A
control confirms the shape — an invalid command `fe` returns `13 fe`, while successful responses
echo the command code instead (`00…`, `7c…`). So 0x13 is bad-command and log masks cannot be set
here.

Port 38, `MODEM:DCI_CMD`, does not answer raw commands at all.

### What that means

Log packets are the only route to NR neighbour measurements, and reaching them needs the **DCI**
(Diag Client Interface) path: a registration handshake on port 38, then log-mask configuration,
then a stream of log packets to decode. That is a protocol, not a command, and it is the next
substantial piece of work — materially bigger than the QMI client, which was one request and a
parser.

Until it exists the app is correct to report that it cannot see NR neighbours, and
`QmiCellParser.Result.lteInfoPresent` is what keeps that honest.
