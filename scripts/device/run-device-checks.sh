#!/bin/bash
# Real-device checks that JVM tests cannot make (PipelineDeviceTest): a Media3/MediaCodec export,
# the native VMAF scorer, the phase transitions they drive, and source preservation; and
# (ProductionEvidenceDeviceTest, b177) certification windows on files with no track duration and a
# real Perceptually Lossless batch through BatchCompressorViewModel writing the b177 evidence fields.
# The batch test leaves replace-original off and checks the source hash afterwards.
#
# Usage:
#   scripts/device/run-device-checks.sh [--installed] CLIP.mp4 [ANDROID_USER]
#
#   CLIP.mp4      a short (10-30 s) SDR H.264/HEVC clip with AAC audio. It is pushed to
#                 /data/local/tmp and read by the test through the shell; it is never uploaded
#                 anywhere else.
#   ANDROID_USER  optional user id (150 for the Secure Folder profile the batches run in). The
#                 debug APK and its test APK must be installed for that user.
#   --installed   use a matched app and test APK pair already installed for that user.
#
# Writes the instrumentation output next to the clip as CLIP.device-checks.txt.
# Exit 2 means the validation was incomplete (skipped tests, zero tests or missing results).
# Runs alongside
# scripts/diagnostics/resilient_capture.sh; start that first to keep the Compressor* log tags.
set -euo pipefail
INSTALL_LOCAL=true
if [[ "${1:-}" == --installed ]]; then
  INSTALL_LOCAL=false
  shift
fi
CLIP="${1:?usage: run-device-checks.sh [--installed] CLIP.mp4 [ANDROID_USER]}"
CLIP_DIR="$(cd -- "$(dirname -- "$CLIP")" && pwd -P)"
CLIP="$CLIP_DIR/$(basename -- "$CLIP")"
USER_ID="${2:-}"
PKG=io.github.zfkirke0109.galaxycompressor
TEST_PACKAGE=compress.joshattic.us
TEST_CLASSES="$TEST_PACKAGE.PipelineDeviceTest,$TEST_PACKAGE.ProductionEvidenceDeviceTest,$TEST_PACKAGE.FramePackingDeviceTest"
REMOTE="/data/local/tmp/compressor_smoke_$$.mp4"
OUT="${CLIP%.*}.device-checks.txt"
CLIP_SHA="$(sha256sum "$CLIP" | cut -c1-16)"

adb get-state >/dev/null
trap 'adb shell rm -f "$REMOTE" >/dev/null || true' EXIT
adb push "$CLIP" "$REMOTE" >/dev/null
adb shell chmod 644 "$REMOTE"

cd "$(dirname "$0")/../.."
USER_ARGS=()
if [ -n "$USER_ID" ]; then USER_ARGS=(--user "$USER_ID"); fi
if "$INSTALL_LOCAL"; then
  ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest
  adb install -r -t "${USER_ARGS[@]}" app/build/outputs/apk/debug/app-debug.apk >/dev/null
  adb install -r -t "${USER_ARGS[@]}" app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
fi

LOG_START="$(adb shell 'date "+%m-%d %H:%M:%S.000"' | tr -d '\r')"
{
  echo "# device checks $(date -u +%FT%TZ) clip=$(basename "$CLIP") sha256=$CLIP_SHA user=${USER_ID:-0}"
  adb shell getprop ro.product.model
  adb shell am instrument -w -r "${USER_ARGS[@]}" \
    -e class "$TEST_CLASSES" \
    -e sourceVideo "$REMOTE" \
    "$PKG.test/androidx.test.runner.AndroidJUnitRunner"
} | tee "$OUT"
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED:|^INSTRUMENTATION_STATUS_CODE: -[12][[:space:]]*$' "$OUT"; then
  echo "device checks failed. See $OUT"
  exit 1
fi
if grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[34][[:space:]]*$|AssumptionViolated' "$OUT" ||
   ! grep -Eq '^[[:space:]]*OK \([1-9][0-9]* tests?\)[[:space:]]*$' "$OUT"; then
  echo "device checks incomplete. See $OUT"
  exit 2
fi
# The b177 tests print what the platform reported (durations, window starts, record fields) to
# System.out, which lands in logcat rather than the instrumentation output.
adb logcat -t "$LOG_START" -s System.out:I | grep -E "b177-(fixture|evidence)" | tee -a "$OUT" || true
echo "device checks passed; see $OUT"
