#!/bin/bash
# Real-device checks that JVM tests cannot make (PipelineDeviceTest): a Media3/MediaCodec export,
# the native VMAF scorer, the phase transitions they drive, and source preservation.
#
# Usage:
#   scripts/device/run-device-checks.sh CLIP.mp4 [ANDROID_USER]
#
#   CLIP.mp4      a short (10-30 s) SDR H.264/HEVC clip with AAC audio. It is pushed to
#                 /data/local/tmp and read by the test through the shell; it is never uploaded
#                 anywhere else.
#   ANDROID_USER  optional user id (150 for the Secure Folder profile the batches run in). The
#                 debug APK and its test APK must be installed for that user.
#
# Writes the instrumentation output next to the clip as CLIP.device-checks.txt. Runs alongside
# scripts/diagnostics/resilient_capture.sh; start that first to keep the Compressor* log tags.
set -euo pipefail
CLIP="${1:?usage: run-device-checks.sh CLIP.mp4 [ANDROID_USER]}"
USER_ID="${2:-}"
PKG=io.github.zfkirke0109.galaxycompressor
REMOTE=/data/local/tmp/compressor_smoke.mp4
OUT="${CLIP%.*}.device-checks.txt"

adb get-state >/dev/null
adb push "$CLIP" "$REMOTE" >/dev/null
adb shell chmod 644 "$REMOTE"

cd "$(dirname "$0")/../.."
./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest
USER_ARGS=()
if [ -n "$USER_ID" ]; then USER_ARGS=(--user "$USER_ID"); fi
adb install -r -t "${USER_ARGS[@]}" app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r -t "${USER_ARGS[@]}" app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null

{
  echo "# device checks $(date -u +%FT%TZ) clip=$(basename "$CLIP") sha256=$(sha256sum "$CLIP" | cut -c1-16) user=${USER_ID:-0}"
  adb shell getprop ro.product.model
  adb shell am instrument -w -r "${USER_ARGS[@]}" \
    -e class compress.joshattic.us.PipelineDeviceTest \
    -e sourceVideo "$REMOTE" \
    "$PKG.test/androidx.test.runner.AndroidJUnitRunner"
} | tee "$OUT"
adb shell rm -f "$REMOTE"
grep -q "FAILURES!!!" "$OUT" && { echo "device checks FAILED; see $OUT"; exit 1; }
grep -q "OK (" "$OUT" || { echo "device checks did not report OK; see $OUT"; exit 1; }
echo "device checks passed; see $OUT"
