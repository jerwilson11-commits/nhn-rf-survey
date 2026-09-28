# Release Signing

Set up 2026-09-28. Covers how the release keystore is generated, where it lives, and how a
release build finds it. The keystore itself is never in this repo and never will be — the whole
point of this doc is to make that safe to rely on rather than something to remember.

## Where things live

- Keystore: `C:\Users\jerwi\AndroidKeystores\rftest-release.jks` — deliberately outside this repo
  entirely, so a `.gitignore` mistake can't expose it.
- `keystore.properties` — repo root, gitignored (`app/build.gradle.kts` reads it if present, and
  a release build comes out unsigned rather than failing when it's absent — see that file for the
  fallback logic). Four keys: `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
- Alias: `rftest-release`. Validity: 10,000 days (expires 2054-02-13).
- Certificate SHA-256 fingerprint (needed for Play App Signing enrollment):
  `30:BE:84:D1:16:A2:72:9E:6C:A5:A5:9C:F9:82:98:F1:A8:97:76:1A:FE:67:74:A6:02:B0:2C:D6:8E:43:9E:B4`

## The one thing that matters

**Back up the `.jks` file and both passwords somewhere outside this machine — a password manager,
encrypted cloud storage — today, not "eventually."** Losing any of the three (file, store
password, key password) means this specific app listing can never receive another update, ever.
There is no recovery path; Google cannot reissue it. If Play App Signing is enrolled (recommended
at first upload), Google keeps the app-signing key safe on their end and this upload key only
needs to sign the AAB you hand them — meaningfully lowers the stakes of a future loss, but not
this one, since enrollment itself requires this key to exist and work first.

## Regenerating from scratch (only if the keystore is genuinely lost)

Means a new Play Store listing under a new application ID — existing users cannot receive the new
version as an update to the old one. Not a routine procedure; a last resort.

## Verifying a release build is actually signed

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat bundleRelease
```

Should run `:app:signReleaseBundle` (not skip it), and:

```bash
"C:\Program Files\Android\Android Studio\jbr\bin\jarsigner.exe" -verify -verbose:summary app/build/outputs/bundle/release/app-release.aab
```

should end with a certificate expiry line, and

```bash
"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -printcert -jarfile app/build/outputs/bundle/release/app-release.aab
```

should print the fingerprint above.
