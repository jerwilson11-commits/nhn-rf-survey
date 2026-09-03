"""
Logs the OS's own view of the serving cell during a walk, over adb.

## Why this exists

The comparison walk runs several measurement apps at once on one handset. When two of them
disagree, nothing in either app can settle which is right -- they are both applications reading the
same platform API, and Android rate-limits how often that API actually reaches the modem, so a
disagreement may only mean one of them got a cached value.

`dumpsys telephony.registry` is not another opinion. It is what the framework itself believes about
the serving cell at that instant, which is the same source every one of these apps is reading
downstream of. It is the arbiter, not a fourth contestant.

The same rule from the Wi-Fi work applies and is the reason this is a poll rather than a listener:
a push cache answers "what did I last hear"; a pull answers "what is true now."

## Scope

Deliberately small. Serving cell only -- level, quality, band, PCI, channel, RAT -- because that is
what the apps display side by side and therefore all this needs to adjudicate. Neighbours are the
app's job.

## Privacy

Writes to the scratchpad, not the repo. The output carries no location, but it does carry a
timestamped record of which cells served this handset, so treat it like the session CSVs: it does
not get committed.
"""
import argparse
import re
import subprocess
import sys
import time
from datetime import datetime, timezone

def adb(args, serial=None):
    cmd = ["adb"] + (["-s", serial] if serial else []) + args
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=25)
    return out.stdout


UNAVAILABLE = {"2147483647", "-2147483648"}

# Values are spaced two different ways in the same dump: LTE prints "rsrp=-96" and NR prints
# "ssRsrp = -96". One pattern has to tolerate both.
def _num(text, key):
    m = re.search(re.escape(key) + r"\s*=\s*(-?\d+)", text)
    if not m:
        return ""
    return "" if m.group(1) in UNAVAILABLE else m.group(1)


def _best_signal_block(text):
    """
    Pick the populated mSignalStrength block.

    There is more than one -- the dump carries a block per phone/SIM slot, and on this handset the
    first is entirely Integer.MAX_VALUE because that slot is empty. Taking the first match is how
    the initial version of this script logged nothing but the RAT: it was reading a real block that
    genuinely had no values in it. Score each and take the fullest.
    """
    blocks = re.findall(r"mSignalStrength=SignalStrength:\{.*?primary=\w+\}", text)
    best, best_score = "", -1
    for b in blocks:
        score = sum(1 for v in re.findall(r"=\s*(-?\d+)", b) if v not in UNAVAILABLE)
        if score > best_score:
            best, best_score = b, score
    return best


def _registered_cell(text):
    """
    The serving cell as it appears in mCellInfo, which is a different surface from
    mSignalStrength and does not always agree with it -- a 5 dB difference between the two was
    observed on this handset at a single instant. Both are logged rather than one being called
    correct, because which one an app displays is a design decision, not an error.
    """
    m = re.search(r"mCellInfo=\[(.*)\]", text, re.S)
    if not m:
        return ""
    for chunk in re.split(r"(?=CellInfo(?:Nr|Lte|Wcdma|Gsm):\{)", m.group(1)):
        if "mRegistered=YES" in chunk:
            return chunk
    return ""


# Cell identifiers -- mPci, mNrArfcn, mTac, mNci -- are redacted to [****] in an unprivileged
# shell dump on this platform version, so this cannot adjudicate PCI or channel. Band survives.
COLUMNS = [
    "rat", "nr_state", "primary", "bands", "level",
    "sig_ss_rsrp", "sig_ss_rsrq", "sig_ss_sinr",
    "sig_lte_rsrp", "sig_lte_rsrq", "sig_lte_rssnr",
    "reg_ss_rsrp", "reg_ss_rsrq", "reg_ss_sinr",
]


def snapshot(serial):
    """One pull of the framework's current signal state."""
    text = adb(["shell", "dumpsys", "telephony.registry"], serial)
    sig = _best_signal_block(text)
    reg = _registered_cell(text)

    nr = re.search(r"mNr=CellSignalStrengthNr:\{(.*?)\}", sig, re.S)
    lte = re.search(r"mLte=CellSignalStrengthLte:(.*?)(?:,mNr=|$)", sig, re.S)
    nr, lte = (nr.group(1) if nr else ""), (lte.group(1) if lte else "")

    primary = re.search(r"primary=CellSignalStrength(\w+)", sig)
    rat = re.search(r"mNetworkType=(\w+)", text)
    nrs = re.search(r"nrState=(\w+)", text)
    bands = re.search(r"mBands\s*=\s*\[([^\]]*)\]", reg)

    return {
        "rat": rat.group(1) if rat else "",
        "nr_state": nrs.group(1) if nrs else "",
        "primary": primary.group(1) if primary else "",
        "bands": (bands.group(1) if bands else "").replace(",", " "),
        "level": _num(nr, "level"),
        "sig_ss_rsrp": _num(nr, "ssRsrp"),
        "sig_ss_rsrq": _num(nr, "ssRsrq"),
        "sig_ss_sinr": _num(nr, "ssSinr"),
        "sig_lte_rsrp": _num(lte, "rsrp"),
        "sig_lte_rsrq": _num(lte, "rsrq"),
        "sig_lte_rssnr": _num(lte, "rssnr"),
        "reg_ss_rsrp": _num(reg, "ssRsrp"),
        "reg_ss_rsrq": _num(reg, "ssRsrq"),
        "reg_ss_sinr": _num(reg, "ssSinr"),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out", help="CSV to write")
    ap.add_argument("--interval", type=float, default=2.0, help="seconds between polls")
    ap.add_argument("--serial", default=None)
    args = ap.parse_args()

    names = COLUMNS
    with open(args.out, "w", encoding="utf-8", newline="") as f:
        f.write("timestamp_utc," + ",".join(names) + "\n")
        f.flush()
        print(f"logging every {args.interval}s -> {args.out}   (ctrl-C to stop)")
        n = 0
        try:
            while True:
                started = time.monotonic()
                try:
                    row = snapshot(args.serial)
                except subprocess.TimeoutExpired:
                    # A dropped cable mid-walk should not end the log; the gap is itself data.
                    row = {k: "" for k in names}
                ts = datetime.now(timezone.utc).isoformat(timespec="milliseconds")
                f.write(ts + "," + ",".join('"%s"' % row[k] for k in names) + "\n")
                f.flush()
                n += 1
                if n % 15 == 0:
                    print(f"  {n} samples   last: {row.get('rat','?')} "
                          f"sig={row.get('sig_ss_rsrp') or '-'} "
                          f"reg={row.get('reg_ss_rsrp') or '-'} b={row.get('bands') or '-'}")
                time.sleep(max(0.0, args.interval - (time.monotonic() - started)))
        except KeyboardInterrupt:
            print(f"\nstopped: {n} samples written to {args.out}")


if __name__ == "__main__":
    sys.exit(main())
