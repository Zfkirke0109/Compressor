# Post-validation investigation queue (do NOT act until the current run completes)

Queued while the fresh-store validation (batch_20260715_193710, build f1e2fbb) is running.
No code, device, or run interaction until complete results are available.

## Item A — Deferred remux (investigate, do not implement)
Evaluate stopping processing early once a file is high-confidence "remux-only, no meaningful
savings," and offering an end-of-batch action ("Remux flagged files now" / "Leave originals
unchanged").

Before proposing, document exactly what the current remux accomplishes — benefits AND drawbacks:
compatibility, container repair, metadata handling, stream copying, verification, storage use,
processing time, temp space, risk of metadata/timestamp change.

A deferred remux must still emit a COMPLETE learning + diagnostics event:
- technical video details; codecs + container
- size, duration, bitrate, resolution, frame rate, HDR state, audio details
- probe scores + thresholds; predicted savings; confidence + learning bucket
- every reason it was classified remux-only
- whether remux was completed / deferred / skipped / later requested
- anonymized identity (no private paths/filenames)

Hard rule: deferred remuxes must NOT count as compression successes, failures, or verified
remuxes in the learning engine.

## Item B — Adaptive parallel processing (investigate, do not implement)
Determine whether controlled concurrency (>1 video at a time) is safe given device capability +
current resources. Assess impact on: encoder quality / delivered bitrate; VMAF + verification
consistency; thermal throttling; hardware encoder-session limits; memory / storage I/O / battery /
temp space; MediaCodec reliability; processing order + deterministic learning updates;
cancellation / recovery / output integrity.

Consider adaptive concurrency: 1 job default, a 2nd only when capability + temperature + memory +
battery + storage are safe. Never let parallel work weaken quality thresholds, skip verification,
alter learning order, or create overlapping output writes.

## Deliverable (AFTER verification finishes)
For BOTH items: whether worthwhile, measurable time saved, risks, and the controlled tests
required before implementation. Combine with the already-queued REMUX_ACCELERATION_INVESTIGATION.md
(surface-original-instead-of-copy fix) — these three are related throughput workstreams.

---

## Item C — Post-validation preservation & handoff (do FIRST, before any new dev)
Immediately after the run finishes, before starting new work:
1. Preserve + analyze the complete validation results (capture batch_20260715_193710).
2. Save every note, conclusion, measurement, command, test result, artifact identity, checksum,
   limitation, unresolved question — nothing important left only in terminal/temp/chat/uncommitted.
3. Update the repo + PR #23 so any other agent (Claude/Codex/ChatGPT/Gemini) can continue via a
   normal `git pull`, no conversation history needed.
4. Commit + push docs and machine-readable evidence to `fix/downloaded-video-pl-diagnostics`.

Create/update repository docs containing:
- exact branch + commit SHAs; validation APK source commit + CI run; APK package/version/
  signature/size/checksum; device model/Android version/profile/thermal conditions
- selected/processed/captured/compressed/skipped/failed/remuxed/deferred counts; compression
  ratios, bytes saved, processing time, inter-item handoff timing, thermal behavior
- latch-recovery results from 034b23f; throughput results from f1e2fbb; comparison vs the
  authoritative 172-file capture (batch_20260715_141019)
- every confirmed improvement / regression / uncertainty / data gap
- logger schemas + anonymized learning records
- exact reproduction/build/install/reset/capture/parse/test commands
- current repo status + any uncommitted/generated files
- prioritized next steps; a concise agent handoff

Suggested files: VERIFICATION_RESULTS.md, AGENT_HANDOFF.md, DECISION_LOG.md,
NEXT_PHASE_INTELLIGENT_ADVISOR_PLAN.md, NEXT_VALIDATION_PLAN.md updates, anonymized CSV/JSON.
Update the PR #23 description or add a detailed comment linking these + final validation commit,
CI status, artifact identity, results, next-phase status.

PRIVACY: never commit private filenames/paths, frames, personal metadata, storage locations,
device identifiers, signing secrets, tokens, passwords, or unrelated phone info. Anonymized
identities + sanitized technical features only. If interruption/limits loom, stop at a clean
checkpoint, commit safe partial work as clearly-marked WIP, push, and update AGENT_HANDOFF.md
with the exact continuation point.

## Item D — Post-validation research: free OSS on-device intelligence layer (design, do not implement)
Design a free, open-source, privacy-preserving intelligence layer to make Compressor's decisions
more accurate/adaptive/faster across Samsung AND non-Samsung Android. Evaluate Samsung ONE vs
portable runtimes (LiteRT/TFLite, ONNX Runtime, NCNN, MNN, ExecuTorch) on: OSS license, Android/
ARM64 support, cross-vendor portability (Samsung/Qualcomm/MediaTek/...), CPU/GPU/DSP/NPU accel,
APK size, runtime/memory, battery/thermal, inference speed, maintainability. **No Samsung-only
dependency.**

Must COMPLEMENT (not replace) SmartPerceptualProfileEngine — heuristic engine + all quality
protections remain the mandatory fallback and final safety authority.

Features: res/duration/fps/bitrate/codec/container/bit-depth/HDR; sampled-frame motion/texture/
grain/complexity/scene-change; encoder caps + observed behavior; memory/storage/battery/thermal;
prior locally-verified compression/remux/failure/probe/quality outcomes.
Predicts: meaningful-compression likelihood; safest codec+encoder; target bitrate/ratio; expected
output size + processing time; verification-failure risk; remux/no-savings probability; probe/
encode/defer/remux/leave decision.

On-device only by default — never upload filenames/paths/frames/metadata/learning records;
anonymized bounded local feature storage.

Conservative path: (1) is evidence sufficient? (2) replaceable `CompressionAdvisor` interface;
(3) heuristic advisor stays default fallback; (4) prototype a tiny quantized model on metadata +
limited frame samples; (5) SHADOW/advisory mode, no real decision change; (6) compare vs verified
results; (7) influence decisions only after it measurably beats the current algorithm; (8)
preserve VMAF thresholds/verification/atomic safety/cancellation/learning correctness/determinism/
profile guarantees; (9) REJECT if complexity/heat/size/runtime cost exceeds measured benefit.

Also investigate (safe, no unsafe parallel encodes, no weakened checks): metadata prefetch, async
feature extraction, caching, early remux prediction, deferred remux, hardware-aware scheduling.
Controlled parallel compression: research-only until separate evidence proves it safe per-device
(encoder-session limits, MediaCodec reliability, bitrate delivery, VMAF consistency, I/O, memory,
thermals, battery, deterministic learning order, cancellation, recovery, output integrity). One
encode remains default.

Deliver + commit: recommended runtime + license analysis; architecture + fallback design; feature
schema + prediction targets; training + on-device personalization strategy; Samsung vs non-Samsung
support; APK/memory/battery/thermal/speed estimates; shadow-mode implementation plan; benchmark +
regression-test plan; measurable acceptance criteria; rejection criteria; phased GitHub roadmap;
updated AGENT_HANDOFF.md. No speculative AI for branding — proceed only when measured evidence shows
it improves compression success, reduces unnecessary processing, strengthens learning, or shortens
batch time without reducing quality/privacy/reliability/compatibility.

## Ordering after the run completes
C (preserve + handoff + commit/push) → A/B/remux-copy throughput report → D (intelligence-layer
design). C must be committed/pushed before starting A/B/D.
