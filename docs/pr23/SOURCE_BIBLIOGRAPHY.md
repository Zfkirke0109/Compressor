# Source bibliography (primary sources)

| # | Source | Org | Date | Type | Used for |
|---|---|---|---|---|---|
| 1 | github.com/Netflix/vmaf/releases (v3.2.0) | Netflix | 2024-06-20 | official release | latest libvmaf version; VMAF v1 initial release |
| 2 | medium.com/netflix-techblog "VMAF v1: Good Is Not Good Enough" | Netflix | Jun 2026 | official techblog | v1 features (banding/chroma, drops VIF), model family |
| 3 | github.com/Netflix/vmaf/blob/master/libvmaf/README.md | Netflix | master | official source docs | model selection, CAMBI requirements |
| 4 | developer.android.com .../MediaCodec, MediaFormat#KEY_BIT_RATE | Google/AOSP | current | official API docs | bitrate is a target under VBR, not guaranteed |
| 5 | developer.android.com/media/media3/transformer | Google/AndroidX | current | official docs | encoder fallback/settings adjustment |
| 6 | en.wikipedia.org/wiki/Video_Multimethod_Assessment_Fusion | community | current | tertiary (locate primaries only) | VMAF background |

Local source-code references (implementation facts): app/src/main/cpp/vmaf_jni.c:83 (model),
prebuilt/include/libvmaf/version.h (API 3.0.0), VmafPairScorer.kt:86,170 (phoneModel=false, pooling),
QualityProbePolicy.kt (thresholds/ladders), SmartPerceptualProfileEngine.kt (latch).
