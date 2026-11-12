# Device validation (b169 and b177 review rounds)

What JVM tests and the observational replay cannot establish, and how to establish it on the
S23 Ultra. Nothing here uploads a source video: files are matched locally by name hash, pushed
to the phone over ADB, and deleted from `/data/local/tmp` after use.

## 1. What is already verified off-device

| Claim | Evidence |
|---|---|
| An encode at 100 % is not a finished item; phases, stale callbacks, cancellation, retry handover | `ItemPipelineTest` (JVM, fake stages) |
| The encoder request, the estimate and the plan log are one value | `ResolvedEncodePlanTest` (row 110: 2,197,440 bps, audio copied at 128 kbps, estimate 247,616,391 B) |
| A discarded candidate never carries a success verdict or replacement permission | `FinalAcceptanceTest`; summariser flags the two b169 records |
| Too few frames is not a quality failure and teaches nothing | `CertificationFailureTest` (real `SmartPerceptualProfileEngine`) |
| The retry is offered only after a measured failure with a measured safer rung | `SaferRungRetryTest` |
| b169 totals, all 23 winners, all 29 size gates and every recorded encode request replay through the production Kotlin | `B169ObservationalReplayTest` (partial observational: rounded scores, no frame counts, no initial learned state) |
| Production scoring is v0.6.1 without the phone transform | `DiagnosticEventsTest.productionScoringIsPlainV061WithoutThePhoneTransform` |

## 2. What only the phone can show

1. A real Media3/MediaCodec export drives the phases as designed (no "100 %" before finalizing).
2. The native scorer certifies a source against itself. The separate frame-parity control checks
   that decoded frames are byte-identical even when raw VMAF is below 100.
3. The source is byte-identical after a discarded candidate.
4. A superseded export's callbacks cannot move a retried row.
5. On a batch: the row text, the indeterminate bar while finalizing/verifying, "Certifying pixels
   k of n windows", MiB labels, the provisional estimate turning into the size-gate number.
6. On a batch: `job_478c2fa19100` and `job_c92a4ca7e1be` recorded with `verdict=Rejected — …`,
   `verified=false`, `replacementSafe=false`, `structuralVerified=true`, `candidateBytes>0`.
7. With the safer-rung retry ON: `job_478c2fa19100` retried at 0.90 and what certification says
   (unknown until run); `job_c92a4ca7e1be` denied with `reason=no_measured_safer_rung`.
8. `learned_state_snapshot` at session start and `learned_state_update` records in order.

## 3. Commands

**Smoke (items 1-4), about 5 minutes:**

```sh
scripts/device/run-device-checks.sh /path/to/short_sdr_clip.mp4 150   # 150 = the Secure Folder user
```

The default command builds and installs the local debug and test APKs for that user. When testing
an Android CI build, install the matched app and test APKs from its `pl-device-controls-ci-<run>`
artifact, then run the checks against that installed pair:

```sh
scripts/device/run-device-checks.sh --installed /path/to/short_sdr_clip.mp4 0
```

The command runs the pipeline, production-evidence and native frame-packing controls. It writes
`short_sdr_clip.device-checks.txt` and reads the relevant log entries from the start of the run.
A short clip is required. Any skipped test makes the run incomplete.

**Find the 44 target files locally (names never leave the machine):**

```sh
python3 scripts/device/map_targets.py /path/to/originals --out targets-local.csv
```

It matches `nameHash` from `scripts/device/b169_targets.csv`; check `sizeMatches` before using a
match, since a name hash identifies a name, not content.

**Target batches (items 5-8).** Start `scripts/diagnostics/resilient_capture.sh` (or the Windows
capture task) first, then in the app, in the Secure Folder profile:

1. Keep learned profiles (do not reset). Note the build number in Diagnostics.
2. Smoke subset: `job_d127463b57d6` (row 110), `job_478c2fa19100`, `job_c92a4ca7e1be`,
   `job_458aa0663c3e`, `job_732f7ecfb699`, and one damaged file (`job_364d10590ff5`). Retry OFF.
   Screen-record row 110 through certification.
3. The same subset with Experiment: safer-rung retry ON. B-frames setting as in b169 (on).
4. Then all 44 targets, retry OFF, then retry ON, same order, device on charge, similar starting
   thermal state. Export "Everything" after each.

**Summarise:**

```sh
python3 scripts/diagnostics/parse_session_jsonl.py Compressor-…-Everything.zip --batch <batchId>
```

Look for `contradictory acceptance: 0` on the new build, `cert decisions`, `safer-rung retry`, and
compare the 23 winners' `savedBytes` against b169 per job (`compare_sessions.py`).

## 4. What a result means

- One run per arm is observational. Do not report a speed change from it; learning state, order,
  temperature and charge all move timing.
- A retry that certifies is one measured recovery of that file under this build, not evidence that
  0.90 is safe for its class. A retry that fails is also evidence, recorded per attempt.
- Nothing in these runs validates HDR, perceptual equivalence (see PERCEPTUALLY_LOSSLESS.md §4.3),
  or VMAF v1 as a gate.

## 5. b177 round

What changed off-device, and its evidence, is in `docs/B177_REVIEW_RESPONSE.md`. What only the
phone can show now:

1. **`ProductionEvidenceDeviceTest`** (run by `run-device-checks.sh` with the same clip):
   - certification windows on `zero_track_duration.mp4` and `fragmented_no_duration.mp4`
     (synthetic, in the test APK's assets; made by `scripts/device/make_missing_duration_fixture.py`)
     are spread through the clip, not collapsed on 0. The run log prints what the platform reported
     for each file's duration (`b177-fixture …`);
   - a real Perceptually Lossless batch through `BatchCompressorViewModel` writes `run_identity`, a
     64-hex `sourceFingerprint`, an `attemptLedger` whose length equals `attemptsStarted` with no
     attempt left open, `audioRequested`, `encode`/`verify` (and, when probed, `probe_rung`) stage
     events with `attemptIndex`, learned-state updates that name their job, and leaves the source's
     SHA-256 unchanged with replace-original off.
2. **The pilot**: `python3 research/encoder_experiments/run_experiment.py plan
   research/encoder_experiments/pilot_manifest.json`. Arms in order A0, A1, A2, A3, A0_REPEAT,
   each in Exhaustive mode with replace-original off; export after each; `ingest` and `table`.
   The step-by-step runbook, with thermal notes and what each measure means, is
   `docs/PREINSTALL_REVIEW_RESPONSE.md` §5.
3. **Timing**: a Perfetto trace with CPU frequency, idle states and scheduling, plus a screen
   recording, on `job_458aa0663c3e` in the first A0. New builds log `timing[...]` per window
   (queue waits, v0, flush, CAMBI, v1, decoder and process CPU).
4. **Encoder inventory**: the `run_identity` record lists every video encoder's advertised
   bitrate modes and complexity and quality ranges. CQ and complexity arms stay blocked until it
   shows support and a CQ quality search exists.
5. Settings to confirm before each arm (Settings, Experiments): safer-rung retry, B-frames, longer
   keyframe interval, VMAF v1 shadow calibration, and (on for every arm when no computer can hash
   the originals) Pilot: full source hash. The `run_identity` record repeats them.

## 6. The fallback after a rejected first encode (v1.6.287 round)

The Secure Folder run of v1.6.287 selected one marginal trial (`job_1e25c8805630`: 0.75 only
cleared the bar, 0.80 passed with the selection margin) and its 0.75 encode certified. The encode
that follows a rejected trial, at the safer measured ratio, has therefore never run on a device.
A real first-encode failure cannot be produced on demand, so `SaferRungFallbackDeviceTest` refuses
the first candidate with a test-only `CandidateVeto` and lets production do everything after it:

```sh
scripts/device/run-device-checks.sh --fallback /path/to/disposable_copy.mp4 150
scripts/device/run-device-checks.sh --installed --fallback /path/to/disposable_copy.mp4 150
```

It runs three real Perceptually Lossless batches over a copy of the clip in the app's cache and
writes `disposable_copy.fallback-checks.txt`:

1. the first encode is refused, the retry is decided by `SaferRungRetry`, and the second encode is
   verified, certified and accepted or kept as the original by the unchanged rules. The test does
   not decide that verdict: it checks that an acceptance rests on every planned window with at
   least 12 compared frames and mean 95.5 / p5 91 / min 84, and that either way the attempt ledger
   has two closed attempts and no refused candidate is left in the cache;
2. control: with the retry experiment off, a refusal keeps the original after one encode;
3. control: a second refusal keeps the original and never starts a third encode.

What to know before running it:

- **The clip decides whether anything is tested.** Items 1 and 3 need a clip whose ladder selects
  a marginal trial on that device with its current learned state. Otherwise they are skipped and
  the script exits 2 (incomplete), never 0. The run log prints the rungs and verdicts it saw
  (`b287-fallback …`).
- It budgets two full encodes of the clip per batch. Use a disposable copy; replace-original must
  be off and is asserted off; the copy's SHA-256 is compared before and after every batch.
- It turns the opt-in safer-rung retry switch on (off for the control) and restores it afterwards.
- Starting a batch clears the app's cached outputs, as it does in the app: run it when no results
  are waiting to be saved.
- The real second encode's result reaches the learned profile exactly as it would in the app. A
  refused attempt writes nothing there, and its records say what it was: `run_identity` carries
  `batchSettings.testCandidateVeto`, the attempt's `certify` stage has reason `cert_test_rejected`
  and its ledger outcome is `test_rejected`. `parse_session_jsonl.py` opens such a session's
  report with `!! TEST CAPTURE`. Do not pool these sessions with field captures.

The veto cannot make a candidate pass and the app never installs one: the app's view model is
built with the veto that refuses nothing, `CandidateVetoTest` fails the unit tests if the main
source set gains another, and the CI build reads the packaged app's DEX for a second
implementation (`scripts/ci/verify_no_test_veto.py`).

Not covered by this check: the retry after a candidate fails structural verification (the other
trigger of the same fallback), and the learning write a real measured failure makes. Both remain
covered by JVM tests only.
