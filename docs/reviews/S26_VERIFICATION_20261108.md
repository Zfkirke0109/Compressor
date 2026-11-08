# S26 verification and merge review

## Build and run identity

The November 8 log-only ZIP passed all 30 payload length and SHA-256 checks. All three batches ran on SM-S948U, Android 17/API 37, in the normal Android profile. The device reported v1.6.213, version code 548307 and compiled merge commit `87749d5`. The v1.6.276 Android CI APK came from the same PR #44 source head `0394f90` and merged tree. The display suffix is the run number of its build workflow. Compare compiled commit and build provenance across workflows.

| Run | Time | Result | Bytes saved |
| --- | ---: | --- | ---: |
| First PL Exhaustive run | 41.032 s | 3 quality skips, 12 cancelled | 0 |
| Completed PL Exhaustive run | 678.551 s | 6 certified smaller outputs, 5 quality skips, 3 rejected candidates, 1 retained on a size prediction | 95,990,763 |
| High Quality control | 117.849 s | 4 smaller lossy outputs | 109,415,522 |

The completed PL run started with five learned entries and spent 195 seconds in recorded inter-item cooldowns. The HQ run used a subset of four byte-identical inputs from the PL batch. All 19 complete before/after hash pairs from the completed runs agree. The self-check reports 87 byte-identical frames for source versus itself and another 87 for source versus stream copy. All three sessions replay with zero missing or orphan stages. The completed PL and HQ runs report zero failed or cancelled jobs.

The six PL outputs passed their final sampled certificates. The 0.75 selections in this capture came from 4K short ladders. The earlier 1080 × 1920 marginal bisection and its safer-rung retry are separate device cases.

## Verification findings

| Candidate | Evidence | Disposition |
| --- | --- | --- |
| 4K60 HEVC | 438 matching video timestamps. AAC decoder configuration and payload digests differ. | Keep original. The capture records a copy request. Actual Media3 conversion and the metadata remux stage are now logged to distinguish pipeline behavior. |
| 1080p30 HEVC | 1,236 video samples on both sides. The candidate begins at 0 μs versus 14,277 μs. The later frame comparison and A/V comparison show the same 14,277 μs offset. The audio configuration and payload digests match. | Try restoring the first timestamp only if the complete remaining timeline and original-relative audio prove that specific correction. |
| 4K60 HEVC | 1,044 video samples on both sides. The corresponding first-frame and A/V offset is 3,633 μs, with matching audio configuration and payload digests. | Apply the same bounded verification path. |

The pinned Media3 1.11.0 `MuxerWrapper.writeSample` explicitly sets a positive first video timestamp to zero when audio is present and trimming-to-video is off. Enabling its trim-to-video flag would drop leading audio. The repair instead copies the already encoded candidate once, when a full timeline scan identifies the single zeroed timestamp and the source audio still matches at the proposed offset. It rechecks the actual repaired file and requires the existing pixel certificate and strictly smaller output before acceptance. The source and the original candidate stay available until the repair is proven. [Pinned Media3 source](https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/transformer/src/main/java/androidx/media3/transformer/MuxerWrapper.java)

The failed timing checks had been mislabeled as audio re-encoding. The verifier now reports the actual identity difference. Retained-source job records use zero output bytes, consistent with their terminal event, and preserve the discarded candidate size separately. A muxer close failure discards the incomplete remux. The previous fallback outcomes in this capture retained the originals.

## Review and validation

The earlier Copilot review has 11 resolved inline findings. Commit `44a3cd3` addresses those findings and `2510d99` corrects its build regression. The review included failure terminal parsing, transient codec discovery, learned state UI and reset, watchdog comments and tests, probe docs, alignment errors, diagnostics privacy wording and dependency rationale. The current review corrected the ExifInterface rationale to match its release notes. [ExifInterface release notes](https://developer.android.com/jetpack/androidx/releases/exifinterface#1.4.2)

The first-timestamp and audio wording regressions reproduce in focused JVM controls. On the phone, repeat the two timing cases and the AAC mismatch with PL Exhaustive and Replace Originals off. Check the finalized repair event, exact audio identity, full timeline, certificate, accepted byte count and full source hashes. This export contains logs only. The earlier run supplies rejection evidence, and the repair's device behavior remains a separate validation step.
