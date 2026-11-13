# Galaxy S26 Ultra hardware and PL review

## October 29, 2026 Secure Folder capture

The v1.6.275 log export passed all 28 payload checksum and length checks. Compare the new v1.6.275 run with the v1.6.271 run using the same 13 inputs, starting learned state and requested settings. All 13 full source SHA-256 hashes and lengths agree before and after each run and across builds. The source-manifest digest also matches. Both runs used PL Exhaustive, two requested B-frames, longer GOP, safer-rung retry, shadow calibration, full source hashes and full frame traces.

| Recorded result | v1.6.271 | v1.6.275 |
| --- | ---: | ---: |
| Batch wall time | 829.627 s | 184.286 s |
| Pixel-certified smaller outputs | 2 / 13 | 2 / 13 |
| Bytes saved out of 1,434,966,283 | 46,762,017 | 52,332,060 |
| 2160 × 3840 AVC item wall time | 365.407 s | 46.978 s |
| Scored probe window wall time, summed | 651.245 s | 90.003 s |
| Time waiting for frame pairs, summed | 563.750 s | 40.811 s |
| Verdict-model reads and flushes, summed | 7.133 s | 6.574 s |

The new run was 4.50× faster and saved 5,570,043 more bytes. The older run had warmer accepted-item starts and two 25-second cooldowns. The newer run had normal accepted-item starts and one 10-second cooldown. This is an observed same-file comparison with different thermal conditions. The prep work and decoder names now in the v1.6.275 logs explain where much of the time went. Reader prep overlaps between the source and candidate and cannot be added to wall time.

The 1080 × 1080 winner shrank from a 76,778,031-byte output at 0.65 to a 71,207,988-byte output at the measured 0.60 floor. Its original was 115,389,080 bytes. The three certification windows scored mean/p5/min of 97.926/92.466/91.712, 99.275/95.647/94.915 and 97.904/93.123/92.579.

The 1080 × 1920 winner stayed at 0.80 and 35,604,009 bytes. Its original was 43,754,977 bytes. The 0.75 probe scored 36 pairs in each of three windows. Its worst mean was 95.963, worst p5 was 92.315 and worst minimum was 91.009. All clear the certification bars. The mean misses the 96.0 probe selection margin. The existing size model estimates 33,366,670 bytes at 0.75 and 35,531,863 at 0.80. Those estimates show a candidate worth testing. The capture has a certified 0.80 output and no full 0.75 output.

Ten inputs were retained after measured low scores on the tested HEVC and alternate AVC ladders. The 4K AVC item passed marginally at 0.97, then its predicted output was larger than the source. The batch had zero failed or cancelled items. Self-checks compared 101 byte-identical frames each for source versus itself and source versus stream copy. The first window of the 2×-bitrate control still scored 94.40 mean and 90.35 p5. The logs alone cannot separate encoder behavior from color, rendering or content effects in that control.

## Hardware paths

| Component | Evidence and use |
| --- | --- |
| Qualcomm video codec hardware | The S26 capture reports hardware HEVC and AVC encode. The reference decoder was `c2.qti.avc.decoder` in all 78 scored probe windows. Candidates used `c2.qti.hevc.decoder` in 48 and `c2.qti.avc.decoder` in 30. This is the working hardware path on the phone. |
| Adreno GPU | Media3 uses OpenGL for video effects where needed. The current VMAF scorer runs on CPU, and upstream libvmaf's optional GPU backend targets CUDA. An Adreno port would need a different backend and pixel and score parity measurements. |
| Hexagon NPU | Google LiteRT supports the Qualcomm HTP backend on both SM8850 and SM8550. A separately validated model could prioritize ratios or identify hard windows. The batch provides no NPU measurements or calibrated neural quality decision. |
| Encoder options | The reported HEVC CQ variant is limited to 512 × 512. The usual Qualcomm hardware AVC and HEVC codecs advertise 4K in VBR/CBR. The AV1 encoder reported in this inventory is the Android software implementation. |

The 6.574 seconds of recorded verdict-model calls make the existing model a smaller target than frame preparation and tracing in this capture. Hardware compatibility is discovered through Android codecs on both phones. References are [Media3 Transformer](https://developer.android.com/media/media3/transformer), [libvmaf build instructions](https://github.com/Netflix/vmaf/blob/master/libvmaf/README.md) and [LiteRT Qualcomm NPU setup](https://ai.google.dev/edge/litert/next/qualcomm).

## Changes for the next build

- The primary full Exhaustive ladder can try a completed lower marginal bisection when safer-rung retry is enabled, both rungs report the same single encoder, and the lower plan is predicted to be smaller than the higher plan. The higher plan must clear the existing retry size check. Selection also checks current retry capacity, elapsed time, heat and space. A failed candidate verification or a nonpassing pixel certificate can try the higher measured rung once if the retry checks still pass. The retry checks the measured fallback's encoder request and original input. Source parse, encode and publication failures use the usual pipeline handling. Fast, short, alternate-codec and normalized-input ladders do not take this marginal trial.
- Probe export now enables the same decoder initialization fallback as full encode. The S26 logs exercised the preferred Qualcomm decoders. This fixes a compatibility mismatch for cases where a secondary decoder can initialize.
- Full frame traces reuse the SHA-256 state after hashing the Y plane for both the Y and the whole I420 hashes. The code computes Y separately when the provider cannot clone its digest state. Hashes, frame metrics and verdicts retain the same meaning. Two-frame host medians across seven alternating rounds of 20 iterations were 8.471 → 5.611 ms at 1080 × 1920 and 29.515 → 19.897 ms at 2160 × 3840. All hashes matched the independent computations. These measurements cover hashing only, and the new build has yet to be timed on either Galaxy.

Both the initial trial and the retry require passing final sampled VMAF (95.5 mean, 91 p5, 84 minimum, at least 12 pairs per window), timeline, audio and color checks, and an actually smaller file. The captured clips were each below 0.5 GB. Larger, high-bitrate sources can have more room to save, while already efficient sources may be retained. Sampled VMAF provides an objective check for the chosen windows. A blinded viewing test on the S26 would add evidence about whether a person can see a difference on that display.

For a paired device test, run the same 1080 × 1920 item in PL Exhaustive with safer-rung retry enabled and replacement off. Compare the selected ratio, retry trigger, three final windows and accepted bytes with the v1.6.275 result. Keep tracing settings matched for speed comparisons. An in-place update signed with the existing certificate preserves the Secure Folder app data. A rollback can use the previous source with a higher Android version code if the system rejects an older APK.
