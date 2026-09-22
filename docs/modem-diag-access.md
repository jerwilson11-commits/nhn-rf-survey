# Reaching the modem directly — spike result

Where the modem's diagnostic and control channels actually are on the OnePlus 9. OxygenOS 14,
rooted, Snapdragon 888 (SM8350). Established 2026-09-22.

## Why this matters

Three things the app cannot do through Android resolve to modem access:

| Need | Why Android can't | What modem access gives |
|---|---|---|
| Neighbour cells | `getAllCellInfo()` returns the serving cell alone on this handset, across all three surfaces | `QMI_NAS_GET_CELL_LOCATION_INFO`, which carries the neighbour lists |
| Technology lock | `setAllowedNetworkTypesForReason` is accepted, then recomputed away by `OplusNetworkUtils` | `QMI_NAS_SET_SYSTEM_SELECTION_PREFERENCE`, below the vendor framework layer |
| Band lock | No API at any privilege level, on any handset | The band-preference TLVs of that same message |

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

### What this does not yet give

**No neighbour TLVs were present in this response.** The handset was camped on NR SA with a single
serving cell, and only serving-cell TLVs came back. Whether neighbours appear in this message under
other conditions — on LTE, or on NR with measurable neighbours — is the next thing to establish,
and it is the open question for the neighbour feature. The classic LTE neighbour TLVs (0x13
intra-frequency, 0x14 inter-frequency) are simply absent here because the radio is not on LTE.

Band and technology lock are also untried: they need `QMI_NAS_SET_SYSTEM_SELECTION_PREFERENCE`
(0x0033) with encoded request TLVs, where this probe so far only sends empty requests. That is a
write to modem state and deserves the same care as the technology lock already in the app —
applied, then verified by observation, never assumed from a SUCCESS result.

## Ground rules

Work from the open-source references: **libqmi** for QMI NAS, and QCSuper / SCAT / MobileInsight
for DIAG framing and log codes. Do not decompile a competitor's application — both a legal problem
and unnecessary given those references.

## Product consequence

QRTR access needs **root**, not the privileged install. These features are therefore permanently
outside a Play Store build, and per the decision of 2026-09-22 they are capability-gated inside the
one app, appearing only where the channel is reachable.
