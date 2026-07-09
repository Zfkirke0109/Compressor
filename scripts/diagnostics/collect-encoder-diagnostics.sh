#!/bin/sh
# Collect S23 Ultra (or any Android device) media/encoder diagnostics over ADB.
# Safe/read-only: property reads, dumpsys dumps, and a UI-hierarchy dump (no screenshots).
# Works from a desktop shell or Termux (see .claude/skills/termux-adb-samsung-workflow).
#
# Usage: sh scripts/diagnostics/collect-encoder-diagnostics.sh [output-dir]
set -u

OUT="${1:-device-diagnostics-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"

echo "== adb devices ==" | tee "$OUT/00-devices.txt"
adb devices -l | tee -a "$OUT/00-devices.txt"

{
  echo "manufacturer=$(adb shell getprop ro.product.manufacturer | tr -d '\r')"
  echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
  echo "device=$(adb shell getprop ro.product.device | tr -d '\r')"
  echo "release=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "soc=$(adb shell getprop ro.soc.model | tr -d '\r')"
} | tee "$OUT/01-props.txt"

echo "collecting dumpsys media.codec ..."
adb shell dumpsys media.codec > "$OUT/02-media-codec.txt" 2>&1
echo "collecting dumpsys media.metrics ..."
adb shell dumpsys media.metrics > "$OUT/03-media-metrics.txt" 2>&1
echo "collecting media_session sessions ..."
adb shell cmd media_session list-sessions > "$OUT/04-media-sessions.txt" 2>&1

# UI hierarchy (accessibility tree) instead of screenshots.
# Note: double slashes so Git Bash on Windows does not rewrite the device path.
echo "collecting UI hierarchy ..."
adb shell uiautomator dump //sdcard/window.xml >/dev/null 2>&1
adb exec-out cat //sdcard/window.xml > "$OUT/05-window.xml" 2>/dev/null
adb shell rm -f //sdcard/window.xml >/dev/null 2>&1

# In-app capability dump (requires a debug build of Compressor to be launched once).
echo "collecting CompressorCodecCaps logcat (launch the debug app first if empty) ..."
adb logcat -d -v time -s CompressorCodecCaps > "$OUT/06-codec-caps.txt" 2>&1

echo "done -> $OUT"
ls -la "$OUT"
