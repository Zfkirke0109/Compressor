#!/bin/bash
# Real-device checks that JVM tests cannot make (PipelineDeviceTest): a Media3/MediaCodec export,
# the native VMAF scorer, the phase transitions they drive, and source preservation; and
# (ProductionEvidenceDeviceTest, b177) certification windows on files with no track duration and a
# real Perceptually Lossless batch through BatchCompressorViewModel writing the b177 evidence fields.
# The batch test leaves replace-original off and checks the source hash afterwards.
# EditListTimelineDeviceTest needs no clip: it runs two synthetic sources from the test APK through
# the platform extractor, a real Media3 export and the metadata remux, and says at which of them a
# video sample count first changes (the 168 -> 166 case of the v1.6.287 regular-profile run).
# GalleryPublicationDeviceTest needs no clip either: it saves bytes the test makes to a test-owned
# row in Movies/PLLabTest through this device's MediaStore, checks that a save is counted only when
# one row was published and read back whole and that a refused save leaves no row behind, and
# deletes every row it inserted.
#
# Usage:
#   scripts/device/run-device-checks.sh [--installed] [--fallback] CLIP.mp4 [ANDROID_USER]
#
#   CLIP.mp4      a short (10-30 s) SDR H.264/HEVC clip with AAC audio. It is pushed to
#                 /data/local/tmp and read by the test through the shell; it is never uploaded
#                 anywhere else.
#   ANDROID_USER  optional user id; the debug APK and its test APK must be installed for that
#                 user. Leave it out for the normal profile. On the S26 (SM-S948U, Android 17) the
#                 Secure Folder user 150 refuses the shell ("Shell does not have permission to
#                 access user 150"), so these tests cannot run there; run them in the normal
#                 profile. A run the device refuses to start is reported incomplete (exit 2).
#   --installed   use a matched app and test APK pair already installed for that user.
#   --fallback    run only SaferRungFallbackDeviceTest instead: three real Perceptually Lossless
#                 batches over CLIP in which a test's candidate veto refuses a first full encode,
#                 so the retry at the safer measured ratio runs with real encodes and its own
#                 verification and certification. CLIP must be one whose probe ladder selects a
#                 marginal trial on this device (a lower rung that only clears the bar under a
#                 higher one that passes); otherwise the run is reported incomplete. Budget two
#                 full encodes of CLIP per batch. The opt-in safer-rung retry switch is turned on
#                 for the run and put back afterwards; replace-original must be off. Writes
#                 CLIP.fallback-checks.txt.
#
# Writes the instrumentation output next to the clip as CLIP.device-checks.txt.
# Exit 2 means the validation was incomplete (skipped tests, zero tests or missing results).
# Runs alongside
# scripts/diagnostics/resilient_capture.sh; start that first to keep the Compressor* log tags.
set -euo pipefail
INSTALL_LOCAL=true
FALLBACK=false
while [[ "${1:-}" == --* ]]; do
  case "$1" in
    --installed) INSTALL_LOCAL=false ;;
    --fallback) FALLBACK=true ;;
    *) echo "unknown option: $1" >&2; exit 64 ;;
  esac
  shift
done
CLIP="${1:?usage: run-device-checks.sh [--installed] [--fallback] CLIP.mp4 [ANDROID_USER]}"
CLIP_DIR="$(cd -- "$(dirname -- "$CLIP")" && pwd -P)"
CLIP="$CLIP_DIR/$(basename -- "$CLIP")"
USER_ID="${2:-}"
PKG=io.github.zfkirke0109.galaxycompressor
TEST_PACKAGE=compress.joshattic.us
TEST_CLASSES="$TEST_PACKAGE.PipelineDeviceTest,$TEST_PACKAGE.ProductionEvidenceDeviceTest,$TEST_PACKAGE.FramePackingDeviceTest,$TEST_PACKAGE.EditListTimelineDeviceTest,$TEST_PACKAGE.GalleryPublicationDeviceTest"
REMOTE="/data/local/tmp/compressor_smoke_$$.mp4"
OUT="${CLIP%.*}.device-checks.txt"
if "$FALLBACK"; then
  TEST_CLASSES="$TEST_PACKAGE.SaferRungFallbackDeviceTest"
  OUT="${CLIP%.*}.fallback-checks.txt"
fi
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

LOG_START="$(adb shell 'date +%s.%N' | tr -d '\r')"
{
  echo "# device checks $(date -u +%FT%TZ) clip=$(basename "$CLIP") sha256=$CLIP_SHA user=${USER_ID:-0}"
  adb shell getprop ro.product.model
  adb shell am instrument -w -r "${USER_ARGS[@]}" \
    -e class "$TEST_CLASSES" \
    -e sourceVideo "$REMOTE" \
    "$PKG.test/androidx.test.runner.AndroidJUnitRunner" ||
    echo "# am instrument exited with status $?"
} | tee "$OUT"
# The verdict is read from the instrumentation output alone, before anything else is appended.
VERDICT=passed
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED:|^INSTRUMENTATION_STATUS_CODE: -[12][[:space:]]*$' "$OUT"; then
  VERDICT=failed
elif grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[34][[:space:]]*$|AssumptionViolated' "$OUT" ||
   ! grep -Eq '^[[:space:]]*OK \([1-9][0-9]* tests?\)[[:space:]]*$' "$OUT"; then
  VERDICT=incomplete
fi
# The b177 tests print what the platform reported (durations, window starts, record fields) to
# System.out, which lands in logcat rather than the instrumentation output; the fallback test
# prints each batch's rungs, attempts, retry and certification the same way, and the timeline test
# what each stage counted. They are collected whatever the verdict: a skipped or failed run is when
# they say most, such as which rungs a clip's ladder measured when it selected no marginal trial.
adb logcat -t "$LOG_START" -s System.out:I | grep -E "b177-(fixture|evidence)|b287-(fallback|timeline)|gallery-publication" | tee -a "$OUT" || true
case "$VERDICT" in
  failed)
    echo "device checks failed. See $OUT"
    exit 1
    ;;
  incomplete)
    if grep -q 'does not have permission to access user' "$OUT"; then
      echo "the tests did not start: this device refuses the shell for user ${USER_ID:-0}; run them in the normal profile"
    elif grep -q '^# am instrument exited with status' "$OUT"; then
      echo "the tests did not start or did not finish: am instrument exited with an error"
    fi
    echo "device checks incomplete. See $OUT"
    exit 2
    ;;
esac
echo "device checks passed; see $OUT"
