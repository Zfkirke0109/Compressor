# Galaxy Compressor changelog

## v1.6.335 · November 14, 2026

The first public fork build uses the stable Android CI signing certificate and is published as a GitHub pre-release. It updates compatible PR and CI installations in place. [Download the app APK](https://github.com/Zfkirke0109/Compressor/releases/download/ci-1.6.335/Galaxy-Compressor-v1.6.335-ci-arm64.apk)

### Perceptually Lossless compression

- The Galaxy S26 Ultra and S23 Ultra now learn separately. Exhaustive mode can refine a measured bitrate down to the existing 0.60 floor, try hardware AVC for eligible SDR AVC sources after an unsuccessful HEVC search, and retry a safer measured bitrate within its time, thermal and storage limits.
- Native arm64 frame packing combines crop, stride handling and quarter-turn rotation. The probe and final encode share decoder fallback behavior. Diagnostics record decoder identity, preparation time and each attempt’s encoder and measurements.
- A smaller result still has to preserve the source video timeline, audio and color information. Every final sampled window requires at least 12 compared frame pairs, a VMAF mean of 95.5, a fifth percentile of 91 and a minimum of 84. A candidate with insufficient evidence leaves the original in place. [Quality criteria](https://github.com/Zfkirke0109/Compressor/blob/ci-1.6.335/docs/PERCEPTUALLY_LOSSLESS.md)

### Original files, audio and batch results

- A candidate with a shifted first video timestamp or a copied AAC track differing only in end padding can receive a targeted remux and full re-verification. Missing video samples or a remaining audio mismatch prevent PL acceptance.
- Optional replacement binds each operation to the source digest and journals a rollback copy. Backups, recovered originals and gallery copies count as saved only after publication and a read-back matching the expected length and SHA-256.
- Batch rows identify the original’s condition, saved copies, retry attempts and recovery warnings. Suggestions disclose batch-wide changes before applying them. Size reporting distinguishes accepted output from a retained original.
- Log-only ZIP exports, run ordering across clock changes, complete audio digests, and per-device learning journals make results easier to reproduce. Diagnostics, learned profiles and rollback files are excluded from Android backup and device transfer.

### Validation

The S26 Ultra safe-copy harness passed 13 cases on build 1.6.322. Follow-up checks through build 1.6.325 brought the total to 144 matching checks of three originals and their baselines. On that corpus, a 1080 × 1920 clip was 23.6% smaller and the other two originals were kept. The final PR #44 revision passed 845 JVM tests, 26 Python suites and both offline VMAF control versions. Physical S23 testing remains pending. The AAC end-padding repair was exercised on a synthetic fixture. [Device results](https://github.com/Zfkirke0109/Compressor/blob/ci-1.6.335/docs/DEVICE_VALIDATION.md) · [Full engineering and validation record in PR #44](https://github.com/Zfkirke0109/Compressor/pull/44)

### Repository maintenance

A lossless image pass reduced 17 of 34 repository images by 121,761 bytes (118.91 KiB) while preserving decoded pixels and metadata. [PR #45](https://github.com/Zfkirke0109/Compressor/pull/45)
