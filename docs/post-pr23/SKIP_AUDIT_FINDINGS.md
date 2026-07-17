# Offline skip-audit — are SKIPPED_WOULD_DEGRADE verdicts genuine? (2026-07-16)

## Why this ran
Owner delegated prioritization ("best algorithm for PL, compress everything even minimally,
stay actually PL, run faster"). Phase 2 (deferred remux, both variants) was PARKED: it compresses
nothing and verifies nothing. The binding constraint on coverage is the probe verdicts, so the
skip population was audited offline before any code change.

## Method (evidence in validation/captures/analysis_skip_audit_20260716/, local-only)
- Classified all 69 SKIPPED_WOULD_DEGRADE jobs of capture batch_20260716_071913 by their
  last-rung window scores; cross-run agreement vs batch_20260715_193710: 54/58.
- Matched jobs to device files via the salted nameHash (SHA-1, app salt) over 289 device videos.
- Pulled representatives; replicated the app probe offline with ffmpeg/libvmaf: identical 1.2 s
  windows at 20/50/80%, encode at ratio x source bitrate (libx265 = quality upper bound of the
  device HEVC path), pooled mean/p5/min per window vs the app bars (95.5/91/84, >=12 frames);
  scored PTS-synced vs decode-order-paired, under vmaf_v0.6.1 AND the NEG model.

## Population verdict (69 skips)
| class | n | GB | verdict |
|---|---|---|---|
| solid_fail | 62 | 11.55 | GENUINE — offline x265-medium also fails the bars (e.g. 75366_hd.mp4: mean 93.6-94.4, p5 86.5-90.8). Honest skips; leave them. |
| collapse (artifact) | 3 | 0.08 | Device scores impossible as real quality; see below. |
| near_miss | 1 | 0.26 | GENUINE by <1 VMAF pt (2039096.mp4 p5 90.1-90.6 vs 91 even via x265). Threshold recalibration would win exactly this ONE file → not a lever. |
| latched | 3 | 1.2 | Probe-skip latch carrying prior measured rejections (by design; re-probes due). |

## The two proven false-verdict mechanisms
1. **Pristine CFR clips scored as garbage.** jellyfish_gradient (25.1 Mb/s) and jellyfish_slow
   (16.8 Mb/s), 1080p29.97 8-bit CFR: device probe recorded ~22.7/16.9/12.9. Offline, the SAME
   windows at the SAME target bitrates score **97-99.8 and PASS every window, both models, both
   pairing modes, at 0.95 AND 0.80**. These are false skips with real savings available (>=20%).
   Root cause is on-device (Media3 Transformer probe clip or scorer), NOT the metric and NOT
   decode-order pairing per se (CFR pairing is provably safe: idx == PTS scores identical).
2. **VFR screen recordings collapse the scorer.** Screen_Recording_...GitHub.mp4 (480x1030,
   ~38 fps avg VFR): device recorded 17.6/0.0/0.0. Offline reproduction: sub-frame temporal
   misalignment (retimed frames) collapses VMAF to p5=0 on screen content while the SAME pixels
   pair-aligned score ~95-97 mean / 83-92 p5. Honest verdict for THIS file is still FAIL at every
   rung (p5 ~86-88 vs 91) — but the 0-scores are artifact, and they poison per-class learning
   (false measured rejections feed the latch for the whole bpp class).

## Metric findings (kills two planned ideas)
- **NEG model is uniformly stricter** (~1-2 pts lower) than vmaf_v0.6.1 on this corpus → a v1/NEG
  gate upgrade would REDUCE coverage, not expand it. Not the lever for the owner's goal.
- **Threshold recalibration**: exactly 1 near-miss in the whole library. Not the lever either.
- The lever is **fixing the on-device probe pipeline artifacts** (alignment/retiming), which
  converts false skips into honest measurements — and prevents latch poisoning.

## Next steps (evidence-gated, in order)
1. Instrument VmafPairScorer/probe with per-frame PTS-pair telemetry (ref pts vs dist pts per
   compared frame, plus frame counts) — one diagnostic APK run on the jellyfish pair + screen
   recording pinpoints the device-side mechanism (Transformer retiming vs scorer pairing vs
   decoder). New fields on job records only; parser must not need new event types.
2. Fix per evidence (candidates: timestamp-based pairing with half-frame-interval tolerance in
   VmafPairScorer; fail-closed on pairing-gap violations instead of scoring; VFR handling in the
   probe export). Never weaken windowsPass bars.
3. Re-run the 176-file batch: expected honest coverage gain = jellyfish-class files compress
   (real savings), screen-recording class gets honest scores (this one still skips, honestly),
   latch learning de-poisoned. The 62 genuine skips must remain skips.

## Files (local evidence dir, not committed)
audit_runner.py, population_analysis.py, audit_results.json (pre-passthrough),
audit_results_passthrough.json (jellyfish run), skip_population.json, matches.json,
device_videos.txt — validation/captures/analysis_skip_audit_20260716/

## ADDENDUM 2026-07-16 evening — device mechanism FOUND (PR #25 diagnostic run)

Capture batch_20260716_185345 (3-file batch on the PR #25 telemetry build):
jellyfish_gradient probed with `probePairDiag = ref=35,dist=34,extra=1/0,skewMs=33.2/33.2/33.2`
— a CONSTANT one-frame-interval skew (33.2 ms at 29.97 fps) across the whole window with a
one-frame count mismatch. The Transformer probe clip and the reference window reader disagree
by exactly one frame about the window start; decode-order pairing then scores ref[i] against
the re-encode of ref[i+1] for every pair. The probe measured inter-frame motion, not encode
quality. Same mechanism explains the VFR screen recording's 17.6/0/0 (variable gaps up to
350 ms → near-zero scores) and implicates CERTIFICATION (same scorer): the screen recording's
encode certed at 92.7/84.0 and was discarded — plausibly a false cert failure, meaning real
savings may be recoverable wherever UNEXPECTED_REMUX followed a healthy encode.

Fix (same branch, PR #25): `PtsAligner` — timestamp-based pairing in VmafPairScorer with an
adaptive half-frame-interval tolerance (floor 4 ms), LEADING-boundary-only budgeted drops
(max 8), fail-closed on internal misalignment (lost-frame evidence) and on unalignable
windows. Typed outcomes: MisalignmentRejected never certifies (not even structurally at the
default ratio); Unavailable keeps legacy semantics; unmeasurable probes never feed the latch.
Bars unchanged.

DEVICE-VALIDATED 2026-07-16 (capture batch_20260716_191148, build c087071, fresh store):
- jellyfish_gradient: skew 33.2 ms -> 0.2-0.8 ms (drop=0/1 leading repair per window);
  probes 98.9/97.0/91.0 -> pixel-proven 0.65 (bisection), cert 99.3/98.7/94.0 ->
  PL Verified, 11.8 MB saved (37.6%). Was a false skip.
- jellyfish_slow: pixel-proven 0.65, cert 97.9/97.4/92.7 -> PL Verified, 7.9 MB saved
  (37.8%). Was a false skip.
- screen recording (VFR): skew 0.3 ms; honest 95.4/87.1/82.7 -> honest skip (p5 87.1 < 91),
  matching the offline aligned prediction. No fabricated zeros; latch now learns from truth.
Remaining gate before merge: 176-file no-regression rerun (62 genuine skips must stay skips).

## ADDENDUM 2026-07-17 — 176-file rerun PASSED + bug also caused FALSE COMPRESSIONS

Capture batch_20260716_233032 (full 176-file corpus, final build 41af64e, fresh store; a first
attempt batch_20260716_194104 was interrupted at 24/176 and is marked PARTIAL). Evidence +
scripts in validation/captures/analysis_rerun_20260717/.

Headline: total "savings" 627.8 MB (baseline batch_20260715_193710) -> 218.4 MB — because the
fix REMOVED A FALSE COMPRESSION, not a real one. The entire delta is one file:

**133776.mp4 (1080p30, 1.77 GB)** — baseline (buggy pairing) probed 97.1/91.6, certed 97.2/93.4
-> "Perceptually Lossless Verified", 466.8 MB "saved". Fix build probed 51.7/27.6 (skew 11.3 ms)
-> SKIPPED. OFFLINE GROUND TRUTH (pulled the file, PTS-synced libx265 at 0.85 AND 0.95x, clean
30fps CFR): 20%/50% windows score mean 68/p5 26 and mean 71/p5 36 — genuine severe degradation
far below the 91 bar; only the 80% window passes. Two independent methods (fix build + offline)
agree it degrades; the lone dissenter is the old decode-order pairing. **The baseline's 466.8 MB
was the app degrading a video and labeling it lossless — a truth-rule violation the fix closes.**

=> The pairing bug cut BOTH ways: false SKIPS (jellyfish) AND false COMPRESSIONS (133776).
This PR is a correctness/honesty fix, not merely coverage.

Corrected honest comparison (common 172 set): baseline reported 627.8 MB (incl. 466.8 MB
proven-degraded) -> honest 161.0 MB; fix build 192.6 MB, every one pixel-verified (+31.6 MB
genuine, plus jellyfish/sintel copies -> 218.4 MB total). ZERO genuine compressions lost (every
real baseline win reproduced byte-for-byte). 62 genuine skips stayed skips (4 relabeled to
honest retained/remux, 0 saved). 3 jellyfish false-skips -> verified. Pairing health: 0
misalignment fail-closed on healthy content, 72 clean leading-drop repairs. Known residual:
aligner reduces skew to sub-frame (11.3 ms on 133776 vs ~0.5 ms on jellyfish), doesn't change
any verdict here. Gate PASSED; recommend merge (owner decision).
