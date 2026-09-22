#!/usr/bin/env python3
"""
Build and decode Qualcomm DIAG frames.

Framing, per the open-source references (QCSuper / SCAT / MobileInsight):
  frame = escape( payload + crc16_x25(payload) little-endian ) + 0x7E
  escape: 0x7E -> 0x7D 0x5E, 0x7D -> 0x7D 0x5D

The CRC is CRC-16/X-25: poly 0x1021 reflected (0x8408), init 0xFFFF, reflected
in and out, final xor 0xFFFF. It is checked against the standard "123456789"
vector below before anything is sent to a modem, because a wrong CRC produces a
silent non-response that looks exactly like "the channel does not work".
"""
import os
import sys

TRAILER = 0x7E
ESC = 0x7D


def crc16_x25(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0x8408 if crc & 1 else crc >> 1
    return crc ^ 0xFFFF


assert crc16_x25(b"123456789") == 0x906E, "CRC-16/X-25 check vector failed"


def escape(data: bytes) -> bytes:
    out = bytearray()
    for b in data:
        if b in (TRAILER, ESC):
            out += bytes([ESC, b ^ 0x20])
        else:
            out.append(b)
    return bytes(out)


def unescape(data: bytes) -> bytes:
    out = bytearray()
    i = 0
    while i < len(data):
        if data[i] == ESC and i + 1 < len(data):
            out.append(data[i + 1] ^ 0x20)
            i += 2
        else:
            out.append(data[i])
            i += 1
    return bytes(out)


def build(payload: bytes) -> bytes:
    crc = crc16_x25(payload)
    return escape(payload + bytes([crc & 0xFF, (crc >> 8) & 0xFF])) + bytes([TRAILER])


def decode(frame: bytes):
    """Split on 0x7E, unescape each, verify CRC. Returns list of (payload, crc_ok)."""
    out = []
    for chunk in frame.split(bytes([TRAILER])):
        if not chunk:
            continue
        raw = unescape(chunk)
        if len(raw) < 3:
            out.append((raw, False))
            continue
        payload, got = raw[:-2], raw[-2] | (raw[-1] << 8)
        out.append((payload, crc16_x25(payload) == got))
    return out


# Read-only commands. Neither changes any modem state.
REQUESTS = {
    "verno": bytes([0x00]),          # DIAG_VERNO_F - version info
    "extbuild": bytes([0x7C]),       # DIAG_EXT_BUILD_ID_F - build id string
}

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "decode":
        data = open(sys.argv[2], "rb").read()
        print("raw %d bytes: %s" % (len(data), data[:80].hex(" ")))
        if not data:
            print("EMPTY - nothing came back")
            sys.exit(0)
        for payload, ok in decode(data):
            print("\npayload %d bytes, CRC %s" % (len(payload), "OK" if ok else "BAD"))
            print("  hex : %s" % payload[:96].hex(" "))
            txt = "".join(chr(c) if 32 <= c < 127 else "." for c in payload)
            print("  text: %s" % txt[:120])
            if payload:
                print("  opcode: 0x%02X" % payload[0])
        sys.exit(0)

    outdir = sys.argv[1] if len(sys.argv) > 1 else "."
    for name, payload in REQUESTS.items():
        frame = build(payload)
        path = os.path.join(outdir, "req_%s.bin" % name)
        open(path, "wb").write(frame)
        print("%-10s payload=%s frame=%s -> %s"
              % (name, payload.hex(" "), frame.hex(" "), path))
