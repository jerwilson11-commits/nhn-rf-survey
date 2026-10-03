#!/bin/bash
# Regenerate asn1c C from the vendored 38.331 Rel-17 ASN.1 and build libnrrrc.so for Android.
#
# Requirements (see README.md):
#   - A MODERN asn1c (mouse07410/asn1c fork). The Ubuntu-packaged 0.9.28 CANNOT parse the
#     [[ ... ]] extension-group syntax used throughout Rel-13+ 38.331.
#   - The Android NDK (set $NDK to its root).
#
# Output: build/<abi>/libnrrrc.so for each ABI. Copy each into app/src/main/jniLibs/<abi>/,
# checked in exactly like the tools/diag DIAG helpers.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$HERE/build}"
ABIS="${ABIS:-arm64-v8a x86_64}"
API="${API:-24}"
ASN1C="${ASN1C:-asn1c}"
: "${NDK:?set NDK to your Android NDK root (e.g. \$ANDROID_SDK/ndk/<ver>)}"

echo "== generate C with asn1c =="
rm -rf "$OUT/gen" && mkdir -p "$OUT/gen"
( cd "$OUT/gen" && "$ASN1C" -fcompound-names -fno-include-deps -gen-UPER -no-gen-example \
      -pdu=auto -D . "$HERE"/spec/*.asn )
rm -f "$OUT/gen/converter-sample.c"   # defensive; -no-gen-example should prevent it

host_tag() { case "$(uname -s)" in Linux) echo linux-x86_64;; Darwin) echo darwin-x86_64;; *) echo windows-x86_64;; esac; }
TOOLS="$NDK/toolchains/llvm/prebuilt/$(host_tag)/bin"

for abi in $ABIS; do
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android;;
    x86_64)    triple=x86_64-linux-android;;
    *) echo "unsupported abi $abi"; exit 2;;
  esac
  mkdir -p "$OUT/$abi"
  echo "== build $abi (API $API) =="
  "$TOOLS/clang" --target="${triple}${API}" -fPIC -O2 -shared -w \
      -I"$OUT/gen" -I"$HERE" \
      "$OUT/gen"/*.c "$HERE/nrrrc_decode.c" "$HERE/nrrrc_jni.c" \
      -o "$OUT/$abi/libnrrrc.so"
  echo "   -> $OUT/$abi/libnrrrc.so"
done

echo "Done. Install with: for a in $ABIS; do cp \"$OUT/\$a/libnrrrc.so\" app/src/main/jniLibs/\$a/; done"
