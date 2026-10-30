# PR #44: S26/S23 review and compression search (2026-10-29)

Reviewed baseline: `258d70a9ebeb9b311a09877b5badf035546e4363`.
Primary target: Galaxy S26 Ultra SM-S948U. Galaxy S23 Ultra SM-S918 models remain supported.

## Verified findings and fixes

| Priority | Finding | Change |
| --- | --- | --- |
| High | HQ/Storage Saver could encode a platform-normalized input missing internal samples and replace the original based only on playability. | Normalization retry is restricted to PL, which compares the full original/candidate video timelines and audio. Lossy parse failures end without replacement. |
| Medium | The native gate accepted Compose's library even when the production VMAF scorer was absent. | Explicitly require `lib/arm64-v8a/libcompressorvmaf.so` as well as checking all libraries' ELF/ZIP alignment. |
| Medium | A retained original emitted an accepted-output event and source-sized kept bytes. | Terminal events use `FinalAcceptance.acceptedOutputBytes`, which is zero for retention. Source size stays in its existing dedicated record. |
| Medium | Diagnostics exports claimed success after failed/zero-row publication. | ZIP and text exports require exactly one published MediaStore row; failed writes stay unpublished and cleanup is attempted. |
| Medium | Pruning before creating a run kept 31 directories under a documented 30-run limit. | Include the incoming run when choosing retention victims; resuming an existing run does not count it twice. |
| Medium | The S23 high-resolution HDR HEVC overshoot seed applied to all devices. | Scope that 1.25 seed to Samsung SM-S918 models. The S26 starts at 1.0 and learns its own measured overshoot. Existing measured profiles are retained. |
| Medium | An imported learning ledger could suppress S26 observations because its encoder name/config/source matched an S23 observation. | Scope deduplication to the persisted technical profile (including model and SDK). Existing v1 reservations remain effective for their original profile. Offline replay and partitioning use the same scope; matching source families stay in the same partition across devices. |

## Compression changes

S26 defaults now have explicit model recognition, correct UI/preset names, HEVC preference, sequential batch work, and opt-in AV1. S23 model/codename recognition and preset labels remain supported. Encoder availability remains a runtime capability check; model identity never invents encoder support.

In **Perceptually Lossless + Auto + Exhaustive**, an AVC SDR source up to the existing full-ladder geometry cap (1920×1088 pixels, including portrait equivalents) can now try a second codec after HEVC has a measured quality failure or a passing rung predicted not smaller. A hardware AVC encoder must report support for the source geometry/rate. A winning HEVC plan, manual codec request, Fast mode, HDR, parser failure, and larger/unsupported geometry do not enter this additional search.

The AVC search requests 0.85/0.95/0.97 of the source video bitrate with the existing bitrate policy, no downward refinement, and a 90-second **soft** ladder budget. The already-running rung and safest-rung measurement may finish beyond that budget under the existing ladder rules. There is at most one additional codec per source. Probe events carry output MIME, configuration/rung/window identity, actual encoder names and measured scores for both searches. The selected full encode uses the selected codec's own plan, learning key, bitrate and rung evidence.

An alternative must have its own measured passing windows and size-gate approval before a full encode is attempted. Full-output certification, timeline/audio/color checks and real smaller-than-source bytes still decide acceptance. Thresholds remain mean **95.5**, p5 **91**, minimum **84**, with at least **12** compared pairs per window. No VMAF v1 promotion, CQ experiment or learning reset is introduced.

This expands the measured search space; no increase in device yield or speed is claimed without a controlled phone run. Extra probes cost time and battery. The previous real batch measured HEVC failures, not AVC failures, so a separate AVC attempt is an empirical question rather than a promise.

## Validation

- Local Python: 20 suites / 236 tests passed, including the new production-library, per-device ledger replay and source-family partition regressions.
- Local Android validation: `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` passed; **647 JVM tests / 91 suites**, zero failures/errors/skips. The app and instrumentation APKs build. The first validation caught a missing assertion import in a new test; it was corrected before the successful run.
- All packaged native libraries pass the 16 KB ELF and APK ZIP-alignment gate, including the required production scorer. The DEX regression gate passes: largest app method **8,076 code units**, below the 10,000 limit.
- Code under test matches `c0210fd` (the preceding `82fee72` application changes plus the test import). The final PR update links GitHub CI and the signed artifact; local APKs use a local signing key and are not the update deliverable.
- No phone installation, full media batch or hardware benchmark has been run in this pass. Historical device/parity/provider-recovery merge blockers still apply.

## Focused phone validation

1. Install a new CI APK with the existing signing identity; retain app data and learned state.
2. On the S26, confirm the profile/presets say S26 Ultra. Use PL + Auto + Exhaustive, source FPS, copies, with experimental GOP/B-frame/shadow options held constant. Export diagnostics before changing settings.
3. Run scorer/structure/audio controls, then a small fixed subset containing previous HEVC failures, known winners, a low-bpp AVC clip, a portrait clip and an HDR/4K exclusion. Do not start with another full library batch.
4. For an AVC rescue, verify both codecs' probe events, the selected AVC plan/config/rung, complete final certification, identical audio/timeline, smaller actual bytes and untouched originals. A failing or unavailable alternative must retain the original without relabeling it a winner.
5. Repeat the same inputs/settings on a working S23 when available, using its own learned profile. Record starting thermal state, cooldown, app/firmware identity and before/after source hashes. Compare accepted bytes and runtime separately; do not pool device outcomes as independent source samples.

## Reference contracts checked

- Android codec capabilities: https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities
- Media3 encoder selection: https://developer.android.com/reference/androidx/media3/transformer/EncoderSelector

These APIs advertise support and preference; measurements remain necessary to establish encoder quality and rate control on each phone.
