#!/usr/bin/env bash
set -e

# Determine Gradle directory
if [ -f "./gradlew" ]; then
    GRADLE_DIR="."
elif [ -d "angel-launcher" ] && [ -f "angel-launcher/gradlew" ]; then
    GRADLE_DIR="angel-launcher"
elif [ -d "guardian-android" ] && [ -f "guardian-android/gradlew" ]; then
    GRADLE_DIR="guardian-android"
else
    echo "Error: gradlew not found."
    exit 1
fi

echo "Building Release APK in $GRADLE_DIR..."
(cd "$GRADLE_DIR" && ./gradlew assembleRelease)

APK_PATH="$GRADLE_DIR/app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "Error: Release APK not found at $APK_PATH"
    exit 1
fi

echo "Checking ADB devices..."
DEVICES=$(adb devices | grep -w "device" | grep -v "List of" || true)

if [ -z "$DEVICES" ]; then
    echo "No attached devices found. Resetting ADB server..."
    adb kill-server
    sleep 0.5
    adb start-server
    DEVICES=$(adb devices | grep -w "device" | grep -v "List of" || true)
fi

if [ -z "$DEVICES" ]; then
    echo "Error: No device detected after ADB server restart."
    echo "Troubleshooting:"
    echo "  1. Ensure USB Debugging is enabled on your phone."
    echo "  2. Accept the 'Allow USB debugging?' prompt on your phone screen."
    echo "  3. Verify cable connection or wireless debugging status."
    exit 1
fi

echo "Device detected: $DEVICES"
echo "Installing $APK_PATH..."
adb install -r "$APK_PATH"

echo "Attempting to launch main activity..."
adb shell am start -n vibes.launcher.angel/.MainActivity || true

echo "Release APK successfully built and pushed!"
