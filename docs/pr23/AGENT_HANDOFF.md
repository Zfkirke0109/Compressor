# Agent handoff — Compressor PR #23 (Perceptually Lossless)

Any agent can continue from a normal `git pull` of `fix/downloaded-video-pl-diagnostics`. No
conversation history required. Status: **PR #23 validated on-device; ready for review/merge; four
throughput/intelligence workstreams queued.**

## Repo state
- Branch: `fix/downloaded-video-pl-diagnostics`; HEAD **`18d4b94`** (docs commit; last validated
  SOURCE commit is `f1e2fbb`). PR **#23** OPEN, MERGEABLE, base `main` (merge-base `16948e1` =
  merged PR #22). PR #23 is FROZEN — post-validation work continues on separate branches (see
  `docs/post-pr23/IMPLEMENTATION_ROADMAP.md`).
- Commit stack on top of `16948e1`:
  - `f10d968` bounded adaptive search (bpp-classed ladders, retreat rungs, bisection, same-codec
    probe-gating, noise-threshold savings, floor recovery)
  - `03b8a2f` measured undershoot tolerance 0.15, decaying probe-skip latch, score+thermal telemetry
  - `75d2624` label learning-latched remuxes as REMUX_PREFERRED_BY_EVIDENCE (not UNEXPECTED_REMUX)
  - `034b23f` known-winnable latch exemption (`everCompressed`)
  - **`f1e2fbb` inter-item handoff latency fix (cooldown gated on `encodeAttempt != null`)**
- Working tree: analysis artifacts under `validation/` are gitignored (evidence lives on disk); the
  committed docs are under `docs/pr23/`. Untracked helper scripts exist in capture dirs (not needed
  to continue). No uncommitted source changes.

## What was done (measured, see docs/pr23/VERIFICATION_RESULTS.md)
- Forensic evaluation of PR #23 on authoritative capture `batch_20260715_141019` (build 4f4909e):
  0 contradictions; the high remux rate is mostly correct; ONE proven defect (coarse-bucket latch
  denying probes to winnable classes) → fixed in `034b23f`.
- Throughput root cause: post-item thermal cooldown applied after zero-heat remux items (~29 min on
  172 files) → fixed in `f1e2fbb`.
- **Fresh-store on-device validation** (`batch_20260715_193710`, build 349aff7): both fixes work;
  latch 77→35 suppressions, probe ladders 88→118, those extra probes correctly failed (SKIPPED
  43→84) ⇒ dataset is genuinely mostly-incompressible; cooldown now only after the 17 encode items
  (6.3 min); 9 compressed / 627.8 MB / 0 regressions.

## VMAF identity (implementation fact)
Real libvmaf API 3.0.0, model **`vmaf_v0.6.1`** (v0, non-phone), pooling window mean+p5+min,
thresholds 95.5/91/84. External: latest lib v3.2.0, current gen VMAF v1 (`vmaf_v1.0.16`, banding/
chroma, stricter). v1 is a correctness upgrade, NOT a compression-increase lever. Do not swap the
model without the integration plan (docs/pr23 or analysis dir VMAF_V1_INTEGRATION_PLAN.md).

## Exact commands
Build/test: `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Parser tests: `cd scripts/diagnostics && python -m unittest test_parse_batch_logcat` (12/12)
Get CI APK: `gh run download <runId> --dir <tmp>` (PR-build workflow "Build PR Debug APK")
Install: resolve port via `adb mdns services | grep R5CW6160`, then
  `adb connect 192.168.1.67:<port>` ; `adb -s 192.168.1.67:<port> install -r <apk>`
VMAF self-test: `adb -s <ep> shell am broadcast -a io.github.zfkirke0109.galaxycompressor.VMAF_SELFTEST -n io.github.zfkirke0109.galaxycompressor/compress.joshattic.us.quality.VmafSelfTestReceiver`
Reset ONLY learning store (fresh run): `adb -s <ep> shell run-as io.github.zfkirke0109.galaxycompressor rm -f shared_prefs/smart_perceptual_profiles_v3.xml` then `am force-stop`
Arm capture: `pwsh -NoProfile -File scripts\diagnostics\Start-CompressorBatchCapture.ps1 -Serial 192.168.1.67:<port> -Environment Normal -SessionDetectionTimeoutSeconds 0 -ClearLogcat`
Parse a capture: the PS1 auto-parses to jobs.jsonl / summary.csv / aggregate.json / manifest.json.
Forensic extract (authoritative 141019): `python validation/captures/batch_20260715_141019/pr23_forensic.py <dir>`

## Next steps (priority order) — see validation/captures/batch_20260715_141019/analysis_pr23_20260715_forensic/POST_VALIDATION_QUEUE.md
1. **Merge PR #23** (or request review) — validated, 0 regressions.
2. **Remux acceleration** (biggest measured win, ~40 min/run): surface original instead of copying
   for keep-original remuxes when privacy=PRESERVE_ALL + container OK. Design in
   REMUX_ACCELERATION_INVESTIGATION.md. Guards + 11 tests listed.
3. **Deferred remux** (Item A) + **adaptive parallel processing** (Item B) — investigations only.
4. **Free OSS on-device intelligence layer** (Item D) — shadow-mode `CompressionAdvisor`, heuristic
   engine stays mandatory fallback; runtime selection (LiteRT/ONNX/NCNN/MNN/ExecuTorch, no Samsung-
   only dep); reject if cost > measured benefit.
5. GitHub Releases update-check page (older backlog item).

## Privacy
All committed evidence uses anonymized salted jobIds + technical features only. No filenames,
paths, URIs, frames, personal metadata, device identifiers, or secrets committed. `validation/` is
gitignored; only `docs/pr23/` is committed.

## Continuation point
If resuming mid-task: the validation is DONE and committed. Start at step 1 (merge) or step 2
(remux acceleration). Nothing is left in an uncommitted WIP state.
