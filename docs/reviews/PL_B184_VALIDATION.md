# Validation record for the October 2 independent PL pass

## Verified Android code baseline

Branch commit `a716f25cd882070085a73b3c64da4afc0bc58e52`, GitHub test-merge commit `95bcf0836227ca41861b6c5ffff2664ca1d1dddf` (embedded short commit `95bcf08`). The two identities are not interchangeable. Initial reviewed head was `aa1b31a`; tested October 1 APK was `f5152fc`.

- Android CI run **36950205832**, all five jobs successful: https://github.com/Zfkirke0109/Compressor/actions/runs/36950205832
- `./gradlew :app:testDebugUnitTest --stacktrace --console=plain`: **630 tests, 88 suites, zero failures/errors/skips**, independently counted from downloaded XML.
- `:app:assembleDebug`: PASS. `:app:compileDebugAndroidTestKotlin`: PASS. Instrumentation compiled, **not executed on a phone**.
- APK workflow **36950205831**, artifact `compressor-debug-pr-44-b203`, **v1.6.203 / pr44-b203**: https://github.com/Zfkirke0109/Compressor/actions/runs/36950205831
- Extracted b203 APK SHA-256: `8e63e6e6d43ded753234f2bd00e7fcfb6a65640f9e259a3cee7cd7fb2facbaf8`.
- Unit XML archive SHA-256: `4a576a307c724c37d00b7f69c926781fc7baa9d6d6c7d166b387e649b5e8fb9a`.
- `scripts/ci/verify_native_alignment.py` on the downloaded b203 APK: **PASS**, all three arm64 libraries and ZIP offsets 16 KB aligned.
- `scripts/ci/verify_dex_method_budget.py` on that APK: **5,815 app methods; maximum 7,992 code units**, below the 10,000 budget. `scoreWindow` 6,842; `runLadder` 6,446. This is an engineering regression budget, not a universal ART guarantee.
- 19 local Python suites exited zero: **227 tests**, including 4 real FFmpeg/libvmaf 3.2 controls, no skips. The final per-window rate/timing enrichment adds one regression: affected suite **5/5 PASS**, bringing the checked set to **228**. The delivered JSON records which full-suite and affected-suite runs produced these counts.
- CI separately builds and runs **4/4 real offline controls for each** of Netflix libvmaf 3.0.0 (`17a67b2`) and 3.2.0 (`8e7a1ac`). Offline synthetic controls are not Android decoder parity.
- `git diff --check`: PASS. Independent capture reconstruction reproduces 244 files, 24 winners, 185 probe failures + 2 certification failures and 735,435,391 bytes.

The subsequent CI-only packaging change builds and publishes a **matched signed app + instrumentation pair** in `pl-device-controls-ci-<run>`. Use the frozen pair named in the delivered validation evidence for phone controls; it avoids rebuilding a test APK with a different signing key. No production acceptance logic changes after `a716f25` are required by this packaging/tooling follow-up.

## Regression evidence, including observed red states

| Specification commit | Observed failing tests | Subsequent correction |
|---|---:|---|
| `5102f15` | 9/600 | measured-only certification, finite evidence, learning/audio/color separation |
| `626e56c` | 4/604 | unreachable coverage, empty self-check, non-finite shadow evidence |
| `3818c3f` | 4/613 | repeated observation suppression and MIME-specific encoder profile IDs |
| `323cb4a` | 9/624 | exact timelines, audio presentation and close VFR/repeated PTS |
| `651bb87` | 3/628 | partial recovery and equal-length write/restore corruption |
| calibration evidence scaffold (local) | 6 errors/6 tests | ledger replay, family split, equivalence bound and hard-window proposals |
| rate/timing table regression (local) | 1 error/5 tests | per-window full-precision observed-rate/timing join |

The initial 591-test baseline grew to 630 JVM tests. Existing fixtures asserting inference-based acceptance were deliberately updated to the stricter evidence contract, not to preserve unsafe outcomes. Concurrent fixes on the same PR were reviewed/reconciled; `b87071a` and `dd41c17` implemented several of the red specifications, and the following commits connected/strengthened the production paths.

## Principal commits and rationale

- `dc25a35`: reject unmeasured/partial/non-finite PL acceptance and isolate integrity failures from learning.
- `88c8369`: per-frame compressed evidence, candidate retention, complete window coverage, scorer cleanup and archive integrity.
- `7ab92f6`: pinned offline parity, controls, independent batch tables and actual-DEX gate.
- `04398c2`: source/config/model/window/epoch-bound learning ledger and geometry-aware encoder inventory.
- `3969098`: complete final video timeline and audio config/relative-timing evidence.
- `dd41c17` + `a716f25`: content-proven replacement, unique durable recovery files, conservative retention and phone/scientific protocol.

Changed-file inventories are supplied in the data pack and in the PR diff. Grouped rationale, limitations and PDF claim classifications are in `PL_B184_INDEPENDENT_REVIEW.md`.

## Explicitly not validated

No on-device run, matched-byte CQ/VBR experiment, human ABX study, real-source Android/FFmpeg pixel parity or yield increase was performed here. Complete v1 offline parity and a validated on-device content-aware selector are not implemented. CQ geometry suitability, untagged/rendered color behavior, new audio/timeline compatibility, peak memory and storage-provider recovery still need focused device evidence. No global learning reset, threshold reduction, v1 promotion, automatic merge or source replacement was performed during this work.
