# On-device ML runtime evaluation for the CompressionAdvisor (Phase 4 research)

Researched 2026-07-16 from primary sources. Evidence categories separated: [verified] = primary
source checked today; [knowledge] = well-established, not re-verified today; [local] = measured
from this repo's captures.

## What an ML advisor can and cannot improve for PL — the framing that decides everything

[local] The fresh-store validation proved the dataset is **mostly genuinely incompressible**
(118 probe ladders run, 84 measured-clear failures, 9 compressions). A model **cannot increase
the compression count** — that's bounded by content + the VMAF gate, which stays authoritative.
What a model CAN do is **predict outcomes earlier** to save time/battery: skip doomed probes
(~4 s each), predict remux/no-savings up front, predict encode time. That is a *scheduling*
benefit, not a quality benefit. **Ceiling: ~7–10 min per 172-file batch** (probe + wasted-encode
time measured in batch_20260715_193710).

[local] Training data today: **9 positive examples** (verified compressions) vs 163 negatives.
No neural network can be responsibly trained on 9 positives. This makes the runtime question
mostly moot for now — the honest Stage 4A is a **pure-Kotlin statistical advisor** (calibrated
class priors / logistic on ~10 features), which needs **no runtime at all**.

## Candidates

| Runtime | Version [verified today unless noted] | License | Android/ARM64 | Cross-vendor | Size cost | Fit for THIS use case |
|---|---|---|---|---|---|---|
| **None (pure Kotlin statistical)** | n/a | n/a | native | universal | **0 MB** | **RECOMMENDED Stage 4A/4B** — sufficient for tabular features at this data scale |
| **LiteRT** (TFLite successor) | production-stable since TF 2.21, Mar 2026; Maven `com.google.ai.edge.litert`; new CompiledModel API | Apache-2.0 | first-party Android, arm64, GPU/NPU delegates (incl. Samsung NPUs via vendor delegates) | Google-maintained, vendor-neutral | ~1–2 MB core [knowledge] | **Designated future runtime (Stage 4C+)** if a real NN is ever justified |
| **ONNX Runtime Mobile** | actively maintained [knowledge] | MIT | AAR arm64, NNAPI EP | vendor-neutral | ~3–6 MB [knowledge] | Portability alternative if models come from non-TF tooling |
| **ExecuTorch** | 1.3.1 stable, May 2026; AAR arm64-v8a; ~50 KB base footprint claim | BSD-3 | yes | vendor-neutral, newer ecosystem | small core, ops add up | Credible, younger; revisit at Stage 4C if PyTorch-native training pipeline |
| **NCNN** (Tencent) | active [knowledge] | BSD-3 | yes (Vulkan GPU) | vendor-neutral | ~1–3 MB [knowledge] | Vision-CNN oriented; wrong shape for tabular advisor |
| **MNN** (Alibaba) | active [knowledge] | Apache-2.0 | yes | vendor-neutral | ~2–4 MB [knowledge] | Same as NCNN — no advantage here |
| **Samsung ONE** | v1.30.1, Sep 2025 | open-source (repo LICENSE) | targets Ubuntu/Tizen/Android | **general third-party use on non-Samsung devices NOT established** | heavy compiler+runtime stack | **REJECTED as a dependency** — violates the no-Samsung-only rule; at most a future optional acceleration backend behind the advisor abstraction |

## Recommendation (evidence-based, staged)

1. **Stage 4A/4B — no ML runtime.** Implement `CompressionAdvisor` with a pure-Kotlin
   statistical model (class priors + logistic calibration over: bpp, resolution class, fps class,
   codec, container, source bitrate, prior class outcomes, delivered/requested history). Shadow
   mode only. Zero APK/memory/thermal cost. This already captures most of the achievable
   scheduling benefit because the measured failure signal is dominated by a few strong features
   (bpp class + codec + prior class rejections — see TERMINAL_ROOT_CAUSES.csv).
2. **Gate to Stage 4C (any NN + runtime):** ≥50 verified-compression positives accumulated
   locally across batches AND the statistical advisor's measured false-negative rate on known
   winners > 0 or calibration demonstrably poor. Then: **LiteRT**, quantized model ≤200 KB,
   CPU-only first, versioned + SHA-recorded, heuristic fallback mandatory.
3. **Samsung ONE:** never a required dependency. Reconsider only as an optional delegate if
   Samsung NPU acceleration ever measurably matters for a model this small (it won't — [local]
   metadata inference must hit p95 < 50 ms, achievable on CPU for a logistic model by orders of
   magnitude).
4. **Acceptance/rejection criteria** stand as written in the mission: the advisor may not
   influence production decisions while it mispredicts any known verified-compression winner;
   reject the runtime when size/heat/maintenance exceeds measured benefit.

## Sources
- LiteRT: developers.googleblog.com "TensorFlow Lite is now LiteRT"; github.com/google-ai-edge/litert;
  ai.google.dev/edge/litert (production graduation with TF 2.21, Mar 2026).
- ExecuTorch: github.com/pytorch/executorch (1.3.1, May 2026); docs.pytorch.org/executorch
  (Android AAR, arm64-v8a, base footprint).
- Samsung ONE: github.com/Samsung/ONE (v1.30.1 Sep 2025; platform targets; third-party
  non-Samsung usability not established).
- ONNX Runtime / NCNN / MNN: [knowledge], to re-verify at Stage 4C gate.
