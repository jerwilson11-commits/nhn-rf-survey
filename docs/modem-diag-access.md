# Reaching the modem directly (DIAG) — spike result

Where the diagnostic channel actually is on the OnePlus 9, established 2026-09-22 on OxygenOS 14,
rooted, Snapdragon 888 (SM8350).

## Why this matters

Three things the app cannot do through Android all resolve to this one channel:

| Need | Why Android can't | What DIAG gives |
|---|---|---|
| Neighbour cells | `getAllCellInfo()` returns the serving cell alone on this handset, across all three surfaces | Modem measurement log packets, which list neighbours |
| Technology lock | `setAllowedNetworkTypesForReason` is accepted then recomputed away by `OplusNetworkUtils` | QMI NAS system-selection control, below the vendor framework layer |
| Band lock | No API at any privilege level, on any handset | QMI NAS band preference |

So "match NSG on neighbours, band lock and technology lock" is one subsystem, not three.

## The finding

There is **no `/dev/diag` and no `diagchar`** on this device — `/proc/devices` lists no diag
character device. That is not a missing feature; it is a different architecture. On SM8350 the
modem is attached over PCIe and exposed through **MHI** (Modem Host Interface), so the diagnostic
channel is an MHI character device:

```
crw-rw---- system system u:object_r:vendor_mhi_diag_device:s0  240,1  /dev/mhi_1103_00.01.00_pipe_4
crw------- root   root   u:object_r:vendor_mhi_device:s0       240,0  /dev/mhi_1103_00.01.00_pipe_0
```

`pipe_4` is DIAG. It was found by listing the open descriptors of `/vendor/bin/diag-router`
(started from `/vendor/etc/init/vendor.qti.diag.rc`), which holds it as fd 95 and bridges it to USB
through FunctionFS (`/dev/ffs-diag*`). That bridge is what the `diag,adb` USB composition
(PID 276C) exposes to a PC — the same data, routed off-device.

### It is not exclusive-open

A second process can open `pipe_4` while `diag-router` still holds it:

```
$ su -c 'exec 3< /dev/mhi_1103_00.01.00_pipe_4 && echo OPEN_OK'
OPEN_OK
```

This is the result that makes an on-device DIAG client viable. We do not have to stop
`diag-router`, break USB diagnostics, or fight another process for the channel.

## What this does and does not settle

Settled:

- The channel exists, is reachable on-device, and can be opened concurrently as root.
- Access requires **root**, not the privileged install. Mode is 0660 `system:system` with SELinux
  type `vendor_mhi_diag_device`, so an ordinary app — even a privileged one — cannot open it.

Not settled, and each is real work:

- **Framing.** The kernel `diagchar` driver used to handle HDLC framing and request routing. A raw
  MHI pipe does not, so the client owns framing, escaping and de-multiplexing itself.
- **Masks.** The modem emits nothing until told what to emit. `diag_mdlog` exists on the device but
  needs a mask file we do not have. Log masks have to be built and sent for the specific NR/LTE
  measurement log codes we want.
- **Nothing has been read from the pipe yet.** Opening it is not reading it. A bare read blocks,
  because no request has been sent.

## Ground rules

Work from the open-source references — QCSuper, SCAT and MobileInsight document the DIAG framing,
log codes and QMI messages. Do not decompile a competitor's application: it is both a legal problem
and unnecessary given those references.

## Product consequence

These features are root-only and permanently outside a Play Store build. Per the decision of
2026-09-22 they are capability-gated inside the one app, the same way the privileged install is —
they appear when the channel is reachable and are silently absent when it is not.
