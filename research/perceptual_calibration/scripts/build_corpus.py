#!/usr/bin/env python3
"""Build a bpp-diverse calibration corpus for perceptual-lossless threshold work.

Motivation (see ../NEXT_ROUND_INSTRUMENTATION.md): a corpus of already-efficient
phone HEVC clips keeps concluding "nothing compresses" because for that
distribution it is true. Calibrating the probe/window thresholds needs source
clips spread across the source bits-per-pixel axis - especially the disputed
[0.03, 0.069) band - with a ground-truth answer to "does this source actually
have perceptually-lossless headroom?"

This tool has two phases:

  build   From a directory of master videos, cut short segments and re-encode each
          to a ladder of target bits-per-pixel values, across codecs/resolutions,
          producing SOURCE clips whose bpp is controlled. Writes corpus_manifest.csv.

  label   For each source clip, produce a perceptually-lossless-style re-encode at
          one or more retreat ratios and score it with the repo's authoritative VMAF
          harness (scripts/diagnostics/measure_quality.py). Attaches a ground-truth
          "compressible" label using the production window floors. An encoder or
          measurement FAILURE is recorded as unlabeled, never as a quality-negative.

  plan    Dry run: report how many clips and how many bytes a build would produce,
          without encoding anything (also runs when ffmpeg is absent).

External tools: ffmpeg + ffprobe on PATH (or --ffmpeg/--ffprobe). Labeling also needs
an ffmpeg built with libvmaf. None of these are bundled. The pure planning/accounting
logic has no external dependency and is unit-tested in test_build_corpus.py.

Nothing here decides a production verdict or writes into the app. It manufactures
test inputs and an offline ground truth for the offline study.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import os
import random
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

# Mirror QualityProbePolicy.kt (WINDOW_MEAN_MIN / P5 / MIN). Kept as data, not imported
# from Kotlin; if production changes, update here and note it in the manifest.
WINDOW_FLOORS = {"mean": 95.5, "p5": 91.0, "min": 84.0}

# Emphasis on the disputed [0.03, 0.069) band plus headroom above and a lean point below.
DEFAULT_BPP_LADDER = [0.02, 0.03, 0.04, 0.05, 0.06, 0.069, 0.08, 0.10, 0.14, 0.20, 0.30]
DEFAULT_PL_RATIOS = [0.97, 0.95, 0.90]  # least -> most aggressive; matches app retreat rungs
DISPUTED_BAND = (0.03, 0.069)  # [lo, hi) the review flagged as unmeasured

VIDEO_EXTS = {".mp4", ".mov", ".mkv", ".webm", ".m4v", ".avi", ".ts", ".y4m"}
MANIFEST_NAME = "corpus_manifest.csv"

BUILD_COLUMNS = [
    "clip_id", "path", "master", "seg_index", "start_s", "duration_s",
    "width", "height", "fps", "source_codec", "target_bpp", "target_bitrate_bps",
    "actual_video_bitrate_bps", "actual_bpp", "size_bytes", "with_audio", "in_disputed_band",
]
LABEL_COLUMNS = [
    "ratios_tested", "best_pass_ratio", "pl_vmaf_mean", "pl_vmaf_p5", "pl_vmaf_min",
    "pl_output_bytes", "pl_size_ratio", "pl_classification", "compressible", "label_note",
]


# --------------------------------------------------------------------------------------
# Pure helpers (no ffmpeg; unit-tested)
# --------------------------------------------------------------------------------------
def target_bitrate_bps(bpp: float, w: int, h: int, fps: float) -> int:
    """Video bitrate that yields `bpp` source bits per pixel per frame."""
    return int(round(bpp * w * h * fps))


def bpp_from_bitrate(bitrate_bps: float, w: int, h: int, fps: float) -> float | None:
    denom = w * h * fps
    return (bitrate_bps / denom) if denom > 0 else None


def parse_fps(rate: str | None) -> float | None:
    """Parse an ffprobe r_frame_rate like '30000/1001' or '30'."""
    if not rate:
        return None
    try:
        if "/" in rate:
            num, den = rate.split("/", 1)
            den_f = float(den)
            return float(num) / den_f if den_f else None
        return float(rate)
    except (TypeError, ValueError):
        return None


def parse_resolutions(spec: str) -> list[tuple[int, int] | None]:
    """'keep' or 'WxH[,WxH...]'. None means keep the master's native resolution."""
    out: list[tuple[int, int] | None] = []
    for token in (t.strip() for t in spec.split(",") if t.strip()):
        if token.lower() == "keep":
            out.append(None)
            continue
        w, h = token.lower().split("x", 1)
        out.append((int(w), int(h)))
    return out or [None]


def parse_float_list(spec: str) -> list[float]:
    return [float(t) for t in (x.strip() for x in spec.split(",")) if t]


def segment_starts(duration_s: float, n: int, seg_dur: float) -> list[float]:
    """Deterministic start times for up to `n` NON-OVERLAPPING segments, spread evenly
    across the master and avoiding head/tail margins.

    Segments never overlap: emitting near-duplicate windows from one master would
    reintroduce exactly the source duplication the v1 dataset suffered from. Returns
    fewer than `n` (down to 1, or 0 when the master is shorter than one segment).
    """
    if duration_s <= 0 or seg_dur <= 0 or n <= 0:
        return []
    if duration_s < seg_dur:
        return []  # too short to yield even one full segment
    margin = min(0.05 * duration_s, 1.0)
    lo = margin
    span = (duration_s - margin) - lo  # region available for [start, start + seg_dur]
    if span < seg_dur:
        return [round(max(0.0, (duration_s - seg_dur) / 2.0), 3)]  # one centered segment
    max_fit = int(span // seg_dur)     # how many non-overlapping windows actually fit
    k = max(1, min(n, max_fit))
    if k == 1:
        return [round(lo + (span - seg_dur) / 2.0, 3)]
    step = (span - seg_dur) / (k - 1)  # >= seg_dur because k <= span // seg_dur
    return [round(lo + step * i, 3) for i in range(k)]


def in_disputed_band(bpp: float) -> bool:
    return DISPUTED_BAND[0] <= bpp < DISPUTED_BAND[1]


def clip_id(master_stem: str, seg_index: int, res: tuple[int, int] | None,
            codec: str, bpp: float) -> str:
    res_tag = f"{res[0]}x{res[1]}" if res else "native"
    safe = "".join(c if c.isalnum() or c in "-_" else "_" for c in master_stem)[:48]
    return f"{safe}__s{seg_index}__{res_tag}__{codec}__bpp{bpp:.3f}"


@dataclass
class ClipSpec:
    clip_id: str
    master: str
    seg_index: int
    start_s: float
    duration_s: float
    width: int
    height: int
    fps: float
    source_codec: str          # "hevc" | "h264"
    target_bpp: float
    target_bitrate_bps: int
    with_audio: bool

    def estimated_bytes(self) -> int:
        video = self.target_bitrate_bps * self.duration_s / 8.0
        audio = (128_000 * self.duration_s / 8.0) if self.with_audio else 0.0
        return int(video + audio)

    def in_disputed_band(self) -> bool:
        return in_disputed_band(self.target_bpp)


@dataclass
class BuildConfig:
    seg_duration: float = 4.0
    segments_per_master: int = 3
    resolutions: list[tuple[int, int] | None] = field(default_factory=lambda: [(1280, 720)])
    bpp_ladder: list[float] = field(default_factory=lambda: list(DEFAULT_BPP_LADDER))
    codecs: list[str] = field(default_factory=lambda: ["hevc", "h264"])
    fps_override: float = 0.0        # 0 => keep master fps
    with_audio: bool = False


def plan_master(meta: dict[str, Any], cfg: BuildConfig) -> list[ClipSpec]:
    """Expand one master's metadata into clip specs (pre-encode). Pure."""
    width0, height0, fps0 = meta["width"], meta["height"], meta["fps"]
    dur = meta["duration_s"]
    stem = meta["stem"]
    fps = cfg.fps_override or fps0
    starts = segment_starts(dur, cfg.segments_per_master, cfg.seg_duration)
    specs: list[ClipSpec] = []
    for seg_index, start in enumerate(starts):
        for res in cfg.resolutions:
            w, h = (res if res else (width0, height0))
            if w <= 0 or h <= 0 or fps <= 0:
                continue
            for codec in cfg.codecs:
                for bpp in cfg.bpp_ladder:
                    br = target_bitrate_bps(bpp, w, h, fps)
                    if br <= 0:
                        continue
                    specs.append(ClipSpec(
                        clip_id=clip_id(stem, seg_index, res, codec, bpp),
                        master=meta["path"], seg_index=seg_index, start_s=start,
                        duration_s=cfg.seg_duration, width=w, height=h, fps=fps,
                        source_codec=codec, target_bpp=bpp, target_bitrate_bps=br,
                        with_audio=cfg.with_audio,
                    ))
    return specs


def select_within_budget(specs: list[ClipSpec], budget_bytes: int, max_clips: int,
                         seed: int) -> list[ClipSpec]:
    """Deterministically pick a budget-respecting, diverse subset.

    Priority: disputed-band rungs first, then spread across masters/codecs/rungs via a
    round-robin key so a truncated corpus stays balanced rather than front-loaded on
    one master. `seed` only shuffles master order for tie-breaking.
    """
    rng = random.Random(seed)
    masters = sorted({s.master for s in specs})
    rng.shuffle(masters)
    master_rank = {m: i for i, m in enumerate(masters)}

    def key(s: ClipSpec) -> tuple:
        return (
            0 if s.in_disputed_band() else 1,   # disputed band first
            s.seg_index,                         # spread across segments
            master_rank[s.master],               # spread across masters
            s.source_codec,                      # spread across codecs
            s.target_bpp,                        # then by rung
            s.clip_id,                           # stable final tie-break
        )

    ordered = sorted(specs, key=key)
    chosen: list[ClipSpec] = []
    total = 0
    for s in ordered:
        if len(chosen) >= max_clips:
            break
        est = s.estimated_bytes()
        if budget_bytes and total + est > budget_bytes:
            continue  # skip this one, keep filling with smaller/later clips
        chosen.append(s)
        total += est
    return chosen


def find_metric(report: dict[str, Any], *names: str) -> float | None:
    """Depth-first search for the first finite numeric value under any of `names`.

    Defensive against measure_quality.py's exact nesting (metrics live under a summary
    block); avoids hard-coding a path that a harness revision might move.
    """
    stack: list[Any] = [report]
    while stack:
        node = stack.pop()
        if isinstance(node, dict):
            for k, v in node.items():
                if k in names and isinstance(v, (int, float)) and not isinstance(v, bool):
                    if math.isfinite(float(v)):
                        return float(v)
                stack.append(v)
        elif isinstance(node, list):
            stack.extend(node)
    return None


def label_from_metrics(vmaf_mean: float | None, vmaf_p5: float | None,
                       vmaf_min: float | None, size_ratio: float | None) -> bool | None:
    """Passes the production window floors AND actually smaller? True/False.
    Returns None (unlabeled) if any metric is missing - never guesses a negative."""
    if None in (vmaf_mean, vmaf_p5, vmaf_min, size_ratio):
        return None
    passes = (vmaf_mean >= WINDOW_FLOORS["mean"]
              and vmaf_p5 >= WINDOW_FLOORS["p5"]
              and vmaf_min >= WINDOW_FLOORS["min"])
    smaller = size_ratio < 0.999
    return bool(passes and smaller)


# --------------------------------------------------------------------------------------
# ffmpeg-dependent operations (not unit-tested here; require ffmpeg/ffprobe)
# --------------------------------------------------------------------------------------
def _run(cmd: list[str], timeout: float | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=timeout, check=False)


def have_tool(name: str) -> bool:
    from shutil import which
    return which(name) is not None


def probe_master(ffprobe: str, path: str) -> dict[str, Any] | None:
    cmd = [ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries",
           "stream=width,height,r_frame_rate,codec_name:format=duration",
           "-of", "json", path]
    res = _run(cmd, timeout=60)
    if res.returncode != 0:
        return None
    try:
        data = json.loads(res.stdout)
    except json.JSONDecodeError:
        return None
    streams = data.get("streams") or []
    if not streams:
        return None
    s = streams[0]
    fmt = data.get("format") or {}
    w, h = int(s.get("width") or 0), int(s.get("height") or 0)
    fps = parse_fps(s.get("r_frame_rate"))
    dur = fps and float(fmt.get("duration") or 0.0)
    if not (w and h and fps and dur):
        return None
    return {
        "path": os.path.abspath(path), "stem": Path(path).stem,
        "width": w, "height": h, "fps": fps, "duration_s": float(fmt["duration"]),
        "source_codec_native": s.get("codec_name"),
    }


def encode_clip(ffmpeg: str, spec: ClipSpec, out_path: Path,
                native_res: tuple[int, int]) -> bool:
    br = spec.target_bitrate_bps
    vf = "format=yuv420p"
    if (spec.width, spec.height) != native_res:
        vf = f"scale={spec.width}:{spec.height}:flags=lanczos,format=yuv420p"
    cmd = [ffmpeg, "-hide_banner", "-nostdin", "-y",
           "-ss", f"{spec.start_s:.3f}", "-i", spec.master, "-t", f"{spec.duration_s:.3f}",
           "-map", "0:v:0", "-vf", vf]
    if spec.fps and spec.fps > 0:
        cmd += ["-r", f"{spec.fps:.6f}"]
    if spec.source_codec == "hevc":
        cmd += ["-c:v", "libx265", "-preset", "medium", "-b:v", str(br),
                "-x265-params", f"vbv-maxrate={int(br*1.5)}:vbv-bufsize={int(br*2)}:log-level=error",
                "-tag:v", "hvc1"]
    else:
        cmd += ["-c:v", "libx264", "-preset", "medium", "-b:v", str(br),
                "-maxrate", str(int(br*1.5)), "-bufsize", str(int(br*2)), "-pix_fmt", "yuv420p"]
    if spec.with_audio:
        cmd += ["-map", "0:a:0?", "-c:a", "aac", "-b:a", "128k"]
    else:
        cmd += ["-an"]
    cmd += ["-movflags", "+faststart", str(out_path)]
    res = _run(cmd, timeout=1800)
    return res.returncode == 0 and out_path.exists() and out_path.stat().st_size > 0


def measure_actual(ffprobe: str, path: Path, w: int, h: int, fps: float) -> tuple[int | None, float | None]:
    cmd = [ffprobe, "-v", "error", "-select_streams", "v:0",
           "-show_entries", "stream=bit_rate:format=bit_rate,duration,size",
           "-of", "json", str(path)]
    res = _run(cmd, timeout=60)
    try:
        data = json.loads(res.stdout)
    except json.JSONDecodeError:
        return None, None
    s = (data.get("streams") or [{}])[0]
    fmt = data.get("format") or {}
    br = s.get("bit_rate")
    if br in (None, "N/A"):
        size = fmt.get("size")
        dur = fmt.get("duration")
        if size and dur and float(dur) > 0:
            br = int(float(size) * 8 / float(dur))
    br = int(br) if br not in (None, "N/A") else None
    return br, (bpp_from_bitrate(br, w, h, fps) if br else None)


# --------------------------------------------------------------------------------------
# Manifest IO
# --------------------------------------------------------------------------------------
def write_manifest(path: Path, rows: list[dict[str, Any]], columns: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=columns)
        w.writeheader()
        for r in rows:
            w.writerow({c: r.get(c, "") for c in columns})


def read_manifest(path: Path) -> list[dict[str, Any]]:
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


# --------------------------------------------------------------------------------------
# Subcommands
# --------------------------------------------------------------------------------------
def gather_masters(masters_dir: Path) -> list[Path]:
    return sorted(p for p in masters_dir.rglob("*")
                  if p.is_file() and p.suffix.lower() in VIDEO_EXTS)


def build_config_from_args(args) -> BuildConfig:
    return BuildConfig(
        seg_duration=args.seg_duration,
        segments_per_master=args.segments_per_master,
        resolutions=parse_resolutions(args.resolutions),
        bpp_ladder=(parse_float_list(args.bpp_ladder) if args.bpp_ladder else list(DEFAULT_BPP_LADDER)),
        codecs=[c.strip() for c in args.codecs.split(",") if c.strip()],
        fps_override=args.fps,
        with_audio=args.with_audio,
    )


def cmd_plan(args) -> int:
    cfg = build_config_from_args(args)
    masters_dir = Path(args.masters)
    ffprobe = args.ffprobe or "ffprobe"
    have_probe = have_tool(ffprobe)
    master_files = gather_masters(masters_dir)
    if not master_files:
        print(f"No videos found under {masters_dir} (extensions: {sorted(VIDEO_EXTS)})", file=sys.stderr)
        return 2

    all_specs: list[ClipSpec] = []
    probed = 0
    for mf in master_files:
        if have_probe:
            meta = probe_master(ffprobe, str(mf))
            if not meta:
                continue
            probed += 1
        else:
            # Without ffprobe, assume a representative 1080p30 60s master for estimation.
            meta = {"path": str(mf.resolve()), "stem": mf.stem, "width": 1920,
                    "height": 1080, "fps": 30.0, "duration_s": 60.0}
        all_specs.extend(plan_master(meta, cfg))

    budget = int(args.size_budget_gb * (1024 ** 3))
    chosen = select_within_budget(all_specs, budget, args.max_clips, args.seed)
    est_bytes = sum(s.estimated_bytes() for s in chosen)
    band = sum(1 for s in chosen if s.in_disputed_band())
    print(json.dumps({
        "masters_found": len(master_files),
        "masters_probed": probed if have_probe else None,
        "ffprobe_available": have_probe,
        "clips_possible": len(all_specs),
        "clips_selected": len(chosen),
        "clips_in_disputed_band": band,
        "estimated_total_mb": round(est_bytes / (1024 ** 2), 1),
        "size_budget_gb": args.size_budget_gb,
        "note": None if have_probe else
                "ffprobe not found; used a 1080p30/60s stand-in per master for estimation only",
    }, indent=2))
    return 0


def cmd_build(args) -> int:
    ffmpeg = args.ffmpeg or "ffmpeg"
    ffprobe = args.ffprobe or "ffprobe"
    if not (have_tool(ffmpeg) and have_tool(ffprobe)):
        print(f"ffmpeg/ffprobe required for build (looked for '{ffmpeg}', '{ffprobe}'). "
              f"Run the 'plan' subcommand for a dry estimate.", file=sys.stderr)
        return 3
    cfg = build_config_from_args(args)
    masters_dir = Path(args.masters)
    out_dir = Path(args.out)
    clips_dir = out_dir / "clips"
    clips_dir.mkdir(parents=True, exist_ok=True)

    metas = []
    for mf in gather_masters(masters_dir):
        meta = probe_master(ffprobe, str(mf))
        if meta:
            metas.append(meta)
        else:
            print(f"skip (unprobeable): {mf.name}", file=sys.stderr)
    all_specs: list[ClipSpec] = []
    meta_by_path = {m["path"]: m for m in metas}
    for m in metas:
        all_specs.extend(plan_master(m, cfg))

    budget = int(args.size_budget_gb * (1024 ** 3))
    chosen = select_within_budget(all_specs, budget, args.max_clips, args.seed)

    rows: list[dict[str, Any]] = []
    total = 0
    for i, spec in enumerate(chosen, 1):
        m = meta_by_path[spec.master]
        out_path = clips_dir / f"{spec.clip_id}.mp4"
        ok = encode_clip(ffmpeg, spec, out_path, (m["width"], m["height"]))
        if not ok:
            print(f"[{i}/{len(chosen)}] encode FAILED: {spec.clip_id}", file=sys.stderr)
            continue
        size = out_path.stat().st_size
        actual_br, actual_bpp = measure_actual(ffprobe, out_path, spec.width, spec.height, spec.fps)
        total += size
        rows.append({
            "clip_id": spec.clip_id, "path": str(out_path.relative_to(out_dir)),
            "master": Path(spec.master).name, "seg_index": spec.seg_index,
            "start_s": spec.start_s, "duration_s": spec.duration_s,
            "width": spec.width, "height": spec.height, "fps": round(spec.fps, 3),
            "source_codec": spec.source_codec, "target_bpp": spec.target_bpp,
            "target_bitrate_bps": spec.target_bitrate_bps,
            "actual_video_bitrate_bps": actual_br if actual_br is not None else "",
            "actual_bpp": round(actual_bpp, 5) if actual_bpp is not None else "",
            "size_bytes": size, "with_audio": int(spec.with_audio),
            "in_disputed_band": int(in_disputed_band(actual_bpp if actual_bpp is not None else spec.target_bpp)),
        })
        if i % 25 == 0 or i == len(chosen):
            print(f"[{i}/{len(chosen)}] {len(rows)} ok, {total/(1024**2):.0f} MB", file=sys.stderr)

    write_manifest(out_dir / MANIFEST_NAME, rows, BUILD_COLUMNS)
    print(json.dumps({"clips_built": len(rows), "total_mb": round(total / (1024**2), 1),
                      "manifest": str(out_dir / MANIFEST_NAME)}, indent=2))
    return 0


def cmd_label(args) -> int:
    ffmpeg = args.ffmpeg or "ffmpeg"
    ffprobe = args.ffprobe or "ffprobe"
    measure = Path(args.measure_quality)
    if not measure.exists():
        print(f"measure_quality.py not found at {measure}", file=sys.stderr)
        return 3
    if not (have_tool(ffmpeg) and have_tool(ffprobe)):
        print("ffmpeg/ffprobe (with libvmaf) required for labeling.", file=sys.stderr)
        return 3
    out_dir = Path(args.out)
    manifest = out_dir / MANIFEST_NAME
    rows = read_manifest(manifest)
    ratios = parse_float_list(args.pl_ratios) if args.pl_ratios else list(DEFAULT_PL_RATIOS)
    work = out_dir / "_label_work"
    work.mkdir(parents=True, exist_ok=True)

    columns = BUILD_COLUMNS + [c for c in LABEL_COLUMNS if c not in BUILD_COLUMNS]
    for i, row in enumerate(rows, 1):
        if row.get("compressible") not in (None, "") and not args.relabel:
            continue  # idempotent: skip already-labeled unless --relabel
        clip = out_dir / row["path"]
        src_br = row.get("actual_video_bitrate_bps") or row.get("target_bitrate_bps")
        try:
            src_br = int(float(src_br))
        except (TypeError, ValueError):
            src_br = None
        w, h = int(row["width"]), int(row["height"])
        fps = float(row["fps"])
        codec = row["source_codec"]
        src_size = int(row["size_bytes"])
        per_ratio: list[dict[str, Any]] = []
        best_pass = None
        best_metrics = {"mean": None, "p5": None, "min": None}
        best_class = ""
        note = ""
        if not clip.exists() or not src_br:
            note = "clip missing or bitrate unknown"
        else:
            for ratio in ratios:
                enc = ClipSpec(clip_id=row["clip_id"] + f"_pl{ratio}", master=str(clip),
                               seg_index=0, start_s=0.0, duration_s=float(row["duration_s"]),
                               width=w, height=h, fps=fps, source_codec=codec,
                               target_bpp=0.0, target_bitrate_bps=int(round(ratio * src_br)),
                               with_audio=False)
                pl_out = work / f"{row['clip_id']}_pl{ratio}.mp4"
                if not encode_clip(ffmpeg, enc, pl_out, (w, h)):
                    per_ratio.append({"ratio": ratio, "error": "encode_failed"})
                    continue
                report_path = work / f"{row['clip_id']}_pl{ratio}.vmaf.json"
                mres = _run([sys.executable, str(measure), "--source", str(clip),
                             "--output", str(pl_out), "--pair-id", f"c{i}r{ratio}",
                             "--ffmpeg", ffmpeg, "--report", str(report_path)], timeout=3600)
                if not report_path.exists():
                    per_ratio.append({"ratio": ratio, "error": "measure_failed",
                                      "detail": mres.stderr[-200:] if mres.stderr else ""})
                    continue
                report = json.loads(report_path.read_text(encoding="utf-8"))
                vmean = find_metric(report, "vmaf_mean")
                vp5 = find_metric(report, "vmaf_5pct_low", "vmaf_p5")
                vmin = find_metric(report, "vmaf_min")
                out_bytes = pl_out.stat().st_size
                size_ratio = out_bytes / src_size if src_size else None
                passed = label_from_metrics(vmean, vp5, vmin, size_ratio)
                per_ratio.append({"ratio": ratio, "vmaf_mean": vmean, "vmaf_p5": vp5,
                                  "vmaf_min": vmin, "size_ratio": size_ratio, "passed": passed})
                if passed:  # most aggressive passing ratio = last in ascending-savings order
                    best_pass = ratio if best_pass is None else min(best_pass, ratio)
                    best_metrics = {"mean": vmean, "p5": vp5, "min": vmin}
                    best_class = str(find_report_status(report))
                if not args.keep_work:
                    pl_out.unlink(missing_ok=True)

        measured_any = any("passed" in r for r in per_ratio)
        compressible = ("" if not measured_any else (1 if best_pass is not None else 0))
        if not measured_any and not note:
            note = "all ratios failed to encode or measure; left unlabeled (not a quality-negative)"
        row.update({
            "ratios_tested": json.dumps(per_ratio),
            "best_pass_ratio": best_pass if best_pass is not None else "",
            "pl_vmaf_mean": best_metrics["mean"] if best_metrics["mean"] is not None else "",
            "pl_vmaf_p5": best_metrics["p5"] if best_metrics["p5"] is not None else "",
            "pl_vmaf_min": best_metrics["min"] if best_metrics["min"] is not None else "",
            "pl_output_bytes": "", "pl_size_ratio": "",
            "pl_classification": best_class, "compressible": compressible, "label_note": note,
        })
        if i % 10 == 0 or i == len(rows):
            print(f"[{i}/{len(rows)}] labeled", file=sys.stderr)

    write_manifest(manifest, rows, columns)
    labeled = sum(1 for r in rows if r.get("compressible") in (0, 1, "0", "1"))
    wins = sum(1 for r in rows if str(r.get("compressible")) == "1")
    print(json.dumps({"rows": len(rows), "labeled": labeled, "compressible_wins": wins,
                      "unlabeled_failures": len(rows) - labeled}, indent=2))
    return 0


def find_report_status(report: dict[str, Any]) -> Any:
    for key in ("classification", "status"):
        if isinstance(report.get(key), str):
            return report[key]
    return ""


# --------------------------------------------------------------------------------------
def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)

    def add_common(sp):
        sp.add_argument("--seg-duration", type=float, default=4.0)
        sp.add_argument("--segments-per-master", type=int, default=3)
        sp.add_argument("--resolutions", default="1280x720", help="'keep' or 'WxH,WxH'")
        sp.add_argument("--bpp-ladder", default="", help="comma list; default emphasizes [0.03,0.069)")
        sp.add_argument("--codecs", default="hevc,h264")
        sp.add_argument("--fps", type=float, default=0.0, help="0 keeps master fps")
        sp.add_argument("--with-audio", action="store_true")
        sp.add_argument("--size-budget-gb", type=float, default=4.0)
        sp.add_argument("--max-clips", type=int, default=2000)
        sp.add_argument("--seed", type=int, default=20260720)
        sp.add_argument("--ffmpeg", default="ffmpeg")
        sp.add_argument("--ffprobe", default=None)

    sp_plan = sub.add_parser("plan", help="dry-run estimate (no encoding)")
    sp_plan.add_argument("--masters", required=True)
    add_common(sp_plan)
    sp_plan.set_defaults(func=cmd_plan)

    sp_build = sub.add_parser("build", help="cut + re-encode source clips")
    sp_build.add_argument("--masters", required=True)
    sp_build.add_argument("--out", required=True)
    add_common(sp_build)
    sp_build.set_defaults(func=cmd_build)

    sp_label = sub.add_parser("label", help="attach offline VMAF ground-truth labels")
    sp_label.add_argument("--out", required=True)
    sp_label.add_argument("--measure-quality",
                          default=str(Path(__file__).resolve().parents[3]
                                      / "scripts" / "diagnostics" / "measure_quality.py"),
                          help="path to scripts/diagnostics/measure_quality.py")
    sp_label.add_argument("--pl-ratios", default="", help="comma list; default 0.97,0.95,0.90")
    sp_label.add_argument("--relabel", action="store_true", help="re-label already-labeled clips")
    sp_label.add_argument("--keep-work", action="store_true", help="keep intermediate PL encodes")
    sp_label.add_argument("--ffmpeg", default="ffmpeg")
    sp_label.add_argument("--ffprobe", default=None)
    sp_label.set_defaults(func=cmd_label)
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
