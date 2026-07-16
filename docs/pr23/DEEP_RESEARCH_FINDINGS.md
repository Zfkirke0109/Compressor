# Deep-research findings (Phases 9–10) + bibliography

Primary sources only. Each finding: source, date, exact point, applicability to S23 Ultra / this
dataset, and whether local evidence agrees. AI summaries were used ONLY to locate primaries.

## VMAF / libvmaf
- **Netflix/vmaf releases** (github.com/Netflix/vmaf/releases, tag v3.2.0, 2024-06-20): latest
  public libvmaf is v3.2.0; that release is "Initial public release for VMAF v1"; adds
  2160p@1.5H CSF support and concurrent sfr/hfr motion handling. — App bundles API **3.0.0**
  (older). Local agrees: version.h + runtime vmaf_version()=3.0.0.
- **Netflix TechBlog, "VMAF v1: Good Is Not Good Enough"** (medium.com/netflix-techblog, Jun
  2026): VMAF v1 adds banding + colour awareness, drops VIF, faster; model family vmaf_v1.0.16;
  4K variants 1.5H `[0,100]` (default) and 3H `[0,110]`. — Implication (derived): v1 is stricter
  on banding → would reject more, not increase compression. Local evidence agrees the model is
  not the over-conservatism lever.
- **libvmaf README** (github.com/Netflix/vmaf/blob/master/libvmaf/README.md): model selection is
  by name or JSON; CAMBI is a feature extractor requiring encode-side params. — Applicability:
  a faithful v1 integration needs CAMBI enabled + correct model selection, not just a JSON present.

## Android MediaCodec / Media3 / QTI (bitrate delivery)
- **Android MediaFormat.KEY_BIT_RATE / MediaCodec docs** (developer.android.com): for video
  encoders KEY_BIT_RATE is a *target*, honored per the selected bitrate mode (CQ/VBR/CBR);
  hardware VBR is explicitly a target average, not a guarantee. — Local agrees: measured
  delivered/requested 0.88–0.95 (this run) and 0.86–0.93 (084112) is normal VBR undershoot on
  easy content, NOT a bug. Supports keeping the 0.15 tolerance + certification gate.
- **AndroidX Media3 Transformer** (developer.android.com/media/media3): DefaultEncoderFactory can
  fall back to another encoder / adjust settings if the requested format is unsupported; the app
  sets VideoEncoderSettings bitrate + VBR mode. — Instrumentation opportunity: intercept and
  record the actually-selected encoder component/profile/level (currently a data gap).
- General VBR hardware behavior: size-and-duration-derived bitrate is more trustworthy than
  container `btrt` metadata (which MediaMuxer often omits) — the app already derives bitrate from
  size/duration in OutputVerifier when metadata is absent, which local evidence confirms is sound.

## Perceptual-quality method (pooling, HFR)
- **VMAF papers / model docs**: worst-case pooling (min / low-percentile) supplements mean to
  catch localized artifacts; HFR content needs an HFR-aware motion window (5-frame). — Local
  design already pools mean+p5+min per window (good). Gap: v0.6.1 is non-HFR; for 50/60fps the
  HFR model would be more faithful, but this dataset's 60fps clips are mostly already-efficient,
  so no false-rejection evidence here.

## Applicability caveats
- All external guidance is content/viewing-condition dependent; the S23 Ultra outputs may be
  viewed on larger displays, so the phone VMAF model must remain SECONDARY (the app already uses
  the non-phone model — correct).
- No external source overrides a contradictory local measurement here; where they interact
  (undershoot, pooling) local and external agree.

## Bibliography
1. Netflix/vmaf releases — github.com/Netflix/vmaf/releases (v3.2.0, 2024-06-20).
2. Netflix TechBlog "VMAF v1: Good Is Not Good Enough" — medium.com/netflix-techblog (Jun 2026).
3. libvmaf README — github.com/Netflix/vmaf/blob/master/libvmaf/README.md.
4. Android MediaCodec / MediaFormat — developer.android.com/reference/android/media/MediaCodec,
   .../MediaFormat#KEY_BIT_RATE.
5. AndroidX Media3 Transformer — developer.android.com/media/media3/transformer.
6. VMAF Wikipedia (for locating primaries only) — en.wikipedia.org/wiki/Video_Multimethod_Assessment_Fusion.
