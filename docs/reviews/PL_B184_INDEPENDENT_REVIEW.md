# PR #44: independent October 1 PL evidence and engineering review

Review date: 2026-10-02. This is an engineering pass, not a claim of new on-device compression yield. No S23 Ultra or original source videos were available in the execution environment. All device experiments below are unrun. The report, the two diagnostic ZIPs, current source/history, tests and upstream documentation were reviewed independently. Thresholds remain **mean 95.5 / p5 91 / minimum 84**, at least 12 scored pairs per window; v1 remains shadow-only.

## Diagnosis

The 24/244 result is real for the measured operating point. It is neither evidence that 220 files could never be compressed transparently nor evidence that reducing the threshold is appropriate. Most measured failures are several points short, so a tiny threshold adjustment cannot explain the population. The best next opportunities are exact scorer parity and controlled encoder operating-point experiments on the source-bpp frontier. Several latent fail-open and evidence-integrity defects warranted fixes even though they do not explain those 185 measured probe failures.

The largest counter-finding is that **all 185 measured probe failures requested HEVC output**, including 184 AVC sources. A claim that the batch primarily measured H.264-to-H.264 encoder limits is contradicted. AVC source dominance does not identify the limiting encoder or distinguish pre-existing damage, noise, generational loss, rate control, model mismatch and search limitations.

The initial fetched head was `aa1b31abf1d60ff9582bad86ffffe06bb14578c2`; the tested build was `f5152fc6eea743509b86901ce2fbbd9d1c402311`. The source change between them was the decoded-byte self-check verdict fix, not a new candidate search or codec configuration. Concurrent commits subsequently landed on PR #44 during this pass; they were fetched, reviewed and reconciled rather than overwritten. The validation record identifies the final tested tree.

## Input identity and reconstructed batch

The PL session is `batch_1790877162297`. Its session JSONL is byte-identical in both archives: SHA-256 `fd2b69b78f960a4c3bdfecc7878e2325c6b4f3af7a11509da5c85cb50b123f2e`. The later archive adds a separate High Quality session, `batch_1790888104190` (18 selected, 15 compressions, 2 failures). It is excluded from every table below. Other historical/partial sessions are not pooled into this batch.

| Outcome | Files | Interpretation |
|---|---:|---|
| Certified smaller | 24 | Accepted by the then-current sampled v0 policy |
| Measured probe failure | 185 | Actual pixel scores below at least one gate |
| Measured final-certification failure | 2 | Full outputs failed the pixel gate |
| Insufficient evidence | 1 | 2 fps source; not a visual-quality label |
| HDR unsupported | 2 | Retain original |
| Geometry unsupported | 13 | Beyond scoring cap |
| Codec search excluded | 7 | AV1/VP9 policy exclusions; not proof pixels are inherently unscoreable |
| Predicted not smaller | 2 | Inference about size, not quality |
| Damaged sources | 6 | Zero-filled input evidence; not bitrate failures |
| Structural failure | 1 | Color-verification failure |
| Measured not smaller | 1 | Validity and size are separate |
| **Total** | **244** | **0 hard batch failures** |

The wire terminals reconcile to 24 `TRANSCODED_SMALLER`, 187 `SKIPPED_WOULD_DEGRADE`, 25 `ALREADY_HIGHLY_OPTIMIZED`, and 8 `UNEXPECTED_REMUX`. All 187 degradation terminals have measured evidence: 185 probe failures plus 2 certification failures. That proves failure of the operational gate, not human detectability.

Accepted reduction is **735,435,391 bytes** (0.735 GB; 9.836% of files). Accepted inputs total 6,466,834,711 bytes and outputs 5,731,399,320 bytes. All selected inputs total 58,308,305,212 bytes. Full before/after source SHA-256 matches for all 244 sources; there are 244 distinct full source hashes. Savings are accepted byte reduction, not necessarily freed device storage while copies/backups remain.

`audit_pl_capture.py` emits source, attempt, window and event tables with raw provenance, exact margins, rejection classifications and non-monotonic comparisons. Missing fields remain null. No frame-level sequence can be reconstructed from the old aggregate-only ZIPs. Bitrate/bpp estimates use recorded total bitrate minus recorded audio bitrate, not a fresh packet-byte measurement.

| Estimated source video bpp | All files | Winners | Measured probe failures |
|---|---:|---:|---:|
| [0, .03) | 41 | 0 | 26 |
| [.03, .04) | 53 | 0 | 49 |
| [.04, .05) | 38 | 0 | 37 |
| [.05, .06) | 29 | 0 | 28 |
| [.06, .08) | 36 | 5 | 26 |
| [.08, .12) | 38 | 16 | 17 |
| [.12, .20) | 2 | 0 | 1 |
| [.20, infinity) | 7 | 3 | 1 |

Use bpp to stratify experiments and eventually prioritize searches. Do not turn these bins into a production impossibility rule. Codec, frame rate, raster, source history and content confound bpp; low-bpp samples and Exhaustive exploration must remain represented.

For each of the 185 probe failures, the highest measured rung is compared with every gate. The nearest limiting constraint is mean for 153, p5 for 32, minimum for 0. Median worst margin is **−4.6169406958 points**. Within 0.25 / 0.5 / 1 / 2 / 2.5 / 5 points of all gates: **3 / 10 / 22 / 46 / 57 / 100** files. These are operational score margins, not expected new winners or human JNDs.

There are 9 adjacent-rung, same-window comparisons with a >0.5-point deterioration in at least one gate as requested bitrate rises. One mean drop exceeds 1 point; a p5 drop reaches about 1.67. Do not replace the existing bounded adaptive ladder with monotonic binary search. Compare actual bytes and encoder names, repeat suspicious operating points, and retain uncertainty.

## Consequential PDF claims

| Claim/hypothesis | Classification | Independent finding/action |
|---|---|---|
| Both archives contain the same PL batch | CONFIRMED | Exact session hash and decisions agree; HQ excluded |
| 24 winners, 187 degradation skips, ~735 MB | CONFIRMED | Exact reconciliation above |
| About 185 skips have measured probe evidence | CONFIRMED | Remaining 2 are measured final-certification failures, not unmeasured skips |
| Most failures are mean-bound | CONFIRMED | 153/185 nearest failures; median deficit 4.62 |
| Rejections overwhelmingly start as AVC | CONFIRMED | 184 AVC + 1 HEVC |
| These chiefly establish same-codec H.264 limits | CONTRADICTED | 185/185 rejected ladders select HEVC |
| Strongest observed frontier is .08–.12 bpp | PARTIALLY CONFIRMED | 16/38 winners; 17 probe failures, not 18; .12–.20 bin omitted in report |
| Current learned state explains most rejection | CONTRADICTED | Exhaustive measured the failures; no learned skip explains them |
| Learning should be reset to increase yield | UNSUPPORTED | No evidence supporting a blanket reset; retain v4 aggregates |
| Identity self-check should demand VMAF near 100 | OUTDATED BY CURRENT PR HEAD | Initial head already used decoded-byte identity; identical input can score below 100 |
| Three windows establish whole-file transparency | UNSUPPORTED | Sampling cannot establish unseen perceptual quality; structural whole-file checks are separate |
| 95.5/91/84 are calibrated for this phone/use condition | UNSUPPORTED | Historical policy reference, no supplied S23 blinded equivalence data |
| CQ may improve frontier rate-distortion | REQUIRES EXPERIMENT | Separate Qualcomm CQ component advertised; geometry was absent from old inventory |
| CQ advertisement proves usable HD/4K quality mode | UNSUPPORTED | Old research notes suggest a 512×512 limit; measure capability per geometry first |
| Switching to v1 with the v0 bar is valid | CONTRADICTED | Different measurement instruments require separate policy calibration |
| PTS fixes eliminated every possible alignment defect | PARTIALLY CONFIRMED | Common origin/offset problems fixed; close VFR and repeated PTS needed new guards |
| Partial-scoring fail-open was completely closed | PARTIALLY CONFIRMED | Measured-failing prefixes were protected; unproven/partial passing evidence still had paths to acceptance |
| Audio payload identity proves the complete listening presentation | PARTIALLY CONFIRMED | Also need decoder configuration, flags and timing relative to video |
| BT.601 NTSC/PAL tags are safely interchangeable | CONTRADICTED | Abstraction collapse is not decoded-color proof; exception removed |
| LOW_MEMORY exit proves a native leak | UNSUPPORTED | Exit evidence gives a cause category, not allocation ownership; new boundary snapshots help investigate |
| Giant coroutine/Dex method was fixed | CONFIRMED | Split remains; new CI gate parses generated APK DEX, with a 10,000-code-unit regression budget |
| Another full 244-file run is the best next experiment | CONTRADICTED | Controls and a small falsifiable subset come first |

## Engineering changes and coupled-pipeline review

| Area | Finding, implemented action, remaining limit |
|---|---|
| Candidate planning/source class | Kept conservative SDR supported-geometry scope. Exhaustive avoids bpp/learned skip suppression. AV1/VP9 exclusion is a search limitation, not observed degradation. Recommendations must not imply HDR certification. |
| Bitrate/floors/size | Source video bitrate is inferred when track bitrate is absent; container overhead contaminates it. Measured probe overshoot has priority over historical profile average. Full final bytes still gate acceptance. No heuristic floor or size prediction may train visual failure. |
| Search | Existing coarse/adaptive/budget logic retained. B184 has real non-monotonic evidence. Offline audit emits offending windows/rungs; no new binary-search assumption or bpp skip. |
| Encoder shape | Probe/final share GOP, B-frame and VBR shape identities; actual encoder names are now retained with rung evidence. Requested versus honored configuration remains distinct. Inventory adds geometry/alignment/fps support and fixes MIME-specific profile ID collisions that falsely advertised AVC ten-bit support. |
| Window placement | Unreachable/duplicate later windows no longer silently reduce coverage to the easy opening. Keyframe lead-in is bounded. Complete planned coverage is required for a passing ladder/certification. Early measured failure may stop a rung because it cannot pass all windows. |
| Frame pairing | Source/probe origins, real motion context and strict internal-drop rejection remain. Repeated/reversed scored timestamps fail; tolerance can narrow below 4 ms for closely spaced VFR frames and never widens. |
| Native scoring | Non-finite/missing/count-mismatched results are unavailable, never passing. Native handles close in finally even on flush errors; partial JNI byte-array acquisition is released. YUV images/buffers close in finally. Shadow results cannot alter v0 verdict. |
| Certification | Every basis now requires complete measured passing windows. No default-ratio structural substitute, passing-prefix substitute, or misalignment-as-quality label. `pixelCertified` requires actual evidence. |
| Whole output | Complete compressed-sample presentation timelines, sorted from decode order, compare exact sample counts and normalized PTS (1 ms bound). Unknown/ambiguous/budget-exceeded evidence retains the original. This catches omissions outside sampled windows; it is not a whole-file pixel comparison. |
| Audio | PL requires packet payload/configuration/flags/count and A/V-relative timing identity. Codec configuration and payload SHA-256, counts, bytes, skew and first mismatch are exported. Missing evidence cannot pass through a high-bitrate inference. Multiple audio tracks fail closed. Re-encoded audio is not certified transparent. |
| Color/HDR | BT.601 cross-variant exception removed; known range/transfer/HDR/bit-depth differences remain guarded. v0 luma scoring does not prove chroma or rendered RGB equivalence. Existing untagged-to-Media3-SDR default behavior still needs explicit rendered-color controls before merge. No HDR support was broadened. |
| Learning | New source/config/window/model/epoch/encoder-bound ledger wraps existing v4 profiles. Durable reservation prevents duplicate application across restarts; outcome/build relabeling does not create independent samples. Visual, size and non-training pipeline kinds remain separate. Legacy aggregates retained, not relabeled human evidence. |
| Ledger crash behavior | RESERVED before profile mutation, APPLIED after. RESERVED without APPLIED is uncertain, not silently replayed. Malformed/full journal pauses training; decisions still run. This is at-most-once training, not an atomic database transaction. Offline replay exposes uncertainty. |
| Diagnostics | Optional compressed per-frame JSONL: exact PTS, context flags, hashes, full-precision v0/v1/CAMBI where available, luma PSNR diagnostic; unobservable GOP/scene fields remain null. Final/probe/self-check trace hooks; optional bounded encoded candidate retention; no raw decoded frames. ZIP v2 checksums hash actual streamed payloads. |
| Media3/damaged sources | Existing parser instrumentation and early zero-fill rejection address the six damaged inputs. Kept inactivity semantics; no global timeout increase. Extractor/decoder/encoder queue-depth and every muxed PTS are not all exposed by the current Media3 path; do not invent them in exports. |
| Runtime/memory | Java/native/PSS boundary snapshots added. These are not true allocation peaks. Existing cancellation, thread/queue shutdown and scorer serialization retained; v1 remains optional/budgeted. Sustained memory/thermal profiling still needs the phone. |
| Original replacement | New regression work closes partial recovery, equal-length content corruption, and recovery filename reuse. Read-back hashes, unique durable recovery files and conservative Shizuku/gallery handling protect recovery bytes. See final validation record for tested commit. |
| Historical compatibility | Preserve wire enum names and old parser support for historical captures. Their presence is not dead code. Remove inference-based certification/audio/color exceptions where they weaken proof; do not perform a broad cosmetic rewrite of the large ViewModel during this safety pass. |

## Measurement/calibration architecture

The threshold provenance is a historical in-repo comment pointing to a July 14 external pixel-quality report, not a reproduced S23 Ultra ABX study. The older offline `measure_quality.py` has different gates/pooling, so it is not exact Android parity. Retain current thresholds while establishing the instrument; do not fit them to the 244-file batch.

A future policy key should include model SHA, feature configuration, preprocessing/raster, viewing condition, fps class, bit depth and calibration epoch. Source-native scoring is reproducible but is not automatically a phone-viewing model. Measure actual displayed video height, distance, scaling, brightness and refresh behavior before choosing a viewing condition.

Netflix's current v1 documentation distinguishes 1080p/3H, phone/5H and 4K models, including a 4K model with a different score range. It recommends 10-bit SDR evaluation for banding sensitivity and specifies HFR temporal processing. The app's 5H shadow, eight-bit decoded input expanded for the native wrapper, and current context handling require their own parity/calibration; a newer model is not an automatic replacement. Pin both the app's v0 library revision and current libvmaf in comparisons.

Use one calibrated primary metric plus targeted artifact checks. CAMBI is useful for banding diagnosis; PSNR exposes pixel/range/decoder mismatches but is not a transparency standard. SSIM/MS-SSIM add structural diagnostics, not independent perceptual ground truth. SSIMULACRA2 and Butteraugli are image-oriented; use offline still-frame investigations for chroma/texture disagreements, not arbitrary video pass thresholds. Temporal deletion/retiming requires explicit structural checks even when spatial scores remain high. HDR requires a separate color/transfer and subjective basis.

`scoring_parity.py` replays Android per-frame evidence and streams precisely selected decoded frames to pinned Netflix libvmaf without writing raw decoded video. It reports per-plane hash differences, PTS differences, model/library identity, per-frame/aggregate/verdict differences and color-tag differences. Synthetic offline controls are explicitly labeled offline, never Android parity. CI runs actual FFmpeg + both pinned Netflix revisions; Android controls still need the S23 Ultra.

`calibration_evidence.py` provides ledger replay, epoch quarantine, reviewed-video-family partitioning, and exact one-sided binomial bounds. `source_window_features.py` generates source-only research proposals for motion, detail, smooth-gradient risk and a representative region. Features are coarse proxies, not calibrated artifact detectors. Compare fixed windows, these proposals and whole-file audits on a small independent set before integrating a selector. A missing tail/scene/transition requires coverage review, not a favorable candidate score.

## Learning reset decision

**NO blanket reset.** The supplied completed run was Exhaustive, and 185 rejected ladders actually measured frames; clearing profiles cannot turn those scores into passes. The 227 ordered profile updates can be linked to 56 captured profiles, but mutable aggregate counts are not independent-source counts. The actual live SharedPreferences may have changed after export; only the exported snapshot was inspected.

Retain legacy v4 search/overshoot information. Do not use pre-fix historical labels as calibration truth. New ledger observations carry the current measurement epoch and separate evidence kinds; same-source/config/window repeats are not independent training records. The ledger uses a stable sampled source fingerprint for on-device dedup and records full hashes when enabled; scientific corpus identity requires full SHA-256 plus reviewed family grouping. Crash-uncertain reservations or an incompatible future model belong in quarantine, not a global reset. No old success/failure aggregate is retroactively certified as human evidence.

## Merge readiness

**Not ready to merge today. No merge was performed.**

**BLOCKING BEFORE MERGE:** run device scorer/structure/audio controls and exact Android-to-offline parity on real retained candidates; investigate any decoded-color/default-tag mismatch; verify the new complete timeline and audio config checks against B-frame/VFR/low-bitrate AAC outputs; verify recovery/read-back behavior on the actual storage provider, including interrupted replacement and Shizuku if enabled. Any known false-certification or source-loss path must remain closed. Final CI must be green on the reviewed head.

**SHOULD COMPLETE BEFORE MERGE:** refresh stale PR description claims (partial fallback, BT.601 exception, old counts); run the focused subset and confirm exported traces reproduce every verdict; verify ledger restart/dedup behavior and memory/thermal bounds on the phone; add rendered-color fixtures for missing/conflicting tags and 601 variants; document schema compatibility and recovery-file discovery. Sampled-policy claims must remain visibly distinguished from whole-file/human transparency.

**CAN FOLLOW AFTER MERGE:** CQ/complexity experiments if supported at the relevant geometry; broader source populations, source-aware window integration, new model families, full subjective population calibration, HDR/ten-bit/unsupported codec expansion, and validated bpp search prioritization. None justifies weaker acceptance now.

## Primary references checked

- Netflix VMAF: https://github.com/Netflix/vmaf ; revisions `17a67b238ce0539bdeafdc95961abac64fa16ea8` (app v0 library, 3.0.0) and `8e7a1ac4eb835a274fb32b2851e6db719fd10c7f` (current 3.2.0).
- Models: https://github.com/Netflix/vmaf/blob/master/resource/doc/models_v1.md and https://github.com/Netflix/vmaf/blob/master/resource/doc/models_v0.md ; viewing/raster caveats: https://github.com/Netflix/vmaf/blob/master/resource/doc/faq.md .
- Timestamp/offline setup: https://github.com/Netflix/vmaf/blob/master/resource/doc/ffmpeg.md ; banding: https://github.com/Netflix/vmaf/blob/master/resource/doc/cambi.md .
- Android encoder capability contract: https://developer.android.com/reference/android/media/MediaCodecInfo.EncoderCapabilities and https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities ; format keys: https://developer.android.com/reference/android/media/MediaFormat . Capability advertisement is not proof of honored settings or rate-distortion superiority.
- Media3 color abstraction and export failures: https://developer.android.com/reference/androidx/media3/common/ColorInfo and https://developer.android.com/reference/androidx/media3/transformer/ExportException .
- Complementary still-image tools: https://github.com/cloudinary/ssimulacra2 and https://github.com/google/butteraugli .
