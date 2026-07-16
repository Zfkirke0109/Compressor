# Patch & test report (Phases 14–17)

## Findings classification
| # | Finding | Category | Action |
|---|---|---|---|
| 1 | Probe-skip latch denies probes to 26 files in classes that verified compressions (coarse-bucket over-grouping) | **4 — probable implementation defect (strong evidence)** | **PATCHED** |
| 2 | Cross-run latch persistence (83/83 inherited) confounds same-set reruns | expected behavior + methodology | documented; fresh-store validation planned |
| 3 | UNEXPECTED_REMUX 33→2 via label fix; 63 REMUX_PREFERRED_BY_EVIDENCE | 7 — expected correct behavior | verified working |
| 4 | Window thresholds too strict | REFUTED (38/39 clear-fail) | no change |
| 5 | VMAF v0.6.1 outdated vs v1.0.16 | 6 — instrumentation/correctness opportunity | proposed, not done (would reduce, not increase, compression) |
| 6 | Per-rung scores / encoder name / learning events not captured | 6 — instrumentation opportunity | proposed |
| 0 | 0 decision-path contradictions; all safety invariants intact | 7 — expected correct behavior | audited, pass |

## Patch (commit 034b23f)
`SmartPerceptualProfileEngine.kt`:
- `LearnedEncodeProfile.everCompressed: Boolean = false` — persisted field (encode/decode),
  defaults false on legacy stored profiles, negative/tamper-safe.
- Set `everCompressed = true` in `recordVerifiedSuccess` (the only "verified compression" signal).
- `shouldSkipProbes` returns false first when `everCompressed` — a known-winnable class keeps
  probing every file regardless of latch counters.

Safety: only ever ADDS probes. Never touches thresholds, floors, ladder shape, OutputVerifier, or
the VMAF model. Certification remains the sole acceptance authority. Truth invariants re-audited
against the diff: all intact.

## Tests
- New: `SmartPerceptualProfileEngineTest.knownWinnableClassIsNeverLatchSuppressed` — arms latch
  (2 rejections) → suppressed; verified success → `everCompressed` true, not suppressed; further
  rejections cannot re-arm; store round-trip + legacy default-false asserted.
- Command: `./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL** (exit 0), full suite green
  (SmartPerceptualProfileEngine, QualityProbePolicy, BatchTerminalClassifier, BatchQualitySafety,
  PixelProvenFloor, OutputVerificationFormatter suites all pass).
- Python parser suite: `python -m unittest test_parse_batch_logcat` → 12/12 (unchanged this pass).

## Not changed (deliberately)
No threshold/floor/ladder/model change — the evidence supports none, and the directive forbids
tuning quality gates to raise the success count. The VMAF v1 model swap is a separate controlled
workstream (VMAF_V1_INTEGRATION_PLAN.md).
