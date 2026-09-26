# Device validation for the b169 review round

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
2. The native scorer scores identical frames 100 on this build.
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

It builds and installs the debug and test APKs for that user, runs `PipelineDeviceTest`, and
writes `short_sdr_clip.device-checks.txt`. Without a clip every test is skipped, not passed.

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
