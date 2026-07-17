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
