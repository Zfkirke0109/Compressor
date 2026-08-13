#!/usr/bin/env python3
"""Measure an HDR source/output pair with BT.2124 DeltaE_ITP and PU21-PSNR.

The extraction layer for `hdr_metrics.py`. Decodes matched windows of two HDR files to
linear BT.2020 RGB and reports per-window statistics.

Fail-closed, in the same spirit as `scripts/diagnostics/measure_quality.py`:

  * Both inputs must be PQ (SMPTE ST 2084). HLG is REJECTED rather than measured — its EOTF is
    a different curve, and applying the PQ curve to HLG would produce confident nonsense.
  * Geometry, transfer, and primaries must match between the two files. Comparing across
    colour spaces measures the conversion, not the encode.
  * SDR input is rejected: use `measure_quality.py`, which has a validated VMAF path.
  * Any decode shortfall fails the window instead of scoring the frames that did arrive.

Nothing here is rescaled or frame-rate converted, matching the contract `measure_quality.py`
states for the SDR path.

RESEARCH TOOLING. It produces evidence; it authorizes nothing. No HDR clip may be re-encoded
by Smart Perceptually Lossless on the strength of this output — see README.md for the five
conditions that must hold first.

Usage:
    python3 hdr_pair_measure.py --ref SOURCE.mp4 --dist OUTPUT.mp4 [--json out.json]
    python3 hdr_pair_measure.py --self-test        # synthesizes a PQ pair and measures it
"""

from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, asdict
from typing import Any, Iterator

try:
    import numpy as np
except ImportError as exc:  # pragma: no cover - environment guard
    raise SystemExit("numpy is required: pip install numpy") from exc

from hdr_metrics import (
    bt2020_luminance,
    delta_e_itp,
    pq_eotf,
    pu21_psnr,
    summarize_delta_e,
)

PQ_TRANSFERS = {"smpte2084"}
HLG_TRANSFERS = {"arib-std-b67"}
BT2020_PRIMARIES = {"bt2020"}

# 16-bit full-scale, the range of an rgb48le sample.
RGB48_MAX = 65535.0


class HarnessFailure(RuntimeError):
    """A failure safe to surface in a report."""


@dataclass(frozen=True)
class VideoMeta:
    width: int
    height: int
    color_transfer: str
    color_primaries: str
    color_space: str
    duration_s: float

    def geometry(self) -> tuple[int, int]:
        return (self.width, self.height)


@dataclass(frozen=True)
class Window:
    """A measurement window, in seconds on both timelines."""
    start_s: float
    duration_s: float


def tool_path(name: str, override: str | None = None) -> str:
    """Locate ffmpeg/ffprobe, preferring an explicit override, then PATH.

    Falls back to the imageio-ffmpeg bundled static build for ffmpeg only, which is how this
    runs in environments without a system ffmpeg. ffprobe has no such fallback.
    """
    if override:
        return override
    found = shutil.which(name)
    if found:
        return found
    if name == "ffmpeg":
        try:
            import imageio_ffmpeg

            return imageio_ffmpeg.get_ffmpeg_exe()
        except Exception:  # noqa: BLE001 - optional dependency
            pass
    raise HarnessFailure(f"{name} not found on PATH")


def probe(ffprobe: str, path: str) -> VideoMeta:
    """Read colour metadata for the first video stream. Unknown fields stay literal."""
    cmd = [
        ffprobe, "-v", "error", "-select_streams", "v:0",
        "-show_entries",
        "stream=width,height,color_transfer,color_primaries,color_space,duration",
        "-show_entries", "format=duration",
        "-of", "json", path,
    ]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=120, check=True).stdout
    except subprocess.CalledProcessError as exc:
        raise HarnessFailure(f"ffprobe failed for input: {exc.stderr.strip()[:200]}") from exc
    data = json.loads(out)
    streams = data.get("streams") or []
    if not streams:
        raise HarnessFailure("no video stream found")
    s = streams[0]
    duration = s.get("duration") or (data.get("format") or {}).get("duration") or "0"
    try:
        duration_s = float(duration)
    except (TypeError, ValueError):
        duration_s = 0.0
    return VideoMeta(
        width=int(s.get("width") or 0),
        height=int(s.get("height") or 0),
        color_transfer=str(s.get("color_transfer") or "unknown"),
        color_primaries=str(s.get("color_primaries") or "unknown"),
        color_space=str(s.get("color_space") or "unknown"),
        duration_s=duration_s,
    )


def validate_pair(ref: VideoMeta, dist: VideoMeta) -> None:
    """Fail closed on anything that would make the comparison meaningless."""
    if ref.width <= 0 or ref.height <= 0:
        raise HarnessFailure("reference geometry unreadable")
    if ref.geometry() != dist.geometry():
        raise HarnessFailure(
            f"geometry mismatch {ref.width}x{ref.height} vs {dist.width}x{dist.height}; "
            "this harness never rescales"
        )
    for label, meta in (("reference", ref), ("distorted", dist)):
        if meta.color_transfer in HLG_TRANSFERS:
            raise HarnessFailure(
                f"{label} is HLG (arib-std-b67). HLG uses a different EOTF and is not "
                "implemented; applying the PQ curve to it would be silently wrong."
            )
        if meta.color_transfer not in PQ_TRANSFERS:
            raise HarnessFailure(
                f"{label} transfer is '{meta.color_transfer}', not PQ (smpte2084). "
                "For SDR use scripts/diagnostics/measure_quality.py, which has a validated "
                "VMAF path."
            )
    if ref.color_transfer != dist.color_transfer:
        raise HarnessFailure("transfer characteristics differ between the two files")
    if ref.color_primaries != dist.color_primaries:
        raise HarnessFailure("colour primaries differ between the two files")


def plan_windows(duration_s: float, count: int = 3, window_s: float = 1.2) -> list[Window]:
    """Windows away from the very start/end, mirroring QualityProbePolicy.probeWindows.

    Codec warm-up and tail padding are unrepresentative, so the app samples at 20/50/80% of
    the clip. Keeping the same shape means offline numbers and on-device numbers describe the
    same parts of a clip.
    """
    if duration_s < 2.0:
        return []
    if duration_s < 10.0:
        return [Window(max(0.0, (duration_s - window_s) / 2.0), window_s)]
    out = []
    for fraction in (0.20, 0.50, 0.80):
        start = min(duration_s * fraction, max(0.0, duration_s - window_s))
        out.append(Window(start, window_s))
    return out[:count]


def decode_cmd(ffmpeg: str, path: str, window: Window, meta: VideoMeta) -> list[str]:
    """ffmpeg args decoding one window to raw PQ-encoded RGB48LE.

    `scale` applies only the YCbCr->RGB matrix; the transfer function is left untouched, so the
    samples are still PQ-encoded and this code applies the EOTF itself. No `zscale` transfer
    conversion and no tone map — either would fold an assumption into the measurement.

    Note the matrix spelling: the scale filter takes `bt2020`, NOT `bt2020nc`. `bt2020nc` is the
    `-colorspace`/ffprobe name for the same matrix and is rejected here with an unhelpful
    "Undefined constant" error. The self-test exists partly to keep catching that.
    """
    return [
        ffmpeg, "-v", "error", "-nostdin",
        "-ss", f"{window.start_s:.6f}",
        "-t", f"{window.duration_s:.6f}",
        "-i", path,
        "-vf", "scale=in_color_matrix=bt2020:out_color_matrix=bt2020,format=rgb48le",
        "-f", "rawvideo", "-pix_fmt", "rgb48le", "-",
    ]


def decode_window(ffmpeg: str, path: str, window: Window, meta: VideoMeta) -> Iterator["np.ndarray"]:
    """Yield each frame of a window as linear BT.2020 RGB in cd/m^2, shape (h, w, 3)."""
    frame_bytes = meta.width * meta.height * 3 * 2  # 3 channels, 16-bit
    proc = subprocess.Popen(
        decode_cmd(ffmpeg, path, window, meta),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    try:
        while True:
            buf = proc.stdout.read(frame_bytes)
            if not buf:
                break
            if len(buf) < frame_bytes:
                raise HarnessFailure("truncated frame from decoder")
            arr = np.frombuffer(buf, dtype="<u2").reshape(meta.height, meta.width, 3)
            # PQ-encoded [0,1] -> absolute luminance in cd/m^2.
            yield pq_eotf(arr.astype(np.float64) / RGB48_MAX)
    finally:
        if proc.stdout:
            proc.stdout.close()
        err = proc.stderr.read().decode("utf-8", "replace") if proc.stderr else ""
        proc.wait()
        if proc.returncode not in (0, None) and err.strip():
            # Surfaced by the caller only when the window also produced too few frames.
            setattr(decode_window, "last_error", err.strip()[:300])


def measure_window(
    ffmpeg: str, ref_path: str, dist_path: str, window: Window, meta: VideoMeta
) -> dict[str, Any]:
    """DeltaE_ITP and PU21-PSNR for one window, frame-paired in decode order."""
    ref_frames = decode_window(ffmpeg, ref_path, window, meta)
    dist_frames = decode_window(ffmpeg, dist_path, window, meta)

    per_frame_mean: list[float] = []
    per_frame_max: list[float] = []
    per_frame_p999: list[float] = []
    per_frame_over1: list[float] = []
    psnrs: list[float] = []
    compared = 0

    for ref, dist in zip(ref_frames, dist_frames):
        field = delta_e_itp(ref, dist)
        stats = summarize_delta_e(field)
        per_frame_mean.append(stats["mean"])
        per_frame_max.append(stats["max"])
        per_frame_p999.append(stats["p999"])
        per_frame_over1.append(stats["fraction_over_1_jnd"])
        psnr = pu21_psnr(bt2020_luminance(ref), bt2020_luminance(dist))
        if math.isfinite(psnr):
            psnrs.append(psnr)
        compared += 1

    if compared == 0:
        raise HarnessFailure(
            "no comparable frames decoded"
            + (f" ({getattr(decode_window, 'last_error', '')})" if getattr(decode_window, "last_error", "") else "")
        )

    return {
        "start_s": round(window.start_s, 3),
        "duration_s": round(window.duration_s, 3),
        "frames": compared,
        # Worst frame in the window drives the verdict: banding and hue shifts are localized in
        # time as well as space, so a window mean would hide the frames that actually matter.
        "delta_e_itp": {
            "mean_of_frame_means": float(np.mean(per_frame_mean)),
            "worst_frame_mean": float(np.max(per_frame_mean)),
            "worst_frame_p999": float(np.max(per_frame_p999)),
            "worst_frame_max": float(np.max(per_frame_max)),
            "worst_frame_fraction_over_1_jnd": float(np.max(per_frame_over1)),
        },
        "pu21_psnr_db": {
            "mean": float(np.mean(psnrs)) if psnrs else None,
            "min": float(np.min(psnrs)) if psnrs else None,
            "identical_frames": compared - len(psnrs),
        },
    }


def measure_pair(
    ref_path: str, dist_path: str, ffmpeg: str, ffprobe: str, window_count: int = 3
) -> dict[str, Any]:
    ref_meta = probe(ffprobe, ref_path)
    dist_meta = probe(ffprobe, dist_path)
    validate_pair(ref_meta, dist_meta)

    duration = min(ref_meta.duration_s, dist_meta.duration_s)
    windows = plan_windows(duration, count=window_count)
    if not windows:
        raise HarnessFailure(f"clip too short to sample honestly ({duration:.2f}s)")

    results = [measure_window(ffmpeg, ref_path, dist_path, w, ref_meta) for w in windows]
    worst = max(r["delta_e_itp"]["worst_frame_p999"] for r in results)
    return {
        "schema": "hdr_pair_measure/1",
        "geometry": f"{ref_meta.width}x{ref_meta.height}",
        "transfer": ref_meta.color_transfer,
        "primaries": ref_meta.color_primaries,
        "windows": results,
        "worst_window_p999_delta_e_itp": worst,
        # Explicitly NOT a verdict. No calibrated HDR threshold exists yet; see README.md.
        "verdict": None,
        "note": (
            "Research measurement only. DeltaE_ITP is in JND units (1.0 = one just-noticeable "
            "difference), but no calibrated acceptance threshold exists for this content yet, "
            "so this report deliberately carries no pass/fail."
        ),
    }


# --------------------------------------------------------------------------------------
# Self-test: synthesize a PQ/BT.2020 pair and measure it end to end.
# --------------------------------------------------------------------------------------

def _synthesize_pq_clip(ffmpeg: str, path: str, crf: int, seconds: int = 12) -> None:
    """A smooth BT.2020/PQ gradient clip — the content banding shows up in first."""
    subprocess.run(
        [
            ffmpeg, "-v", "error", "-y",
            "-f", "lavfi",
            "-i", f"gradients=size=320x240:rate=10:duration={seconds}:type=linear",
            "-c:v", "libx265", "-crf", str(crf), "-preset", "ultrafast",
            "-pix_fmt", "yuv420p10le",
            "-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc",
            "-x265-params", "log-level=none",
            "-t", str(seconds), path,
        ],
        check=True,
        capture_output=True,
        timeout=300,
    )


def _self_test(ffmpeg: str) -> int:
    print("Synthesizing a PQ/BT.2020 pair (no ffprobe needed for this path)...")
    tmp = tempfile.mkdtemp(prefix="hdrpair_")
    ref = os.path.join(tmp, "ref.mp4")
    dist = os.path.join(tmp, "dist.mp4")
    try:
        _synthesize_pq_clip(ffmpeg, ref, crf=10)
        _synthesize_pq_clip(ffmpeg, dist, crf=40)  # deliberately much worse
        meta = VideoMeta(320, 240, "smpte2084", "bt2020", "bt2020nc", 12.0)

        window = Window(4.0, 1.0)
        identical = measure_window(ffmpeg, ref, ref, window, meta)
        degraded = measure_window(ffmpeg, ref, dist, window, meta)

        print(f"  frames compared: {identical['frames']} (ref vs ref), {degraded['frames']} (ref vs dist)")
        same = identical["delta_e_itp"]["worst_frame_max"]
        worse = degraded["delta_e_itp"]["worst_frame_mean"]
        print(f"  ref vs ref   : worst-frame max DeltaE_ITP = {same:.6f}  (expect 0)")
        print(f"  ref vs crf40 : worst-frame mean DeltaE_ITP = {worse:.3f} JND")
        print(f"                 PU21-PSNR min = {degraded['pu21_psnr_db']['min']}")

        ok = True
        if identical["frames"] == 0:
            print("  FAIL: decoded no frames"); ok = False
        if same != 0.0:
            print(f"  FAIL: identical inputs must score exactly 0, got {same}"); ok = False
        if not (worse > same):
            print("  FAIL: a visibly worse encode must score higher than an identical one"); ok = False
        print("\nSELF-TEST", "PASSED" if ok else "FAILED")
        return 0 if ok else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ref", help="reference (source) file")
    ap.add_argument("--dist", help="distorted (compressor output) file")
    ap.add_argument("--json", help="write the report here")
    ap.add_argument("--windows", type=int, default=3)
    ap.add_argument("--ffmpeg")
    ap.add_argument("--ffprobe")
    ap.add_argument("--self-test", action="store_true", help="synthesize a PQ pair and verify the pipeline")
    args = ap.parse_args()

    try:
        ffmpeg = tool_path("ffmpeg", args.ffmpeg)
        if args.self_test:
            return _self_test(ffmpeg)
        if not args.ref or not args.dist:
            ap.error("--ref and --dist are required unless --self-test is given")
        ffprobe = tool_path("ffprobe", args.ffprobe)
        report = measure_pair(args.ref, args.dist, ffmpeg, ffprobe, args.windows)
    except HarnessFailure as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    text = json.dumps(report, indent=2)
    if args.json:
        with open(args.json, "w") as fh:
            fh.write(text + "\n")
        print(f"wrote {args.json}")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
