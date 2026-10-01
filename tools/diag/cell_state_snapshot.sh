#!/usr/bin/env bash
#
# Read-only serving-cell state snapshot, via standard Android telephony only
# (dumpsys telephony.registry). No root, no /dev/diag, no modem-firmware access --
# this reads the same public cell-state telemetry any signal app sees.
#
# Purpose: run it with an external cell lock ON, then again with it OFF, to see
# what the lock changes about how the handset behaves on the network -- which
# cell/frequency it serves on, its neighbour set, radio technology and signal.
# It does NOT inspect the modem's EFS/NV config and is not a way to derive how a
# lock is stored; it only observes the outcome.
#
# usage: cell_state_snapshot.sh <label>   e.g. cell_state_snapshot.sh lock-on
#
set -euo pipefail

ADB="${ADB:-$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
LABEL="${1:-snapshot}"
TS=$(date +%Y%m%d_%H%M%S)
OUTDIR="${OUTDIR:-.}"
RAW="$OUTDIR/.cell_state_raw_$$.txt"
OUT="$OUTDIR/cell_state_${LABEL}_${TS}.txt"

# One dumpsys call; extract locally so a flaky adb link is hit only once.
"$ADB" shell dumpsys telephony.registry > "$RAW"

{
    echo "=== cell state: $LABEL @ $TS ==="
    echo
    echo "--- radio tech / registration / serving channel / operator ---"
    grep -oE 'getRilDataRadioTechnology=[0-9]+\([A-Za-z_]+\)|mDataRegState=[0-9]+\([A-Za-z_]+\)|mVoiceRegState=[0-9]+\([A-Za-z_]+\)|mChannelNumber=[0-9]+|mOperatorAlphaLong=[^,]*' "$RAW" | sort -u
    echo
    echo "--- NR cell identities seen (serving + observed) ---"
    grep -oE 'CellIdentityNr:\{[^}]*' "$RAW" | sort -u
    echo
    echo "--- LTE cell identities seen (serving + observed) ---"
    grep -oE 'CellIdentityLte:\{[^}]*' "$RAW" | sort -u
    echo
    echo "--- serving signal ---"
    grep -oE 'rsrp=-?[0-9]+ rsrq=-?[0-9]+ rssnr=-?[0-9]+|ssRsrp = -?[0-9]+ ssRsrq = -?[0-9]+ ssSinr = -?[0-9]+' "$RAW" | sort -u
} > "$OUT"

rm -f "$RAW"
echo "wrote $OUT"
cat "$OUT"
