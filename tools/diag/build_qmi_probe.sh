#!/usr/bin/env bash
# Cross-compile qmi_probe for the survey handset and push it.
#
# Needs the NDK. Install with the current CLI (the old `sdkmanager "ndk;<ver>"`
# form is deprecated, and its .bat wrapper splits the argument on the semicolon
# so the package is never found):
#
#     android sdk install ndk/<version>
#
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}"
SRC="$(cd "$(dirname "$0")" && pwd)/qmi_probe.c"
OUT="${1:-/tmp/qmi_probe}"
API="${API:-30}"

NDK_DIR=$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)
[ -n "$NDK_DIR" ] || { echo "no NDK under $SDK/ndk - run: android sdk install ndk" >&2; exit 1; }

# The NDK ships per-host prebuilts; pick whichever one is present.
BIN=""
for host in windows-x86_64 linux-x86_64 darwin-x86_64; do
    cand="$NDK_DIR/toolchains/llvm/prebuilt/$host/bin"
    [ -d "$cand" ] && { BIN="$cand"; break; }
done
[ -n "$BIN" ] || { echo "no llvm prebuilt under $NDK_DIR" >&2; exit 1; }

CC="$BIN/aarch64-linux-android$API-clang"
[ -x "$CC" ] || CC="$CC.cmd"
[ -e "$CC" ] || { echo "no aarch64 clang for API $API in $BIN" >&2; exit 1; }

echo "ndk: $NDK_DIR"
echo "cc : $CC"
"$CC" -O2 -Wall -Wextra -o "$OUT" "$SRC"
echo "built: $OUT"
file "$OUT" 2>/dev/null || true
