---
name: push-release
description: >-
  Builds the Android release APK via assembleRelease and pushes/installs it onto connected devices via ADB. If no devices are found, resets the ADB server with adb kill-server and retries. Trigger with /push-release or request to build & push release APK.
---

# Android Release APK Build & Push (`/push-release`)

This skill builds the optimized production release APK (with R8 code minification and resource shrinking enabled) for the Android application using Gradle `assembleRelease` and installs it on an attached device via `adb`.

## Triggering
- User types `/push-release` in chat.
- User asks to build and push/install the release APK to their phone or device.

---

## Workflow

### 1. Build Release APK
Navigate to project directory and run:
```bash
./gradlew assembleRelease
```
Verify the release APK artifact is generated at:
`app/build.outputs/apk/release/app-release.apk`

### 2. Device Detection & ADB Retry Logic
1. Run `adb devices`.
2. Check for attached devices in state `device`.
3. If no devices are found:
   - Run `adb kill-server`.
   - Wait 0.5 seconds.
   - Run `adb start-server` and re-check `adb devices`.
4. If no device is detected after retry, inform the user with troubleshooting steps for USB/Wireless debugging.

### 3. Installation
If a device is found, install the release build using:
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## Automated Script Execution
You can directly run the bundled script:
```bash
.agents/skills/push-release/scripts/push_release_apk.sh
```
