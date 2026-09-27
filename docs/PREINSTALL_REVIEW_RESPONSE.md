# Pre-install review: response and S23 Ultra pilot runbook

Input: `Compressor-Preinstall-Review-2026-09-27.md` (an external review of seven commits,
`1b31717`..`b82d465`, published over `c032dee`; local test head `6996913`, the same tree
`f4dbd5cf…` as `b82d465`). The review is treated as claims to check, not as instructions.
The b177 captures (`batch_1790443993165` PL-A, `batch_1790453977026` PL-B, build `pr44-b177`)
are historical evidence. **Nothing below is a measurement of this build on the device.**

Gates unchanged: window mean 95.5 / p5 91 / min 84, probe selection margins +0.5 / +1.25 / +1.0,
at least 12 compared frames per window, verdict model vmaf_v0.6.1 with `phoneModel=false`.

## 1. The seven commits, checked against the code

| Commit | Claim | Checked | Integration / regression check |
|---|---|---|---|
| `1b31717` audio | Known-different packets cannot use a missing bitrate as copy evidence | Confirmed. `audioLooksStreamCopied` now requires `!audioPacketsDiffer`; `AudioPreservation` ranks a measured difference above inference | Stricter only. In every b177-era capture, 283 PL encodes were bit-identical and none was an inferred copy with differing packets; the 11 PL re-encodes already failed the inference (`inferredStreamCopy = audioLooksStreamCopied && !identical`), so no historical verdict changes |
| `1b31717` verification reuse | One immutable input per candidate; floor recovery re-evaluates it with the certified floor | Confirmed. `verify(input)` is pure (no I/O); the candidate is not modified between capture and re-evaluation; a retry or remux captures its own input | Recovery-then-certification also reuses the recovery scores (`floorRecoveryCertScores`), so the same output is not scored twice |
| `1b31717` cancel identity | `cancelEvent` takes token and index from the same ledger entry | Confirmed | none |
| `dbfdbd1` B-frame baseline | The first toggle pins the previous setting | Confirmed; b177 ran `maxBFrames=2`, so an upgraded install pins 2 as the baseline on its first plan or toggle, and A2 (B-frames off) learns under `;bf0` | none |
| `af0d08a` scorer queues | Stop before join; 50 ms cancellation checks | Confirmed root cause: on any error exit the old code cleared a full queue, the freed producer refilled it and blocked forever publishing END (capacity is 1 at 4K), costing a 10 s `join` timeout per reader and leaking the thread. Successful windows end only after both END markers, so they never hit it | No queue timeouts appear in either b177 decision log; this was a latent stall on error/cancel paths |
| `d14d273` human labels | HUMAN only for 0/1 labels with human provenance; the study optimises the selected column | Confirmed | none |
| `5b6e68f` HDR layouts | Mismatched native pixel formats are rejected | Confirmed | Research only; no HDR gate |
| `59e2a64` pilot identity | Ingestion requires completion, settings, build, gate, fingerprints; no overwrite | Confirmed against the app: every required field is written (`session_start` build/mode/count, `run_identity.scoring` frozen gate + four settings, one `learned_state_snapshot`, `session_summary` processed/manifest/wall time, per-job fingerprint). On both b177 runs the accepted-savings invariants hold (23 and 24 accepted; 818,443,848 and 819,538,365 B) | **Gap found and fixed (§2.2):** nothing required Exhaustive mode |
| `b82d465` per-job replay | Coverage per job | The check is right; **the app could not satisfy it (§2.1)** | Fixed on the app side |

## 2. Additional fixes, each from concrete evidence

### 2.1 Every job now ends with its terminal event (replay coverage)

`replay_coverage` requires an `accept` stage for every job, `finalize` for every job with
`candidateBytes`, and `certify` for every `ran_*` certification status. The app emitted `accept`
only from `finalizeItem`: eight other job-record paths (probe skip, doomed lossy skip,
keep-original up front, certification failure, item failure, keep-original after a discarded
attempt, already-compressed, batch cancellation) wrote none. In each b177 PL batch 199 of 226 jobs
ended on those paths, so every realistic capture would have read `PARTIAL_OBSERVATIONAL_REPLAY`.
Also, `finalize` was emitted only for encodes (a remux candidate had none), and a failed floor
recovery (`ran_floor_recovery_*`) had no `certify` event.

- `recordDiagnosticJob`, the single funnel for job records, emits `StageEvent.terminal` (reason
  `accepted` only when the job keeps an output and is not a failure; `keptBytes`, `candidateBytes`,
  `terminal`) unless the finalize path already emitted its own.
- `finalize` is emitted for every candidate, with `operation=encode|remux`.
- A floor recovery that did not pass emits `certify` with `scope=floor_recovery`, `accepted=false`
  and its windows. A passing recovery needs none: final certification reuses its scores and emits
  its own `certify`. The drift join reads only `accepted=true` events, so it is unaffected.

Telemetry only: no decision reads these events. Tests: `DiagnosticEventsTest.everyTerminalPath…`;
`test_replay_coverage_matches_what_each_terminal_path_emits` (a mixed session is
`REPLAYABLE_RECORD` with the new events and names exactly the three pre-fix gaps without them);
`ProductionEvidenceDeviceTest` now asserts an `accept` event for every job (device, not run).
Floor recovery never ran in b177 (0 log lines), so that part is from the code alone.

### 2.2 Pilot arms must be Exhaustive captures; thermal state and cooldown are reported

Fast mode skips probing where learned history predicts failure, so each arm's learned state would
decide which sources were measured at all. b177 PL-A and PL-B ran Exhaustive and, despite
different learned snapshots, probed 448 vs 449 rungs with the same proven ratio on all 204 probed
jobs. `run_experiment.py ingest` now refuses a capture without `exhaustivePerceptualLossless=true`
and `table` refuses a mix. The table also reports each arm's summed thermal cooldown (part of
batch wall time; 370 s in PL-B) and the thermal state each measured job started in.

### 2.3 Accepted size reduction is not called saved space

The batch summary said "Saved by real compression" while outputs sit beside retained originals
(copy mode, which the pilot uses) or behind backups; nothing is freed then. It now reads
"Accepted size reduction (real compression)" plus, when applicable, "Not free space yet: N of M
accepted outputs are copies beside the original; backups of K replaced originals are kept". Item
cards say "X smaller" instead of "saved X". The counted quantity is unchanged.

### Investigated, no change

- **Progress:** accurate. The batch shows terminal items over all items; an item's bar has a
  fraction only for encoding, remuxing and certification windows; other phases are indeterminate.
  Probing is indeterminate for its whole duration (up to 15.8 min on `job_458aa0663c3e` in PL-B)
  because the ladder length is not known in advance: honest, uninformative.
- **Speed:** the dominant b177 time difference was the device, not the work. PL-A vs PL-B probed
  448 vs 449 rungs and scored 610 vs 613 windows, yet took 2.7 vs 4.5 s per rung. On
  `job_458aa0663c3e` the decisions, window scores and output size were identical while probing
  took 117 s vs 948 s and certification 85 s vs 482 s. Timing comparisons need matched conditions
  and a repeat.
- **Retry overhead:** bounded (one retry, eligible files only); PL-B used one (+1,094,517 B).
- **Savings accounting:** app, summariser and runner count only `countsAsRealCompression` records
  with `finalAccepted`, `verified`, pixel certification and `0 < output < source`.

## 3. Validation

Commands and results are in the PR body for the exact commit; the CI run for that commit is the
authority before installing.

## 4. APK and signing

Install the **PR debug APK built from the reviewed head** (`Build PR Debug APK`, artifact
`compressor-debug-pr-44-b<run>`), only after that commit's Android CI is green. The installed
build is `io.github.zfkirke0109.galaxycompressor` debug `1.6.177` (`pr44-b177`) in user 150. The
PR workflow refuses to build unless the keystore's certificate SHA-256 equals the
`EXPECTED_SIGNER_SHA256` secret, and neither the workflow nor the Gradle signing config changed
since b177, so a successful PR build is signed like b177 and installs as an update (its
`versionCode` is larger). A locally built APK is signed with a different key and must not be
installed over it (that would require uninstalling, which deletes the app's learned state).

## 5. Pilot runbook (S23 Ultra, Secure Folder user 150)

Preconditions: originals backed up; replace-original **off**; Exhaustive **on**; the ten sources
in `pilot_manifest.json` available in the Secure Folder gallery; no other heavy app running.

1. **Before installing**, in the current build: Diagnostics → Everything → export (keeps the
   b177-era state on record). Do not clear app data or reset learned profiles.
2. Install the APK from §4 as an update in user 150 (`adb install -r --user 150 <apk>` or the
   Secure Folder installer). Open the app once; confirm Settings shows `1.6.<run>`.
3. On a computer, privately: `python3 research/encoder_experiments/run_experiment.py hash
   research/encoder_experiments/pilot_manifest.json <originals-dir> --out private-hashes.csv`.
   Keep the CSV and paths local; they never go in the repository or a report.
4. Optional device checks first (need adb): `scripts/device/run-device-checks.sh <short-clip>.mp4
   150` (PipelineDeviceTest + ProductionEvidenceDeviceTest).
5. For each arm in order **A0, A1, A2, A3, A0_REPEAT**:
   - Settings → Experiments to the arm's settings (all arms: safer-rung retry on, shadow off
     except A3; A1 longer keyframe interval on; A2 B-frames off; A0/A3/A0_REPEAT B-frames on).
   - Phone on charge, screen on with the app in front, battery saver off, cooled to the same
     starting state. Before and after, record `adb shell dumpsys thermalservice` (thermal status)
     and `adb shell dumpsys battery` (temperature, level); without adb, the job records'
     `thermalStart`/`thermalEnd` and the probe lines' `thermalStatus` are the record. Note the
     start and end time.
   - Select exactly the ten manifest sources (same order), mode Perceptually Lossless, start.
   - In the first A0 only: screen-record the run and take a Perfetto trace (CPU frequency, idle,
     scheduling) while `job_458aa0663c3e` runs.
   - Do not cancel. When it ends: Diagnostics → Current run → export ZIP.
   - `python3 research/encoder_experiments/run_experiment.py ingest
     research/encoder_experiments/pilot_manifest.json --arm <ARM> --capture <zip> --batch
     batch_<id> --out results/`. A refusal names what is missing; fix and re-run the arm under
     the next ID rather than editing a capture.
6. `python3 research/encoder_experiments/run_experiment.py table
   research/encoder_experiments/pilot_manifest.json --results results/ --baseline A0 --markdown
   table.md` and `python3 scripts/diagnostics/parse_session_jsonl.py <zip> --batch batch_<id>`
   per arm (replay coverage, attempts, audio claims, drift).
7. Share the ZIPs, `table.md`, the thermal notes and the recording. Not the private hash sheet.

What each measure means:

- **Accepted byte savings**: `acceptedSavedBytes` per arm; incremental bytes against A0 on the
  same ten sources. A3 must equal A0's acceptance on every source (the gate never reads v1).
- **Batch wall time** vs **summed job time** vs **cooldown**: all three are reported; A0 vs
  A0_REPEAT is the noise floor for any timing difference.
- **Failures/retries**: measured rejections, unavailable evidence, attempts started, jobs retried.
- **Progress accuracy**: from the screen recording, each item's shown phase against the
  `stage` events' `elapsedMs` (plan, probe_rung, encode, finalize, verify, certify, accept).
- **Learned state**: every arm records its starting snapshot. A1 and A2 learn under their own
  keys, starting empty; A3 and A0_REPEAT start from the state A0 left. The table prints the
  snapshots and says when they differ; Exhaustive limits, but does not remove, that confound.

## 6. What remains unproven

- Nothing in §2 has run on the device; this build's timing, progress and replay coverage are
  unmeasured. Device validation is **pending**.
- **Savings:** the only measured total is b177's 819,538,365 B of 50,946,351,461 B (1.61 %).
  No operating point beyond b177 has been measured; 2, 3 or 5 GB are not supported by any evidence.
- **Quality:** sampled luma VMAF windows under a fixed gate. Whole-file chroma, banding,
  long-range temporal behaviour, HDR and re-encoded audio are not certified. No human viewing or
  listening study backs the thresholds (the v2 study checked policy consistency only). No claim of
  perceptual equivalence is made.
- **Speed:** b177's timing varied up to 8x on identical work; no speed claim is possible without
  matched, repeated runs.
