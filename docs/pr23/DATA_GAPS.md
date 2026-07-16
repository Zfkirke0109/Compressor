# Data gaps (unresolved — not reconstructable from this capture)

Kept strictly separate from measured findings. None of these are guessed at.

1. **Per-rung window scores.** Only the LAST measured rung's `probeWindowScores` persist. When a
   ladder tries 0.70✗, 0.80✗, 0.90✓, only the 0.90 (or the last-failing) window scores are stored.
   Intermediate rung *verdicts* are inferable from ladder semantics; their *scores* are not.
   → Instrumentation opportunity: record scores for every measured rung.

2. **Encoder component / profile / level / bitrate mode.** Not in the structured record. Media3
   selected the encoder (expected `c2.qti.hevc.encoder`, CBR requested via ExperimentalEncoder
   Controls when supported), but the exact component/profile/level is not captured.
   → Blocks: confirming QTI-specific undershoot attribution beyond the delivered/requested ratio.

3. **Learning-engine updates.** `CompressorLearning` logcat tag is not captured in privacy mode,
   so latch counter transitions, `preferRemux`, `everCompressed`, and `nextTargetRatio` writes are
   inferred from `probeDetail`, not directly observed.
   → Instrumentation opportunity: emit a structured `learning` event per job.

4. **Thermal on skip paths.** `thermalStart/End` present on 105/172 jobs (main record path);
   absent on early-skip paths. Thermal association analysis is therefore partial.

5. **Undershoot sample size = 5.** Only 5 jobs recorded delivered/requested video bitrate this run
   (fewer discards because tolerance 0.15 accepts them). The full undershoot distribution (Phase 6F
   percentiles) is under-powered here; the richer sample is the OLD 084112 run (n=11, mean 0.887).
   → Statistical caveat: do not tune tolerance from n=5.

6. **Offline VMAF v1 cross-validation.** Not performed (needs official `vmaf_v1.0.16` model assets
   + pulling all pairs). On-device certification used `vmaf_v0.6.1`, so banding/chroma sensitivity
   of accepted outputs is unproven. See VMAF_CURRENT_IMPLEMENTATION_AUDIT.md.

7. **Fresh-store confound.** 83/83 latch suppressions inherited prior-run state. First-encounter
   probing behavior cannot be measured from a warmed-store rerun.

8. **64-file Secure Folder set.** Unrunnable on this build (Knox user-150 app not updatable via
   adb; DCIM/Secure empty). Its baseline (`batch_20260715_042633`, 6/64 compressions) is
   documented but no matched new-build comparison exists for that dataset.
