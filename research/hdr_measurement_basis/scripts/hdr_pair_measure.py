#!/usr/bin/env python3
"""Measure an HDR source/output pair with BT.2124 DeltaE_ITP and PU21-PSNR.

The extraction layer for `hdr_metrics.py`. Decodes matched windows of two HDR files to
linear BT.2020 RGB and reports per-window statistics.

Fail-closed, in the same spirit as `scripts/diagnostics/measure_quality.py`:

  * Both inputs must be PQ (SMPTE ST 2084). HLG is REJECTED rather than measured — its EOTF is
    a different curve, and applying the PQ curve to HLG would produce confident nonsense.
  * Colour interpretation must be KNOWN, SUPPORTED and IDENTICAL on both sides: BT.2020
    primaries, the BT.2020 non-constant-luminance matrix, a declared range (tv or pc) and a bit
    depth of at least 10. "unknown" matching "unknown" is not a match (b177 F7): the decode
    would silently assume BT.2020 limited range anyway.
  * Geometry and frame rate must be known and match; durations must agree within one frame, so
    a truncated output cannot be measured up to its own end.
  * SDR input is rejected: use `measure_quality.py`, which has a validated VMAF path.
  * Frames are paired by presentation timestamp, never by position (`zip`). A frame on either
    side without a partner, a non-monotonic timeline, a window short of its expected frame count,
    or any decoder error — including one after valid frames — fails the window instead of
    scoring the frames that did arrive.

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
    pq_inverse_eotf,
    pu21_psnr,
    summarize_delta_e,
)

PQ_TRANSFERS = {"smpte2084"}
HLG_TRANSFERS = {"arib-std-b67"}
BT2020_PRIMARIES = {"bt2020"}
# ffprobe matrix name -> (Kr, Kb). Only BT.2020 non-constant luminance is supported: the
# constant-luminance variant (bt2020c) needs a different, non-linear decode.
SUPPORTED_MATRICES = {"bt2020nc": (0.2627, 0.0593)}
SUPPORTED_RANGES = {"tv", "pc"}
MIN_BIT_DEPTH = 10
# Planar layouts decoded without any conversion: pix_fmt -> (chroma x subsampling, y subsampling, bits).
SUPPORTED_PIX_FMTS = {
    "yuv420p10le": (2, 2, 10), "yuv422p10le": (2, 1, 10), "yuv444p10le": (1, 1, 10),
    "yuv420p12le": (2, 2, 12), "yuv422p12le": (2, 1, 12), "yuv444p12le": (1, 1, 12),
}



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
    # Declared by ffprobe; "unknown"/0 when the file does not say (and then validation fails).
    color_range: str = "unknown"
    bit_depth: int = 0
    fps: float = 0.0
    start_time_s: float = 0.0
    pix_fmt: str = "yuv420p10le"

    def geometry(self) -> tuple[int, int]:
        return (self.width, self.height)


@dataclass(frozen=True)
class DecodedFrame:
    """One decoded frame: its presentation time relative to the file's start, and linear RGB."""
    pts_s: float
    rgb: "np.ndarray"


@dataclass
class Coverage:
    """How two decoded windows paired up. Complete only when every frame found its partner."""
    matched: int = 0
    ref_unmatched: list = None
    dist_unmatched: list = None

    def __post_init__(self):
        self.ref_unmatched = [] if self.ref_unmatched is None else self.ref_unmatched
        self.dist_unmatched = [] if self.dist_unmatched is None else self.dist_unmatched

    @property
    def complete(self) -> bool:
        return self.matched > 0 and not self.ref_unmatched and not self.dist_unmatched


def transfer_family(transfer: str) -> str:
    if transfer in PQ_TRANSFERS:
        return "PQ"
    if transfer in HLG_TRANSFERS:
        return "HLG"
    return "other"


def _bit_depth(pix_fmt: str, bits_per_raw_sample: Any) -> int:
    """Bits per component from ffprobe: bits_per_raw_sample when given, else the pix_fmt name."""
    try:
        bits = int(bits_per_raw_sample)
        if bits > 0:
            return bits
    except (TypeError, ValueError):
        pass
    import re

    m = re.search(r"p(\d+)(le|be)?$", pix_fmt or "")
    if m:
        return int(m.group(1))
    if pix_fmt and re.fullmatch(r"(yuv|yuvj|gray|nv)\w*", pix_fmt) and not re.search(r"\d{2}", pix_fmt[3:]):
        return 8
    return 0


def _rate(text: Any) -> float:
    try:
        num, _, den = str(text).partition("/")
        value = float(num) / float(den or 1)
        return value if math.isfinite(value) and value > 0 else 0.0
    except (TypeError, ValueError, ZeroDivisionError):
        return 0.0


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
        "stream=width,height,color_transfer,color_primaries,color_space,color_range,pix_fmt,"
        "bits_per_raw_sample,avg_frame_rate,r_frame_rate,start_time,duration",
        "-show_entries", "format=duration,start_time",
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
    start = s.get("start_time") or (data.get("format") or {}).get("start_time") or "0"
    try:
        start_s = float(start)
    except (TypeError, ValueError):
        start_s = 0.0
    return VideoMeta(
        width=int(s.get("width") or 0),
        height=int(s.get("height") or 0),
        color_transfer=str(s.get("color_transfer") or "unknown"),
        color_primaries=str(s.get("color_primaries") or "unknown"),
        color_space=str(s.get("color_space") or "unknown"),
        duration_s=duration_s,
        color_range=str(s.get("color_range") or "unknown"),
        bit_depth=_bit_depth(str(s.get("pix_fmt") or ""), s.get("bits_per_raw_sample")),
        fps=_rate(s.get("avg_frame_rate")) or _rate(s.get("r_frame_rate")),
        start_time_s=start_s,
        pix_fmt=str(s.get("pix_fmt") or "unknown"),
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
    if ref.color_primaries not in BT2020_PRIMARIES:
        raise HarnessFailure(
            f"colour primaries '{ref.color_primaries}' are unknown or unsupported; the decode "
            "assumes BT.2020 and would silently measure a different colour space"
        )
    if ref.color_space != dist.color_space:
        raise HarnessFailure(f"colour matrix differs ({ref.color_space} vs {dist.color_space})")
    if ref.color_space not in SUPPORTED_MATRICES:
        raise HarnessFailure(
            f"colour matrix '{ref.color_space}' is unknown or unsupported (only BT.2020 "
            "non-constant luminance, bt2020nc, is implemented)"
        )
    if ref.color_range != dist.color_range:
        raise HarnessFailure(f"colour range differs ({ref.color_range} vs {dist.color_range})")
    if ref.color_range not in SUPPORTED_RANGES:
        raise HarnessFailure(
            f"colour range '{ref.color_range}' is not declared; limited and full range decode "
            "to different values and neither may be assumed"
        )
    if ref.bit_depth != dist.bit_depth or ref.bit_depth < MIN_BIT_DEPTH:
        raise HarnessFailure(
            f"bit depth {ref.bit_depth} vs {dist.bit_depth}: both must be known, equal and at least "
            f"{MIN_BIT_DEPTH} (an 8-bit HDR output is itself a loss this harness does not measure)"
        )
    for label, meta in (("reference", ref), ("distorted", dist)):
        layout = SUPPORTED_PIX_FMTS.get(meta.pix_fmt)
        if layout is None or layout[2] != meta.bit_depth:
            raise HarnessFailure(
                f"{label} pixel format '{meta.pix_fmt}' is not a supported planar 10/12-bit Y'CbCr "
                "layout; converting it first would put a scaler's rounding into the measurement"
            )
    if ref.fps <= 0 or dist.fps <= 0:
        raise HarnessFailure("frame rate unknown: timestamps cannot be paired within half a frame")
    if abs(ref.fps - dist.fps) > 1e-3 * ref.fps:
        raise HarnessFailure(f"frame rate differs ({ref.fps:.3f} vs {dist.fps:.3f}); this harness never retimes")


def check_durations(ref: VideoMeta, dist: VideoMeta) -> None:
    """The two files must end together (within one frame): a short output has an unmeasured tail."""
    frame = 1.0 / ref.fps if ref.fps > 0 else 0.0
    if ref.duration_s <= 0 or dist.duration_s <= 0:
        raise HarnessFailure("duration unknown on one side; coverage of the whole clip cannot be checked")
    if abs(ref.duration_s - dist.duration_s) > frame * 1.5 + 1e-6:
        raise HarnessFailure(
            f"duration mismatch {ref.duration_s:.3f}s vs {dist.duration_s:.3f}s; the part of the longer "
            "file past the shorter one's end would go unmeasured"
        )


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
    """ffmpeg args decoding one window to its NATIVE planar Y'CbCr, with per-frame timestamps.

    No scaler touches the samples. The Y'CbCr->R'G'B' matrix and the range expansion are applied
    in [yuv_to_rgb_prime] with the exact BT.2100 formulas, because swscale's limited-range
    expansion at 10 bits is not exact: in the b177 known-vector test it decoded code 940
    (limited-range peak) as 0.99615 instead of 1.0 and code 502 as 0.49807 instead of 0.5, with or
    without accurate_rnd/bitexact. The transfer function is untouched here too: this code applies
    the PQ EOTF itself. No `zscale` transfer conversion and no tone map.

    Timestamps: `-copyts -start_at_zero` keeps each frame's own presentation time, relative to
    the file's start, and `showinfo` prints it to stderr (one line per frame, in output order).
    `-xerror` makes a decoding error end the process with a failure status.
    """
    return [
        ffmpeg, "-hide_banner", "-nostats", "-loglevel", "level+info", "-nostdin", "-xerror",
        "-copyts", "-start_at_zero",
        "-ss", f"{window.start_s:.6f}",
        "-t", f"{window.duration_s:.6f}",
        "-i", path,
        "-vf", "showinfo",
        "-fps_mode", "passthrough",
        "-f", "rawvideo", "-pix_fmt", meta.pix_fmt, "-",
    ]


def yuv_to_rgb_prime(y: "np.ndarray", cb: "np.ndarray", cr: "np.ndarray", meta: VideoMeta) -> "np.ndarray":
    """Integer Y'CbCr code values -> non-linear R'G'B' in [0, 1], shape (h, w, 3).

    [cb]/[cr] are already at luma resolution. BT.2100 Table 9 quantisation: limited ("tv") range
    Y' = (D - 16*2^(n-8)) / (219*2^(n-8)), C' = (D - 128*2^(n-8)) / (224*2^(n-8)); full ("pc")
    range Y' = D / (2^n - 1), C' = (D - 2^(n-1)) / (2^n - 1). Then the non-constant-luminance
    matrix with Kr/Kb of the declared colour space. Values outside [0, 1] are clipped, as a display would.
    """
    kr, kb = SUPPORTED_MATRICES[meta.color_space]
    kg = 1.0 - kr - kb
    n = meta.bit_depth
    yf = y.astype(np.float64)
    cbf = cb.astype(np.float64)
    crf = cr.astype(np.float64)
    if meta.color_range == "tv":
        step = float(1 << (n - 8))
        yp = (yf - 16.0 * step) / (219.0 * step)
        cbp = (cbf - 128.0 * step) / (224.0 * step)
        crp = (crf - 128.0 * step) / (224.0 * step)
    elif meta.color_range == "pc":
        top = float((1 << n) - 1)
        half = float(1 << (n - 1))
        yp = yf / top
        cbp = (cbf - half) / top
        crp = (crf - half) / top
    else:
        raise HarnessFailure(f"colour range '{meta.color_range}' cannot be decoded")
    r = yp + 2.0 * (1.0 - kr) * crp
    b = yp + 2.0 * (1.0 - kb) * cbp
    g = (yp - kr * r - kb * b) / kg
    return np.clip(np.stack([r, g, b], axis=-1), 0.0, 1.0)


def _upsample(plane: "np.ndarray", sx: int, sy: int, height: int, width: int) -> "np.ndarray":
    """Chroma to luma resolution by sample replication (identical on both sides of a pair)."""
    if sx == 1 and sy == 1:
        return plane
    return np.repeat(np.repeat(plane, sy, axis=0), sx, axis=1)[:height, :width]


def _showinfo_pts(line: str) -> float | None:
    if "Parsed_showinfo" not in line or "pts_time:" not in line:
        return None
    try:
        return float(line.split("pts_time:", 1)[1].split()[0])
    except (IndexError, ValueError):
        return None


def decode_window(ffmpeg: str, path: str, window: Window, meta: VideoMeta) -> Iterator[DecodedFrame]:
    """Yield each frame of a window with its timestamp, as linear BT.2020 RGB in cd/m^2.

    Raises HarnessFailure on any decoder error, including one after valid frames were yielded:
    the consumer must not treat a window the decoder abandoned as a finished measurement.
    """
    import queue
    import threading

    sx, sy, _bits = SUPPORTED_PIX_FMTS[meta.pix_fmt]
    w, hgt = meta.width, meta.height
    cw, ch = -(-w // sx), -(-hgt // sy)
    luma_bytes = w * hgt * 2
    chroma_bytes = cw * ch * 2
    frame_bytes = luma_bytes + 2 * chroma_bytes
    proc = subprocess.Popen(
        decode_cmd(ffmpeg, path, window, meta),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    stamps: "queue.Queue[float | None]" = queue.Queue()
    errors: list[str] = []

    def drain_stderr() -> None:
        for raw in proc.stderr:
            line = raw.decode("utf-8", "replace").rstrip()
            pts = _showinfo_pts(line)
            if pts is not None:
                stamps.put(pts)
            elif "[error]" in line or "[fatal]" in line:
                errors.append(line[:300])
        stamps.put(None)

    reader = threading.Thread(target=drain_stderr, daemon=True)
    reader.start()
    finished = False
    try:
        while True:
            buf = proc.stdout.read(frame_bytes)
            if not buf:
                break
            if len(buf) < frame_bytes:
                raise HarnessFailure("truncated frame from decoder")
            try:
                pts = stamps.get(timeout=60)
            except queue.Empty as exc:
                raise HarnessFailure("decoded frame arrived without a timestamp") from exc
            if pts is None:
                raise HarnessFailure("decoded frame arrived without a timestamp")
            raw = np.frombuffer(buf, dtype="<u2")
            y = raw[: w * hgt].reshape(hgt, w)
            cb = raw[w * hgt: w * hgt + cw * ch].reshape(ch, cw)
            cr = raw[w * hgt + cw * ch:].reshape(ch, cw)
            rgb_prime = yuv_to_rgb_prime(y, _upsample(cb, sx, sy, hgt, w), _upsample(cr, sx, sy, hgt, w), meta)
            # PQ-encoded [0,1] -> absolute luminance in cd/m^2.
            yield DecodedFrame(pts, pq_eotf(rgb_prime))
        finished = True
    finally:
        if proc.stdout:
            proc.stdout.close()
        if not finished:
            proc.kill()
        proc.wait()
        reader.join(timeout=10)
    if proc.returncode != 0 or errors:
        detail = "; ".join(errors[-3:]) or f"exit status {proc.returncode}"
        raise HarnessFailure(f"decoder failed: {detail}")


def pair_by_pts(ref_frames, dist_frames, tolerance_s: float, coverage: Coverage):
    """Merge-join two timestamped frame streams, yielding (ref, dist) pairs within tolerance.

    Frames without a partner are recorded in [coverage], never compared with a neighbour; `zip`
    used to pair by position, so one dropped frame shifted every comparison after it and a short
    side silently truncated the window. Both streams are drained to the end, so a decoder error
    in either surfaces here. Timestamps must increase strictly.
    """
    def checked(frames, label):
        last = None
        for f in frames:
            pts = getattr(f, "pts_s", None)
            if pts is None:
                raise HarnessFailure(f"{label} frame carries no timestamp; frames cannot be paired by PTS")
            if last is not None and pts <= last:
                raise HarnessFailure(f"{label} timestamps not monotonic ({last} then {pts})")
            last = pts
            yield f

    ref_it = checked(ref_frames, "reference")
    dist_it = checked(dist_frames, "distorted")
    r = next(ref_it, None)
    d = next(dist_it, None)
    while r is not None and d is not None:
        diff = d.pts_s - r.pts_s
        if abs(diff) <= tolerance_s:
            coverage.matched += 1
            yield r, d
            r = next(ref_it, None)
            d = next(dist_it, None)
        elif diff > 0:
            coverage.ref_unmatched.append(r.pts_s)
            r = next(ref_it, None)
        else:
            coverage.dist_unmatched.append(d.pts_s)
            d = next(dist_it, None)
    while r is not None:
        coverage.ref_unmatched.append(r.pts_s)
        r = next(ref_it, None)
    while d is not None:
        coverage.dist_unmatched.append(d.pts_s)
        d = next(dist_it, None)


def expected_frames(window: Window, fps: float) -> int:
    """Frames a complete decode of [window] holds: presentation times start_s + k/fps inside it."""
    return max(0, int(math.floor(window.duration_s * fps + 1e-6)))


def measure_window(
    ffmpeg: str, ref_path: str, dist_path: str, window: Window, meta: VideoMeta
) -> dict[str, Any]:
    """DeltaE_ITP and PU21-PSNR for one window, frame-paired by presentation timestamp.

    Fails (HarnessFailure) unless every frame on both sides found its partner and the reference
    held the frames the window should hold (within one frame at either edge).
    """
    if meta.fps <= 0:
        raise HarnessFailure("frame rate unknown: timestamps cannot be paired")
    ref_frames = decode_window(ffmpeg, ref_path, window, meta)
    dist_frames = decode_window(ffmpeg, dist_path, window, meta)
    coverage = Coverage()

    per_frame_mean: list[float] = []
    per_frame_max: list[float] = []
    per_frame_p999: list[float] = []
    per_frame_over1: list[float] = []
    psnrs: list[float] = []
    compared = 0

    for ref_f, dist_f in pair_by_pts(ref_frames, dist_frames, 0.5 / meta.fps, coverage):
        ref, dist = ref_f.rgb, dist_f.rgb
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

    expected = expected_frames(window, meta.fps)
    ref_total = coverage.matched + len(coverage.ref_unmatched)
    if not coverage.complete:
        raise HarnessFailure(
            f"incomplete coverage in window at {window.start_s:.3f}s: {coverage.matched} frame(s) paired, "
            f"{len(coverage.ref_unmatched)} reference frame(s) without a partner "
            f"(first at {coverage.ref_unmatched[:1]}), {len(coverage.dist_unmatched)} distorted frame(s) without "
            f"a partner (first at {coverage.dist_unmatched[:1]}); a partial window is not a measurement"
        )
    if abs(ref_total - expected) > 1:
        raise HarnessFailure(
            f"reference window at {window.start_s:.3f}s decoded {ref_total} frame(s), expected {expected} "
            f"at {meta.fps:.3f} fps; the decode did not cover the window"
        )

    return {
        "start_s": round(window.start_s, 3),
        "duration_s": round(window.duration_s, 3),
        "frames": compared,
        "coverage": {
            "expected": expected,
            "matched": coverage.matched,
            "ref_unmatched": len(coverage.ref_unmatched),
            "dist_unmatched": len(coverage.dist_unmatched),
        },
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
    check_durations(ref_meta, dist_meta)

    # Planned on the reference: the durations agree within a frame, so this covers both files.
    duration = ref_meta.duration_s
    windows = plan_windows(duration, count=window_count)
    if not windows:
        raise HarnessFailure(f"clip too short to sample honestly ({duration:.2f}s)")

    results = [measure_window(ffmpeg, ref_path, dist_path, w, ref_meta) for w in windows]
    worst = max(r["delta_e_itp"]["worst_frame_p999"] for r in results)
    return {
        "schema": "hdr_pair_measure/2",
        "geometry": f"{ref_meta.width}x{ref_meta.height}",
        "transfer": ref_meta.color_transfer,
        "transfer_family": transfer_family(ref_meta.color_transfer),
        "primaries": ref_meta.color_primaries,
        "matrix": ref_meta.color_space,
        "range": ref_meta.color_range,
        "bit_depth": ref_meta.bit_depth,
        "fps": ref_meta.fps,
        "durations_s": {"reference": ref_meta.duration_s, "distorted": dist_meta.duration_s},
        # Sampled windows, each fully paired; frames outside them were not compared.
        "coverage": {
            "windows": len(results),
            "frames_compared": sum(r["frames"] for r in results),
            "scope": "sampled windows only (20/50/80%); not every frame of the clip",
        },
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
        meta = VideoMeta(320, 240, "smpte2084", "bt2020", "bt2020nc", 12.0,
                         color_range="tv", bit_depth=10, fps=10.0)

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
