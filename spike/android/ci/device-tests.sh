#!/usr/bin/env bash
# Phased on-device checks for the spike. Run from spike/android with one device/emulator attached.
# Each `am instrument` starts the app in a NEW process; app data persists between phases.
set -uo pipefail
OUT=${OUT:-device-results}
PKG=org.seerah.spike
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p "$OUT"
FAILED=0

net() { # on|off
  if [ "$1" = off ]; then adb shell cmd connectivity airplane-mode enable; adb shell svc wifi disable; adb shell svc data disable
  else adb shell cmd connectivity airplane-mode disable; adb shell svc wifi enable; adb shell svc data enable; fi
  sleep 6
}

run() { # name, instrument args...
  local name=$1; shift
  echo "=== $name" | tee -a "$OUT/summary.txt"
  adb shell am instrument -w -r "$@" "$RUNNER" > "$OUT/$name.txt" 2>&1
  if grep -q "^OK (" "$OUT/$name.txt"; then echo "PASS $name" | tee -a "$OUT/summary.txt"
  else echo "FAIL $name" | tee -a "$OUT/summary.txt"; grep -E "INSTRUMENTATION_STATUS: stack=|Error|Exception" "$OUT/$name.txt" | head -20 | tee -a "$OUT/summary.txt"; FAILED=1; fi
}

serve() { # args for serve.py
  python3 ../pack/serve.py "$@" > "$OUT/serve.log" 2>&1 & SERVER=$!
  sleep 2; adb reverse tcp:8765 tcp:8765
}

# Emulators on CI are slow; hide system "isn't responding" dialogs so they don't cover screenshots.
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure anr_show_background 0 || true
sleep 20
APK=$(ls app/build/outputs/apk/debug/*x86_64*.apk 2>/dev/null | head -1); APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}
adb install -r "$APK"
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c; adb logcat -v time > "$OUT/logcat.txt" 2>&1 & LOGCAT=$!

run 1-flow -e class $PKG.app.DeviceFlowTest

adb shell am force-stop $PKG; net off
run 2-restart-offline-v1 -e class $PKG.app.RestartTest -e expectOffline true -e expectVersion 1.0.0-test -e phase offline-v1

net on
serve --offer corrupt
run 3a-update-corrupt -e class $PKG.app.UpdateTest -e mode corrupt
kill $SERVER; sleep 1
serve --cut-after 300000
run 3b-update-cut-resume -e class $PKG.app.UpdateTest -e mode cut
kill $SERVER

adb shell am force-stop $PKG; net off
run 4-restart-offline-v2 -e class $PKG.app.RestartTest -e expectOffline true -e expectVersion 1.1.0-test -e phase offline-v2

run 5-perf -e class $PKG.app.PerfTest
net on

adb pull /sdcard/Android/data/$PKG/files/screens "$OUT/" || true
adb shell dumpsys meminfo $PKG > "$OUT/meminfo.txt" || true
kill $LOGCAT || true
grep -E "SeerahSpike|SeerahDeviceTest|MapLibre|mbgl" "$OUT/logcat.txt" > "$OUT/logcat-app.txt" || true
ls -l app/build/outputs/apk/*/*.apk > "$OUT/apk-sizes.txt"
cat "$OUT/summary.txt"
exit $FAILED
