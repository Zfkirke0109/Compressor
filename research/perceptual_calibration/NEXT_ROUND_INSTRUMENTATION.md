# What the next calibration round needs

The v2 study verdict on the existing captures is **no change**, and that is
correct for the data we have. The bottleneck is not the optimizer — it is the
evidence. Before another calibration round is worth running, three things must
change: the diagnostics must log enough precision, the capture must probe the
unmeasured band, and the corpus must contain sources that *can* compress.

Nothing in this document has been implemented in this PR — the app-side change
needs an Android build + device run this session could not perform. Each item is
specified precisely so it can be done as its own small, tested PR.

---

## 1. App-side: log unrounded window scores (additive logging only)

**Problem.** Window floors are logged rounded to one decimal, but production
compares the **unrounded** score. The v1 study's entire "improvement" was one row
whose offline false-accept existed only because of this rounding (see
`REVIEW_FINDINGS.md`). Any future study fit to 0.1-rounded floors inherits the
same artifact near every boundary.

**Change.** In
[`BatchCompressorViewModel.kt`](../../app/src/main/java/compress/joshattic/us/BatchCompressorViewModel.kt),
`compactWindowScores` (~line 2504) formats scores with `"%.1f/%.1f/%.1f"`.
Widen to `"%.3f/%.3f/%.3f"`. Apply the same to the cert-scores formatting used
for the `scores=` diagnostic (~line 1076) and to `compactPairingDiag` (~line
2508) if it rounds.

**Why this is safe.** These format strings feed *only* the `CompressorDiag`
string fields (`probeWindowScores` / `certWindowScores`). Every actual decision —
`QualityProbePolicy.windowsPass` / `certificationPasses` (QualityProbePolicy.kt
~line 119 / 146) — operates on the unrounded `WindowScore` doubles and is
untouched. This changes what we *log*, never what we *decide* or *label*. It does
not touch `OutputVerifier`, any verdict, or any threshold, so it cannot violate
the truth rules in the `android-media3-perceptual-compression` skill.

**Tests.** Add a unit test asserting `compactWindowScores` round-trips a score of
e.g. 95.54 without collapsing to 95.5. Parser side: confirm
`scripts/diagnostics/parse_batch_logcat.py` and the normalizer parse 3-decimal
values (they already parse floats, so this should be a no-op — verify with a
fixture line).

**Validation before merge.** `./gradlew testDebugUnitTest assembleDebug`, then
one S23 Ultra capture confirming 3-decimal floors appear in logcat.

## 2. App-side (optional): emit `sourceBitsPerPixelPerFrame` directly

Today bpp is recomputed downstream from `sourceTotalBitrate / w / h / fps`, which
silently drops any row with unknown bitrate/geometry. Emitting the value the app
already computes (`BatchQualityBitratePolicy.sourceBitsPerPixelPerFrame`) into the
job record removes a reconstruction step and makes "bitrate measured from size
vs. container" explicit. Purely additive; no decision changes.

## 3. Dataset-prep: carry build identity per row

`buildCommit` is emitted in `session_start` but the v1 prepared CSVs never joined
it onto job rows, so no reviewer could stratify by build.
`scripts/prepare_dataset_v2.py` already tags each row with `build_era`
(pre/post the 2026-07-16 pairing fix) from the capture-batch → build map; a future
version should join the actual `buildCommit` from each batch's `session_start`
so the era split is derived from data, not a hard-coded batch list.

## 4. Capture plan (S23 Ultra)

The 0.03–0.069 bpp band is the whole dispute and it has **no** trustworthy
labels. Round 2 must:

- Probe sources in the [0.03, 0.069) band **including the 0.97 retreat rung** —
  every in-band probe to date used only 0.90/0.95, so the safest rung was never
  measured.
- Run on a **post-pairing-fix build** only (≥ commit `4d50c03`), so scores are
  PTS-aligned.
- Produce an **offline PTS-synced VMAF audit** for a sample of in-band skips, so
  labels have a ground truth independent of the on-device scorer (which is proven
  capable of 70-point false fails on the jellyfish clips).
- Capture ≥ 2 build-consistent batches so dedup keeps one row per source without
  discarding evidence.

## 5. The corpus — this is the real lever

> "Most of my videos don't compress."

For a corpus of modern **phone-camera HEVC** clips, that is largely the algorithm
working, not failing. Samsung's camera encoder already sits near the
perceptual-lossless floor (high bpp efficiency), so there is no transparent
headroom to recover — and Smart PL correctly prefers an honest remux (0.0 MB
saved) over burning an encode that would fail verification. The
`android-media3-perceptual-compression` skill names this exact case as *correct*
behavior, and names its opposite — "a lower-bitrate SDR/H.264 source that has
genuine headroom" — as the case that *should* produce real savings.

So the calibration corpus must be chosen for **headroom diversity across the bpp
axis**, not realism of a single phone. A corpus of 1000 more phone clips will
keep concluding "nothing compresses," because for that distribution it is true.

### Constraint math

"1000+ clips under 4 GB" ⇒ ~4 MB/clip average. Pristine research sets are stored
raw or 4K and blow past this immediately (a single 20 s 1080p raw YUV clip is
~1.5 GB). The corpus is therefore **built by segmenting**: cut short (3–6 s)
clips from a diverse master set with `ffmpeg`, spanning a controlled bpp ladder
and codec/content mix. Short segments also match the app's own probe/cert windows
(1.2 s × 3), so a clip need not be long to be representative.

### Recommended sources (all freely usable for research)

- **YouTube-UGC** — 1500 × 20 s clips explicitly built for compression research,
  deliberately *non-pristine* (the UGC case), spanning Gaming/Sports/Vlog/HDR and
  a wide bitrate range. Download a category subset, not the whole raw set.
  <https://media.withyoutube.com/> · paper: <https://arxiv.org/abs/1904.06457>
- **Blender open movies** (Big Buck Bunny, Sintel, Tears of Steel, Cosmos
  Laundromat, Spring, Agent 327) — CC-BY, available in many encodes; excellent as
  masters you re-encode to controlled bitrates. <https://archive.org/details/Sintel_201709>
- **Xiph.org derf test media** — the classic sequence collection for codec work
  (grain, motion, detail extremes). <https://media.xiph.org/>
- **Inter4K** — 1000 diverse web-sourced clips (4K; downscale/segment for size).
  <https://github.com/alexandrosstergiou/Inter4K>

### The key trick: manufacture headroom

`scripts/build_corpus.py` automates exactly this (see the README). Point it at a
directory of masters and it cuts short non-overlapping segments, re-encodes each
across a bpp ladder that emphasizes the [0.03, 0.069) band, and — in its `label`
phase — attaches an offline VMAF ground-truth "compressible?" verdict per clip via
the repo's `measure_quality.py`. Start with `build_corpus.py plan --masters <dir>`
for a size/spread estimate before encoding.

For calibration you need **known-compressible** and **known-incompressible**
examples spanning the bpp axis — especially the [0.03, 0.069) band the review
flagged as unmeasured. The most valuable clips are ones where you control the
answer:

- Take a handful of masters (Blender films, a few UGC clips) and transcode each
  to a **ladder of bitrates** — e.g. deliberately over-encoded high-bitrate H.264
  (lots of headroom → PL should win) down through efficient HEVC near the floor
  (no headroom → PL should honestly remux).
- Include your **own real device clips** too, but as one bucket among many, not
  the whole corpus.
- Keep an offline VMAF ground truth for each, so a clip's label doesn't depend on
  the on-device scorer.

A few hundred masters × a small bitrate ladder × short segments yields >1000
clips well under 4 GB, with a *labelled* spread across the exact bpp region the
policy is unsure about — which is worth far more than 1000 random near-optimal
phone videos that all correctly refuse to compress.

### Frustration check

If the goal is "make my phone videos smaller," the honest answer is that
already-efficient HEVC camera footage often *can't* be made perceptibly-losslessly
smaller — that is physics, not a bug, and the app is being truthful by saying so.
The lossy **High Quality** / **Storage Saver** modes are the levers for
guaranteed size reduction on those files (with visible-loss labeling). Perceptual
Lossless is for the headroom cases above.
