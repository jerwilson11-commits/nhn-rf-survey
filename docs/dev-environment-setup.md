# Phase 0 — Dev Environment Setup

Checked on this machine 2026-08-28: **git 2.55** and **Node 24.16** are installed. **Java, Android
Studio, the Android SDK, and Gradle are not.** Android Studio installs all four.

Work through this top to bottom. Phase 1 cannot start until step 6 passes.

---

## 1. Install Android Studio

Download from <https://developer.android.com/studio> — the Windows `.exe` installer.

During setup choose **Standard** installation. It pulls down, unattended:

- JetBrains Runtime (the JDK — you do not need to install Java separately)
- Android SDK, platform tools including `adb`, and build tools
- Gradle
- An Android emulator image

Budget roughly 8–12 GB of disk and 30–60 minutes depending on connection speed.

**Do not install it into the OneDrive folder.** Accept the default location under
`C:\Program Files\Android\Android Studio` and the SDK default at
`C:\Users\jerwi\AppData\Local\Android\Sdk`.

---

## 2. Where the code will live

Put the Android project **outside OneDrive**:

```
C:\Users\jerwi\AndroidStudioProjects\RFTestApp
```

OneDrive's sync engine fights with Gradle's build directory — it locks files mid-build, syncs
gigabytes of throwaway artifacts, and produces build failures that look like code errors but are
not. This folder in OneDrive stays as the docs, spec, and planning layer. The two are linked by
this documentation, not by living in the same directory.

Git provides the version history and backup instead. That is the correct tool for source anyway.

---

## 3. Create the project

Android Studio → **New Project** → **Empty Activity** (the Compose one, not "Empty Views
Activity").

| Field | Value |
|---|---|
| Name | `RF Test App` |
| Package name | `com.nhnengineering.rftest` |
| Save location | `C:\Users\jerwi\AndroidStudioProjects\RFTestApp` |
| Language | Kotlin |
| Minimum SDK | **API 31 (Android 12)** |
| Build configuration language | Kotlin DSL (`build.gradle.kts`) |

After the first sync, open `app/build.gradle.kts` and confirm `compileSdk = 36` and
`targetSdk = 36`. The test device runs Android 17 (API 37), but we target 36 on purpose — see
`docs/android-17-impact-notes.md`. An app targeting 36 runs normally on an Android 17 device.

The package name is permanent once published to the Play Store — worth a moment's thought now.
It does not have to match a domain you own, but convention is reverse-DNS.

Let it finish the first Gradle sync before touching anything. First sync downloads dependencies
and takes several minutes.

---

## 4. Enable the test handset

RF work has to run on real hardware. The emulator has no modem and will report nothing.

On the Android phone:

1. **Settings → About phone** → tap **Build number** seven times → developer mode enabled.
2. **Settings → System → Developer options** → enable **USB debugging**.
3. **Settings → System → Developer options** → turn **Wi-Fi scan throttling OFF.** Without this the
   OS caps scans at 4 per 2 minutes and `getScanResults()` returns stale data silently — a whole
   afternoon of debugging a collector that is working correctly.
4. Connect by USB. Accept the "Allow USB debugging?" prompt on the phone, ticking *always allow*.

**Do this after the Android 17 update finishes, not before.** A major OS update can re-hide
developer options, reset the scan-throttling toggle to its default, and clear previously authorized
USB debugging keys. If `adb` reports `unauthorized` later, this is why — re-accept the prompt.

Verify from a terminal:

```bash
adb devices
```

The phone should appear with status `device`. If it shows `unauthorized`, the on-phone prompt was
not accepted. If nothing appears, the USB cable may be charge-only — a surprisingly common cause.

---

## 5. Record what handset we are targeting

While in **Settings → About phone**, note down the model, Android version, and chipset. This
determines which of the RF fields will actually be populated — see section 8 of the API reference.
Add it to the Open Items section of the Master file.

---

## 6. Gate: Hello World on hardware

Press **Run** in Android Studio with the phone selected as the target. The stock template app
should install and launch on the handset.

Once that works, Phase 0 is done and we start writing the telephony collector.

---

## 7. Version control

From the project directory:

```bash
git init && git add -A && git commit -m "Initial project scaffold from Android Studio template"
```

Android Studio's template generates a correct `.gitignore` that excludes `build/`, `.gradle/`, and
`local.properties`. Do not commit `local.properties` — it contains machine-specific SDK paths.

A private GitHub repository is worth setting up as offsite backup before real work accumulates, but
it is not blocking.

---

## 8. What to expect from the learning curve

Realistic first-time expectations, so nothing feels like it is going wrong when it is not:

- First Gradle sync is slow and looks stuck. It is not.
- Gradle will occasionally fail with errors that resolve on **File → Invalidate Caches / Restart**.
- Compose previews render in the IDE without a device; RF data will not.
- Deprecation warnings on `PhoneStateListener` are expected — we support pre-31 devices deliberately.

I write the code; the parts you need to learn are navigating the IDE, running builds, and reading
Logcat output. That is a much smaller surface than learning Kotlin from scratch.
