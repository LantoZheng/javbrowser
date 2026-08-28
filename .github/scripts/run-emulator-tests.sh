#!/usr/bin/env bash
set -uo pipefail

report_dir="app/build/reports/emulator-qa"
mkdir -p "$report_dir"
adb logcat -c

test_status=0
./gradlew connectedDebugAndroidTest --stacktrace || test_status=$?

# Instrumentation hosts close after each test. Launch the production entry point so
# the saved UI tree and screenshot show the actual native app rather than Launcher.
adb shell am force-stop com.example.javbrowser || true
adb shell am start -W -n com.example.javbrowser/.nativeapp.NativeMainActivity \
  > "$report_dir/launch.txt" 2>&1 || true
sleep 2
adb exec-out uiautomator dump /dev/tty > "$report_dir/ui.xml" || true
adb exec-out screencap -p > "$report_dir/final-screen.png" || true
adb logcat -d > "$report_dir/logcat.txt" || true

exit "$test_status"
