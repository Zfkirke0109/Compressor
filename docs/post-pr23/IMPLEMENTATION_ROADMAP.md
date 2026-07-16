# Post-PR#23 implementation roadmap

Baseline: PR #23 head `18d4b94` (last validated source commit `f1e2fbb`; `18d4b94` is docs-only).
PR #23 is FROZEN — no new work lands on it. Do not merge any PR without explicit owner approval.
Secure Folder validation is DEFERRED this cycle.

## Phase order, branches, PRs

| Phase | Branch | Deliverable | Gate to next |
|---|---|---|---|
| 0 | (current) | baseline verification, this roadmap, status JSON, stale-doc fix | baseline green |
| 1 | `perf/remux-keep-original-fast-path` | typed MaterializedOutput + audited OriginalReuseDecision + fast path + 11+ tests + benchmark | tests green, PR open, results doc |
| 2 | `feat/deferred-remux` | learning-neutral deferred states + persistent queue + end-of-batch actions + tests | Phase 1 accepted |
| 3 | `research/adaptive-batch-concurrency` | disabled-by-default AdaptiveBatchScheduler + measured gates + qualification design; NO enablement without evidence (≥15% wall win, zero integrity cost) | framework + negative/positive benchmark committed |
| 4 | `feat/compression-advisor-shadow` | Stage 4A: versioned advisor interface + feature schema + heuristic wrapper + shadow logging; runtime evaluation doc; NO ML runtime dependency yet | shadow records accumulating; eval tooling |

## Hard invariants (all phases)
PL/HQ/DataSaver behavior, VMAF thresholds/windows, OutputVerifier authority, probe certification,
HDR remux-only, ratio floors, privacy modes, verification, atomic writes, cancellation, learning
correctness, deterministic accounting, original-file protection. Remux/deferred/retained ≠
compression success. No fake savings. Local-only intelligence; no analytics/telemetry/network
requirement; SmartPerceptualProfileEngine stays the mandatory fallback and final authority.

## Evidence base (validated 2026-07-15, see docs/pr23/)
172-file fresh-store run: 9 compressed / 627.8 MB / 0 regressions; dataset mostly genuinely
incompressible; remux path = 40.3 min (max 267 s for 0-byte savings) → Phase 1 is the largest
proven win; cooldown gate exact → Phase 3 starts from a clean serial baseline.
