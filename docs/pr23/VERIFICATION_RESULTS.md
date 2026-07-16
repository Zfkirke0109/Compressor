# PR #23 — fresh-store device validation results

**Final validation commit:** `f1e2fbb` (installed as CI build `349aff7`).
**Capture:** `validation/captures/batch_20260715_193710` — 172/172, complete, reconciled,
parserExitCode 0, **integrity: authoritative**. Machine-readable:
`validation/captures/batch_20260715_193710/analysis/VERIFICATION_EVIDENCE.json` +
`.../analysis/PER_JOB_ANALYSIS.csv`.

## Environment
- Device: Samsung Galaxy S23 Ultra (SM-S918U1), Android 16, arm64-v8a, **Normal profile (user 0)**.
- APK: pkg `io.github.zfkirke0109.galaxycompressor`, vc26 / 1.6.1, DEBUGGABLE, from CI run
  **29466640763** (headSha `f1e2fbb`). Downloaded artifact SHA-256
  `d261e547c91a345e2d8dafd00c4e6fc5ccb5ff9a505ae8b76c1b83de2eff5ab5` (13,202,507 bytes packaged;
  installed 19:36:02). Native `lib/arm64-v8a/libcompressorvmaf.so` present; on-device VMAF self-test PASS.
- Learning store: **fresh** — `shared_prefs/smart_perceptual_profiles_v3.xml` deleted pre-run; no
  other app data / permissions / grants / settings cleared.
- Thermal: run recorded thermalStart/End per item; no thermal pause/failure observed; 0 failures.

## Counts (measured)
selected 172 = processed 172 = captured 172. **9 compressed, 627.8 MB saved** (median 40.7% /
weighted 21.9% of compressed sources), 84 SKIPPED_WOULD_DEGRADE, 61 ALREADY_HIGHLY_OPTIMIZED,
12 UNEXPECTED_REMUX, 6 REMUX_PREFERRED_BY_EVIDENCE, 0 failed. **9 improved / 0 regressed** vs the
old-build 084112 baseline.

## Latch fix (034b23f) — CONFIRMED working
| | warm 141019 | **fresh 193710** |
|---|---:|---:|
| latch-suppressed | 77 | **35** |
| probe ladders run | 88 | **118** (+30 files got their measured trial) |
| SKIPPED_WOULD_DEGRADE | 43 | **84** |

**Interpretation (honest):** the `everCompressed` exemption + fresh store gave 30 more files their
measured probe. Those files **correctly failed** the quality windows (SKIPPED rose 43→84), so they
are genuinely not compressible. Compressions held at 9 — **the latch was not hiding winnable
compressions in this dataset.** Net: the high remux/skip rate is **largely genuine** for this
content, confirmed by measured trials rather than inference. The fix is still correct and necessary
(it removes the class-level denial), it just did not unlock hidden wins here.

## Throughput fix (f1e2fbb) — CONFIRMED working, gate exact
- **Cooldown applied ONLY after the 17 full-encode items** (9 TRANSCODED_SMALLER + 8
  UNEXPECTED_REMUX); **zero cooldown after any of the 155 remux/optimized/skip items.** The
  `encodeAttempt != null` gate is exact.
- Total cooldown this run: **6.3 min** (vs the warm run's ~29 min inter-item, dominated by cooldown
  after no-encode items).
- Wall: old 42.6 → warm 95.4 → **fresh 81.3 min**; inter-item: 7.6 → 28.9 → **18.5 min**. (Wall
  comparison is confounded by store state — the fresh run probed 30 more files, adding work time;
  the *cooldown mechanism* is provably fixed regardless.)

## Confirmed improvements
1. Learning-latch remuxes correctly labeled (UNEXPECTED_REMUX low; REMUX_PREFERRED_BY_EVIDENCE used).
2. Latch no longer denies probes to proven-winnable classes.
3. Thermal cooldown no longer wasted after zero-heat operations.
4. 0 regressions vs old build; all safety invariants intact (0 decision-path contradictions).

## Uncertainties / data gaps
- Wall-time A/B vs the warm run is confounded by learning-store state (not a clean isolation of the
  throughput fix); the clean proof is the cooldown-gate telemetry above.
- Offline VMAF-v1 cross-validation of accepted outputs still not run (v0.6.1 on-device).
- Remux is still slow: **79 remux items = 40.3 min, max 267 s** for a 0-byte-savings file — the
  next big throughput target (see POST_VALIDATION_QUEUE.md).
- 64-file Secure Folder set intentionally deferred this run (user decision) — no matched Secure
  Folder comparison.
