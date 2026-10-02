# Focused S23 Ultra validation — do not start with another 244-file batch

Status: designed, not run. Use the exact APK/commit in `docs/reviews/PL_B184_VALIDATION.md`; do not use a moving branch APK. Retain the original APK/settings and exports for reproducibility. Keep app data and current learned profiles. Install as an update, not an uninstall/reinstall.

## Stage 0: non-destructive controls and evidence integrity

Settings: Perceptually Lossless, original resolution/fps, Auto/HEVC as in b184, **Exhaustive ON**, **replace originals OFF**, full source hash ON, full frame traces ON, encoded candidate retention ON for this small run only. v1 shadow OFF initially. Set B-frames=2, longer GOP ON and safer-rung retry ON to reproduce the b184 operating point. The candidate retention budget is 512 MiB per run; split exports/runs if it is reached. Turn retention/traces off after the experiment. The ZIP may contain the actual encoded video when retention is enabled.

No learning reset: new evidence is source/config/window-bound. A repeat can test measurement stability but does not become another independent training observation. Keep each run's learned snapshot/ledger; experimental arms must keep distinct configuration identities. In Exhaustive mode the old skip latch does not suppress measurement. Compare actual requested configurations, not just toggle labels.

First run the synthetic device controls, then the app self-check (source/self, stream copy, high-quality hardware encode) on a short SDR source. Commands from a matching checkout with Android SDK and adb:

```bash
python3 scripts/device/make_scoring_controls.py --out /tmp/compressor-controls
adb push /tmp/compressor-controls /data/local/tmp/compressor-controls
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument --user 150 -w -r \
  -e class compress.joshattic.us.ScoringParityDeviceTest \
  -e scoringControls /data/local/tmp/compressor-controls \
  io.github.zfkirke0109.galaxycompressor.test/androidx.test.runner.AndroidJUnitRunner
```

Use `--user 150` only if that is still the active Compressor profile (the exports used 150); determine it with `adb shell pm list users`. APK filenames/signatures must match the actual build outputs. A test skipped for missing fixture argument is **not** a pass.

Controls cover identity, remux, lossless encoded control, 7-second PTS shift, internal frame deletion, one-frame offset, VFR jitter, context first frame, unchanged pixels with changed color tags, changed chroma and a synthetic corrupt MP4. The app self-check provides the separate very-high-quality hardware encode. It need not be smaller; it tests the instrument/encoder ceiling on that source only. Do not feed the damaged fixture into destructive replacement.

Export **Everything** immediately. Check the manifest and replay each complete frame trace. Native-raster v0 hashes/scores should match the app-library offline build before testing the newer library. The v0 model JSON SHA is `5950d61fa1f861bd45d8149d80539ed9f3376cfc2495b8f0fa8e9f57cb131ee3`.

```bash
python3 scripts/diagnostics/scoring_parity.py verify-archive diagnostics.zip
python3 scripts/diagnostics/scoring_parity.py replay trace.jsonl.gz
python3 scripts/diagnostics/scoring_parity.py compare \
  --trace trace.jsonl.gz --reference original.mp4 --candidate retained-candidate.mp4 \
  --vmaf-bin /path/to/pinned/vmaf --model /path/to/vmaf_v0.6.1.json \
  --model-sha256 5950d61fa1f861bd45d8149d80539ed9f3376cfc2495b8f0fa8e9f57cb131ee3 \
  --lib-version 3.0.0 --tool-version 17a67b2 --out parity-result
```

The original is needed locally: per-frame hashes cannot reconstruct pixels. The exact candidate and its SHA come from the optional retained candidates. Keep Android decoder/FFmpeg decoder differences as findings; do not relax tolerance or force a pass. A color-tag mismatch can coexist with identical YUV hashes and must still fail preservation. No threshold recalibration before these controls and real-candidate parity are explained.

The complete video timeline and stronger audio identity checks are new. Confirm low-bitrate copied AAC, B-frame reorder, nonzero origin and VFR pass when genuinely preserved. Confirm deletion, config changes, relative A/V shift, unknown evidence and corruption fail closed without updating visual-quality learning. The synthetic scorer test does not yet exercise all container/audio/provider paths; those are separate device gates.

## Stage 1: 15-file source subset

`phone_subset.json` binds every selection to the original full SHA-256 and recorded characteristics. Most are short; two longer sources provide a very near gate case and low-bpp control. Do not silently trim them and call them the same experiment: a trimmed clip has a new source identity and encoder context. Start with the three shortest winners, one borderline failure and one low-bpp failure; stop on an evidence/structural regression before the rest.

The manifest includes AVC and HEVC, 24/30/60/95 fps, 270p through portrait 4K, several .06–.12 bpp cases, clear winners, near-gate failures, moderate failures, low-bpp failures, and a non-monotonic case. Content labels are not present in the logs: **inspect and annotate motion/static/noise/gradient/text before testing**. Add a short synthetic gradient/noise/static control if the chosen real sources do not cover a category. Do not infer scene content from bpp or filename hashes.

Outcomes to falsify:

- A winner whose complete structural evidence fails may expose a real preservation defect or a new false rejection; inspect the exact reason. Do not waive it to preserve the old count.
- A stable candidate must reproduce its per-frame aggregation and gate decision offline. A mismatch blocks calibration.
- Repeats with identical source/config/window/model identity should not increment independent learning observations.
- A measured rejection with a different requested configuration is not evidence that learning alone changed perception.
- Source before/after hashes must match; corrupt inputs must retain original and finish through a parser/unavailable outcome, not quality-negative learning.

## Stage 2: encoder efficiency, only after controls

Use 6 frontier sources from Stage 1, matched windows and content; run baseline twice with cooldown to estimate nondeterminism. Randomize one-factor arm order between clips. Record actual encoder name, supported geometry/fps, requested/honored formats, codec profile, GOP/I cadence, B-frames, requested and observed rate, candidate bytes, score distribution, elapsed time, memory and thermal state.

1. VBR baseline: b184 B=2, long GOP on. Repeat at beginning/end.
2. VBR B=0, all other requests fixed.
3. VBR ordinary source GOP, B=2, all else fixed.
4. CQ/quality **conditional and not implemented as a production toggle**: only if the distinct CQ encoder advertises and actually configures the exact raster/fps. A 512×512-only CQ component is not an HD experiment. Never downscale to make it fit and claim a PL gain. Implement a separate explicitly experimental factory/quality sweep only after this capability gate, with normal certification intact.
5. Complexity/profile controls: use documented supported keys only. Capability presence is not proof the request was honored. No undocumented vendor key guesses. No hardware AV1 experiment unless actual capability changes; this capture showed software AV1 only.

For any supported CQ arm, sweep several quality values then refine around matched final bytes (target ±1%, report actual bytes; interpolate only for plots). Hold source, windows, context, structural requirements and scorer fixed. Repeat finalists at least three times across thermally comparable runs. Compare Pareto points using final bytes, not nominal requested bitrate. A smaller candidate that passes unchanged final certification is a legitimate recovery; a higher score at larger bytes is not. If CQ is unsupported, report that result and concentrate on VBR/GOP/B-frame efficiency.

Nine b184 non-monotonic comparisons are listed in the data pack. Do not use unqualified binary search. A future refinement should retain measured brackets, repeat a material inversion, and fall back to a bounded grid; the smallest measured passing final encode wins. This pass leaves production search unchanged.

## Stage 3: windows and human validation

Run `source_window_features.py original.mp4 --out features.json` to obtain deterministic source-only proposals. Compare fixed windows, hard proposals and whole-file scoring on a small, source-disjoint set. Check missed scene cuts, tails, gradients and chroma changes manually. Measure false acceptance/rejection against whole-file audits and human labels. The selector is research-only; no app acceptance depends on it.

For blinded ABX, prepare equal-duration A/B/X clips in a private operator mapping; X independently copies A or B with a preregistered balanced randomization schedule. Give participants only opaque trial IDs. Use the same local player, rendering mode, video scaling, seek start, audio endpoint and volume for every item; disable titles/metadata/size overlays and network adaptation. Keep files in separate opaque folders with the same displayed filename. Do not let load latency, differing durations or audio offsets reveal the answer.

Measure displayed picture height and viewing distance on the S23 Ultra. Fix display mode/resolution, brightness in measured units when possible, ambient lighting, refresh mode and orientation. Record any tone-mapping or enhancement setting. Specify normal viewing and whether replay is allowed before collecting responses; log trial response, confidence and response time. Separate video and audio investigations when appropriate. Start with winners, clear failures, near failures, CQ/VBR pairs and v0/v1 disagreements.

Preregister an equivalence criterion, not a null-hypothesis non-rejection. Example design margin for discussion: one-sided 95% upper confidence bound on correct identification below 0.60. This margin is a research design choice, **not** an established definition of PL. At 30/60 correct the exact bound is 0.6126, so equivalence is not established; at 60/120 it is 0.5786 under independent-trial assumptions. `calibration_evidence.py abx-bound` calculates that bound. Repeated viewers/videos are clustered; use a hierarchical or cluster-aware analysis and account for multiplicity/optional stopping. Do not treat 120 repeats from one viewer/video as 120 independent population observations.

Keep calibration/validation/holdout split by reviewed video family, not frames. The current batch's policy labels are never human truth. Lock a model-specific proposed policy on calibration data, assess source-disjoint validation, then evaluate holdout once. Capture uncertainty and coverage, not only an optimized pass rate. A new full batch follows only after controls, stability and focused accepted-byte improvements justify it.
