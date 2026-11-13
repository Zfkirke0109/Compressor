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
3. Source identity. **On the phone (no computer needed):** Settings → Experiments → turn on
   **Pilot: full source hash** and leave it on for every arm. Each original is then hashed in
   full (SHA-256) before and after its job, and the export carries both hashes by job id, no
   names or paths. `ingest` refuses an arm where a before/after pair differs or is missing, and
   `table` refuses arms whose hashes differ. The hashing reads each original twice; its time is
   reported separately and kept out of the job times. **Or, with a computer or a shell that can
   read the Secure Folder:** `python3 research/encoder_experiments/run_experiment.py hash
   research/encoder_experiments/pilot_manifest.json <originals-dir> --out private-hashes.csv`,
   kept local.
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
     batch_<id> --out results/` (or send the ZIP and have it ingested for you). A refusal names
     what is missing; fix and re-run the arm under the next ID rather than editing a capture.
6. `python3 research/encoder_experiments/run_experiment.py table
   research/encoder_experiments/pilot_manifest.json --results results/ --baseline A0 --markdown
   table.md` and `python3 scripts/diagnostics/parse_session_jsonl.py <zip> --batch batch_<id>`
   per arm (replay coverage, attempts, audio claims, drift).
7. Share the ZIPs, `table.md`, the thermal notes and the recording. The in-app hashes are in the
   ZIPs already; a computer-made hash sheet, if any, stays private (it holds paths).

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

## 7. First device run: b182 (`pr44-b182`, merge `f5deaa8` of `bd33cae`)

Two exports (`20260928-015343`, `20260928-231408`) hold the same two b182 batches, byte-identical.
Both ran with **longer keyframe interval (x2) on, VMAF v1 shadow calibration on**, safer-rung retry
on, B-frames 2, Exhaustive on, replace-original off. Two experiment switches at once: this is an
observational run of the long-GOP configuration, not a pilot arm, and it learned under `;gopx2`.

| Batch | Sources | Result |
|---|---:|---|
| `batch_1790560725342` | 12 (all new since b177), 4.11 GB | 12 probe rejections, 175 s; created the first 5 `;gopx2` profiles |
| `batch_1790570024266` | 236, 54.67 GB | 22 accepted, **723,250,383 B**, all pixel-certified, 0 contradictory records; audio 21 bit-identical + 1 none |

On the 223 sources common to b177 (same name hash and size), observational, not controlled:

| Run | Keyframe interval | Accepted | Accepted reduction | Summed job time | Batch wall (all sources) |
|---|---|---:|---:|---:|---:|
| b177 PL-A | source | 22 | 707,773,964 B | 6,996 s | 8,033 s (226) |
| b177 PL-B | source | 23 | 708,868,481 B | 9,721 s | 10,677 s (226) |
| b182 | source x2 | 22 | 723,250,383 B (+14,381,902 vs PL-B) | 7,972 s | 8,375 s (236) |

The net +2.0 % is mixed, not uniform. `job_458aa0663c3e` (4K) passed at 0.70 where b177 failed at
0.70 on the same short ladder (+14.3 MB; certification mean 95.7 against the 95.5 bar);
`job_d127463b57d6` 0.75 → 0.70 (+15.5 MB); `job_dbe605676504` 0.75 → 0.68 (+6.5 MB). Against that,
`job_34f35736a973` failed 0.75 and certified at 0.80 (−22.4 MB), and `job_c0f82bb62f94` passed its
probe but measured 1.181 overshoot (b177: 1.076), so the size gate kept the original (−0.88 MB).
Build changes since b177 and different learned states also differ between these runs.

Also measured:
- **Failures:** 2 measured certification failures (`job_01cf1624426d` at 0.95, `job_c92a4ca7e1be` at
  0.97), each kept as the original; the retry was denied (no measured safer rung). 2 discarded
  candidates kept the original: one lost its colour standard (`standardMatches`), one passed
  every predicate but was 23,628 B larger than its source. 6 damaged sources, as in b177.
- **Drift** probe → certification over 62 identity-joined windows: median −0.06 / −0.05 / −0.07,
  p10 −0.36 / −0.77 / −0.86, inside the +0.5 / +1.25 / +1.0 selection margins. Marginal passes: 5
  attempted, 2 certified.
- **Timing** (this build's per-window instrumentation, 678 windows): 5,744 s of window wall time,
  **89 % waiting for decoded frames**; VMAF v0 378 s; v1 shadow 216 s on its 24-window budget
  (b177 PL-B's unbudgeted shadow: 748 s). 72 % of decoded frames are lead-in (decoded, paired,
  never scored), the same share as b177 (72 %): it comes from the window plan, not the GOP.
  Decoding, not scoring, is where scoring time goes; that is the speed lever, unmeasured so far.

Two record defects this run exposed, fixed after `093d98e`:
- `replay_coverage` called both batches partial: the 12-source batch because no job reached an
  encode (a batch-wide stage list, not a per-job gap), and `job_641d0183abbb` because its record
  names the last measured rung although both rungs were undecided and no size gate ran. Coverage
  is now per job only, and the size gate is required when a rung passed or was marginal: in b182
  that is exactly the 29 of 236 jobs with a `size_gate` event. Both batches now read
  `REPLAYABLE_RECORD`.
- The attempt ledger recorded `job_50d1ff00cad6` as `structural_failed` although verification
  passed and only the replacement was blocked (not smaller). Such a discard is now
  `replacement_blocked`; a structural failure stays `structural_failed`.

Not shown by b182: the full-source hash (not in that build), the progress display (no recording),
any single-factor effect (two switches on), and repeatability (one run).

## 8. b184 (`pr44-b184`, merge `f5152fc` of `708012e`): why most sources do not pass

Export `20260929-142625`. Two PL batches, both with longer keyframe interval, VMAF v1 shadow and
the new full source hash on (so again not a pilot arm):

- `batch_1790665769897`: stopped after 127 of 236 sources with no terminal record and no matching
  process-exit record in the export; 15 accepted, 530,716,467 B before it stopped.
- `batch_1790699126317`: complete. **22 accepted, 723,250,383 B** of 54.67 GB, all pixel-certified,
  0 contradictory records. Every source hashed before and after its job: **236 of 236 originals
  unchanged** (300.8 s of hashing). Identical to b182 source by source (236 of 236 terminals and
  output sizes), although the starting learned state differed (33 vs 56 profiles).

**What stops the other 180 (`SKIPPED_WOULD_DEGRADE`) is measured quality, not a defect.**

| Source density (bits per pixel) | Sources | Accepted | Rejected by measurement |
|---|---:|---:|---:|
| < 0.04 | 72 | 0 | 54 |
| 0.04–0.06 | 73 | 0 | 70 |
| 0.06–0.08 | 42 | 2 | 34 |
| 0.08–0.12 | 38 | 17 | 17 |
| ≥ 0.12 | 11 | 3 | 5 |

- 179 of the 180 are H.264 (median 0.050 bpp; accepted median 0.095). At the safest rung tried
  (0.95 or 0.97 of the source's own bitrate) the worst window is a median **4.55 points** below the
  bar (148 bound by the mean, 32 by p5); 23 are within 1 point, 81 more than 5 points below.
- The self-check's encoder ceiling on one of them (`job_0b01f05b3686`, 1080x1920 at 60 fps): the
  same window scores 93.16 mean at 0.90x, 93.48 at 0.95x and **97.06 at 2.00x the source bitrate**.
  It clears the bar only at a bitrate above the source's own, which cannot make the file smaller.
  On an HEVC 720p source the same pipeline reaches 100/100/100 at 2x, so the decode/encode path
  itself is not losing quality; re-encoding dense-noise H.264 at a lower bitrate is.
- Pairing is exact (the drift p10 of −0.36 / −0.77 / −0.86 is inside the selection margins), the
  size prediction held on all 22 encodes (actual minus predicted −0.106 to +0.022), and the 2 certification failures and 2 discards were
  kept as the original. Nothing here is recoverable by a fix without lowering the bar.

Fixed from this capture: the self-check printed **"FAIL (min 98.86; a scorer or pairing defect)"**
on the source compared with itself, four times, with exact pairing. VMAF v0.6.1 scores identical
frames 97.43–100 depending on motion (VIF and ADM are exactly 1; motion comes from the reference
alone), so a low-motion run scored 98.9 on identical frames. The identity controls now count
byte-identical decoded frames and decide PASS/FAIL on that count; the score is only described.

Not a defect, but noted: a 2 fps screen recording (`job_641d0183abbb`) reaches only 81 at 2x its
bitrate. Five sources are below 15 fps (about 61 MB together), so rate control at very low frame
rates is not a savings lever for this library.

**Learned profiles: do not reset.** In Exhaustive mode every source is probed on every run, and the
learned state did not change a single outcome: b177 PL-A and PL-B (different snapshots) probed the
same rungs with the same proven ratios, and b182 and b184 (33 and 56 profiles) produced the same
236 terminals and output sizes. A reset cannot make a measured rejection pass. It would discard
the learned overshoot used when probes give too few windows, and it would break comparability with
the recorded snapshots the pilot relies on. The experimental `;gopx2` profiles are kept apart
under their own key and do not affect the baseline.

What could still raise the accepted count, each unmeasured: the pilot's one-factor arms (A0 with
the long keyframe interval and the shadow off, then A1/A2/A3), and an encoder operating point the
app cannot yet request (CQ, complexity, another encoder). Lowering 95.5 / 91 / 84 would pass more
files by redefining "perceptually lossless", which needs a blinded viewing study first
(`docs/PERCEPTUALLY_LOSSLESS.md`).

## 9. October 1 adversarial tests (`5102f15`) and the fix (`dc25a35`)

`ScientificEvidenceSafetyTest` (owner, nine tests) failed nine of nine on `aa1b31a`; `dc25a35`
(owner) fixes all nine. Every change is stricter. The bar (95.5 / 91 / 84), the probe margins and
the 12-frame rule are unchanged.

| Test | What it caught | Change in `dc25a35` |
|---|---|---|
| `absentPixelsCannotAuthorizeAPlTranscodeAtAnyRatio` | An `Unavailable` certification kept a transcode at or above the default ratio (probe basis) and always (no probe basis) | Every basis needs a fully scored, passing sample. Sources that cannot be scored at all (`pixelCertifiable` false: HDR, codec downgrade, above the scoring cap, no scorer) are planned as keep-original |
| `passingPrefixDoesNotProveTheMissingWindow` | A partial sample whose scored windows passed fell back to that rule | Partial samples keep the original on every basis |
| `nonFiniteScoresAreUnavailableEvidence` | A NaN window read as a measured failure; +Infinity cleared every gate | `QualityProbePolicy.validScore`: non-finite or negative scores are unmeasured; the scorer returns unavailable when per-frame scores are missing or non-finite |
| `pipelineFailureAlongsideBitrateFailureMustNotTrainQuality` | A floor failure beside an audio failure taught QUALITY | Any non-floor failure makes it PIPELINE |
| `inferredBitrateFloorIsNotMeasuredVisualEvidence` | The structural floor alone taught QUALITY | Only recovery pixels measured below the bar teach QUALITY; otherwise UNDECIDED |
| `misalignmentDoesNotProveBitrateStarvation` | Misalignment was "measured negative": labelled "would degrade" and learned | Still rejects; now UNEXPECTED_REMUX and not learned |
| `highBitrateDoesNotMakeDifferentAudioPacketsTransparent` | A PL audio re-encode at ≥ 256 kbps (less 10 %) passed with packets known to differ | PL audio passes only on packet proof |
| `unavailableAudioComparisonIsNotProofOfACopy` | Matching codec and shape with no exposed bitrate passed as an inferred copy | As above; Remux unchanged |
| `differentBt601PrimariesNeedDecodedColorEvidence` | BT.601 NTSC → PAL was accepted for PL (`355d19f`) | A colour mismatch again; the report names the BT.601 cause |

`dc25a35` also releases the distorted-frame buffer on a JNI early return and closes each decoded
`Image`, and rewords three keep-original labels so a heuristic or a gate result is not described
as visible loss.

**Follow-up.** CI's `unit-tests` job failed on `dc25a35`: eight older tests still asserted the
replaced behaviour (`BatchQualitySafetyTest` ×6, `PixelProvenFloorTest`,
`ExhaustivePerceptualLosslessPolicyTest`). The verifier fixtures that model the S23 Ultra AAC
pass-through now carry the packet proof the device produces, and the exhaustive test asserts the
new rule. Three stale texts were corrected: the certification reason still said "unavailable for a
sub-default-ratio encode" (absent pixels now reject at every ratio), `CertificationFailure` and a
ViewModel comment still counted misalignment as measured, and a PL audio line still said "(stream
copied)" for an uncompared inferred copy beside the failing check it caused.

**Effect on the last device run.** In b184 (both full batches) all 22 accepted outputs were
pixel-certified with decision `passed`; 21 carried a bit-identical audio copy and one had no audio.
The certification and audio changes would have kept every one of them. The job record does not
store the colour standard, so the BT.601 change cannot be counted from that capture; b161 had two
such encodes. The learning changes alter only which discarded attempts move a profile.

**`626e56c` (owner, four tests), fixed here.** `ScientificPipelineRegressionTest` failed four of
four on `626e56c`:

- `inaccessibleHardWindowsCannotCollapseOntoTheOpeningScene`: when neither keyframe was within
  12 s of a wanted window, `ProbeWindowPlanner.place` fell back to the file's opening keyframe, so
  every hard position of a long-GOP file collapsed onto one window of the opening scene. Such a
  window is now unplaceable (absent evidence, which since `dc25a35` keeps the original).
- `aDistantNextKeyframeDoesNotReplaceTheRequestedContent`: a next keyframe any distance away
  replaced the wanted position. It must now be within 12 s of it, as the previous keyframe must.
- `invalidShadowScoresAreAbsentDiagnostics`: `WindowV1Diag.fromPerFrame` rejected NaN and
  negative scores but not infinities.
- `emptySelfCheckCannotReportIdentity`: `SelfCheckVerdict.identity` (from `aa1b31a`) threw on an
  empty window list instead of reporting UNPROVEN.

`ProbeWindowPlannerTest` asserted the opening-keyframe fallback for a single-keyframe source and now
asserts that every window is unplaceable. In b184 every accepted job's certification windows sit at
about 20 / 50 / 80 % of its duration (one window for the two clips under 10 s), so no accepted output
used the removed fallback.
