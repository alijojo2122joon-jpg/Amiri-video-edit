#!/usr/bin/env bash
# Installs the release APK on the emulator and runs the in-app export self-test.
set -x
PKG=com.amiri.cut
APK=$(ls apk/*.apk | head -1)
adb install -r -g "$APK" || { echo "::error::install failed"; exit 1; }
adb shell mkdir -p /sdcard/Android/data/$PKG/files
adb push .github/testdata/selftest.mp4 /sdcard/Android/data/$PKG/files/selftest.mp4
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity --ez amiri_selftest true
died=0
for i in $(seq 1 100); do
  if adb logcat -d -s AmiriSelfTest | grep -q "RESULT"; then break; fi
  if ! adb shell pidof $PKG >/dev/null 2>&1; then died=1; echo "process died"; break; fi
  sleep 3
done
sleep 2
adb logcat -d -v brief AmiriSelfTest:V AmiriExport:V AndroidRuntime:E '*:S' > log.txt
adb logcat -d -b crash > crash.txt
cat log.txt; cat crash.txt
emit() { while IFS= read -r l; do echo "::$1::${l//%/%25}"; done; }
grep "RESULT\|progress" log.txt | tail -5 | emit notice
[ "$died" = 1 ] && echo "::error::app process died during the self-test"
head -80 crash.txt | emit error
grep -E "foreground|failed|FATAL|Exception:" log.txt | head -40 | emit warning
adb shell ls -l /sdcard/Android/data/$PKG/files/ | emit notice
grep -q "RESULT DONE" log.txt
