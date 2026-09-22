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

## What is still unproven

Nothing has yet been sent over QRTR. The transport is confirmed reachable and the services are
confirmed registered; the QMI client itself — socket, control point allocation, TLV encoding — is
not written. Expect that to need a small native component, since `AF_QIPCRTR` is not reachable from
the Java/Kotlin socket API.

## Ground rules

Work from the open-source references: **libqmi** for QMI NAS, and QCSuper / SCAT / MobileInsight
for DIAG framing and log codes. Do not decompile a competitor's application — both a legal problem
and unnecessary given those references.

## Product consequence

QRTR access needs **root**, not the privileged install. These features are therefore permanently
outside a Play Store build, and per the decision of 2026-09-22 they are capability-gated inside the
one app, appearing only where the channel is reachable.
