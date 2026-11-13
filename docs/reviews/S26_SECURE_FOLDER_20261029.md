# S26 Secure Folder capture review — October 29, 2026

PR #44; starting source head `1fcfcda88d7385e73aa94fa2bf0ef2b9542df83f`.

## Capture and scope

The user supplied `Compressor-v1.6.274-20261029-170947-Everything-LogsOnly.zip` (412,067 bytes). All 24 payload lengths and SHA-256 checksums match the manifest. The export is from **v1.6.274**, but all nine recorded batches are **v1.6.271 / pr44-b271 / compiled commit 98b759b**. Therefore this is evidence about the earlier compression implementation, not an on-device test of the v1.6.274 reset/cancellation fixes.

The records identify Samsung **SM-S948U**, Android 17/API 37, Android user 150/secondary profile, consistent with the user's Secure Folder context. Eight completed PL batches contain the same 13 source identities, sizes, durations, sampled content fingerprints and source-manifest digest. The ninth is a single-file High Quality batch. No original media or frame traces were supplied in this log-only export; scores and decisions are analyzed as recorded, not independently rescored or visually assessed.

## Observed comparisons

Times below are local Pacific time. Settings accumulate unless indicated. Each row is one observed run; learning state, temperature, execution order and process history were not controlled. These are not causal speed claims.

| Start | Added setting | Time | Real PL outputs | Bytes saved |
| --- | --- | ---: | ---: | ---: |
| 14:28:01 | Baseline: experiment toggles off; PL Exhaustive on | 691.973 s | 2/13 | 46,797,748 |
| 14:42:46 | Request 2 B-frames | 588.287 s | 2/13 | 46,797,748 |
| 15:01:28 | Safer-rung retry | 620.429 s | 2/13 | 46,797,748 |
| 15:12:39 | Longer GOP | 513.951 s | 2/13 | 46,762,017 |
| 15:23:06 | Shadow calibration | 492.780 s | 2/13 | 46,762,017 |
| 15:32:50 | Full frame trace | 586.498 s | 2/13 | 46,762,017 |
| 15:42:58 | Same recorded settings as preceding run | 781.147 s | 2/13 | 46,762,017 |
| 15:56:18 | Full source hashes | 829.627 s | 2/13 | 46,762,017 |

Every PL batch has 10 `SKIPPED_WOULD_DEGRADE`, two `TRANSCODED_SMALLER`, and one `ALREADY_HIGHLY_OPTIMIZED`. The same two files win throughout. The baseline saves 3.261% of the 1,434,966,283 input bytes; repeated runs must not be counted as independent videos or additive savings. The longer-GOP outputs are 35,731 bytes larger in these runs, without additional winners. B-frame requests produced identical accepted output sizes; this does not prove the requested feature was honored or caused a speed change.

All completed session summaries report zero failed/cancelled jobs. The exit report is `USER_REQUESTED`, description `[REMOVE TASK] remove task`, not an app crash. The first self-check passes source-versus-itself and stream-copy identity (101 byte-identical compared frames); the 2x-bitrate encoder control still falls below PL at the tested window (mean 94.40 / p5 90.35). The later self-check records only startup/window planning before task removal; it is not a completed control.

## Bottleneck and quality evidence

- The 30.348-second 2160×3840 AVC source takes 213.3–365.4 seconds per PL attempt. Its 0.97 probe clears the certification bar only marginally. The last run predicts 86,441,755 bytes against its 84,632,887-byte source and retains it without a full encode. That is a size prediction about the tested plan, not proof that every encoder/configuration is incapable of compression.
- Baseline probe windows total 574,662 ms, including 563,060 ms waiting for decoded pairs and only 10,736 ms in verdict-model reads/flushes. Last-run windows total 651,245 ms; queue waiting is 563,750 ms and verdict-model work 7,133 ms. Queue wait is consumer blocking time, not evidence of CPU idleness: decoder-thread CPU includes frame preparation, and overlaps across the two readers.
- The old frame reader performs per-sample direct-ByteBuffer chroma reads, allocates a full packed frame, and then allocates/transposes another full frame for display rotation. That is an expensive, isolated path to benchmark. The existing capture cannot separate codec execution from pixel preparation, so the update adds preparation durations and actual decoder names to every window's timing record.
- All ten rejected sources have measured low-window results for HEVC and the bounded alternate AVC ladder. There are no unmeasured ladders or silent structural rejections in this capture. Several means remain below 95.5 even at the highest useful ratios; lowering the quality bar would relabel the outcome rather than fix it.
- Full hashing consumed **3,142 ms** across all 26 before/after hashes in the last PL run, and each before/after content hash agrees. It does not explain the whole slowdown. That run also reports two 25-second post-item cooldowns and warm accepted-output thermal snapshots. The preceding trace run has two 10-second cooldowns. No thermal protections are weakened.
- The single 16:10:15 HQ run compresses that 4K source to 74,235,710 bytes, saving 10,397,177 bytes in 9,431 ms of item time / 14,638 ms batch time. It is explicitly **lossy**, with no PL pixel certificate; it is not an extra PL winner.

## Changes

1. **Lossless frame preparation:** a bounds-checked native quarter-turn packer reads cropped/strided YUV planes directly into display-space I420, with an 8×8 transpose tile and one output allocation. Unrotated/180-degree and non-direct-buffer paths stay portable; JNI showed no consistent benefit on unrotated controls. There is no rescaling, color conversion, precision change or frame removal. Decoder buffers keep their position/limit. The JNI code uses ordinary array elements rather than pinning the Java heap through a transpose.
2. **More search within the existing floor:** after a successful downward bisection in an Exhaustive, full-size-eligible primary ladder, and only with no lower measured failure and budget remaining, try the existing 0.60 hard floor once. A failing/marginal/unavailable floor trial retains the prior measured winner. A passing floor keeps the prior winner as a safer-rung fallback. Fast/short/alternate ladders do not gain this trial. The captured 1080×1080 winner stopped at 0.65 with ample margin; no new gain is claimed before a real run measures 0.60.
3. **Faithful diagnostics:** wrap nested record maps/lists as Android JSON rather than stringifying learning observations. The durable ledger already has valid nested objects: it replays 75 applied measurements, 125 duplicates, zero errors/uncertain effects, and 33 resulting profiles. Existing learning is preserved; no automatic reset is needed.
4. **Analysis correctness:** use the actual `totalElapsedMs` field with legacy `elapsedMs` fallback. Legacy stringified observations now produce `REQUIRES_REVIEW` instead of crashing or being treated as usable labels.

The 95.5/91/84 gates, minimum 12 pairs, full timeline/audio/color checks, and final smaller-file requirements remain unchanged. The new search cannot accept a file based on a prediction or partial pixel evidence.

## Synthetic measurement and verification boundary

The host harness keeps the previous ByteBuffer-copy/second-rotation implementation as its baseline, alternates measurement order, uses equal warmup/sample counts, and compares bytes before timing. Initial native per-pixel/32-tile and bulk-only prototypes were inconsistent or slower and were replaced. An 8×8 staged transpose improved the repeat host controls: examples from two runs are 4.902→3.359 / 4.753→3.365 ms for 1080×1920 and 19.806→13.473 / 23.627→16.179 ms for 2160×3840 at 90 degrees. Landscape 4K gains were smaller and variable (23.708→18.794 / 20.607→19.344 ms). These are **host function timings**, not S26/S23 batch-speed measurements.

Native tests cover 72 bit-exact geometry/rotation/stride cases, short final rows and invalid-layout guards under address/undefined-behavior sanitizers. The JNI harness compares all 16 geometry/rotation cases with the old path. JVM tests cover cropped/padded/direct/heap/read-only buffers, overlapping NV21 U/V views, unchanged buffer state, truncated planes and overflowing geometry. Instrumentation adds Android JNI-versus-portable pixel controls and the real Android JSON serialization contract; compilation is not execution on a phone.

Validation results and matched signed APK links are recorded in the PR description after final-head CI. The source patch's functional rollback is the prior signed v1.6.274 source/APK, but Android may reject an in-place version downgrade; do not uninstall the Secure Folder app or discard its state to perform a rollback. A forward-version rollback build can preserve that state if needed.

For everyday runs, optional full-frame traces, shadow research data and full-source hash controls provide evidence rather than a higher PL acceptance rate. Keep them off when measuring normal throughput; preserve one captured state and repeat the same source subset without resetting learning. Check new `prep`/decoder timing, total wall time excluding documented cooldown, accepted bytes, and the two identity controls before attributing any speed or savings change to this update.
