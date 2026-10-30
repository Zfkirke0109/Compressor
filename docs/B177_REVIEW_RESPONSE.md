# b177 review: what was confirmed, what changed, and what is still unproven

This answers the b177 review of PR #44 at `75e3b96` (tree `5b9383db`, the same tree as build 177)
and its handoff prompt. The inputs were the two b177 Everything exports, the review, the
calibration research note and the evidence files. No original video, no phone and no new screen
recording were available. So everything below the "device" line is still unrun.

The production gate is unchanged: v0.6.1 without the phone transform, 95.5 / 91.0 / 84.0, probe
margins 0.5 / 1.25 / 1.0, at least 12 frames per window. No threshold, margin or evidence rule was
relaxed. No result was relabelled.

## 1. Findings ledger

| # | Finding | Status at 75e3b96 | Evidence used | Disposition |
|---|---|---|---|---|
| F1 | A later window that cannot be scored erases the earlier ones | **Confirmed**, latent (no false acceptance is shown in the b177 logs) | `VmafPairScorer.score` returned bare `Unavailable` on the first unscorable window. `certificationOutcomePasses` / `…WithoutProbeBasis` then accept `Unavailable` at the default ratio, or without a probe basis | **Fixed.** `collectWindows` attempts every window and keeps the scored ones. Some scored and some unavailable is `PairScoreOutcome.Incomplete`. A measured failure among the scored windows rejects on every basis. A clean partial sample is `PARTIAL`, which is never pixel certification. `certifyPixels` decides through `CertificationGate`. Tests: `PartialScoringTest` (8 cases, including all five the prompt names) |
| F2 | The retry record mixes two rungs | **Confirmed, observed**: PL-B `session.jsonl` line 46, `job_478c2fa19100`, `plannedTargetRatio=0.9` beside the 0.85 windows `99.081/…;97.694/…;96.491/…` | `ProbeDecision` kept `saferPassingRatio` without its scores. `retrySaferRung` copied the plan with the new ratio and the old windows | **Fixed.** `RungEvidence` is frozen per rung and carries the ratio, config id, planned window ids, full-precision scores, rate factors and time. The retry carries the 0.90 rung's own evidence and `probeRungId`. The summariser joins drift only on identity. Tests: `RungEvidenceTest`, `test_parse_session_jsonl` (identity join, legacy exclusion) |
| F3 | Failed retries vanish from the denominator | **Confirmed** (static) | `retainOriginalInsteadOfCopy` and `handleItemFailure` wrote no attempts. The stage `attempt` field held the batch-wide token (260, 261) | **Fixed.** `AttemptLedger` is append-only and each attempt is finished once, by export failure, structural failure, certification, acceptance, fallback, cancellation or failure. The record carries `attemptLedger` and `attemptsStarted`. Stage events carry a per-job `attemptIndex` beside the token. Tests: `AttemptLedgerTest` (measured failure then a second-attempt export failure, structural failure, cancellation, or acceptance) |
| F4 | Only partial replay is possible | **Confirmed, observed**: PL-B has only plan / finalize / certify / retry / accept stages, and all 208 learned updates have `jobId=null` | `StageEvent` constants existed that production never emitted | **Fixed.** Production now emits `probe_rung` (one per rung, with window ids), `size_gate`, `encode` (started, completed, failed, cancelled) and `verify`. Learned-state updates carry jobId, token, attemptIndex, updateIndex and snapshot sha. `run_identity` records the libvmaf version, model names, native library hashes, the encoder capability inventory and experiment settings. Each job gets a `sourceFingerprint`, and the session summary gets `sourceManifestSha256`. Historical fixtures stay `PARTIAL_OBSERVATIONAL_REPLAY`; the summariser now reports `replayCoverage` |
| F5 | HQ audio wording contradicts the plan | **Confirmed, observed**: 16 HQ jobs, including both HDR jobs, requested `audio=copy` and were recorded "re-encoded (lossy mode)" | `AudioPreservation` used that text for every lossy mode | **Fixed.** `audioRequested` is recorded, and `audioPreservation` states what was observed: bit-identical, inferred copy, packets differ, or not shown to be a copy. Packets are compared in every re-encoding mode. Audio processing is unchanged. **New observation:** one accepted PL-B output, `job_c0f82bb62f94`, requested a copy but is recorded as a re-encode (see §5) |
| F6 | An insufficient floor recovery still teaches a quality failure | **Confirmed** (static) | Floor-recovery failures went to `LearningEvidencePolicy` as QUALITY, with a step-up | **Fixed; policy documented.** If recovery pixels measured nothing below the bar (INSUFFICIENT, PARTIAL or PASSED), the result is UNDECIDED and nothing is learned. If no pixel was measured at all (no recovery, or UNAVAILABLE), the structural floor keeps its conservative step-up. A measured recovery failure is QUALITY. The original is kept in every case. Test: `FloorRecoveryLearningTest` checks high scores on 11 frames against the real engine and asserts an empty state delta |
| F7 | The HDR harness can mislead | **Confirmed** by the handoff reproduction (3/3 cases) | `validate_pair`, `decode_cmd`, `zip()` pairing, and the minimum duration | **Fixed.** See `research/hdr_measurement_basis/README.md`. The known-vector test also found that swscale expands 10-bit limited range inexactly (940 decodes as 0.99615). Decoding now bypasses the scaler. 38 tests; the self-test and a real PQ HEVC `measure_pair` pass. `verdict` stays null |
| F8 | Long scoring looks like stalled progress | **Observed.** `job_458aa0663c3e` took 1,430,434 ms in PL-B and 203,049 ms in PL-A. The cause is not established | Log-interval estimate (`window_timing_from_log.py`, below) | **Instrumented.** `WindowTiming` records per-stage wall/CPU time per window. The shadow is now opt-in and budgeted. Scoring has a 10-minute window ceiling, cooperative cancellation, and frame-pair checkpoints that close native sessions only on the scoring thread. The cause still needs a Perfetto trace |
| F9 | Calibration labels are policy-derived | **Confirmed** (`prepare_dataset_v2.py`, `build_corpus.py`) | The label code | **Addressed.** `label_basis.py` separates `policy_label`, `measurement_status`, `structural_status` and `human_visibility_label`. `run_study_v2.py` refuses to emit a threshold candidate unless every row has a human label. Tests: `test_label_basis.py` |

The review's two non-findings stand. Production uses `PRODUCTION_PHONE_MODEL=false`, and the earlier
fixes (phases, token guards, resolved plan, `FinalAcceptance`, bounded retry) were kept, not redone.

### What F8's logs actually show

These are timestamp intervals from `decisions.log`, from the line that starts a window's work to
its score line. They cover decode, pairing and scoring. This is an estimate, not a measured
duration.

| Batch | Probe windows | Probe time | Cert windows | Cert time | of which v1 shadow |
|---|---:|---:|---:|---:|---:|
| PL-A `batch_1790443993165` | 610 | 4,653.8 s | 71 | 419.3 s | 328.2 s |
| PL-B `batch_1790453977026` | 613 | 6,375.8 s | 74 | 902.3 s | 748.1 s |

PL-B took 2,644 s longer than PL-A. About 1,722 s of that is **probe** scoring, which never runs
v1. Certification added about 483 s, of which v1 accounts for about 420 s. So the shadow is not
the main cause of the slowdown.

On `job_458aa0663c3e`, single 36-frame 4K probe windows took 51–202 s in PL-B. The same windows took
about 20 s in PL-A. The logged `thermalStatus=0` / `powerSave=false` samples do not explain this,
and the review's caution applies: CPU frequency, scheduling or memory pressure need a trace.

Reproduce with:

```sh
python3 scripts/diagnostics/window_timing_from_log.py runs/batch_1790443993165/decisions.log runs/batch_1790453977026/decisions.log
```

## 2. Test and build evidence (this session, this container)

| Gate | Command | Result |
|---|---|---|
| F1 regression before the fix | `./gradlew :app:testDebugUnitTest --tests '…PartialScoringTest' --offline` | failed to compile: `collectWindows` and `CertificationGate` did not exist (exit 1) |
| F1 and F6 after the fix | same, plus `FloorRecoveryLearningTest` and the existing certification and learning suites | PartialScoring 8/8, FloorRecovery 5/5, CertificationFailure 9/9, LearningEvidencePolicy 5/5, QualityProbePolicy 26/26 |
| JVM unit tests (all) | `./gradlew :app:testDebugUnitTest --offline` | **570 tests, 79 suites, 0 failures, 0 skipped** (532 before this round) |
| Instrumentation compile | `./gradlew :app:compileDebugAndroidTestKotlin --offline` | compiles; **not run** (no device) |
| Debug APK | `./gradlew :app:assembleDebug --offline` | builds |
| HDR harness before the fix | `python3 test_hdr_pair_measure.py` with the new tests | ImportError (`DecodedFrame` did not exist), then 3 known-vector failures, then the swscale finding |
| Python suites (all, as CI runs them) | each `*/test_*.py` from its own directory | **13 suites, 186 tests, 0 failures, 0 skipped** (ffmpeg 6.1.1 installed here; the ffmpeg-dependent tests SKIP without it) |
| HDR self-test | `python3 hdr_pair_measure.py --self-test` | PASSED (identity ΔE 0.000000) |
| HDR end-to-end | `measure_pair` on synthesized PQ HEVC (identity, crf 40, truncated) | identity 0.0 with 12/12 frames matched in each of 3 windows; crf 40 measured; truncated rejected (`duration mismatch 12.000s vs 9.200s`) |
| Handoff reproduction | `tools/reproduce_hdr_harness_gaps.py` | now stops with `HarnessFailure` on case 3. The same cases with timestamps all fail closed (matrix differs; unknown primaries; incomplete coverage) |
| Audit replay | `tools/audit_b177.py <both zips>` | output identical to the handoff's `audit-summary.json` and `run-summary.json` |
| Summariser, PL-B | `parse_session_jsonl.py <190028 zip> --batch batch_1790453977026` | 24 real compressions, 819,538,365 bytes. Drift: **68 pairs** (legacy same-rung), `job_478c2fa19100` excluded with its reason. One audio contradiction. Replay: PARTIAL (4 stages missing, 0/208 updates linked, no run identity) |

**Device results: none.** No ADB, emulator or S23 was reachable from this environment.

## 3. Experiment manifest and runner

`research/encoder_experiments/pilot_manifest.json` freezes the following:
- the gate;
- the baseline runs' batch ids, build and learned-snapshot hashes;
- the ten pilot sources from the research note (four largest rejected, a 60 fps control, a near-bar case, two winners, the 4K timing case and the retry case);
- the HDR track, kept separate;
- four one-factor arms: A0 baseline, A1 long GOP ×2, A2 B-frames off, A3 shadow on (timing only; acceptance must equal A0);
- the blocked arms, each with its reason.

`run_experiment.py` provides `validate`, `plan`, `hash` (private full SHA-256 of local originals), `ingest` and `table`.

New opt-in app experiments for the pilot:
- **Longer keyframe interval**: twice the source's, capped at 10 s, used by probes and encode alike.
- **VMAF v1 shadow calibration.**

A configuration other than the baseline learns under its own key suffix, so it never reads or
writes the baseline's ratios. The B-frame setting in force on the first plan becomes the pinned
baseline, so existing profiles keep their keys; nothing is reset.

## 4. Before/after on matched sources (the only runs that exist)

The rows below are PL-A (retry off) and PL-B (safer-rung retry on). They use the same 226 sources,
the same build (`pr44-b177`, `2b4a2cf`) and different learned snapshots (`f6192479…` and
`d6193bd0…`). This is observational, not a controlled A/B. Totals are produced by
the initial experiment runner from the export. Strict pilot ingestion now requires the captured
gate, source fingerprints, run settings, and completed manifest sources, which these historical
logs do not carry; the older observational table is retained as historical analysis only.

All 226 sources:

| Arm | Accepted | Source bytes | Kept output bytes | Accepted saved bytes | Incremental | Measured rejections | Retried | Sum of job elapsed ms | Largest candidate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| PL-A | 23 | 50,946,351,461 | 5,615,231,923 | 818,443,848 | 0 | 170 | 0 | 7,201,127 | 771,754,918 |
| PL-B | 24 | 50,946,351,461 | 5,630,890,751 | 819,538,365 | +1,094,517 | 169 | 1 | 9,960,415 | 771,754,918 |

The ten pilot sources:

| Arm | Accepted | Source bytes | Accepted saved bytes | Incremental | Measured rejections | Sum of job elapsed ms |
|---|---:|---:|---:|---:|---:|---:|
| PL-A | 3 | 8,603,236,107 | 88,216,584 | 0 | 7 | 735,872 |
| PL-B | 4 | 8,603,236,107 | 89,311,101 | +1,094,517 | 6 | 2,271,909 |

Notes on these tables:
- The only changed outcome is `job_478c2fa19100` (SKIPPED_WOULD_DEGRADE → TRANSCODED_SMALLER, 1,094,517 bytes).
- Certification evidence that was not a measurement: 0 in both. The 6 parse failures and the 1 frames-limited ladder ended before certification.
- Audio in both: 23 bit-identical copies, and one accepted output whose copy is not shown (`job_c0f82bb62f94`).
- "Largest candidate" is the largest single candidate file. It is not a measured peak of temporary storage; the app does not record that yet.
- Accepted size reduction is not device free-space change. Originals and outputs coexist until replacement, which stayed off.

## 5. Savings conclusion

- **Accepted saving on the latest PL batch: 819,538,365 bytes (0.820 GB, 1.609 % of 50.946 GB).**
- **Neither 2, 3 nor 5 GB was reached.** The shortfalls are 1,180,461,635, 2,180,461,635 and 4,180,461,635 bytes.
- The tested frontier is the b177 configuration: VBR, source-matched GOP, two requested B-frames and ratios down to the ladder floor.
  - The safer-rung retry added one clip (+1,094,517 bytes).
  - No other operating point has been measured.
  - The pilot arms are defined and unrun.
- Not counted as savings:
  - The 795,405,607 bytes the HQ HDR outputs save are not PL.
  - The 13,955,991,541 `copyAvoidedBytes` are avoided copying, not compression.
  - The six parse-failure files (7.92 GB) are damaged sources, not savings.
- The byte opportunity is the 168 measured probe rejections (30.26 GB). Whether a supported operating point certifies more of them is a hypothesis until the pilot runs.
- The accepted outputs now include one whose audio was not shown to be a copy: `job_c0f82bb62f94`, 877,194 bytes. It is still counted, because the gate and the audio rule it passed are unchanged. It needs the file to explain.

## 6. What still needs the phone or a person

1. `scripts/device/run-device-checks.sh CLIP.mp4 150`. It runs `PipelineDeviceTest` and
   `ProductionEvidenceDeviceTest`:
   - windows on the two no-track-duration fixtures;
   - a real PL batch through `BatchCompressorViewModel`, checking the run identity, source fingerprint, attempt ledger, `audioRequested`, stage coverage, learned-update links and an unchanged source hash.
   A skip is not a pass.
2. The pilot (`run_experiment.py plan pilot_manifest.json`) on user 150:
   - device on charge, learned state kept, and the snapshot recorded per arm;
   - Everything export after each arm, then `ingest` and `table`.
3. A Perfetto trace (CPU frequency, idle, scheduling) and a screen recording on `job_458aa0663c3e`,
   through probe, encode, finalize, verify, certify and completion. The b169 recording shows an
   older build.
4. The `run_identity` encoder inventory. It shows whether `c2.qti.hevc.encoder` advertises CQ or a
   complexity range; the CQ and complexity arms stay blocked until it does, and until a CQ quality
   search exists.
5. `job_c0f82bb62f94`: compare its audio packets offline against the original.
6. HDR track:
   - the two originals and the HQ outputs;
   - PQ or HLG and dynamic metadata identified;
   - the fixed harness run on them;
   - planned blinded viewing.
   There is no PL promotion from size or ΔE alone.
7. Any threshold change needs blinded human labels under a declared protocol
   (`research/perceptual_calibration/scripts/label_basis.py`). Policy-label studies now refuse to
   recommend one.
