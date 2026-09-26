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
