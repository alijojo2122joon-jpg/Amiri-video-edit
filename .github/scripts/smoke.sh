#!/usr/bin/env bash
# Installs the release APK on the emulator and runs the in-app export self-test.
set -x
PKG=com.amiri.cut
APK=$(ls apk/*x86_64*.apk 2>/dev/null | head -1); [ -n "$APK" ] || APK=$(ls apk/*.apk | head -1)
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
grep "RESULT\|progress" log.txt | tail -4 | emit notice
grep "DENOISE\|CUTOUT\|ROTO" log.txt | emit notice
[ "$died" = 1 ] && echo "::error::app process died during the self-test"
head -80 crash.txt | emit error
grep -E "foreground|failed|FATAL|Exception:" log.txt | head -40 | emit warning
adb shell ls -l /sdcard/Android/data/$PKG/files/ | emit notice
adb pull /sdcard/Android/data/$PKG/files/selftest-out.mp4 selftest-out.mp4 || true
grep -q "RESULT DONE" log.txt
selftest_ok=$?

# ───────── UI screenshots (design review) ─────────
G=demo-media
if [ -d "$G" ]; then
  adb shell mkdir -p /sdcard/DCIM/Camera
  for f in "$G"/gallery/*; do adb push "$f" /sdcard/DCIM/Camera/ >/dev/null; done
  for f in demo1.mp4 demo2.mp4 demo_cat.jpg demo_overlay.png demo_music.wav; do adb push "$G/$f" /sdcard/Android/data/$PKG/files/$f >/dev/null; done
  SCAN=$(ls "$G"/gallery | sed 's#^#/sdcard/DCIM/Camera/#' | paste -sd, -)
  mkdir -p ui-shots
  shot() {
    local name=$1 screen=$2 wait=$3
    adb shell am force-stop $PKG
    sleep 1
    adb shell am start -W -n $PKG/.MainActivity --es amiri_screen "$screen" --es amiri_scan "$SCAN" >/dev/null
    sleep "$wait"
    adb exec-out screencap -p > "ui-shots/$name.png"
    echo "shot $name: $(stat -c %s ui-shots/$name.png) bytes"
  }
  shot 01-home home 8
  shot 02-picker picker 9
  shot 03-editor editor 14
  shot 04-editor-selected editor_sel 12
  shot 05-filters editor:FILTERS 12
  shot 06-ratio editor:RATIO 12
  shot 07-background editor:BACKGROUND 12
  shot 08-text editor:TEXT 12
  shot 09-audio editor:AUDIO 12
  shot 10-export export 12
  adb logcat -d -b crash > ui-crash.txt
  head -60 ui-crash.txt | emit error
fi
[ "$selftest_ok" = 0 ]
