# NR RRC TDD decoder (`libnrrrc.so`)

On-device ASN.1 UPER decoder that turns a captured NR RRC message into the TDD UL/DL configuration,
as JSON, for the worldwide TDD auto-capture feature. Plan of record: `docs/nr-rrc-decoder-plan.md`.

This mirrors the `tools/diag/` pattern: the sources and build recipe live here; the built artifact
(`libnrrrc.so`) is checked into `app/src/main/jniLibs/<abi>/`. Unlike the DIAG helpers, this `.so` is
**pure computation (no root)** and loads in-process via `System.loadLibrary("nrrrc")`.

## What's here

- `spec/*.asn` — 3GPP TS 38.331 **v17.4.0 (h40, Rel-17)** NR RRC ASN.1, vendored from pycrate's
  `pycrate_asn1dir/3GPP_NR_RRC_38331/` (the exact grammar our pycrate cross-check oracle uses). This
  is the public 3GPP ASN.1; pycrate is only the delivery vehicle.
- `nrrrc_decode.c` / `nrrrc_decode.h` — the hand-written core: `nrrrc_decode_tdd_json(pduKind, buf,
  n)`. Does the nested decode and walks to `tdd-UL-DL-ConfigCommon`, emitting JSON of raw ASN.1 enum
  indices (the Kotlin adapter maps them to ms/kHz).
- `nrrrc_jni.c` — the JNI bridge (`NrRrcDecoder.decodeTdd`). Android-only.
- `nrrrc_test_main.c` — host test harness (`t <pduKind> <hexfile>` → prints the JSON).
- `build_libnrrrc.sh` — regenerate the asn1c C and cross-compile `libnrrrc.so` per ABI.

## Build notes (hard-won; see the plan doc's "Spike result")

- **Use a modern asn1c** — the mouse07410/asn1c fork. Ubuntu's packaged **0.9.28 cannot parse
  `[[ ... ]]`** extension groups (fails at `NR-RRC-Definitions.asn:9169`). Build the fork from source:
  `git clone --depth 1 https://github.com/mouse07410/asn1c && cd asn1c && autoreconf -iv && ./configure && make && sudo make install`.
- The PER flag is split in the fork: use **`-gen-UPER`** (unaligned), not `-gen-PER`.
- Generation: `asn1c -fcompound-names -fno-include-deps -gen-UPER -no-gen-example -pdu=auto -D . spec/*.asn`.
- asn1c prints `FATAL: Type SetupRelease expects specialization` for 38.331's parameterized
  `SetupRelease{}`, but still emits compilable code that decodes correctly. Keep the pycrate
  cross-check to guard against any message where this bites.

## Decode paths (asn1c does NOT auto-recurse `OCTET STRING (CONTAINING ...)`)

- **NSA (`pduKind 0`, RRCReconfiguration):**
  `RRCReconfiguration → criticalExtensions.choice.rrcReconfiguration.secondaryCellGroup` (OCTET
  STRING) → decode as **CellGroupConfig** → `spCellConfig → reconfigurationWithSync →
  spCellConfigCommon` (ServingCellConfigCommon) → `tdd-UL-DL-ConfigurationCommon`.
- **SA (`pduKind 1`, SIB1):** `BCCH-DL-SCH-Message → message.c1.systemInformationBlockType1 →
  servingCellConfigCommon` (ServingCellConfigCommonSIB) → `tdd-UL-DL-ConfigurationCommon`.
  (Shared leaf type `TDD_UL_DL_ConfigCommon`.)

## Rebuilding

```bash
export NDK=$ANDROID_SDK/ndk/<version>
./build_libnrrrc.sh            # -> build/<abi>/libnrrrc.so
for a in arm64-v8a x86_64; do cp build/$a/libnrrrc.so ../../app/src/main/jniLibs/$a/; done
```

## Validation

Decode the known-good NSA n41 vector and confirm it matches the pycrate oracle:
`t 0 vec_rrcreconf.hex` →
`refSCS 1 (kHz30); pattern1 periodicity 0 (ms0p5) 3/6/2/4, v1530 0 (ms3); pattern2 4 (ms2) 4/0/0/0;
ssbHex 20 (= 00100000, SSB position 2)` — matching the n41 profile in
`project-rf-test-app-sib1-tdd`. Add vectors from other bands/operators as they're captured.
