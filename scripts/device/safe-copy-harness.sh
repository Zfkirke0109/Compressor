#!/bin/bash
# Safe-copy harness: device tests that never touch a personal original.
#
#   A  a personal original. Read only: hashed, and copied from, never written, moved or renamed.
#   B  a verified baseline: a byte-for-byte copy of A in this run's own folder,
#      Documents/PLLabValidation/<run>/ (with .nomedia, so the gallery does not list it), outside
#      the app's cache and anything that cleans it. Never encoded into, never handed to a
#      replacement: the device tests only read it, through the shell, by path.
#   C  disposable copies the device tests make from B, as this app's own rows in
#      Movies/PLLabValidation/<run>/, read back whole and allowlisted before anything writes to
#      them (HarnessCopies). Replacement, rollback, interruption and recovery run on C only.
#   F  a synthetic fixture made here with ffmpeg (no one's media), for the end padding repair and
#      a full replacement batch.
#
# A and B are hashed (full SHA-256 and length) before and after every case; any change, or any
# hash that cannot be taken, stops the run with exit 3 and leaves everything where it is. Nothing
# here deletes anything: the run's files stay until the owner approves their cleanup.
#
# Run in Termux on the phone (pkg install android-tools; ffmpeg for the fixture), with Wireless
# debugging connected to the phone itself, the debug app and its test APK installed:
#
#   safe-copy-harness.sh [--serial S] inventory
#   safe-copy-harness.sh [--serial S] stage --source-id ID [--source-id ID ...] [--dry-run]
#   safe-copy-harness.sh [--serial S] verify-protection --run RUN [--final]
#   safe-copy-harness.sh [--serial S] fixture --run RUN
#   safe-copy-harness.sh [--serial S] run-copy-tests --run RUN --cases app-checks,padding-repair,batch-replace,corpus[,fallback] [--fallback-baseline K]
#   safe-copy-harness.sh [--serial S] run-replacement-tests --run RUN --disposable-only
#   safe-copy-harness.sh [--serial S] collect --run RUN
#   safe-copy-harness.sh [--serial S] all --source-id ID [...] [--fallback-baseline K]
#
# ID is a video's MediaStore _id from `inventory`. RUN is the run id `stage` prints. Every file
# that names a personal path, digest or name stays in ~/pllab-runs/RUN (private); report.txt there
# is the readable summary. Exit: 0 passed, 1 failed, crashed or error, 2 incomplete or blocked, 3 a
# protection check failed (stop).
set -uo pipefail

PKG=io.github.zfkirke0109.galaxycompressor
TEST_PACKAGE=compress.joshattic.us
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
DEV_ROOT=/storage/emulated/0
RUNS_ROOT="$DEV_ROOT/Documents/PLLabValidation"
HOST_ROOT=${PLLAB_HOME:-$HOME/pllab-runs}
# Free space kept beyond what a run needs, for the app's own reserve and everything else.
MARGIN_BYTES=$((2 * 1024 * 1024 * 1024))
SERIAL=""

say() { printf '%s\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die() { printf 'STOP: %s\n' "$1" >&2; exit "${2:-1}"; }
adb() { if [ -n "$SERIAL" ]; then command adb -s "$SERIAL" "$@" </dev/null; else command adb "$@" </dev/null; fi; }
# Single-quoted for the device's shell, whatever the text holds.
q() { local s=${1//\'/\'\\\'\'}; printf "'%s'" "$s"; }
dsh() { adb shell "$*" 2>/dev/null | tr -d '\r'; }

choose_device() {
  local devices count
  devices=$(command adb devices 2>/dev/null </dev/null | tr -d '\r' | sed -n '2,$p' | grep -E '[[:space:]]device$' | cut -f1)
  count=$(printf '%s' "$devices" | grep -c . || true)
  if [ -n "$SERIAL" ]; then
    printf '%s\n' "$devices" | grep -qxF -- "$SERIAL" || die "$SERIAL is not a connected device (adb devices lists: ${devices:-none})" 2
    return
  fi
  [ "$count" -ge 1 ] || die "no device: connect Wireless debugging (adb connect) and try again" 2
  [ "$count" -eq 1 ] || die "several devices are connected ($(printf '%s' "$devices" | tr '\n' ' ')): pass --serial" 2
  SERIAL=$devices
}

identity() {
  MODEL=$(dsh getprop ro.product.model)
  SDK=$(dsh getprop ro.build.version.sdk)
  RELEASE=$(dsh getprop ro.build.version.release)
  [ -n "$MODEL" ] || die "the device does not answer over adb" 2
  APP_VERSION=$(dsh dumpsys package "$PKG" | grep -m1 'versionName=' | sed 's/.*versionName=//')
  [ -n "$APP_VERSION" ] || die "$PKG is not installed for this user" 2
  dsh pm list instrumentation | grep -qF "$PKG.test/" || die "the test APK ($PKG.test) is not installed" 2
}

# The app's own state, by count only: outputs waiting to be saved, and rollback copies an earlier
# replacement left. A batch clears the first; the app's next start settles the second.
app_state() {
  # Counting through run-as needs the debug build; an empty answer must not read as "nothing there".
  [ "$(dsh run-as "$PKG" id | cut -c1-4)" = "uid=" ] || die "run-as $PKG does not work here: install the debug build" 2
  UNSAVED=$(dsh run-as "$PKG" ls -1 cache/batch_compressed_videos | grep -c . || true)
  LEFTOVERS=$(dsh run-as "$PKG" ls -1 files/replace_recovery | grep -c '^replace_recovery_' || true)
}

host_run() { printf '%s/%s' "$HOST_ROOT" "$1"; }
manifest() { printf '%s/manifest.tsv' "$(host_run "$1")"; }

require_run() {
  [ -n "${RUN:-}" ] || die "pass --run RUN (the id stage printed)" 64
  [[ $RUN =~ ^[A-Za-z0-9_-]{1,64}$ ]] || die "not a run id: $RUN" 64
  [ -f "$(manifest "$RUN")" ] || die "no manifest for $RUN in $HOST_ROOT" 2
}

media_rows() { # media_rows WHERE: "id|bytes|ms|w|h|mime|path" for each matching video
  local out re line
  out=$(adb shell "content query --uri content://media/external/video/media --projection _id:_size:duration:width:height:mime_type:_data --where \"$1\" --sort \"_size ASC\"" 2>/dev/null | tr -d '\r')
  re='_id=([0-9]+), _size=([0-9]+|NULL), duration=([0-9]+|NULL), width=([0-9]+|NULL), height=([0-9]+|NULL), mime_type=([^,]*), _data=(.*)$'
  while IFS= read -r line; do
    [[ $line =~ $re ]] && printf '%s|%s|%s|%s|%s|%s|%s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}" \
      "${BASH_REMATCH[4]}" "${BASH_REMATCH[5]}" "${BASH_REMATCH[6]}" "${BASH_REMATCH[7]}"
  done <<<"$out"
}

# sha256 and length of a device file, as "SHA BYTES"; empty when either cannot be read.
device_identity() {
  local sha bytes
  sha=$(dsh sha256sum "$(q "$1")" | cut -d' ' -f1)
  bytes=$(dsh stat -c %s "$(q "$1")")
  [[ $sha =~ ^[0-9a-f]{64}$ && $bytes =~ ^[0-9]+$ ]] && printf '%s %s' "$sha" "$bytes"
}

free_bytes() { # available bytes on shared storage
  local kb
  kb=$(dsh df -k "$DEV_ROOT" | sed -n '2p' | tr -s ' ' | cut -d' ' -f4)
  [[ $kb =~ ^[0-9]+$ ]] && printf '%s' $((kb * 1024))
}

cmd_inventory() {
  identity
  app_state
  mkdir -p "$HOST_ROOT"
  local out stamp n=0 total=0 id bytes ms w h mime path
  stamp=$(date -u +%Y%m%d-%H%M%S)
  out="$HOST_ROOT/inventory-$stamp.private.tsv"
  printf 'id\tbytes\tms\twidth\theight\tmime\tpath\n' >"$out"
  while IFS='|' read -r id bytes ms w h mime path; do
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$id" "$bytes" "$ms" "$w" "$h" "$mime" "$path" >>"$out"
    n=$((n + 1)); [[ $bytes =~ ^[0-9]+$ ]] && total=$((total + bytes))
  done < <(media_rows "1=1")
  say "device       : $MODEL, Android $RELEASE (API $SDK), app $APP_VERSION"
  say "app state    : $UNSAVED unsaved output(s) in its cache, $LEFTOVERS rollback cop(y/ies) from an earlier replacement"
  say "videos       : $n in this profile's media index, $((total / 1048576)) MiB (read only; nothing was hashed or copied)"
  say "by type      : $(cut -f6 "$out" | sed 1d | sort | uniq -c | sed 's/^ *//' | tr '\n' ';' | sed 's/;$//')"
  say "private list : $out (ids, sizes, paths; it stays on this phone)"
  # A bounded suggestion for the replacement baseline: the smallest real clip of 5-80 MB.
  local pick
  pick=$(sed 1d "$out" | awk -F'\t' '$2 >= 5242880 && $2 <= 83886080 { print $1 "\t" $2; exit }')
  [ -n "$pick" ] && say "suggested    : --source-id $(cut -f1 <<<"$pick") ($(( $(cut -f2 <<<"$pick") / 1048576 )) MiB) as the replacement baseline"
  return 0
}

cmd_stage() {
  [ ${#SOURCE_IDS[@]} -gt 0 ] || die "pass --source-id ID (from inventory)" 64
  identity
  local rows=() id row bytes need=0 free
  for id in "${SOURCE_IDS[@]}"; do
    [[ $id =~ ^[0-9]+$ ]] || die "not a media id: $id" 64
    row=$(media_rows "_id=$id")
    [ "$(printf '%s' "$row" | grep -c .)" -eq 1 ] || die "media id $id is not one video in this profile's index" 2
    rows+=("$row")
    bytes=$(cut -d'|' -f2 <<<"$row")
    [[ $bytes =~ ^[0-9]+$ ]] || die "media id $id has no size in the index" 2
    # B, a C per case at a time, a rollback copy, and an output, at most.
    need=$((need + 4 * bytes))
  done
  need=$((need + MARGIN_BYTES))
  free=$(free_bytes) || die "could not read the free space on shared storage" 2
  say "space        : $((free / 1048576)) MiB free; this run needs at most $((need / 1048576)) MiB (copies, rollback, outputs, 2 GiB margin)"
  [ "$free" -ge "$need" ] || die "not enough free space for this run; nothing was copied" 2
  if $DRY_RUN; then
    local k=0
    for row in "${rows[@]}"; do
      k=$((k + 1))
      say "would copy   : A$k (media id $(cut -d'|' -f1 <<<"$row"), $(( $(cut -d'|' -f2 <<<"$row") / 1048576 )) MiB) -> $RUNS_ROOT/<new run>/B-$k.mp4, then verify A, B and A again"
    done
    say "dry run      : nothing was written"
    return 0
  fi
  RUN=$(date -u +%Y%m%d-%H%M%S)-$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')
  local dev="$RUNS_ROOT/$RUN" host
  host=$(host_run "$RUN")
  [ ! -e "$host" ] || die "$host exists; refusing to reuse a run" 1
  [ -z "$(dsh ls -d "$(q "$dev")")" ] || die "$dev exists on the device; refusing to reuse a run" 1
  mkdir -p "$host"
  dsh mkdir -p "$(q "$dev")" >/dev/null
  dsh "touch $(q "$dev/.nomedia")" >/dev/null
  {
    printf '# safe-copy-harness manifest v1 (private: paths and digests of personal files)\n'
    printf 'run\t%s\t%s\t%s\t%s\t%s\t%s\n' "$RUN" "$(date -u +%FT%TZ)" "$MODEL" "$SDK" "$SERIAL" "$APP_VERSION"
  } >"$(manifest "$RUN")"
  local k=0 path a1 a2 b bpath
  for row in "${rows[@]}"; do
    k=$((k + 1))
    id=$(cut -d'|' -f1 <<<"$row"); path=$(cut -d'|' -f7- <<<"$row")
    case "$path" in "$DEV_ROOT"/*) ;; *) die "A$k is not on shared storage ($path); stopping" 2 ;; esac
    a1=$(device_identity "$path") || die "A$k could not be hashed; stopping before any copy" 3
    bpath="$dev/B-$k.mp4"
    # Exclusive create: the copy fails rather than overwrite anything that is already there.
    adb shell "set -C; cat $(q "$path") > $(q "$bpath")" >/dev/null 2>&1 || die "B$k could not be created; A$k was only read" 3
    dsh sync >/dev/null
    b=$(device_identity "$bpath") || die "B$k could not be read back; stopping" 3
    a2=$(device_identity "$path") || die "A$k could not be hashed after the copy; stopping" 3
    [ "$a1" = "$a2" ] || die "A$k changed while it was being copied; stopping, B$k is kept for inspection" 3
    [ "$b" = "$a1" ] || die "B$k is not A$k's bytes; stopping, B$k is kept for inspection" 3
    printf 'A\t%s\t%s\t%s\t%s\t%s\n' "$k" "$id" "${a1#* }" "${a1%% *}" "$path" >>"$(manifest "$RUN")"
    printf 'B\t%s\t%s\t%s\t%s\n' "$k" "${b#* }" "${b%% *}" "$bpath" >>"$(manifest "$RUN")"
    say "staged       : A$k -> B$k, $(( ${b#* } / 1048576 )) MiB, SHA-256 and length equal (A hashed before and after)"
  done
  adb push "$(manifest "$RUN")" "$dev/manifest.tsv" >/dev/null 2>&1 || warn "could not keep a copy of the manifest on the device"
  say "run          : $RUN"
  return 0
}

# verify_protection LABEL: every A and B as the manifest records them. Exit 3 on any difference.
verify_protection() {
  local label=$1 kind k id bytes sha path now bad=0 log
  log="$(host_run "$RUN")/protection.log"
  while IFS=$'\t' read -r kind k id bytes sha path; do
    case "$kind" in
      A) ;;
      B) path=$sha; sha=$bytes; bytes=$id ;;
      *) continue ;;
    esac
    now=$(device_identity "$path") || now="unreadable"
    if [ "$now" = "$sha $bytes" ]; then
      printf '%s\t%s%s\tunchanged\n' "$label" "$kind" "$k" >>"$log"
    else
      printf '%s\t%s%s\tCHANGED-OR-UNREADABLE\t%s\n' "$label" "$kind" "$k" "$now" >>"$log"
      warn "$kind$k does not match the manifest at $label"
      bad=1
    fi
  done <"$(manifest "$RUN")"
  [ "$bad" -eq 0 ] || die "a protected file changed or could not be read ($label); stopping with everything left in place" 3
  say "protected    : every A and B unchanged ($label)"
}

cmd_verify() {
  require_run
  identity
  verify_protection "$([ "$FINAL" = true ] && echo final || echo check)"
}

field() { # field KIND K COLUMN: a manifest value
  awk -F'\t' -v kind="$1" -v k="$2" -v col="$3" '$1 == kind && $2 == k { print $col; exit }' "$(manifest "$RUN")"
}

cmd_fixture() {
  require_run
  identity
  command -v ffmpeg >/dev/null || die "ffmpeg is not installed here (pkg install ffmpeg); the fixture cases cannot run" 2
  local host out dev sha bytes now
  host=$(host_run "$RUN"); out="$host/F-padding.mp4"; dev="$RUNS_ROOT/$RUN/F-padding.mp4"
  [ ! -e "$out" ] || die "$out exists; a run makes its fixture once" 1
  # 12 s of 1080p30 H.264 without B-frames (one presentation edit), and mono AAC whose 575,488
  # samples are a whole number of 1,024-sample frames: encoder delay 1,024, end padding 0, written
  # exactly on a 48 kHz movie timescale. Media3's muxer writes such a track's edit on a 1/10,000 s
  # timescale, which the platform reads as 2 samples of end padding the source does not have.
  ffmpeg -nostdin -v error -y -f lavfi -i "testsrc2=size=1920x1080:rate=30:duration=12" \
    -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=12" \
    -filter_complex "[1:a]atrim=end_sample=575488,asetpts=N/SR/TB[a]" -map 0:v -map "[a]" \
    -c:v libx264 -threads 4 -preset medium -crf 12 -bf 0 -g 60 -pix_fmt yuv420p \
    -c:a aac -b:a 96k -ac 1 -movie_timescale 48000 -video_track_timescale 15360 "$out" ||
    die "ffmpeg could not make the fixture" 2
  sha=$(sha256sum "$out" | cut -d' ' -f1); bytes=$(stat -c %s "$out")
  adb push "$out" "$dev" >/dev/null 2>&1 || die "could not copy the fixture to the device" 2
  now=$(device_identity "$dev") || die "the fixture on the device could not be read back" 2
  [ "$now" = "$sha $bytes" ] || die "the fixture on the device is not the one made here" 2
  printf 'F\tpadding\t%s\t%s\t%s\n' "$bytes" "$sha" "$dev" >>"$(manifest "$RUN")"
  say "fixture      : F-padding.mp4, $((bytes / 1048576)) MiB, sha256 ${sha:0:16}…, on the device and read back"
}

# instrument [--expect-crash] LABEL CLASS [-e KEY VALUE ...]: runs, keeps the output, and prints a
# verdict. A crash fails the run, except in the one case meant to kill its own process
# (--expect-crash), which passes only by crashing: if it ends any other way, its process did not
# die where it should have, and it is a failure unless it never got that far (incomplete).
instrument() {
  local expect_crash=false label class out verdict
  if [ "$1" = --expect-crash ]; then expect_crash=true; shift; fi
  label=$1 class=$2
  shift 2
  out="$(host_run "$RUN")/instrument-$label.txt"
  adb shell am instrument -w -r -e class "$class" "$@" "$RUNNER" 2>&1 | tr -d '\r' >"$out"
  if grep -Eq 'Process crashed|INSTRUMENTATION_RESULT: shortMsg=Process crashed' "$out"; then verdict=crashed
  elif grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED:|^INSTRUMENTATION_STATUS_CODE: -[12][[:space:]]*$' "$out"; then verdict=failed
  elif grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[34][[:space:]]*$|AssumptionViolated' "$out" ||
    ! grep -Eq '^[[:space:]]*OK \([1-9][0-9]* tests?\)[[:space:]]*$' "$out"; then verdict=incomplete
  else verdict=passed; fi
  if $expect_crash; then
    case "$verdict" in
      crashed) verdict=crashed-as-intended ;;
      passed) verdict=failed ;;
    esac
  fi
  printf '%s\t%s\n' "$label" "$verdict" >>"$(host_run "$RUN")/verdicts.tsv"
  say "case         : $label -> $verdict"
  LAST_VERDICT=$verdict
}

require_quiet_app() {
  app_state
  [ "$UNSAVED" -eq 0 ] || die "the app holds $UNSAVED unsaved output(s); a test batch would clear them. Save or discard them first" 2
  [ "$LEFTOVERS" -eq 0 ] || die "the app holds $LEFTOVERS rollback cop(y/ies) from an earlier replacement; open the app once so it settles them, then try again" 2
}

cmd_run_copy_tests() {
  require_run
  identity
  require_quiet_app
  local case fsha fbytes fpath
  for case in ${CASES//,/ }; do
    verify_protection "before-$case"
    case "$case" in
      padding-repair | batch-replace)
        fsha=$(field F padding 4); fbytes=$(field F padding 3); fpath=$(field F padding 5)
        [ -n "$fsha" ] || die "no fixture in this run: run the fixture command first" 2
        instrument "$case" "$TEST_PACKAGE.PaddingRepairBatchDeviceTest#$([ "$case" = padding-repair ] &&
          echo a_aSmallerCandidateHasItsAudioEndPaddingRepairedAndIsAccepted || echo b_aHarnessBatchReplacesItsDisposableCopyOnlyAfterAProvenBackup)" \
          -e pllabRunId "$RUN" -e pllabFixture "$fpath" -e pllabFixtureSha256 "$fsha" -e pllabFixtureBytes "$fbytes"
        ;;
      fallback)
        [ -n "$FALLBACK_K" ] || die "the fallback case needs --fallback-baseline K (the B that selects a marginal trial)" 64
        fpath=$(field B "$FALLBACK_K" 5)
        [ -n "$fpath" ] || die "no B$FALLBACK_K in this run" 2
        instrument "$case" "$TEST_PACKAGE.SaferRungFallbackDeviceTest" -e sourceVideo "$fpath"
        ;;
      corpus)
        # Every B of this run, as "path:sha256:bytes": one copy-mode batch over copies of them all.
        fpath=$(awk -F'\t' '$1 == "B" { printf "%s%s:%s:%s", (n++ ? "," : ""), $5, $4, $3 }' "$(manifest "$RUN")")
        [ -n "$fpath" ] || die "no B in this run" 2
        instrument "$case" "$TEST_PACKAGE.CorpusBatchDeviceTest" -e pllabRunId "$RUN" -e pllabBaselines "$fpath"
        ;;
      app-checks)
        # Needs no media: gallery publication with test-made bytes, and the batch row's states.
        instrument "$case" "$TEST_PACKAGE.GalleryPublicationDeviceTest,$TEST_PACKAGE.BatchItemCardDeviceTest"
        ;;
      *) die "unknown case: $case (app-checks, padding-repair, batch-replace, corpus, fallback)" 64 ;;
    esac
    verify_protection "after-$case"
  done
}

cmd_run_replacement_tests() {
  require_run
  $DISPOSABLE_ONLY || die "replacement cases run only with --disposable-only: they write to the C copies this run makes, never to A or B" 64
  identity
  local k bpath bsha bbytes first=true
  for k in $(awk -F'\t' '$1 == "B" { print $2 }' "$(manifest "$RUN")"); do
    bbytes=$(field B "$k" 3); bsha=$(field B "$k" 4); bpath=$(field B "$k" 5)
    local args=(-e pllabRunId "$RUN" -e pllabBaseline "$bpath" -e pllabBaselineSha256 "$bsha" -e pllabBaselineBytes "$bbytes")
    verify_protection "before-replacement-B$k"
    instrument "replacement-B$k" "$TEST_PACKAGE.SafeCopyReplacementDeviceTest" "${args[@]}"
    verify_protection "after-replacement-B$k"
    if $first; then
      first=false
      # A process killed a third of the way through overwriting a C, then two starts after it.
      instrument --expect-crash "death-B$k" "$TEST_PACKAGE.SafeCopyProcessDeathDeviceTest" "${args[@]}" -e pllabPhase die
      [ "$LAST_VERDICT" = crashed-as-intended ] || warn "the death case was meant to end 'Process crashed'; it ended $LAST_VERDICT"
      verify_protection "after-death-B$k"
      instrument "recover-1-B$k" "$TEST_PACKAGE.SafeCopyProcessDeathDeviceTest" "${args[@]}" -e pllabPhase recover-1
      instrument "recover-2-B$k" "$TEST_PACKAGE.SafeCopyProcessDeathDeviceTest" "${args[@]}" -e pllabPhase recover-2
      verify_protection "after-recovery-B$k"
    fi
  done
}

cmd_collect() {
  require_run
  identity
  local host report
  host=$(host_run "$RUN"); report="$host/report.txt"
  adb exec-out run-as "$PKG" cat "files/pllab/$RUN/results.jsonl" >"$host/results.jsonl" 2>/dev/null || true
  adb logcat -d -s System.out:I 2>/dev/null | tr -d '\r' | grep -E 'pllab-(result|batch)|b287-fallback|gallery-publication' >"$host/printed.txt" || true
  # The rendered row states (synthetic rows only), as PNGs.
  local png
  mkdir -p "$host/ui-evidence"
  for png in $(dsh run-as "$PKG" ls -1 files/pllab/ui-evidence | grep -E '^[A-Za-z0-9_-]+\.png$'); do
    adb exec-out run-as "$PKG" cat "files/pllab/ui-evidence/$png" >"$host/ui-evidence/$png" 2>/dev/null || true
  done
  {
    say "safe-copy harness run $RUN on $MODEL (API $SDK), app $APP_VERSION"
    say ""
    say "cases (instrumentation verdicts):"
    sed 's/^/  /' "$host/verdicts.tsv" 2>/dev/null || say "  none run"
    say ""
    say "what each case recorded:"
    sed -E 's/^/  /' "$host/results.jsonl" 2>/dev/null || true
    say ""
    say "protection checks (A and B against the manifest):"
    sed 's/^/  /' "$host/protection.log" 2>/dev/null || say "  none"
    say ""
    say "left in place until the owner approves cleanup: B and F in $RUNS_ROOT/$RUN/,"
    say "the C copies and recovered copies in Movies/PLLabValidation/$RUN/, and this folder."
  } >"$report"
  say "report       : $report"
}

summary_exit() { # 3 protection, 1 failed or crashed, 2 incomplete, 0 passed
  local v
  v=$(cut -f2 "$(host_run "$RUN")/verdicts.tsv" 2>/dev/null)
  if grep -qxE 'failed|crashed' <<<"$v"; then return 1; fi
  if grep -qx incomplete <<<"$v"; then return 2; fi
  return 0
}

# ---- arguments ------------------------------------------------------------------------------
COMMAND=""; RUN=""; SOURCE_IDS=(); DRY_RUN=false; FINAL=false; CASES=""; FALLBACK_K=""; DISPOSABLE_ONLY=false
while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL=${2:?}; shift ;;
    --run) RUN=${2:?}; shift ;;
    --source-id) SOURCE_IDS+=("${2:?}"); shift ;;
    --dry-run) DRY_RUN=true ;;
    --final) FINAL=true ;;
    --cases) CASES=${2:?}; shift ;;
    --fallback-baseline) FALLBACK_K=${2:?}; shift ;;
    --disposable-only) DISPOSABLE_ONLY=true ;;
    inventory | stage | verify-protection | fixture | run-copy-tests | run-replacement-tests | collect | all)
      [ -z "$COMMAND" ] || die "one command at a time" 64; COMMAND=$1 ;;
    *) die "unknown argument: $1" 64 ;;
  esac
  shift
done
[ -n "$COMMAND" ] || die "usage: safe-copy-harness.sh [--serial S] inventory|stage|verify-protection|fixture|run-copy-tests|run-replacement-tests|collect|all ..." 64
command -v adb >/dev/null 2>&1 || die "adb is not installed here (pkg install android-tools)" 2
choose_device

case "$COMMAND" in
  inventory) cmd_inventory ;;
  stage) cmd_stage ;;
  verify-protection) cmd_verify ;;
  fixture) cmd_fixture ;;
  run-copy-tests) [ -n "$CASES" ] || die "pass --cases" 64; cmd_run_copy_tests; cmd_collect; summary_exit; exit $? ;;
  run-replacement-tests) cmd_run_replacement_tests; cmd_collect; summary_exit; exit $? ;;
  collect) cmd_collect ;;
  all)
    $DRY_RUN && die "all has no dry run; use stage --dry-run" 64
    cmd_stage
    verify_protection initial
    cmd_fixture
    CASES="app-checks,padding-repair,batch-replace,corpus${FALLBACK_K:+,fallback}"
    cmd_run_copy_tests
    DISPOSABLE_ONLY=true
    cmd_run_replacement_tests
    verify_protection final
    cmd_collect
    summary_exit; exit $?
    ;;
esac
