# External audit triage — verified against source at main@d57b936 (2026-07-17)

Two external code reviews of `main` were supplied: an **Opus 4.8 (Notion)** multi-pass audit
(Conditional Go) and an **OpenRouter free-model** review (NO-GO). Neither was taken on faith —
every finding was adversarially verified against the actual source (15 skeptical subagents, each
told to REFUTE by default, plus independent hand-reads of the two crown-jewel claims).

## Headline
- **The Opus 4.8 review is high-signal.** Every one of its findings is real (with a couple of
  severity corrections). It correctly identified the app's one genuine data-loss path.
- **The OpenRouter NO-GO verdict is not supported by the code.** All four of its P0 "Critical"
  findings are REFUTED or MOOT: a `renameTo` data-loss bug that does not exist (that path keeps a
  `.bak` + rolls back), a `File(uri.path!!)` crash in **dead** `MainActivity`, a "use-after-free"
  that is actually a minor array leak reachable only on native OOM, and a fabricated honesty gap
  ("failed cert reported as Verified") — the cert-fail branch deletes the output and returns
  `SKIPPED_WOULD_DEGRADE`. Its NPE, thermal-window-ignored, and atomic-move claims also misread
  the code. The app's honesty core is intact.

## Verdict table (15 findings)
| id | claim source | verdict | real sev | disposition |
|---|---|---|---|---|
| SAFE-001 replace-original `rwt` truncate + `statSize<=0`=success + false "protected" msg | Opus | **CONFIRMED** | High | **Fixed — PR #26** |
| QUAL-002 doomed >1080p probe encodes | Opus | **CONFIRMED** | Med | **Fixed — PR #27** (also a speed win) |
| QUAL-001 >1080p / no-VMAF labeled "Verified" on structural evidence only | Opus | CONFIRMED | Med | backlog (label honesty; own PR) |
| PERF-001 no foreground service / wakelock in batch path | Opus | CONFIRMED | High | backlog (reliability) |
| free-space (StatFs) precheck absent | Opus | CONFIRMED | Med | backlog |
| `recommendedBatchParallelism` dead config | Opus | CONFIRMED | Low | backlog (delete) |
| dead `MainActivity`/`CompressorViewModel` (~1747 LOC) | Opus | CONFIRMED | Low | backlog (remove) |
| vmaf_jni: dist array not released on `ref==NULL` error branch | Opus (SAFE-003) | PARTIAL | Low | backlog (1-line) — refutes OpenRouter's "use-after-free P0" |
| `clearBatchCache()` may delete unsaved prior-batch outputs | Opus (IO-001) | PARTIAL | Low | backlog (scope to current items) |
| MediaMuxer/Extractor release on empty-track early return | OpenRouter | PARTIAL | Low | backlog (route through cleanup) |
| two-step `renameTo` data loss in `Mp4MetadataRemuxer` | OpenRouter P0 | **REFUTED** | None | `.bak` + rollback, atomic same-dir, never touches source |
| `File(uri.path!!)` SAF crash in `MainActivity` | OpenRouter P0 | **MOOT** | None | dead code; launcher is `BatchMainActivity` |
| failed cert reported as "Verified" (honesty) | OpenRouter P0 | **REFUTED** | None | cert-fail deletes output → `SKIPPED_WOULD_DEGRADE`; no success path |
| NPE on null `pixelProvenVerifierFloor` | OpenRouter | **REFUTED** | None | nullable Int?, `?:`-guarded at the sole call site |
| `waitForThermalWindow` result ignored | OpenRouter | **REFUTED** | None | it is a blocking suspend gate, not a returned flag |

## Fixed this pass
- **PR #26** `fix/replace-original-crash-safety` — SAFE-001: recovery-copy-before-truncate, strict
  `ReplacementSizeCheck` (unit-tested), honest messaging, no false "protected".
- **PR #27** `fix/probe-geometry-gate` — QUAL-002: `isPixelScoreableGeometry` gate (unit-tested),
  no acceptance change, removes ~4 doomed 4K probe encodes per source.

## Confirmed backlog (own PRs, prioritized)
1. PERF-001 foreground service + wake lock (High, reliability).
2. QUAL-001 honest structural-only label (Med, honesty-of-label).
3. IO-001 scope `clearBatchCache` to current-batch items (Low).
4. free-space precheck (Low). vmaf_jni dist release (Low). muxer empty-track cleanup (Low).
   Delete dead `MainActivity`/`CompressorViewModel` + `recommendedBatchParallelism` (Low).

No fix weakens a quality gate or the honesty invariants; the OpenRouter NO-GO is retracted as
evidence-unsupported.
