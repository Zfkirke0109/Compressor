#!/usr/bin/env python3
"""Fail-closed full-reference quality measurement for Compressor validation.

This development-only harness compares one anonymized source/output pair. It never
rescales or frame-rate-converts either input. Structural parity is established from
decoded presentation timestamps and sanitized ffprobe metadata before libvmaf runs.

The first libvmaf input is always the distorted Compressor output; the second is
always the source reference. HDR/wide-gamut material is rejected because this suite
does not implement a validated HDR VMAF model or a shared deterministic tone map.

Reports intentionally omit input paths and basenames. They are written atomically so
an interrupted run cannot look like a complete measurement.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
import re
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Iterable


SCHEMA_VERSION = 3
VMAF_MODEL = "version=vmaf_v0.6.1"
VMAF_FEATURES = "name=psnr|name=float_ssim"
PAIR_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
HARNESS_SHA256 = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()

VIDEO_FIELDS = (
    "codec_name", "codec_long_name", "profile", "level", "codec_tag_string",
    "width", "height", "coded_width", "coded_height", "sample_aspect_ratio",
    "display_aspect_ratio", "pix_fmt", "bits_per_raw_sample", "field_order",
    "refs", "has_b_frames", "r_frame_rate", "avg_frame_rate", "time_base",
    "start_time", "duration", "nb_frames", "bit_rate", "color_range",
    "color_space", "color_transfer", "color_primaries", "chroma_location",
)
AUDIO_FIELDS = (
    "codec_name", "codec_long_name", "profile", "codec_tag_string", "sample_fmt",
    "sample_rate", "channels", "channel_layout", "bits_per_sample",
    "bits_per_raw_sample", "time_base", "start_time", "duration", "nb_frames",
    "bit_rate", "initial_padding", "trailing_padding",
)
FORMAT_FIELDS = (
    "format_name", "format_long_name", "start_time", "duration", "size",
    "bit_rate", "nb_streams", "probe_score",
)
SIDE_DATA_FIELDS = (
    "side_data_type", "displaymatrix", "rotation", "max_content", "max_average",
    "red_x", "red_y", "green_x", "green_y", "blue_x", "blue_y",
    "white_point_x", "white_point_y", "min_luminance", "max_luminance",
    "dv_version_major", "dv_version_minor", "dv_profile", "dv_level",
    "rpu_present_flag", "el_present_flag", "bl_present_flag",
    "dv_bl_signal_compatibility_id", "view", "inverted",
)
COLOR_FIELDS = ("color_range", "color_space", "color_transfer", "color_primaries")
UNKNOWN_VALUES = {None, "", "unknown", "unspecified", "N/A"}

PL_THRESHOLDS = {
    "vmaf_mean_min": 95.0,
    "vmaf_1pct_low_min": 90.0,
    "vmaf_min_min": 80.0,
    "ssim_mean_min": 0.990,
    "ssim_min_min": 0.950,
    "psnr_mean_db_min": 40.0,
    "psnr_min_db_min": 30.0,
}


class HarnessFailure(RuntimeError):
    """A sanitized failure safe to place in a report."""


def _utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def _float(value: Any) -> float | None:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def _int(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _ratio(value: Any) -> float | None:
    if value in UNKNOWN_VALUES:
        return None
    try:
        numerator, denominator = str(value).split("/", 1)
        denominator_value = float(denominator)
        if denominator_value == 0:
            return None
        result = float(numerator) / denominator_value
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def _run(command: list[str], timeout: float, role: str) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout if timeout > 0 else None,
            check=False,
        )
    except FileNotFoundError as exc:
        raise HarnessFailure(f"{role} executable was not found") from exc
    except subprocess.TimeoutExpired as exc:
        raise HarnessFailure(f"{role} exceeded the configured timeout") from exc


def _stderr_summary(text: str) -> str:
    """Return a short diagnostic without copying possible private path text."""
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines:
        return "no diagnostic text"
    # ffmpeg diagnostics may echo private paths. Retain only generic error keywords.
    lowered = " | ".join(lines[-8:]).lower()
    known = (
        "invalid data", "error while decoding", "conversion failed", "no such filter",
        "option not found", "failed to configure", "out of memory", "permission denied",
        "no space left", "i/o error", "cannot allocate memory", "unknown encoder",
    )
    matches = [item for item in known if item in lowered]
    return ", ".join(matches) if matches else "tool reported an error; raw stderr withheld for privacy"


def _tool_version(executable: str, timeout: float, role: str) -> dict[str, Any]:
    result = _run([executable, "-version"], timeout, role)
    if result.returncode != 0:
        raise HarnessFailure(f"{role} version check failed ({_stderr_summary(result.stderr)})")
    lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    return {
        "executable": Path(executable).name,
        "version_line": lines[0] if lines else "unknown",
        "library_versions": lines[1:9],
    }


def _ffprobe_for(ffmpeg: str) -> str:
    path = Path(ffmpeg)
    replacement = path.name.replace("ffmpeg", "ffprobe", 1)
    candidate = path.with_name(replacement)
    return str(candidate) if candidate.exists() else "ffprobe"


def _side_data(stream: dict[str, Any]) -> list[dict[str, Any]]:
    sanitized: list[dict[str, Any]] = []
    for item in stream.get("side_data_list") or []:
        if not isinstance(item, dict):
            continue
        safe = {key: item[key] for key in SIDE_DATA_FIELDS if key in item}
        if safe:
            sanitized.append(safe)
    return sanitized


def _rotation(stream: dict[str, Any]) -> tuple[float, int | None]:
    raw_rotation: Any = None
    for item in stream.get("side_data_list") or []:
        if isinstance(item, dict) and item.get("rotation") is not None:
            raw_rotation = item.get("rotation")
            break
    if raw_rotation is None:
        tags = stream.get("tags") or {}
        raw_rotation = tags.get("rotate") if isinstance(tags, dict) else None
    if raw_rotation is None:
        raw = 0.0
    else:
        parsed = _float(raw_rotation)
        if parsed is None:
            return 0.0, None
        raw = parsed
    nearest = round(raw / 90.0) * 90
    if abs(raw - nearest) > 0.1:
        return raw, None
    return raw, int(nearest) % 360


def _stream_duration(stream: dict[str, Any], format_info: dict[str, Any]) -> float | None:
    return _float(stream.get("duration")) or _float(format_info.get("duration"))


def _sanitize_stream(stream: dict[str, Any], fields: Iterable[str], ordinal: int) -> dict[str, Any]:
    result = {key: stream[key] for key in fields if key in stream}
    result["ordinal"] = ordinal
    result["disposition"] = {
        key: value for key, value in (stream.get("disposition") or {}).items()
        if isinstance(value, (bool, int))
    }
    result["side_data"] = _side_data(stream)
    return result


def _probe_metadata(ffprobe: str, media_path: str, timeout: float, role: str) -> tuple[dict[str, Any], dict[str, Any]]:
    command = [ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json", media_path]
    result = _run(command, timeout, f"ffprobe {role} metadata")
    if result.returncode != 0 or result.stderr.strip():
        raise HarnessFailure(
            f"ffprobe {role} metadata failed ({_stderr_summary(result.stderr)})"
        )
    try:
        raw = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise HarnessFailure(f"ffprobe {role} metadata returned invalid JSON") from exc

    streams = raw.get("streams") or []
    video_streams = [item for item in streams if item.get("codec_type") == "video"]
    audio_streams = [item for item in streams if item.get("codec_type") == "audio"]
    if len(video_streams) != 1:
        raise HarnessFailure(f"{role} must contain exactly one video stream; found {len(video_streams)}")
    video_raw = video_streams[0]
    format_raw = raw.get("format") or {}
    raw_rotation, rotation = _rotation(video_raw)
    width = _int(video_raw.get("width")) or 0
    height = _int(video_raw.get("height")) or 0
    if rotation is None:
        display_width = display_height = 0
    elif rotation in (90, 270):
        display_width, display_height = height, width
    else:
        display_width, display_height = width, height

    sanitized_video = _sanitize_stream(video_raw, VIDEO_FIELDS, 0)
    sanitized_video.update({
        "rotation_degrees_raw": raw_rotation,
        "rotation_degrees_normalized": rotation,
        "display_width": display_width,
        "display_height": display_height,
        "resolved_duration_seconds": _stream_duration(video_raw, format_raw),
    })
    sanitized = {
        "format": {key: format_raw[key] for key in FORMAT_FIELDS if key in format_raw},
        "video": sanitized_video,
        "audio_streams": [
            _sanitize_stream(item, AUDIO_FIELDS, index) for index, item in enumerate(audio_streams)
        ],
    }
    return raw, sanitized


def _probe_video_frames(
    ffprobe: str,
    media_path: str,
    timeout: float,
    role: str,
) -> list[dict[str, Any]]:
    command = [
        ffprobe, "-v", "error", "-select_streams", "v:0", "-show_frames",
        "-show_entries",
        "frame=pts_time,best_effort_timestamp_time,duration_time,pkt_duration_time,"
        "interlaced_frame,top_field_first",
        "-of", "json", media_path,
    ]
    result = _run(command, timeout, f"ffprobe {role} frame decode")
    if result.returncode != 0 or result.stderr.strip():
        raise HarnessFailure(
            f"ffprobe {role} frame decode failed ({_stderr_summary(result.stderr)})"
        )
    try:
        raw = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise HarnessFailure(f"ffprobe {role} frame decode returned invalid JSON") from exc

    decoded: list[dict[str, Any]] = []
    for index, frame in enumerate(raw.get("frames") or []):
        pts = _float(frame.get("best_effort_timestamp_time"))
        if pts is None:
            pts = _float(frame.get("pts_time"))
        duration = _float(frame.get("duration_time"))
        if duration is None:
            duration = _float(frame.get("pkt_duration_time"))
        if pts is None:
            raise HarnessFailure(f"{role} decoded frame {index} has no finite presentation timestamp")
        interlaced = _int(frame.get("interlaced_frame"))
        top_field_first = _int(frame.get("top_field_first"))
        decoded.append({
            "pts": pts,
            "duration": duration or 0.0,
            "interlaced_frame": interlaced if interlaced in (0, 1) else None,
            "top_field_first": top_field_first if top_field_first in (0, 1) else None,
        })
    if not decoded:
        raise HarnessFailure(f"{role} produced zero decoded video frames")
    return decoded


def _probe_audio_frames(
    ffprobe: str,
    media_path: str,
    audio_ordinal: int,
    timeout: float,
    role: str,
) -> list[dict[str, float | int]]:
    command = [
        ffprobe, "-v", "error", "-select_streams", f"a:{audio_ordinal}",
        "-show_frames", "-show_entries",
        "frame=pts_time,best_effort_timestamp_time,duration_time,pkt_duration_time,nb_samples",
        "-of", "json", media_path,
    ]
    result = _run(command, timeout, f"ffprobe {role} audio {audio_ordinal} decode")
    if result.returncode != 0 or result.stderr.strip():
        raise HarnessFailure(
            f"ffprobe {role} audio {audio_ordinal} decode failed "
            f"({_stderr_summary(result.stderr)})"
        )
    try:
        raw = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise HarnessFailure(
            f"ffprobe {role} audio {audio_ordinal} decode returned invalid JSON"
        ) from exc

    decoded: list[dict[str, float | int]] = []
    for frame_index, frame in enumerate(raw.get("frames") or []):
        pts = _float(frame.get("best_effort_timestamp_time"))
        if pts is None:
            pts = _float(frame.get("pts_time"))
        duration = _float(frame.get("duration_time"))
        if duration is None:
            duration = _float(frame.get("pkt_duration_time"))
        samples = _int(frame.get("nb_samples"))
        if pts is None or samples is None or samples <= 0:
            raise HarnessFailure(
                f"{role} audio {audio_ordinal} frame {frame_index} lacks finite PTS or sample count"
            )
        decoded.append({"pts": pts, "duration": duration or 0.0, "nb_samples": samples})
    if not decoded:
        raise HarnessFailure(f"{role} audio {audio_ordinal} produced zero decoded frames")
    return decoded


def _audio_timeline(
    frames: list[dict[str, float | int]],
    stream: dict[str, Any],
    role: str,
    audio_ordinal: int,
) -> dict[str, Any]:
    if not frames:
        raise HarnessFailure(f"{role} audio {audio_ordinal} produced zero decoded frames")
    sample_rate = _int(stream.get("sample_rate"))
    if sample_rate is None or sample_rate <= 0:
        raise HarnessFailure(f"{role} audio {audio_ordinal} has no valid sample rate")
    parsed_pts = [_float(frame.get("pts")) for frame in frames]
    sample_counts = [_int(frame.get("nb_samples")) for frame in frames]
    if any(value is None for value in parsed_pts) or any(
        value is None or value <= 0 for value in sample_counts
    ):
        raise HarnessFailure(
            f"{role} audio {audio_ordinal} lacks finite PTS or decoded sample counts"
        )
    first_pts = float(parsed_pts[0])
    normalized_pts = [float(value) - first_pts for value in parsed_pts]
    if any(
        normalized_pts[index] <= normalized_pts[index - 1]
        for index in range(1, len(normalized_pts))
    ):
        raise HarnessFailure(f"{role} audio {audio_ordinal} PTS are not strictly increasing")
    sample_counts = [int(value) for value in sample_counts]
    tick = _ratio(stream.get("time_base")) or 0.0
    gap_tolerance = max(2.0 / sample_rate, 2.0 * tick, 0.0001)
    gap_residuals: list[float] = []
    for index in range(1, len(frames)):
        expected = normalized_pts[index - 1] + sample_counts[index - 1] / sample_rate
        gap_residuals.append(normalized_pts[index] - expected)
    effective_duration = normalized_pts[-1] + sample_counts[-1] / sample_rate
    gap_indices = [
        index + 1 for index, residual in enumerate(gap_residuals)
        if abs(residual) > gap_tolerance
    ]
    return {
        "ordinal": audio_ordinal,
        "sample_rate_hz": sample_rate,
        "first_pts_seconds": first_pts,
        "normalized_pts": normalized_pts,
        "samples_per_frame": sample_counts,
        "decoded_frame_count": len(frames),
        "decoded_sample_count": sum(sample_counts),
        "effective_duration_seconds": effective_duration,
        "effective_end_pts_seconds": first_pts + effective_duration,
        "time_base_seconds": tick,
        "gap_tolerance_seconds": gap_tolerance,
        "gap_count": len(gap_indices),
        "gap_frame_indices": gap_indices,
        "max_abs_gap_seconds": max((abs(value) for value in gap_residuals), default=0.0),
        "reported_duration_seconds": _float(stream.get("duration")),
        "monotonic_pts": True,
    }


def _technical_audio_timeline(timeline: dict[str, Any]) -> dict[str, Any]:
    return {
        key: value for key, value in timeline.items()
        if key not in {"normalized_pts", "samples_per_frame"}
    }


def _probe_all_audio_timelines(
    ffprobe: str,
    media_path: str,
    media_meta: dict[str, Any],
    timeout: float,
    role: str,
) -> list[dict[str, Any]]:
    timelines = []
    for ordinal, stream in enumerate(media_meta["audio_streams"]):
        frames = _probe_audio_frames(ffprobe, media_path, ordinal, timeout, role)
        timelines.append(_audio_timeline(frames, stream, role, ordinal))
    return timelines


def _timeline(frames: list[dict[str, Any]], time_base: Any, role: str) -> dict[str, Any]:
    first_pts = frames[0]["pts"]
    normalized = [frame["pts"] - first_pts for frame in frames]
    deltas = [normalized[index] - normalized[index - 1] for index in range(1, len(normalized))]
    if any(delta <= 0 for delta in deltas):
        raise HarnessFailure(f"{role} decoded video PTS are not strictly increasing")

    tick = _ratio(time_base) or 0.0
    median_delta = statistics.median(deltas) if deltas else (frames[0]["duration"] or 0.0)
    last_duration = frames[-1]["duration"] or median_delta
    variation_threshold = max(4.0 * tick, 0.0005, median_delta * 0.01)
    cadence_kind = "CFR"
    if deltas and (max(deltas) - min(deltas)) > variation_threshold:
        cadence_kind = "VFR"
    frame_end = normalized[-1] + max(0.0, last_duration)
    interlaced_flags = [frame.get("interlaced_frame") for frame in frames]
    top_field_first_flags = [frame.get("top_field_first") for frame in frames]
    interlace_unknown_count = sum(flag not in (0, 1) for flag in interlaced_flags)
    interlaced_count = sum(flag == 1 for flag in interlaced_flags)
    progressive_count = sum(flag == 0 for flag in interlaced_flags)
    interlaced_top_field_unknown_count = sum(
        interlaced_flags[index] == 1 and top_field_first_flags[index] not in (0, 1)
        for index in range(len(frames))
    )
    return {
        "first_pts_seconds": first_pts,
        "normalized_pts": normalized,
        "decoded_frame_count": len(frames),
        "frame_timeline_duration_seconds": frame_end,
        "cadence_kind": cadence_kind,
        "time_base_seconds": tick,
        "delta_min_seconds": min(deltas) if deltas else None,
        "delta_max_seconds": max(deltas) if deltas else None,
        "delta_mean_seconds": statistics.fmean(deltas) if deltas else None,
        "delta_median_seconds": median_delta or None,
        "delta_stddev_seconds": statistics.pstdev(deltas) if len(deltas) > 1 else 0.0,
        "measured_fps": (1.0 / statistics.fmean(deltas)) if deltas and statistics.fmean(deltas) else None,
        "interlaced_flags": interlaced_flags,
        "top_field_first_flags": top_field_first_flags,
        "decoded_interlace": {
            "known_frame_count": len(frames) - interlace_unknown_count,
            "unknown_frame_count": interlace_unknown_count,
            "progressive_frame_count": progressive_count,
            "interlaced_frame_count": interlaced_count,
            "interlaced_top_field_unknown_count": interlaced_top_field_unknown_count,
            "all_frames_proven_progressive": (
                interlace_unknown_count == 0 and interlaced_count == 0
            ),
        },
    }


def _technical_timeline(timeline: dict[str, Any]) -> dict[str, Any]:
    return {
        key: value for key, value in timeline.items()
        if key not in {"normalized_pts", "interlaced_flags", "top_field_first_flags"}
    }


def _is_hdr_or_wide_gamut(video: dict[str, Any]) -> bool:
    transfer = str(video.get("color_transfer") or "").lower()
    primaries = str(video.get("color_primaries") or "").lower()
    space = str(video.get("color_space") or "").lower()
    if transfer in {"smpte2084", "arib-std-b67"}:
        return True
    if "bt2020" in primaries or "bt2020" in space:
        return True
    hdr_side_data = {"Mastering display metadata", "Content light level metadata", "DOVI configuration record"}
    return any(item.get("side_data_type") in hdr_side_data for item in video.get("side_data") or [])


def _known(value: Any) -> bool:
    return value not in UNKNOWN_VALUES


def _add_failure(failures: list[dict[str, Any]], code: str, **details: Any) -> None:
    failures.append({"code": code, **details})


def _add_regression_failure(
    comparison_failures: list[dict[str, Any]],
    pl_integrity_failures: list[dict[str, Any]],
    code: str,
    **details: Any,
) -> None:
    _add_failure(comparison_failures, code, **details)
    _add_failure(pl_integrity_failures, code, **details)


def _checkpoint_indices(count: int) -> list[tuple[str, int]]:
    positions = (
        ("beginning", 0),
        ("25_percent", round((count - 1) * 0.25)),
        ("middle", round((count - 1) * 0.50)),
        ("75_percent", round((count - 1) * 0.75)),
        ("end", count - 1),
    )
    unique: list[tuple[str, int]] = []
    used: set[int] = set()
    for label, index in positions:
        if index not in used:
            unique.append((label, index))
            used.add(index)
    return unique


def _audio_duration(stream: dict[str, Any]) -> float | None:
    return _float(stream.get("duration"))


def _compare_structure(
    source: dict[str, Any],
    output: dict[str, Any],
    source_timeline: dict[str, Any],
    output_timeline: dict[str, Any],
    source_audio_timelines: list[dict[str, Any]] | None = None,
    output_audio_timelines: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    comparison_failures: list[dict[str, Any]] = []
    pl_integrity_failures: list[dict[str, Any]] = []
    warnings: list[dict[str, Any]] = []
    src_video = source["video"]
    out_video = output["video"]

    if src_video.get("rotation_degrees_normalized") is None or out_video.get("rotation_degrees_normalized") is None:
        _add_failure(comparison_failures, "NON_ORTHOGONAL_OR_INVALID_ROTATION")
    source_display = (src_video.get("display_width"), src_video.get("display_height"))
    output_display = (out_video.get("display_width"), out_video.get("display_height"))
    if source_display != output_display or 0 in source_display or 0 in output_display:
        _add_regression_failure(
            comparison_failures, pl_integrity_failures,
            "DISPLAY_RESOLUTION_MISMATCH",
            source_display=list(source_display), output_display=list(output_display),
        )
    source_sar = _ratio(src_video.get("sample_aspect_ratio") or "1/1")
    output_sar = _ratio(out_video.get("sample_aspect_ratio") or "1/1")
    if source_sar is not None and output_sar is not None and abs(source_sar - output_sar) > 1e-6:
        _add_regression_failure(
            comparison_failures, pl_integrity_failures,
            "SAMPLE_ASPECT_RATIO_MISMATCH",
        )

    if _is_hdr_or_wide_gamut(src_video) or _is_hdr_or_wide_gamut(out_video):
        _add_failure(
            comparison_failures,
            "HDR_OR_WIDE_GAMUT_UNSUPPORTED_FAIL_CLOSED",
            detail="No validated HDR model or same-pipeline tone-map proxy is implemented",
        )

    for field in COLOR_FIELDS:
        src_value = src_video.get(field)
        out_value = out_video.get(field)
        if _known(src_value) and src_value != out_value:
            _add_failure(
                pl_integrity_failures, "COLOR_METADATA_REGRESSION", field=field,
                source=src_value, output=out_value,
            )
    if src_video.get("pix_fmt") != out_video.get("pix_fmt"):
        _add_failure(
            pl_integrity_failures, "PIXEL_FORMAT_MISMATCH",
            source=src_video.get("pix_fmt"), output=out_video.get("pix_fmt"),
        )
    source_bits = _int(src_video.get("bits_per_raw_sample"))
    output_bits = _int(out_video.get("bits_per_raw_sample"))
    if source_bits and output_bits and output_bits < source_bits:
        _add_failure(
            pl_integrity_failures, "BIT_DEPTH_REGRESSION",
            source=source_bits, output=output_bits,
        )

    source_interlace = source_timeline.get("decoded_interlace") or {}
    output_interlace = output_timeline.get("decoded_interlace") or {}
    source_interlaced_flags = source_timeline.get("interlaced_flags") or []
    output_interlaced_flags = output_timeline.get("interlaced_flags") or []
    source_top_flags = source_timeline.get("top_field_first_flags") or []
    output_top_flags = output_timeline.get("top_field_first_flags") or []
    interlace_paired_count = min(
        len(source_interlaced_flags), len(output_interlaced_flags)
    )
    missing_interlace_observations = (
        max(0, source_timeline["decoded_frame_count"] - len(source_interlaced_flags))
        + max(0, output_timeline["decoded_frame_count"] - len(output_interlaced_flags))
    )
    unknown_interlace_frames = [
        index for index in range(interlace_paired_count)
        if source_interlaced_flags[index] not in (0, 1)
        or output_interlaced_flags[index] not in (0, 1)
    ]
    if unknown_interlace_frames or missing_interlace_observations:
        _add_failure(
            comparison_failures,
            "DECODED_INTERLACE_STATUS_UNKNOWN",
            affected_frame_count=(
                len(unknown_interlace_frames) + missing_interlace_observations
            ),
        )
    decoded_interlaced_source_count = sum(flag == 1 for flag in source_interlaced_flags)
    decoded_interlaced_output_count = sum(flag == 1 for flag in output_interlaced_flags)
    if decoded_interlaced_source_count or decoded_interlaced_output_count:
        _add_failure(
            comparison_failures,
            "DECODED_INTERLACED_CONTENT_UNSUPPORTED",
            source_interlaced_frame_count=decoded_interlaced_source_count,
            output_interlaced_frame_count=decoded_interlaced_output_count,
            detail="Progressive VMAF correspondence is not validated for interlaced frames",
        )
    interlace_mismatch_frames = [
        index for index in range(interlace_paired_count)
        if source_interlaced_flags[index] in (0, 1)
        and output_interlaced_flags[index] in (0, 1)
        and source_interlaced_flags[index] != output_interlaced_flags[index]
    ]
    if interlace_mismatch_frames:
        _add_failure(
            pl_integrity_failures,
            "DECODED_INTERLACE_MISMATCH",
            affected_frame_count=len(interlace_mismatch_frames),
        )
    field_order_unknown_or_mismatch = []
    for index in range(interlace_paired_count):
        if source_interlaced_flags[index] == output_interlaced_flags[index] == 1:
            src_top = source_top_flags[index] if index < len(source_top_flags) else None
            out_top = output_top_flags[index] if index < len(output_top_flags) else None
            if src_top not in (0, 1) or out_top not in (0, 1) or src_top != out_top:
                field_order_unknown_or_mismatch.append(index)
    if field_order_unknown_or_mismatch:
        _add_failure(
            pl_integrity_failures,
            "DECODED_INTERLACE_FIELD_ORDER_MISMATCH_OR_UNKNOWN",
            affected_frame_count=len(field_order_unknown_or_mismatch),
        )

    all_frames_proven_progressive = (
        source_interlace.get("all_frames_proven_progressive") is True
        and output_interlace.get("all_frames_proven_progressive") is True
    )
    src_field_order = src_video.get("field_order")
    out_field_order = out_video.get("field_order")
    if _known(src_field_order) and src_field_order != out_field_order:
        if (
            str(src_field_order).lower() == "progressive"
            and not _known(out_field_order)
            and all_frames_proven_progressive
        ):
            warnings.append({
                "code": "VIDEO_FIELD_ORDER_METADATA_OMITTED",
                "source_field_order": src_field_order,
                "output_field_order": out_field_order,
                "decoded_frames_proven_progressive": True,
            })
        else:
            _add_failure(
                pl_integrity_failures, "VIDEO_METADATA_REGRESSION",
                field="field_order", source=src_field_order, output=out_field_order,
            )
    src_chroma = src_video.get("chroma_location")
    out_chroma = out_video.get("chroma_location")
    if _known(src_chroma) and src_chroma != out_chroma:
        _add_failure(
            pl_integrity_failures, "VIDEO_METADATA_REGRESSION",
            field="chroma_location", source=src_chroma, output=out_chroma,
        )

    src_pts = source_timeline["normalized_pts"]
    out_pts = output_timeline["normalized_pts"]
    frame_count_match = len(src_pts) == len(out_pts)
    if not frame_count_match:
        _add_regression_failure(
            comparison_failures, pl_integrity_failures,
            "DECODED_FRAME_COUNT_MISMATCH",
            source=len(src_pts), output=len(out_pts), dropped_or_added=abs(len(src_pts) - len(out_pts)),
        )
    src_interval = source_timeline.get("delta_median_seconds") or 0.0
    out_interval = output_timeline.get("delta_median_seconds") or 0.0
    timestamp_tolerance = max(
        0.001,
        2.0 * source_timeline.get("time_base_seconds", 0.0)
        + 2.0 * output_timeline.get("time_base_seconds", 0.0),
        min(value for value in (src_interval, out_interval) if value > 0) * 0.05
        if src_interval > 0 and out_interval > 0 else 0.0,
    )
    paired_count = min(len(src_pts), len(out_pts))
    pts_deltas = [abs(src_pts[index] - out_pts[index]) for index in range(paired_count)]
    mismatched_pts = sum(delta > timestamp_tolerance for delta in pts_deltas)
    if mismatched_pts:
        _add_regression_failure(
            comparison_failures, pl_integrity_failures,
            "PRESENTATION_CADENCE_MISMATCH", mismatched_frames=mismatched_pts,
            max_pts_delta_seconds=max(pts_deltas), tolerance_seconds=timestamp_tolerance,
        )
    timeline_duration_delta = abs(
        source_timeline["frame_timeline_duration_seconds"]
        - output_timeline["frame_timeline_duration_seconds"]
    )
    if timeline_duration_delta > timestamp_tolerance:
        _add_regression_failure(
            comparison_failures, pl_integrity_failures,
            "VIDEO_TIMELINE_DURATION_MISMATCH",
            delta_seconds=timeline_duration_delta, tolerance_seconds=timestamp_tolerance,
        )

    src_format_duration = _float(source["format"].get("duration"))
    out_format_duration = _float(output["format"].get("duration"))
    format_duration_delta = None
    if src_format_duration is not None and out_format_duration is not None:
        format_duration_delta = abs(src_format_duration - out_format_duration)
        format_tolerance = max(0.050, src_interval, out_interval)
        if format_duration_delta > format_tolerance:
            _add_failure(
                pl_integrity_failures, "CONTAINER_DURATION_MISMATCH",
                delta_seconds=format_duration_delta, tolerance_seconds=format_tolerance,
            )

    checkpoints = []
    for label, index in _checkpoint_indices(paired_count):
        checkpoints.append({
            "position": label,
            "frame_index": index,
            "source_pts_seconds": src_pts[index],
            "output_pts_seconds": out_pts[index],
            "delta_seconds": out_pts[index] - src_pts[index],
            "within_tolerance": abs(out_pts[index] - src_pts[index]) <= timestamp_tolerance,
        })

    source_audio = source["audio_streams"]
    output_audio = output["audio_streams"]
    source_audio_timelines = source_audio_timelines or []
    output_audio_timelines = output_audio_timelines or []
    audio_checks: list[dict[str, Any]] = []
    if len(source_audio) != len(output_audio):
        _add_failure(
            pl_integrity_failures, "AUDIO_STREAM_COUNT_MISMATCH",
            source=len(source_audio), output=len(output_audio),
        )
    if len(source_audio_timelines) != len(source_audio):
        _add_failure(
            pl_integrity_failures, "SOURCE_AUDIO_TIMELINE_MISSING",
            expected=len(source_audio), measured=len(source_audio_timelines),
        )
    if len(output_audio_timelines) != len(output_audio):
        _add_failure(
            pl_integrity_failures, "OUTPUT_AUDIO_TIMELINE_MISSING",
            expected=len(output_audio), measured=len(output_audio_timelines),
        )
    for index, (src_audio, out_audio) in enumerate(zip(source_audio, output_audio)):
        stream_failures: list[str] = []
        for field in ("codec_name", "sample_rate", "channels"):
            if not _known(src_audio.get(field)) or not _known(out_audio.get(field)):
                stream_failures.append(field)
                _add_failure(
                    pl_integrity_failures, "AUDIO_REQUIRED_METADATA_MISSING", stream=index,
                    field=field,
                )
        for field in ("codec_name", "profile", "sample_fmt", "sample_rate", "channels", "channel_layout"):
            src_value = src_audio.get(field)
            out_value = out_audio.get(field)
            if _known(src_value) and src_value != out_value:
                stream_failures.append(field)
                _add_failure(
                    pl_integrity_failures, "AUDIO_PARAMETER_REGRESSION", stream=index, field=field,
                    source=src_value, output=out_value,
                )
        src_audio_duration = _audio_duration(src_audio)
        out_audio_duration = _audio_duration(out_audio)
        metadata_duration_delta = None
        if src_audio_duration is not None:
            if out_audio_duration is None:
                stream_failures.append("duration")
                _add_failure(
                    pl_integrity_failures, "AUDIO_DURATION_METADATA_MISSING", stream=index,
                )
            else:
                metadata_duration_delta = abs(src_audio_duration - out_audio_duration)
                if metadata_duration_delta > 0.050:
                    stream_failures.append("duration")
                    _add_failure(
                        pl_integrity_failures, "AUDIO_DURATION_REGRESSION", stream=index,
                        delta_seconds=metadata_duration_delta, tolerance_seconds=0.050,
                    )
        src_bitrate = _int(src_audio.get("bit_rate"))
        out_bitrate = _int(out_audio.get("bit_rate"))
        if src_bitrate and out_bitrate and out_bitrate < src_bitrate * 0.95:
            stream_failures.append("bit_rate")
            _add_failure(
                pl_integrity_failures, "AUDIO_BITRATE_REGRESSION", stream=index,
                source_bps=src_bitrate, output_bps=out_bitrate,
            )

        decoded_comparison: dict[str, Any] = {"available": False}
        if index < len(source_audio_timelines) and index < len(output_audio_timelines):
            src_decoded = source_audio_timelines[index]
            out_decoded = output_audio_timelines[index]
            decoded_comparison["available"] = True
            sample_rate = src_decoded["sample_rate_hz"]
            base_audio_tolerance = max(
                0.001,
                2.0 / sample_rate,
                2.0 * src_decoded.get("time_base_seconds", 0.0)
                + 2.0 * out_decoded.get("time_base_seconds", 0.0),
            )
            base_audio_tick_tolerance = max(
                2.0 / sample_rate,
                2.0 * src_decoded.get("time_base_seconds", 0.0)
                + 2.0 * out_decoded.get("time_base_seconds", 0.0),
                0.0001,
            )
            src_sample_layout = src_decoded["samples_per_frame"]
            out_sample_layout = out_decoded["samples_per_frame"]
            access_unit_samples = max(src_sample_layout + out_sample_layout)
            access_unit_quantum_seconds = access_unit_samples / sample_rate
            padding_tolerance_cap_seconds = 0.050
            padding_tolerance = min(
                access_unit_quantum_seconds, padding_tolerance_cap_seconds
            )
            frame_count_delta_signed = (
                out_decoded["decoded_frame_count"]
                - src_decoded["decoded_frame_count"]
            )
            frame_count_delta = abs(frame_count_delta_signed)
            sample_count_delta_signed = (
                out_decoded["decoded_sample_count"]
                - src_decoded["decoded_sample_count"]
            )
            sample_count_delta = abs(sample_count_delta_signed)
            padding_duration_from_sample_delta = (
                max(0, sample_count_delta_signed) / sample_rate
            )
            common_sample_frames = min(len(src_sample_layout), len(out_sample_layout))
            sample_layout_delta = sum(
                abs(src_sample_layout[frame] - out_sample_layout[frame])
                for frame in range(common_sample_frames)
            ) + sum(src_sample_layout[common_sample_frames:]) + sum(
                out_sample_layout[common_sample_frames:]
            )
            effective_duration_delta = abs(
                src_decoded["effective_duration_seconds"]
                - out_decoded["effective_duration_seconds"]
            )
            effective_duration_padding_residual = abs(
                effective_duration_delta - padding_duration_from_sample_delta
            )
            src_audio_pts = src_decoded["normalized_pts"]
            out_audio_pts = out_decoded["normalized_pts"]
            compared_audio_frames = min(len(src_audio_pts), len(out_audio_pts))
            audio_pts_deltas = [
                abs(src_audio_pts[frame] - out_audio_pts[frame])
                for frame in range(compared_audio_frames)
            ]
            max_audio_pts_delta = max(audio_pts_deltas, default=0.0)
            audio_pts_mismatches = sum(
                delta > base_audio_tolerance for delta in audio_pts_deltas
            )
            av_start_delta = abs(
                (src_decoded["first_pts_seconds"] - source_timeline["first_pts_seconds"])
                - (out_decoded["first_pts_seconds"] - output_timeline["first_pts_seconds"])
            )
            av_start_padding_residual = abs(
                av_start_delta - padding_duration_from_sample_delta
            )
            source_video_end = (
                source_timeline["first_pts_seconds"]
                + source_timeline["frame_timeline_duration_seconds"]
            )
            output_video_end = (
                output_timeline["first_pts_seconds"]
                + output_timeline["frame_timeline_duration_seconds"]
            )
            av_end_delta = abs(
                (src_decoded["effective_end_pts_seconds"] - source_video_end)
                - (out_decoded["effective_end_pts_seconds"] - output_video_end)
            )
            av_end_padding_residual = abs(
                av_end_delta - padding_duration_from_sample_delta
            )
            source_absolute_audio_pts = [
                src_decoded["first_pts_seconds"] + value for value in src_audio_pts
            ]
            output_absolute_audio_pts = [
                out_decoded["first_pts_seconds"] + value for value in out_audio_pts
            ]
            absolute_pts_after_first_deltas = [
                abs(source_absolute_audio_pts[frame] - output_absolute_audio_pts[frame])
                for frame in range(1, compared_audio_frames)
            ]
            max_absolute_pts_after_first_delta = max(
                absolute_pts_after_first_deltas, default=0.0
            )
            absolute_effective_end_delta = abs(
                src_decoded["effective_end_pts_seconds"]
                - out_decoded["effective_end_pts_seconds"]
            )
            gap_structure_match = (
                src_decoded["gap_count"] == out_decoded["gap_count"]
                and src_decoded["gap_frame_indices"]
                == out_decoded["gap_frame_indices"]
            )
            has_padding_shape_difference = (
                frame_count_delta > 0
                or sample_count_delta > 0
                or src_sample_layout != out_sample_layout
            )
            if len(src_sample_layout) == len(out_sample_layout):
                tail_only_padding_shape = (
                    src_sample_layout[:-1] == out_sample_layout[:-1]
                    and out_sample_layout[-1] >= src_sample_layout[-1]
                )
            elif len(out_sample_layout) == len(src_sample_layout) + 1:
                tail_only_padding_shape = (
                    out_sample_layout[:len(src_sample_layout)] == src_sample_layout
                )
            else:
                tail_only_padding_shape = False
            padding_variation = all((
                not stream_failures,
                has_padding_shape_difference,
                tail_only_padding_shape,
                frame_count_delta_signed in (0, 1),
                sample_count_delta_signed >= 0,
                frame_count_delta <= 1,
                sample_count_delta <= access_unit_samples,
                sample_layout_delta <= access_unit_samples,
                effective_duration_delta <= padding_tolerance,
                effective_duration_padding_residual <= base_audio_tolerance,
                max_audio_pts_delta <= padding_tolerance,
                av_start_delta <= base_audio_tolerance,
                av_end_padding_residual <= base_audio_tolerance,
                gap_structure_match,
                src_decoded["gap_count"] == 0,
                out_decoded["gap_count"] == 0,
                metadata_duration_delta is None or metadata_duration_delta <= 0.050,
            ))
            equal_tail_after_first = (
                len(src_sample_layout) == len(out_sample_layout)
                and len(src_sample_layout) > 1
                and src_sample_layout[1:] == out_sample_layout[1:]
            )
            first_access_unit_sample_delta = (
                out_sample_layout[0] - src_sample_layout[0]
                if len(src_sample_layout) == len(out_sample_layout)
                and src_sample_layout else 0
            )
            established_access_unit_samples = (
                max(src_sample_layout[1:]) if equal_tail_after_first else 0
            )
            priming_duration_from_first_au_delta = (
                first_access_unit_sample_delta / sample_rate
                if first_access_unit_sample_delta > 0 else 0.0
            )
            output_first_pts_earlier_seconds = (
                src_decoded["first_pts_seconds"] - out_decoded["first_pts_seconds"]
            )
            first_pts_priming_residual = abs(
                output_first_pts_earlier_seconds
                - priming_duration_from_first_au_delta
            )
            priming_variation = all((
                not stream_failures,
                frame_count_delta_signed == 0,
                equal_tail_after_first,
                first_access_unit_sample_delta > 0,
                sample_count_delta_signed == first_access_unit_sample_delta,
                first_access_unit_sample_delta <= established_access_unit_samples,
                sample_layout_delta == first_access_unit_sample_delta,
                output_first_pts_earlier_seconds > 0,
                first_pts_priming_residual <= base_audio_tick_tolerance,
                effective_duration_delta <= padding_tolerance,
                effective_duration_padding_residual <= base_audio_tolerance,
                max_audio_pts_delta <= padding_tolerance,
                max_absolute_pts_after_first_delta <= base_audio_tolerance,
                absolute_effective_end_delta <= base_audio_tolerance,
                av_end_delta <= base_audio_tolerance,
                gap_structure_match,
                src_decoded["gap_count"] == 0,
                out_decoded["gap_count"] == 0,
                metadata_duration_delta is None or metadata_duration_delta <= 0.050,
            ))
            if padding_variation:
                warnings.append({
                    "code": "AUDIO_ENCODER_PADDING_VARIATION",
                    "stream": index,
                    "access_unit_samples": access_unit_samples,
                    "sample_rate_hz": sample_rate,
                    "access_unit_quantum_seconds": access_unit_quantum_seconds,
                    "padding_tolerance_cap_seconds": padding_tolerance_cap_seconds,
                    "derived_padding_tolerance_seconds": padding_tolerance,
                    "base_audio_tolerance_seconds": base_audio_tolerance,
                    "padding_duration_from_sample_delta_seconds": (
                        padding_duration_from_sample_delta
                    ),
                    "decoded_frame_count_delta": frame_count_delta,
                    "decoded_sample_count_delta": sample_count_delta,
                    "output_minus_source_sample_count": sample_count_delta_signed,
                    "tail_only_padding_shape": tail_only_padding_shape,
                    "sample_layout_absolute_delta_samples": sample_layout_delta,
                    "effective_duration_delta_seconds": effective_duration_delta,
                    "effective_duration_padding_residual_seconds": (
                        effective_duration_padding_residual
                    ),
                    "max_normalized_pts_delta_seconds": max_audio_pts_delta,
                    "av_start_representation_delta_seconds": av_start_delta,
                    "av_start_padding_residual_seconds": av_start_padding_residual,
                    "av_end_drift_delta_seconds": av_end_delta,
                    "av_end_padding_residual_seconds": av_end_padding_residual,
                    "metadata_duration_delta_seconds": metadata_duration_delta,
                    "source_gap_count": src_decoded["gap_count"],
                    "output_gap_count": out_decoded["gap_count"],
                })
            if priming_variation:
                warnings.append({
                    "code": "AUDIO_ENCODER_PRIMING_VARIATION",
                    "stream": index,
                    "sample_rate_hz": sample_rate,
                    "source_first_access_unit_samples": src_sample_layout[0],
                    "output_first_access_unit_samples": out_sample_layout[0],
                    "first_access_unit_sample_delta": first_access_unit_sample_delta,
                    "established_access_unit_samples": established_access_unit_samples,
                    "priming_duration_from_sample_delta_seconds": (
                        priming_duration_from_first_au_delta
                    ),
                    "source_first_pts_seconds": src_decoded["first_pts_seconds"],
                    "output_first_pts_seconds": out_decoded["first_pts_seconds"],
                    "output_first_pts_earlier_seconds": output_first_pts_earlier_seconds,
                    "first_pts_priming_residual_seconds": first_pts_priming_residual,
                    "base_audio_tick_tolerance_seconds": base_audio_tick_tolerance,
                    "base_audio_tolerance_seconds": base_audio_tolerance,
                    "all_sample_counts_after_first_identical": equal_tail_after_first,
                    "sample_layout_absolute_delta_samples": sample_layout_delta,
                    "effective_duration_delta_seconds": effective_duration_delta,
                    "effective_duration_padding_residual_seconds": (
                        effective_duration_padding_residual
                    ),
                    "max_normalized_pts_delta_seconds": max_audio_pts_delta,
                    "max_absolute_pts_after_first_delta_seconds": (
                        max_absolute_pts_after_first_delta
                    ),
                    "absolute_effective_end_delta_seconds": absolute_effective_end_delta,
                    "av_end_drift_delta_seconds": av_end_delta,
                    "metadata_duration_delta_seconds": metadata_duration_delta,
                    "source_gap_count": src_decoded["gap_count"],
                    "output_gap_count": out_decoded["gap_count"],
                })
            if not padding_variation and not priming_variation:
                if frame_count_delta:
                    stream_failures.append("decoded_frame_count")
                    _add_failure(
                        pl_integrity_failures,
                        "AUDIO_DECODED_FRAME_COUNT_MISMATCH", stream=index,
                        source=src_decoded["decoded_frame_count"],
                        output=out_decoded["decoded_frame_count"],
                    )
                if sample_count_delta:
                    stream_failures.append("decoded_sample_count")
                    _add_failure(
                        pl_integrity_failures,
                        "AUDIO_DECODED_SAMPLE_COUNT_MISMATCH", stream=index,
                        source=src_decoded["decoded_sample_count"],
                        output=out_decoded["decoded_sample_count"],
                    )
                if src_sample_layout != out_sample_layout:
                    stream_failures.append("sample_layout")
                    _add_failure(
                        pl_integrity_failures,
                        "AUDIO_FRAME_SAMPLE_LAYOUT_MISMATCH", stream=index,
                        absolute_delta_samples=sample_layout_delta,
                    )
                if effective_duration_delta > base_audio_tolerance:
                    stream_failures.append("decoded_duration")
                    _add_failure(
                        pl_integrity_failures,
                        "AUDIO_EFFECTIVE_DURATION_MISMATCH", stream=index,
                        delta_seconds=effective_duration_delta,
                        tolerance_seconds=base_audio_tolerance,
                    )
                if audio_pts_mismatches:
                    stream_failures.append("pts_cadence")
                    _add_failure(
                        pl_integrity_failures,
                        "AUDIO_PRESENTATION_CADENCE_MISMATCH", stream=index,
                        mismatched_frames=audio_pts_mismatches,
                        max_pts_delta_seconds=max_audio_pts_delta,
                        tolerance_seconds=base_audio_tolerance,
                    )
                if av_start_delta > base_audio_tolerance:
                    stream_failures.append("av_start_drift")
                    _add_failure(
                        pl_integrity_failures,
                        "AUDIO_VIDEO_START_DRIFT", stream=index,
                        delta_seconds=av_start_delta,
                        tolerance_seconds=base_audio_tolerance,
                    )
                if av_end_delta > base_audio_tolerance:
                    stream_failures.append("av_end_drift")
                    _add_failure(
                        pl_integrity_failures,
                        "AUDIO_VIDEO_END_DRIFT", stream=index,
                        delta_seconds=av_end_delta,
                        tolerance_seconds=base_audio_tolerance,
                    )
                if not gap_structure_match:
                    stream_failures.append("gap_structure")
                    _add_failure(
                        pl_integrity_failures,
                        "AUDIO_GAP_STRUCTURE_MISMATCH", stream=index,
                        source_gap_count=src_decoded["gap_count"],
                        output_gap_count=out_decoded["gap_count"],
                    )
            decoded_comparison.update({
                "base_tolerance_seconds": base_audio_tolerance,
                "access_unit_samples": access_unit_samples,
                "sample_rate_hz": sample_rate,
                "access_unit_quantum_seconds": access_unit_quantum_seconds,
                "padding_tolerance_cap_seconds": padding_tolerance_cap_seconds,
                "derived_padding_tolerance_seconds": padding_tolerance,
                "padding_duration_from_sample_delta_seconds": (
                    padding_duration_from_sample_delta
                ),
                "padding_variation_accepted": padding_variation,
                "priming_variation_accepted": priming_variation,
                "base_audio_tick_tolerance_seconds": base_audio_tick_tolerance,
                "decoded_frame_count_match": (
                    src_decoded["decoded_frame_count"] == out_decoded["decoded_frame_count"]
                ),
                "decoded_sample_count_match": (
                    src_decoded["decoded_sample_count"] == out_decoded["decoded_sample_count"]
                ),
                "effective_duration_delta_seconds": effective_duration_delta,
                "effective_duration_padding_residual_seconds": (
                    effective_duration_padding_residual
                ),
                "pts_mismatch_count": audio_pts_mismatches,
                "max_pts_delta_seconds": max_audio_pts_delta,
                "av_start_drift_delta_seconds": av_start_delta,
                "av_start_padding_residual_seconds": av_start_padding_residual,
                "av_end_drift_delta_seconds": av_end_delta,
                "av_end_padding_residual_seconds": av_end_padding_residual,
                "max_absolute_pts_after_first_delta_seconds": (
                    max_absolute_pts_after_first_delta
                ),
                "absolute_effective_end_delta_seconds": absolute_effective_end_delta,
                "decoded_frame_count_delta": frame_count_delta,
                "decoded_sample_count_delta": sample_count_delta,
                "output_minus_source_sample_count": sample_count_delta_signed,
                "sample_layout_absolute_delta_samples": sample_layout_delta,
                "tail_only_padding_shape": tail_only_padding_shape,
                "equal_sample_counts_after_first": equal_tail_after_first,
                "first_access_unit_sample_delta": first_access_unit_sample_delta,
                "established_access_unit_samples": established_access_unit_samples,
                "output_first_pts_earlier_seconds": output_first_pts_earlier_seconds,
                "first_pts_priming_residual_seconds": first_pts_priming_residual,
                "gap_structure_match": gap_structure_match,
                "source_gap_count": src_decoded["gap_count"],
                "output_gap_count": out_decoded["gap_count"],
            })
        audio_checks.append({
            "stream": index,
            "passed": not stream_failures,
            "failed_fields": stream_failures,
            "metadata_duration_delta_seconds": metadata_duration_delta,
            "codec_source": src_audio.get("codec_name"),
            "codec_output": out_audio.get("codec_name"),
            "channels_source": src_audio.get("channels"),
            "channels_output": out_audio.get("channels"),
            "sample_rate_source": src_audio.get("sample_rate"),
            "sample_rate_output": out_audio.get("sample_rate"),
            "duration_source_seconds": src_audio_duration,
            "duration_output_seconds": out_audio_duration,
            "bit_rate_source_bps": src_bitrate,
            "bit_rate_output_bps": out_bitrate,
            "decoded": decoded_comparison,
        })

    source_rotation = src_video.get("rotation_degrees_normalized") or 0
    output_rotation = out_video.get("rotation_degrees_normalized") or 0
    autorotation_needed = (
        source_rotation != 0
        or output_rotation != 0
        or (src_video.get("width"), src_video.get("height"))
        != (out_video.get("width"), out_video.get("height"))
    )
    if autorotation_needed and source_display == output_display:
        warnings.append({
            "code": "DISPLAY_EQUIVALENT_AUTOROTATION",
            "detail": "Both inputs will be explicitly autorotated to their matching display geometry",
        })

    hard_failures: list[dict[str, Any]] = []
    seen_failures: set[str] = set()
    for failure in comparison_failures + pl_integrity_failures:
        failure_key = json.dumps(failure, sort_keys=True, separators=(",", ":"))
        if failure_key not in seen_failures:
            seen_failures.add(failure_key)
            hard_failures.append(failure)
    return {
        "passed": not hard_failures,
        "comparison_passed": not comparison_failures,
        "pl_integrity_passed": not pl_integrity_failures,
        "hard_failures": hard_failures,
        "comparison_failures": comparison_failures,
        "pl_integrity_failures": pl_integrity_failures,
        "pl_disqualifiers": pl_integrity_failures,
        "warnings": warnings,
        "display_geometry": {
            "source_coded": [src_video.get("width"), src_video.get("height")],
            "output_coded": [out_video.get("width"), out_video.get("height")],
            "source_display": list(source_display),
            "output_display": list(output_display),
            "source_rotation_degrees": src_video.get("rotation_degrees_normalized"),
            "output_rotation_degrees": out_video.get("rotation_degrees_normalized"),
            "display_equivalent": source_display == output_display,
            "explicit_autorotation": True,
            "autorotation_changes_geometry": autorotation_needed,
            "scaling_applied": False,
        },
        "cadence": {
            "source": _technical_timeline(source_timeline),
            "output": _technical_timeline(output_timeline),
            "frame_count_match": frame_count_match,
            "paired_frame_count": paired_count,
            "timestamp_tolerance_seconds": timestamp_tolerance,
            "timestamp_mismatch_count": mismatched_pts,
            "max_timestamp_delta_seconds": max(pts_deltas) if pts_deltas else None,
            "timeline_duration_delta_seconds": timeline_duration_delta,
            "container_duration_delta_seconds": format_duration_delta,
            "checkpoints": checkpoints,
            "fps_forcing_applied": False,
        },
        "decoded_interlace": {
            "source": source_interlace,
            "output": output_interlace,
            "paired_frame_count": interlace_paired_count,
            "unknown_or_missing_frame_count": (
                len(unknown_interlace_frames) + missing_interlace_observations
            ),
            "interlace_mismatch_frame_count": len(interlace_mismatch_frames),
            "interlaced_field_order_mismatch_or_unknown_frame_count": (
                len(field_order_unknown_or_mismatch)
            ),
            "all_source_and_output_frames_proven_progressive": (
                all_frames_proven_progressive
            ),
        },
        "audio": {
            "source_stream_count": len(source_audio),
            "output_stream_count": len(output_audio),
            "source_decoded_timelines": [
                _technical_audio_timeline(item) for item in source_audio_timelines
            ],
            "output_decoded_timelines": [
                _technical_audio_timeline(item) for item in output_audio_timelines
            ],
            "checks": audio_checks,
        },
    }


def _escape_filter_path(path: str) -> str:
    """Escape a filesystem path for a single-quoted libavfilter option value."""
    normalized = os.path.abspath(path).replace("\\", "/")
    return normalized.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'")


def _percentile(values: list[float], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    rank = (percentile / 100.0) * (len(ordered) - 1)
    lower = math.floor(rank)
    upper = math.ceil(rank)
    if lower == upper:
        return ordered[lower]
    fraction = rank - lower
    return ordered[lower] * (1.0 - fraction) + ordered[upper] * fraction


def _distribution(values: list[float]) -> dict[str, float | None]:
    return {
        f"p{percentile}": _percentile(values, percentile)
        for percentile in (0, 1, 5, 10, 25, 50, 75, 90, 95, 99, 100)
    }


def _harmonic_mean(values: list[float]) -> float | None:
    if not values:
        return None
    if any(value <= 0 for value in values):
        return 0.0
    return len(values) / sum(1.0 / value for value in values)


def _worst_fraction_mean(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    count = max(1, math.ceil(len(values) * fraction))
    return statistics.fmean(sorted(values)[:count])


def _summarize_metric_frames(
    ordered_frames: list[dict[str, Any]],
    libvmaf_log_version: Any,
) -> dict[str, Any]:
    if not ordered_frames:
        raise HarnessFailure("no retained libvmaf metric frames")
    expected_indices = list(range(len(ordered_frames)))
    actual_indices = [frame["frame_index"] for frame in ordered_frames]
    if actual_indices != expected_indices:
        raise HarnessFailure("aggregated libvmaf frames are missing, duplicated, or out of order")

    vmaf_values = [frame["vmaf"] for frame in ordered_frames]
    ssim_values = [frame["ssim"] for frame in ordered_frames]
    psnr_values = [frame["psnr_y"] for frame in ordered_frames]
    return {
        "frame_count": len(ordered_frames),
        "compared_frame_count": len(ordered_frames),
        "invalid_metric_frame_count": 0,
        "dropped_comparison_frame_count": 0,
        "vmaf_mean": statistics.fmean(vmaf_values),
        "vmaf_harmonic_mean": _harmonic_mean(vmaf_values),
        "vmaf_min": min(vmaf_values),
        "vmaf_1pct_low": _percentile(vmaf_values, 1),
        "vmaf_5pct_low": _percentile(vmaf_values, 5),
        "vmaf_worst_1pct_mean": _worst_fraction_mean(vmaf_values, 0.01),
        "vmaf_worst_5pct_mean": _worst_fraction_mean(vmaf_values, 0.05),
        "ssim_mean": statistics.fmean(ssim_values),
        "ssim_min": min(ssim_values),
        "psnr_mean": statistics.fmean(psnr_values),
        "psnr_min": min(psnr_values),
        "percentile_method": "linear_interpolation",
        "percentile_distribution": {
            "vmaf": _distribution(vmaf_values),
            "ssim": _distribution(ssim_values),
            "psnr_y_db": _distribution(psnr_values),
        },
        "per_frame_metrics": ordered_frames,
        "libvmaf_log_version": libvmaf_log_version,
    }


def _parse_vmaf_log(
    log: dict[str, Any],
    expected_frames: int,
    frame_offset: int = 0,
) -> dict[str, Any]:
    raw_frames = log.get("frames")
    if not isinstance(raw_frames, list) or not raw_frames:
        raise HarnessFailure("libvmaf log contains no metric frames")
    if len(raw_frames) != expected_frames:
        raise HarnessFailure(
            f"libvmaf compared {len(raw_frames)} frames but structural preflight expected {expected_frames}"
        )

    ordered_frames: list[dict[str, Any]] = []
    for expected_index, frame in enumerate(raw_frames):
        frame_number = _int(frame.get("frameNum"))
        if frame_number != expected_index:
            raise HarnessFailure("libvmaf frame numbers are missing, duplicated, or out of order")
        metrics = frame.get("metrics") or {}
        values = {
            "vmaf": _float(metrics.get("vmaf")),
            "ssim": _float(metrics.get("float_ssim")),
            "psnr_y": _float(metrics.get("psnr_y")),
            "psnr_cb": _float(metrics.get("psnr_cb")),
            "psnr_cr": _float(metrics.get("psnr_cr")),
        }
        if any(values[key] is None for key in ("vmaf", "ssim", "psnr_y")):
            raise HarnessFailure(f"libvmaf frame {expected_index} has missing or non-finite required metrics")
        ordered_frames.append({"frame_index": frame_offset + expected_index, **values})

    # A single log starts at frame zero unless it is an overlapped chunk. Normalize
    # only for the summary helper; callers retain the globally offset raw frames.
    normalized = [
        {**frame, "frame_index": index} for index, frame in enumerate(ordered_frames)
    ]
    summary = _summarize_metric_frames(normalized, log.get("version"))
    summary["per_frame_metrics"] = ordered_frames
    return summary


def _metric_chunks(total_frames: int, chunk_frames: int) -> list[dict[str, int]]:
    """Return logical chunks plus one-frame temporal context on internal edges."""
    if chunk_frames == 0:
        return [{
            "start_frame": 0,
            "end_frame": total_frames,
            "analysis_start_frame": 0,
            "analysis_end_frame": total_frames,
        }]
    chunks = []
    for start in range(0, total_frames, chunk_frames):
        end = min(total_frames, start + chunk_frames)
        chunks.append({
            "start_frame": start,
            "end_frame": end,
            "analysis_start_frame": max(0, start - 1),
            "analysis_end_frame": min(total_frames, end + 1),
        })
    return chunks


def _vmaf_filtergraph(
    escaped_log: str,
    threads: int,
    trim_start_frame: int | None = None,
    trim_end_frame: int | None = None,
) -> str:
    if trim_start_frame is None or trim_end_frame is None:
        distorted = "[0:v]settb=AVTB,setpts=PTS-STARTPTS[distorted];"
        reference = "[1:v]settb=AVTB,setpts=PTS-STARTPTS[reference];"
    else:
        trim = f"trim=start_frame={trim_start_frame}:end_frame={trim_end_frame}"
        distorted = f"[0:v]{trim},settb=AVTB,setpts=PTS-STARTPTS[distorted];"
        reference = f"[1:v]{trim},settb=AVTB,setpts=PTS-STARTPTS[reference];"
    return (
        distorted
        + reference
        + "[distorted][reference]libvmaf="
        + f"model='{VMAF_MODEL}':feature='{VMAF_FEATURES}':"
        + f"log_fmt=json:log_path='{escaped_log}':n_threads={threads}:"
        + "shortest=1:eof_action=endall:repeatlast=0:ts_sync_mode=nearest"
    )


def _parse_framehash(output: str, role: str) -> list[str]:
    hashes: list[str] = []
    for line in output.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        fields = [field.strip() for field in line.split(",")]
        if len(fields) < 6 or not re.fullmatch(r"[0-9a-fA-F]{64}", fields[-1]):
            raise HarnessFailure(f"{role} framehash output is malformed")
        hashes.append(fields[-1].lower())
    if not hashes:
        raise HarnessFailure(f"{role} produced zero decoded frame signatures")
    return hashes


def _probe_video_framehashes(
    ffmpeg: str,
    media_path: str,
    timeout: float,
    role: str,
    seek_seconds: float | None = None,
    trim_start_frame: int | None = None,
    trim_end_frame: int | None = None,
) -> list[str]:
    command = [ffmpeg, "-hide_banner", "-nostats", "-nostdin", "-v", "error"]
    if seek_seconds is not None:
        command.extend(["-ss", f"{seek_seconds:.12f}"])
    command.extend(["-autorotate", "-i", media_path, "-map", "0:v:0"])
    if trim_start_frame is not None and trim_end_frame is not None:
        command.extend([
            "-vf",
            f"trim=start_frame={trim_start_frame}:end_frame={trim_end_frame},"
            "setpts=PTS-STARTPTS",
        ])
    command.extend([
        "-an", "-sn", "-dn", "-c:v", "rawvideo", "-fps_mode", "passthrough",
        "-f", "framehash", "-hash", "sha256", "-",
    ])
    result = _run(command, timeout, role)
    if result.returncode != 0 or result.stderr.strip():
        raise HarnessFailure(f"{role} failed ({_stderr_summary(result.stderr)})")
    return _parse_framehash(result.stdout, role)


def _chunk_execution_plan(
    chunk: dict[str, int],
    source_timeline: dict[str, Any],
    output_timeline: dict[str, Any],
    seek_enabled: bool,
) -> dict[str, Any]:
    logical_count = chunk["end_frame"] - chunk["start_frame"]
    analysis_count = chunk["analysis_end_frame"] - chunk["analysis_start_frame"]
    seek_index = max(0, chunk["analysis_start_frame"] - 2)
    local_trim_start = chunk["analysis_start_frame"] - seek_index
    local_trim_end = local_trim_start + analysis_count
    seek_applied = seek_enabled and seek_index > 0
    return {
        **chunk,
        "seek_enabled": seek_enabled,
        "logical_count": logical_count,
        "analysis_count": analysis_count,
        "seek_index": seek_index,
        "seek_applied": seek_applied,
        "seek_preroll_frames": chunk["analysis_start_frame"] - seek_index,
        "source_seek_seconds": (
            source_timeline["normalized_pts"][seek_index] if seek_applied else None
        ),
        "output_seek_seconds": (
            output_timeline["normalized_pts"][seek_index] if seek_applied else None
        ),
        "local_trim_start": local_trim_start if seek_enabled else chunk["analysis_start_frame"],
        "local_trim_end": local_trim_end if seek_enabled else chunk["analysis_end_frame"],
    }


def _verify_signature_boundaries(
    expected_hashes: list[str],
    observed_hashes: list[str],
    plan: dict[str, Any],
    role: str,
) -> dict[str, Any]:
    if len(observed_hashes) != plan["analysis_count"]:
        raise HarnessFailure(
            f"{role} chunk signature count {len(observed_hashes)} does not match "
            f"expected {plan['analysis_count']}"
        )
    first_offset = plan["start_frame"] - plan["analysis_start_frame"]
    last_offset = first_offset + plan["logical_count"] - 1
    first_global = plan["start_frame"]
    last_global = plan["end_frame"] - 1
    checks = {
        "first": {
            "global_frame_index": first_global,
            "expected_sha256": expected_hashes[first_global],
            "observed_sha256": observed_hashes[first_offset],
        },
        "last": {
            "global_frame_index": last_global,
            "expected_sha256": expected_hashes[last_global],
            "observed_sha256": observed_hashes[last_offset],
        },
    }
    for check in checks.values():
        check["matches"] = check["expected_sha256"] == check["observed_sha256"]
    if not all(check["matches"] for check in checks.values()):
        raise HarnessFailure(f"{role} seek boundary signature mismatch")
    return {
        "algorithm": "sha256 decoded raw frame",
        "observed_analysis_frame_count": len(observed_hashes),
        "certified": True,
        **checks,
    }


def _certify_chunk_boundaries(
    ffmpeg: str,
    source_path: str,
    output_path: str,
    source_timeline: dict[str, Any],
    output_timeline: dict[str, Any],
    plans: list[dict[str, Any]],
    timeout: float,
) -> dict[str, Any]:
    source_expected = _probe_video_framehashes(
        ffmpeg, source_path, timeout, "source full decoded framehash"
    )
    output_expected = _probe_video_framehashes(
        ffmpeg, output_path, timeout, "output full decoded framehash"
    )
    if len(source_expected) != source_timeline["decoded_frame_count"]:
        raise HarnessFailure("source framehash count does not match decoded structural frame count")
    if len(output_expected) != output_timeline["decoded_frame_count"]:
        raise HarnessFailure("output framehash count does not match decoded structural frame count")

    proofs = []
    for chunk_index, plan in enumerate(plans):
        source_observed = _probe_video_framehashes(
            ffmpeg,
            source_path,
            timeout,
            f"source chunk {chunk_index} decoded framehash",
            seek_seconds=plan["source_seek_seconds"],
            trim_start_frame=plan["local_trim_start"],
            trim_end_frame=plan["local_trim_end"],
        )
        output_observed = _probe_video_framehashes(
            ffmpeg,
            output_path,
            timeout,
            f"output chunk {chunk_index} decoded framehash",
            seek_seconds=plan["output_seek_seconds"],
            trim_start_frame=plan["local_trim_start"],
            trim_end_frame=plan["local_trim_end"],
        )
        proofs.append({
            "chunk_index": chunk_index,
            "start_frame_inclusive": plan["start_frame"],
            "end_frame_exclusive": plan["end_frame"],
            "seek_applied": plan["seek_applied"],
            "seek_frame_index": plan["seek_index"] if plan["seek_applied"] else None,
            "execution_mode": (
                "SEEK_WITH_PREROLL_LOCAL_TRIM"
                if plan["seek_applied"]
                else "DECODE_FROM_START_TRIM"
            ),
            "source_seek_seconds": plan["source_seek_seconds"],
            "output_seek_seconds": plan["output_seek_seconds"],
            "local_trim_start_frame": plan["local_trim_start"],
            "local_trim_end_frame": plan["local_trim_end"],
            "source": _verify_signature_boundaries(
                source_expected, source_observed, plan, f"source chunk {chunk_index}"
            ),
            "output": _verify_signature_boundaries(
                output_expected, output_observed, plan, f"output chunk {chunk_index}"
            ),
        })
    return {
        "certified": True,
        "algorithm": "ffmpeg framehash sha256 over autorotated decoded raw frames",
        "full_source_frame_count": len(source_expected),
        "full_output_frame_count": len(output_expected),
        "chunks": proofs,
    }


def _classification(metrics: dict[str, Any]) -> str:
    strongly_supported = all((
        metrics["vmaf_mean"] >= PL_THRESHOLDS["vmaf_mean_min"],
        metrics["vmaf_1pct_low"] >= PL_THRESHOLDS["vmaf_1pct_low_min"],
        metrics["vmaf_min"] >= PL_THRESHOLDS["vmaf_min_min"],
        metrics["ssim_mean"] >= PL_THRESHOLDS["ssim_mean_min"],
        metrics["ssim_min"] >= PL_THRESHOLDS["ssim_min_min"],
        metrics["psnr_mean"] >= PL_THRESHOLDS["psnr_mean_db_min"],
        metrics["psnr_min"] >= PL_THRESHOLDS["psnr_min_db_min"],
    ))
    if strongly_supported:
        return "PL_STRONGLY_SUPPORTED"
    if (
        metrics["vmaf_mean"] >= 90.0
        and metrics["vmaf_1pct_low"] >= 80.0
        and metrics["ssim_mean"] >= 0.980
        and metrics["psnr_mean"] >= 35.0
    ):
        return "PL_PLAUSIBLE_WITH_CAVEATS"
    if metrics["vmaf_mean"] >= 80.0:
        return "VISIBLE_DIFFERENCE_LIKELY"
    return "PL_NOT_SUPPORTED"


def _classification_with_integrity(
    metrics: dict[str, Any],
    pl_integrity_passed: bool,
    warnings: list[dict[str, Any]] | None = None,
) -> tuple[str, str]:
    metric_only = _classification(metrics)
    warning_codes = {
        item.get("code") for item in (warnings or []) if isinstance(item, dict)
    }
    if not pl_integrity_passed:
        final = "PL_NOT_SUPPORTED"
    elif (
        metric_only == "PL_STRONGLY_SUPPORTED"
        and warning_codes.intersection({
            "AUDIO_ENCODER_PADDING_VARIATION",
            "AUDIO_ENCODER_PRIMING_VARIATION",
        })
    ):
        final = "PL_PLAUSIBLE_WITH_CAVEATS"
    else:
        final = metric_only
    return metric_only, final


def _atomic_json_write(path: str, report: dict[str, Any]) -> None:
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(
        dir=str(destination.parent), prefix=f".{destination.name}.", suffix=".tmp"
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(report, handle, indent=2, sort_keys=False, allow_nan=False)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, destination)
    except Exception:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def _harness_provenance() -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "script_basename": Path(__file__).name,
        "script_sha256": HARNESS_SHA256,
    }


def _base_report(pair_id: str) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "pair_id": pair_id,
        "generated_at_utc": _utc_now(),
        "harness_provenance": _harness_provenance(),
        "status": "METRIC_FAILED",
        "report_complete": False,
        "measurement_valid": False,
        "classification": "METRIC_FAILED",
        "evidence_level": "METRIC_FAILED",
        "error": None,
        "notes": [],
        "pl_disqualifiers": [],
        "privacy": {
            "input_paths_recorded": False,
            "input_basenames_recorded": False,
        },
        "metric_input_order": {
            "first_main_distorted": "output",
            "second_reference": "source",
        },
        "pl_thresholds": dict(PL_THRESHOLDS),
    }


def _running_sentinel(
    pair_id: str,
    mode: str,
    prior_report_sha256: str | None = None,
) -> dict[str, Any]:
    report = _base_report(pair_id)
    report.update({
        "status": "RUNNING",
        "classification": "INCOMPLETE",
        "evidence_level": "INCOMPLETE",
        "report_complete": False,
        "measurement_valid": False,
        "error": "Measurement or certification is in progress",
        "operation_mode": mode,
        "prior_report_sha256": prior_report_sha256,
    })
    return report


def _canonical_report_sha256(report: dict[str, Any]) -> str:
    encoded = json.dumps(
        report, sort_keys=True, separators=(",", ":"), ensure_ascii=True, allow_nan=False
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _finite_metric(value: Any, label: str) -> float:
    if isinstance(value, bool):
        raise HarnessFailure(f"existing report {label} is not a finite number")
    parsed = _float(value)
    if parsed is None:
        raise HarnessFailure(f"existing report {label} is not a finite number")
    return parsed


def _metric_values_match(existing: Any, recomputed: Any, label: str) -> None:
    if isinstance(recomputed, dict):
        if not isinstance(existing, dict):
            raise HarnessFailure(f"existing report {label} is missing or malformed")
        for key, value in recomputed.items():
            _metric_values_match(existing.get(key), value, f"{label}.{key}")
        return
    if recomputed is None:
        if existing is not None:
            raise HarnessFailure(f"existing report {label} is internally inconsistent")
        return
    actual = _finite_metric(existing, label)
    if not math.isclose(actual, float(recomputed), rel_tol=1e-9, abs_tol=1e-9):
        raise HarnessFailure(f"existing report {label} is internally inconsistent")


def _metric_chunking_provenance(
    existing: dict[str, Any],
    total_frames: int,
) -> dict[str, Any]:
    chunking = existing.get("metric_chunking")
    if isinstance(chunking, dict):
        return chunking
    if "metric_chunking" in existing:
        raise HarnessFailure("existing report metric chunking provenance is malformed")

    # The earliest corrected schema-2 whole-file reports predate metric_chunking.
    # Accept only an unambiguous no-trim/no-seek filtergraph and synthesize the
    # single whole-file record needed for schema-3 certification bookkeeping.
    filtergraph = existing.get("filtergraph_provenance") or {}
    trim = filtergraph.get("trim")
    if trim is not None and (
        not isinstance(trim, dict)
        or trim.get("enabled") is not False
        or trim.get("mode") not in (None, "")
    ):
        raise HarnessFailure(
            "legacy whole-file report does not prove that frame trimming was disabled"
        )
    input_seek = filtergraph.get("input_seek")
    if input_seek is not None and (
        not isinstance(input_seek, dict)
        or input_seek.get("enabled") is not False
    ):
        raise HarnessFailure(
            "legacy whole-file report does not prove that input seeking was disabled"
        )
    if total_frames <= 0:
        raise HarnessFailure("legacy whole-file report has no valid frame coverage")
    return {
        "enabled": False,
        "requested_chunk_frames": 0,
        "sequential": True,
        "expected_total_frame_count": total_frames,
        "expected_chunk_count": 1,
        "completed_chunk_count": 1,
        "retained_total_frame_count": total_frames,
        "temporal_context_frames_per_internal_edge": 0,
        "provenance": "SYNTHESIZED_LEGACY_WHOLE_FILE_NO_TRIM",
        "chunks": [{
            "chunk_index": 0,
            "start_frame_inclusive": 0,
            "end_frame_exclusive": total_frames,
            "expected_frame_count": total_frames,
            "analysis_start_frame_inclusive": 0,
            "analysis_end_frame_exclusive": total_frames,
            "expected_analysis_frame_count": total_frames,
            "analysis_metric_frame_count": total_frames,
            "retained_metric_frame_count": total_frames,
            "seek_applied": False,
            "seek_frame_index": None,
            "source_seek_seconds": None,
            "output_seek_seconds": None,
            "local_trim_start_frame": None,
            "local_trim_end_frame": None,
            "execution_mode": "WHOLE_FILE_NO_TRIM",
            "status": "COMPLETE",
            "libvmaf_log_version": existing.get("libvmaf_log_version"),
        }],
    }


def _validate_existing_metrics(
    existing: dict[str, Any],
    pair_id: str,
    source_size: int,
    output_size: int,
) -> dict[str, Any]:
    """Audit schema-2 raw metrics before any libvmaf-free upgrade."""
    if existing.get("schema_version") != 2:
        raise HarnessFailure("--upgrade-existing requires a schema-2 report")
    if existing.get("pair_id") != pair_id:
        raise HarnessFailure("existing report pair ID does not match --pair-id")
    if (
        existing.get("status") != "COMPLETE"
        or existing.get("report_complete") is not True
        or existing.get("measurement_valid") is not True
    ):
        raise HarnessFailure("existing report is not a complete valid measurement")
    if (existing.get("structural_validation") or {}).get("passed") is not True:
        raise HarnessFailure("existing report did not pass structural validation")
    if existing.get("metric_input_order") != {
        "first_main_distorted": "output",
        "second_reference": "source",
    }:
        raise HarnessFailure("existing report does not prove correct libvmaf input order")
    filtergraph = existing.get("filtergraph_provenance") or {}
    if filtergraph.get("scale_filter") is not False or filtergraph.get("fps_filter") is not False:
        raise HarnessFailure("existing report used or does not rule out scaling/FPS forcing")
    if filtergraph.get("explicit_autorotation") is not True:
        raise HarnessFailure("existing report does not prove explicit autorotation")
    if filtergraph.get("timestamp_normalization") != "settb=AVTB,setpts=PTS-STARTPTS":
        raise HarnessFailure("existing report timestamp normalization is unproven")
    expected_framesync = {
        "shortest": True,
        "eof_action": "endall",
        "repeatlast": False,
        "ts_sync_mode": "nearest",
    }
    if filtergraph.get("framesync") != expected_framesync:
        raise HarnessFailure("existing report framesync behavior is unproven")
    if filtergraph.get("log_path_windows_drive_colon_escaped") is not True:
        raise HarnessFailure("existing report does not prove the corrected Windows VMAF log path")
    old_tools = existing.get("tool_provenance") or {}
    if old_tools.get("vmaf_model") != VMAF_MODEL:
        raise HarnessFailure("existing report VMAF model is missing or unsupported")
    if old_tools.get("features") != ["vmaf", "float_ssim", "psnr"]:
        raise HarnessFailure("existing report metric feature set is incomplete")

    sizes = existing.get("file_sizes") or {}
    if (
        isinstance(sizes.get("source_bytes"), bool)
        or isinstance(sizes.get("output_bytes"), bool)
        or _int(sizes.get("source_bytes")) != source_size
        or _int(sizes.get("output_bytes")) != output_size
    ):
        raise HarnessFailure("existing report file sizes do not exactly match current inputs")

    frame_count = _int(existing.get("frame_count"))
    if frame_count is None or frame_count <= 0:
        raise HarnessFailure("existing report has no valid compared frame count")
    if (
        _int(existing.get("compared_frame_count")) != frame_count
        or _int(existing.get("invalid_metric_frame_count")) != 0
        or _int(existing.get("dropped_comparison_frame_count")) != 0
    ):
        raise HarnessFailure("existing report frame accounting is incomplete")
    raw_frames = existing.get("per_frame_metrics")
    if not isinstance(raw_frames, list) or len(raw_frames) != frame_count:
        raise HarnessFailure("existing report raw frame metrics are incomplete")

    audited_frames: list[dict[str, Any]] = []
    for expected_index, frame in enumerate(raw_frames):
        if not isinstance(frame, dict) or frame.get("frame_index") != expected_index:
            raise HarnessFailure("existing report frame metrics are missing, duplicated, or out of order")
        audited: dict[str, Any] = {"frame_index": expected_index}
        for metric in ("vmaf", "ssim", "psnr_y"):
            audited[metric] = _finite_metric(
                frame.get(metric), f"per_frame_metrics[{expected_index}].{metric}"
            )
        for metric in ("psnr_cb", "psnr_cr"):
            value = frame.get(metric)
            audited[metric] = (
                None if value is None else _finite_metric(
                    value, f"per_frame_metrics[{expected_index}].{metric}"
                )
            )
        audited_frames.append(audited)

    log_version = existing.get("libvmaf_log_version")
    if log_version is None or (
        isinstance(log_version, str) and log_version in UNKNOWN_VALUES
    ):
        raise HarnessFailure("existing report has no libvmaf log version")
    recomputed = _summarize_metric_frames(audited_frames, log_version)
    for key in (
        "frame_count", "compared_frame_count", "invalid_metric_frame_count",
        "dropped_comparison_frame_count", "vmaf_mean", "vmaf_harmonic_mean",
        "vmaf_min", "vmaf_1pct_low", "vmaf_5pct_low", "vmaf_worst_1pct_mean",
        "vmaf_worst_5pct_mean", "ssim_mean", "ssim_min", "psnr_mean", "psnr_min",
        "percentile_distribution",
    ):
        _metric_values_match(existing.get(key), recomputed[key], key)
    if existing.get("classification") != _classification(recomputed):
        raise HarnessFailure("existing report classification is inconsistent with raw metrics")

    chunking = _metric_chunking_provenance(existing, frame_count)
    if _int(chunking.get("expected_total_frame_count")) != frame_count:
        raise HarnessFailure("existing report chunk accounting does not match raw metrics")
    if _int(chunking.get("retained_total_frame_count")) != frame_count:
        raise HarnessFailure("existing report did not retain exactly every compared frame")
    return recomputed


def _existing_chunk_plans(
    existing: dict[str, Any],
    total_frames: int,
    source_timeline: dict[str, Any],
    output_timeline: dict[str, Any],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], bool]:
    chunking = _metric_chunking_provenance(existing, total_frames)
    enabled = chunking.get("enabled") is True
    records = chunking.get("chunks")
    if not isinstance(records, list) or not records:
        raise HarnessFailure("existing report has no complete chunk records")
    if _int(chunking.get("expected_chunk_count")) != len(records):
        raise HarnessFailure("existing report chunk count is inconsistent")
    if _int(chunking.get("completed_chunk_count")) != len(records):
        raise HarnessFailure("existing report contains incomplete chunks")

    chunks: list[dict[str, int]] = []
    previous_end = 0
    for index, record in enumerate(records):
        if not isinstance(record, dict) or record.get("status") != "COMPLETE":
            raise HarnessFailure("existing report contains an incomplete chunk")
        values = {
            "start_frame": _int(record.get("start_frame_inclusive")),
            "end_frame": _int(record.get("end_frame_exclusive")),
            "analysis_start_frame": _int(record.get("analysis_start_frame_inclusive")),
            "analysis_end_frame": _int(record.get("analysis_end_frame_exclusive")),
        }
        if any(value is None for value in values.values()):
            raise HarnessFailure("existing report chunk bounds are malformed")
        chunk = {key: int(value) for key, value in values.items()}
        if (
            chunk["start_frame"] != previous_end
            or chunk["end_frame"] <= chunk["start_frame"]
            or chunk["analysis_start_frame"] > chunk["start_frame"]
            or chunk["analysis_end_frame"] < chunk["end_frame"]
            or chunk["analysis_start_frame"] < 0
            or chunk["analysis_end_frame"] > total_frames
        ):
            raise HarnessFailure("existing report chunk coverage is invalid")
        logical_count = chunk["end_frame"] - chunk["start_frame"]
        analysis_count = chunk["analysis_end_frame"] - chunk["analysis_start_frame"]
        if (
            _int(record.get("chunk_index")) != index
            or _int(record.get("expected_frame_count")) != logical_count
            or _int(record.get("expected_analysis_frame_count")) != analysis_count
            or _int(record.get("analysis_metric_frame_count")) != analysis_count
            or _int(record.get("retained_metric_frame_count")) != logical_count
        ):
            raise HarnessFailure("existing report chunk frame accounting is inconsistent")
        chunks.append(chunk)
        previous_end = chunk["end_frame"]
    if previous_end != total_frames:
        raise HarnessFailure("existing report chunks do not cover the full decoded timeline")
    if not enabled and len(chunks) != 1:
        raise HarnessFailure("existing whole-file report unexpectedly contains multiple chunks")

    seek_enabled = bool(
        ((existing.get("filtergraph_provenance") or {}).get("input_seek") or {}).get("enabled")
    )
    if seek_enabled and not enabled:
        raise HarnessFailure("existing whole-file report has inconsistent seek provenance")
    plans = [
        _chunk_execution_plan(chunk, source_timeline, output_timeline, seek_enabled)
        for chunk in chunks
    ]
    trim_provenance = (existing.get("filtergraph_provenance") or {}).get("trim") or {}
    if enabled and not seek_enabled and (
        trim_provenance.get("enabled") is not True
        or trim_provenance.get("mode") != "start_frame/end_frame"
    ):
        raise HarnessFailure(
            "legacy chunk report does not prove decode-from-start global frame trimming"
        )
    for record, plan in zip(records, plans):
        if bool(record.get("seek_applied")) != plan["seek_applied"]:
            raise HarnessFailure("existing report seek application is inconsistent")
        if seek_enabled:
            for field, expected in (
                ("seek_frame_index", plan["seek_index"]),
                ("local_trim_start_frame", plan["local_trim_start"] if enabled else None),
                ("local_trim_end_frame", plan["local_trim_end"] if enabled else None),
            ):
                actual = record.get(field)
                if field == "seek_frame_index" and not plan["seek_applied"] and actual is None:
                    continue
                if expected is None:
                    if actual is not None:
                        raise HarnessFailure("existing report trim provenance is inconsistent")
                elif _int(actual) != expected:
                    raise HarnessFailure("existing report trim provenance is inconsistent")
        elif enabled:
            # Older schema-2 chunks decoded from frame zero and used global trim
            # indices. Their records predate explicit seek/local-trim fields.
            for field, expected in (
                ("local_trim_start_frame", plan["analysis_start_frame"]),
                ("local_trim_end_frame", plan["analysis_end_frame"]),
            ):
                actual = record.get(field)
                if actual is not None and _int(actual) != expected:
                    raise HarnessFailure("legacy global trim provenance is inconsistent")
        if seek_enabled and plan["seek_applied"]:
            for field, expected in (
                ("source_seek_seconds", plan["source_seek_seconds"]),
                ("output_seek_seconds", plan["output_seek_seconds"]),
            ):
                actual = _finite_metric(record.get(field), field)
                if not math.isclose(actual, expected, rel_tol=0.0, abs_tol=1e-9):
                    raise HarnessFailure("existing report seek timestamp is inconsistent")
    return chunks, plans, seek_enabled


def _upgraded_chunking(
    existing: dict[str, Any],
    plans: list[dict[str, Any]],
) -> dict[str, Any]:
    old = _metric_chunking_provenance(existing, plans[-1]["end_frame"])
    chunks = []
    for old_record, plan in zip(old["chunks"], plans):
        record = {
            "chunk_index": old_record["chunk_index"],
            "start_frame_inclusive": plan["start_frame"],
            "end_frame_exclusive": plan["end_frame"],
            "expected_frame_count": plan["logical_count"],
            "analysis_start_frame_inclusive": plan["analysis_start_frame"],
            "analysis_end_frame_exclusive": plan["analysis_end_frame"],
            "expected_analysis_frame_count": plan["analysis_count"],
            "analysis_metric_frame_count": old_record["analysis_metric_frame_count"],
            "retained_metric_frame_count": old_record["retained_metric_frame_count"],
            "seek_applied": plan["seek_applied"],
            "seek_frame_index": plan["seek_index"] if plan["seek_applied"] else None,
            "source_seek_seconds": plan["source_seek_seconds"],
            "output_seek_seconds": plan["output_seek_seconds"],
            "local_trim_start_frame": (
                plan["local_trim_start"] if old.get("enabled") is True else None
            ),
            "local_trim_end_frame": (
                plan["local_trim_end"] if old.get("enabled") is True else None
            ),
            "execution_mode": (
                "SEEK_WITH_PREROLL_LOCAL_TRIM"
                if plan["seek_applied"]
                else (
                    "DECODE_FROM_START_GLOBAL_TRIM"
                    if old.get("enabled") is True else "WHOLE_FILE_NO_TRIM"
                )
            ),
            "status": "COMPLETE",
            "libvmaf_log_version": old_record.get("libvmaf_log_version"),
        }
        runtime = _float(old_record.get("runtime_seconds"))
        if runtime is not None:
            record["original_runtime_seconds"] = runtime
        chunks.append(record)
    return {
        "enabled": old.get("enabled") is True,
        "requested_chunk_frames": _int(old.get("requested_chunk_frames")) or 0,
        "sequential": True,
        "expected_total_frame_count": plans[-1]["end_frame"],
        "expected_chunk_count": len(plans),
        "completed_chunk_count": len(plans),
        "retained_total_frame_count": plans[-1]["end_frame"],
        "temporal_context_frames_per_internal_edge": (
            _int(old.get("temporal_context_frames_per_internal_edge")) or 0
        ),
        "metrics_reused_without_libvmaf": True,
        "original_chunking_provenance": old.get("provenance", "REPORTED"),
        "chunks": chunks,
    }


def upgrade_existing(
    ffmpeg: str,
    source_path: str,
    output_path: str,
    pair_id: str,
    existing: dict[str, Any],
    timeout: float = 0,
    ffprobe: str | None = None,
    audited_metrics: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Re-certify schema-2 evidence without invoking libvmaf."""
    started = time.perf_counter()
    report = _base_report(pair_id)
    report["operation_mode"] = "UPGRADE_EXISTING_NO_LIBVMAF"
    ffprobe = ffprobe or _ffprobe_for(ffmpeg)
    source_size = os.path.getsize(source_path)
    output_size = os.path.getsize(output_path)
    original_sha256 = _canonical_report_sha256(existing)
    try:
        metrics = audited_metrics or _validate_existing_metrics(
            existing, pair_id, source_size, output_size
        )
        report["tool_provenance"] = {
            "ffmpeg": _tool_version(ffmpeg, timeout, "ffmpeg"),
            "ffprobe": _tool_version(ffprobe, timeout, "ffprobe"),
            "harness": _harness_provenance(),
            "libvmaf_executed_during_upgrade": False,
            "original_libvmaf_log_version": metrics["libvmaf_log_version"],
        }
        _, source_meta = _probe_metadata(ffprobe, source_path, timeout, "source")
        _, output_meta = _probe_metadata(ffprobe, output_path, timeout, "output")
        source_frames = _probe_video_frames(ffprobe, source_path, timeout, "source")
        output_frames = _probe_video_frames(ffprobe, output_path, timeout, "output")
        source_timeline = _timeline(source_frames, source_meta["video"].get("time_base"), "source")
        output_timeline = _timeline(output_frames, output_meta["video"].get("time_base"), "output")
        source_audio = _probe_all_audio_timelines(
            ffprobe, source_path, source_meta, timeout, "source"
        )
        output_audio = _probe_all_audio_timelines(
            ffprobe, output_path, output_meta, timeout, "output"
        )
    except HarnessFailure as exc:
        report["error"] = str(exc)
        report["total_runtime_seconds"] = time.perf_counter() - started
        return report

    report["file_sizes"] = {
        "source_bytes": source_size,
        "output_bytes": output_size,
        "bytes_saved": source_size - output_size,
        "percentage_saved": ((source_size - output_size) / source_size * 100.0) if source_size else None,
    }
    report["source_meta"] = source_meta
    report["output_meta"] = output_meta
    structure = _compare_structure(
        source_meta, output_meta, source_timeline, output_timeline, source_audio, output_audio
    )
    report["structural_validation"] = structure
    report["notes"] = [item["code"] for item in structure["warnings"]]
    report["pl_disqualifiers"] = structure["pl_integrity_failures"]
    report["metadata_comparison"] = {
        "resolution_match": structure["display_geometry"]["display_equivalent"],
        "fps_match": structure["cadence"]["timestamp_mismatch_count"] == 0,
        "duration_delta_ms": (
            structure["cadence"]["container_duration_delta_seconds"] * 1000.0
            if structure["cadence"]["container_duration_delta_seconds"] is not None else None
        ),
        "nb_frames_src": source_timeline["decoded_frame_count"],
        "nb_frames_out": output_timeline["decoded_frame_count"],
        "pix_fmt_match": source_meta["video"].get("pix_fmt") == output_meta["video"].get("pix_fmt"),
        "color_metadata_match": not any(
            item["code"] == "COLOR_METADATA_REGRESSION"
            for item in structure["pl_integrity_failures"]
        ),
        "audio_params_match": not any(
            item["code"].startswith("AUDIO_")
            for item in structure["pl_integrity_failures"]
        ),
        "video_codec_src": source_meta["video"].get("codec_name"),
        "video_codec_out": output_meta["video"].get("codec_name"),
    }
    if not structure["comparison_passed"]:
        report.update({
            "status": "INVALID_COMPARISON",
            "report_complete": True,
            "classification": "INVALID_COMPARISON",
            "evidence_level": "INVALID_COMPARISON",
            "error": (
                "Schema-3 video correspondence re-certification failed; "
                "preserved metrics were rejected"
            ),
            "total_runtime_seconds": time.perf_counter() - started,
        })
        return report
    if source_timeline["decoded_frame_count"] != metrics["frame_count"]:
        report.update({
            "status": "INVALID_COMPARISON",
            "report_complete": True,
            "classification": "INVALID_COMPARISON",
            "evidence_level": "INVALID_COMPARISON",
            "error": "current decoded frame count does not match preserved raw metrics",
            "total_runtime_seconds": time.perf_counter() - started,
        })
        return report

    try:
        old_chunking = _metric_chunking_provenance(existing, metrics["frame_count"])
        _, plans, seek_enabled = _existing_chunk_plans(
            existing, metrics["frame_count"], source_timeline, output_timeline
        )
        if old_chunking.get("enabled") is True:
            certification = _certify_chunk_boundaries(
                ffmpeg, source_path, output_path, source_timeline, output_timeline,
                plans, timeout,
            )
        else:
            certification = {
                "certified": True,
                "mode": "NOT_APPLICABLE_WHOLE_FILE_NO_SEEK",
                "chunks": [],
            }
    except HarnessFailure as exc:
        report["error"] = str(exc)
        report["total_runtime_seconds"] = time.perf_counter() - started
        return report

    source_duration = source_timeline["frame_timeline_duration_seconds"]
    output_duration = output_timeline["frame_timeline_duration_seconds"]
    report["bitrate_and_savings"] = {
        "source_bit_rate_bps": (
            source_size * 8.0 / source_duration if source_duration > 0 else None
        ),
        "output_bit_rate_bps": (
            output_size * 8.0 / output_duration if output_duration > 0 else None
        ),
        **report["file_sizes"],
    }
    report["filtergraph_provenance"] = {
        "fps_filter": False,
        "scale_filter": False,
        "timestamp_normalization": "settb=AVTB,setpts=PTS-STARTPTS",
        "explicit_autorotation": True,
        "framesync": {
            "shortest": True,
            "eof_action": "endall",
            "repeatlast": False,
            "ts_sync_mode": "nearest",
        },
        "log_path_windows_drive_colon_escaped": True,
        "metrics_reused_from_schema_2": True,
        "trim": {
            "enabled": old_chunking.get("enabled") is True,
            "mode": (
                "start_frame/end_frame"
                if old_chunking.get("enabled") is True else None
            ),
        },
        "input_seek": {
            "enabled": seek_enabled,
            "boundary_signatures_certified_during_upgrade": True,
        },
    }
    report["metric_chunking"] = _upgraded_chunking(existing, plans)
    report["seek_boundary_certification"] = certification
    report["upgrade_provenance"] = {
        "original_schema_version": 2,
        "original_report_sha256": original_sha256,
        "original_harness_sha256": (
            (existing.get("harness_provenance") or {}).get("script_sha256")
        ),
        "raw_frame_metrics_recomputed_and_matched": True,
        "source_output_sizes_exact": True,
        "libvmaf_rerun": False,
        "legacy_whole_file_chunking_synthesized": (
            old_chunking.get("provenance")
            == "SYNTHESIZED_LEGACY_WHOLE_FILE_NO_TRIM"
        ),
    }
    report.update(metrics)
    metric_only_classification, classification = _classification_with_integrity(
        metrics, structure["pl_integrity_passed"], structure["warnings"]
    )
    classification_cap_warning_codes = (
        [
            item["code"] for item in structure["warnings"]
            if item.get("code") in {
                "AUDIO_ENCODER_PADDING_VARIATION",
                "AUDIO_ENCODER_PRIMING_VARIATION",
            }
        ]
        if metric_only_classification == "PL_STRONGLY_SUPPORTED"
        and classification == "PL_PLAUSIBLE_WITH_CAVEATS"
        else []
    )
    report.update({
        "status": "COMPLETE",
        "report_complete": True,
        "measurement_valid": True,
        "metric_only_classification": metric_only_classification,
        "classification_forced_by_pl_integrity": (
            not structure["pl_integrity_passed"]
        ),
        "classification_capped_by_warning_codes": classification_cap_warning_codes,
        "classification": classification,
        "evidence_level": classification,
        "error": None,
        "total_runtime_seconds": time.perf_counter() - started,
    })
    return report


def measure(
    ffmpeg: str,
    source_path: str,
    output_path: str,
    pair_id: str,
    threads: int = 4,
    timeout: float = 0,
    ffprobe: str | None = None,
    chunk_frames: int = 0,
) -> dict[str, Any]:
    started = time.perf_counter()
    report = _base_report(pair_id)
    ffprobe = ffprobe or _ffprobe_for(ffmpeg)
    source_size = os.path.getsize(source_path)
    output_size = os.path.getsize(output_path)
    report["file_sizes"] = {
        "source_bytes": source_size,
        "output_bytes": output_size,
        "bytes_saved": source_size - output_size,
        "percentage_saved": ((source_size - output_size) / source_size * 100.0) if source_size else None,
    }

    try:
        report["tool_provenance"] = {
            "ffmpeg": _tool_version(ffmpeg, timeout, "ffmpeg"),
            "ffprobe": _tool_version(ffprobe, timeout, "ffprobe"),
            "harness": _harness_provenance(),
            "vmaf_model": VMAF_MODEL,
            "features": ["vmaf", "float_ssim", "psnr"],
            "threads": threads,
            "timeout_seconds": timeout if timeout > 0 else None,
            "chunk_frames": chunk_frames,
        }
        _, source_meta = _probe_metadata(ffprobe, source_path, timeout, "source")
        _, output_meta = _probe_metadata(ffprobe, output_path, timeout, "output")
        source_frames = _probe_video_frames(ffprobe, source_path, timeout, "source")
        output_frames = _probe_video_frames(ffprobe, output_path, timeout, "output")
        source_timeline = _timeline(source_frames, source_meta["video"].get("time_base"), "source")
        output_timeline = _timeline(output_frames, output_meta["video"].get("time_base"), "output")
        source_audio_timelines = _probe_all_audio_timelines(
            ffprobe, source_path, source_meta, timeout, "source"
        )
        output_audio_timelines = _probe_all_audio_timelines(
            ffprobe, output_path, output_meta, timeout, "output"
        )
    except HarnessFailure as exc:
        report["error"] = str(exc)
        report["total_runtime_seconds"] = time.perf_counter() - started
        return report

    report["source_meta"] = source_meta
    report["output_meta"] = output_meta
    structure = _compare_structure(
        source_meta,
        output_meta,
        source_timeline,
        output_timeline,
        source_audio_timelines,
        output_audio_timelines,
    )
    report["structural_validation"] = structure
    report["notes"] = [item["code"] for item in structure["warnings"]]
    report["pl_disqualifiers"] = structure["pl_integrity_failures"]
    report["metadata_comparison"] = {
        "resolution_match": structure["display_geometry"]["display_equivalent"],
        "fps_match": structure["cadence"]["timestamp_mismatch_count"] == 0,
        "duration_delta_ms": (
            structure["cadence"]["container_duration_delta_seconds"] * 1000.0
            if structure["cadence"]["container_duration_delta_seconds"] is not None else None
        ),
        "nb_frames_src": source_timeline["decoded_frame_count"],
        "nb_frames_out": output_timeline["decoded_frame_count"],
        "pix_fmt_match": source_meta["video"].get("pix_fmt") == output_meta["video"].get("pix_fmt"),
        "color_metadata_match": not any(
            item["code"] == "COLOR_METADATA_REGRESSION"
            for item in structure["pl_integrity_failures"]
        ),
        "audio_params_match": not any(
            item["code"].startswith("AUDIO_")
            for item in structure["pl_integrity_failures"]
        ),
        "video_codec_src": source_meta["video"].get("codec_name"),
        "video_codec_out": output_meta["video"].get("codec_name"),
    }

    source_duration = source_timeline["frame_timeline_duration_seconds"]
    output_duration = output_timeline["frame_timeline_duration_seconds"]
    report["bitrate_and_savings"] = {
        "source_bit_rate_bps": (source_size * 8.0 / source_duration) if source_duration > 0 else None,
        "output_bit_rate_bps": (output_size * 8.0 / output_duration) if output_duration > 0 else None,
        **report["file_sizes"],
    }

    if not structure["comparison_passed"]:
        report.update({
            "status": "INVALID_COMPARISON",
            "report_complete": True,
            "classification": "INVALID_COMPARISON",
            "evidence_level": "INVALID_COMPARISON",
            "error": "Video correspondence preflight failed; libvmaf was not run",
            "total_runtime_seconds": time.perf_counter() - started,
        })
        return report

    report["filtergraph_provenance"] = {
        "fps_filter": False,
        "scale_filter": False,
        "timestamp_normalization": "settb=AVTB,setpts=PTS-STARTPTS",
        "explicit_autorotation": True,
        "framesync": {
            "shortest": True,
            "eof_action": "endall",
            "repeatlast": False,
            "ts_sync_mode": "nearest",
        },
        "log_path_windows_drive_colon_escaped": True,
        "trim": {
            "enabled": chunk_frames > 0,
            "mode": "start_frame/end_frame" if chunk_frames > 0 else None,
            "temporal_context_frames_per_internal_edge": 1 if chunk_frames > 0 else 0,
        },
        "input_seek": {
            "enabled": chunk_frames > 0,
            "mode": "accurate pre-input -ss from each input's validated normalized PTS",
            "seek_preroll_frames": 2 if chunk_frames > 0 else 0,
            "omitted_at_frame_zero": True,
        },
    }
    total_frames = source_timeline["decoded_frame_count"]
    chunks = _metric_chunks(total_frames, chunk_frames)
    plans = [
        _chunk_execution_plan(
            chunk, source_timeline, output_timeline, seek_enabled=chunk_frames > 0
        )
        for chunk in chunks
    ]
    report["metric_chunking"] = {
        "enabled": chunk_frames > 0,
        "requested_chunk_frames": chunk_frames,
        "sequential": True,
        "expected_total_frame_count": total_frames,
        "expected_chunk_count": len(chunks),
        "temporal_context_frames_per_internal_edge": 1 if chunk_frames > 0 else 0,
        "chunks": [],
    }
    all_metric_frames: list[dict[str, Any]] = []
    log_versions: list[Any] = []
    measurement_runtime = 0.0
    try:
        if chunk_frames > 0:
            report["seek_boundary_certification"] = _certify_chunk_boundaries(
                ffmpeg,
                source_path,
                output_path,
                source_timeline,
                output_timeline,
                plans,
                timeout,
            )
        else:
            report["seek_boundary_certification"] = {
                "certified": True,
                "mode": "NOT_APPLICABLE_WHOLE_FILE_NO_SEEK",
                "chunks": [],
            }
        for chunk_index, plan in enumerate(plans):
            chunk = chunks[chunk_index]
            logical_count = plan["logical_count"]
            analysis_count = plan["analysis_count"]
            seek_index = plan["seek_index"]
            local_trim_start = plan["local_trim_start"]
            local_trim_end = plan["local_trim_end"]
            source_seek_seconds = plan["source_seek_seconds"]
            output_seek_seconds = plan["output_seek_seconds"]
            seek_applied = plan["seek_applied"]
            chunk_record: dict[str, Any] = {
                "chunk_index": chunk_index,
                "start_frame_inclusive": chunk["start_frame"],
                "end_frame_exclusive": chunk["end_frame"],
                "expected_frame_count": logical_count,
                "analysis_start_frame_inclusive": chunk["analysis_start_frame"],
                "analysis_end_frame_exclusive": chunk["analysis_end_frame"],
                "expected_analysis_frame_count": analysis_count,
                "overlap_before_frames": chunk["start_frame"] - chunk["analysis_start_frame"],
                "overlap_after_frames": chunk["analysis_end_frame"] - chunk["end_frame"],
                "seek_applied": seek_applied,
                "seek_frame_index": seek_index if seek_applied else None,
                "seek_preroll_frames": chunk["analysis_start_frame"] - seek_index,
                "source_seek_seconds": source_seek_seconds if seek_applied else None,
                "output_seek_seconds": output_seek_seconds if seek_applied else None,
                "local_trim_start_frame": local_trim_start if chunk_frames > 0 else None,
                "local_trim_end_frame": local_trim_end if chunk_frames > 0 else None,
                "execution_mode": (
                    "SEEK_WITH_PREROLL_LOCAL_TRIM"
                    if seek_applied
                    else (
                        "DECODE_FROM_START_TRIM" if chunk_frames > 0
                        else "WHOLE_FILE_NO_TRIM"
                    )
                ),
                "status": "METRIC_FAILED",
            }
            report["metric_chunking"]["chunks"].append(chunk_record)
            descriptor, log_path = tempfile.mkstemp(
                prefix=f"compressor_vmaf_{pair_id}_{chunk_index}_", suffix=".json"
            )
            os.close(descriptor)
            os.unlink(log_path)
            escaped_log = _escape_filter_path(log_path)
            filtergraph = _vmaf_filtergraph(
                escaped_log,
                threads,
                local_trim_start if chunk_frames > 0 else None,
                local_trim_end if chunk_frames > 0 else None,
            )
            command = [ffmpeg, "-hide_banner", "-nostats", "-nostdin", "-v", "warning"]
            if seek_applied:
                command.extend(["-ss", f"{output_seek_seconds:.12f}"])
            command.extend(["-autorotate", "-i", output_path])
            if seek_applied:
                command.extend(["-ss", f"{source_seek_seconds:.12f}"])
            command.extend([
                "-autorotate", "-i", source_path,
                "-lavfi", filtergraph, "-an", "-f", "null", "-",
            ])
            chunk_started = time.perf_counter()
            try:
                result = _run(command, timeout, f"ffmpeg/libvmaf chunk {chunk_index}")
                chunk_runtime = time.perf_counter() - chunk_started
                measurement_runtime += chunk_runtime
                chunk_record["runtime_seconds"] = chunk_runtime
                if result.returncode != 0:
                    raise HarnessFailure(
                        f"ffmpeg/libvmaf chunk {chunk_index} exited nonzero "
                        f"({_stderr_summary(result.stderr)})"
                    )
                if not os.path.exists(log_path) or os.path.getsize(log_path) == 0:
                    raise HarnessFailure(f"ffmpeg/libvmaf chunk {chunk_index} produced no metric log")
                try:
                    with open(log_path, "r", encoding="utf-8") as handle:
                        raw_log = json.load(handle)
                except (OSError, json.JSONDecodeError) as exc:
                    raise HarnessFailure(
                        f"ffmpeg/libvmaf chunk {chunk_index} log is missing, partial, or invalid JSON"
                    ) from exc
                parsed = _parse_vmaf_log(
                    raw_log, analysis_count, frame_offset=chunk["analysis_start_frame"]
                )
                retained = [
                    frame for frame in parsed["per_frame_metrics"]
                    if chunk["start_frame"] <= frame["frame_index"] < chunk["end_frame"]
                ]
                if len(retained) != logical_count:
                    raise HarnessFailure(
                        f"libvmaf chunk {chunk_index} retained {len(retained)} frames; "
                        f"expected {logical_count}"
                    )
                all_metric_frames.extend(retained)
                log_versions.append(parsed.get("libvmaf_log_version"))
                chunk_record.update({
                    "status": "COMPLETE",
                    "analysis_metric_frame_count": parsed["frame_count"],
                    "retained_metric_frame_count": len(retained),
                    "libvmaf_log_version": parsed.get("libvmaf_log_version"),
                })
            finally:
                try:
                    os.unlink(log_path)
                except FileNotFoundError:
                    pass

        if len(all_metric_frames) != total_frames:
            raise HarnessFailure(
                f"chunk aggregation retained {len(all_metric_frames)} frames; expected {total_frames}"
            )
        if len(set(log_versions)) != 1:
            raise HarnessFailure("libvmaf version changed between chunks")
        metrics = _summarize_metric_frames(all_metric_frames, log_versions[0])
        report["measurement_runtime_seconds"] = measurement_runtime
        report["metric_chunking"]["completed_chunk_count"] = len(chunks)
        report["metric_chunking"]["retained_total_frame_count"] = len(all_metric_frames)
    except HarnessFailure as exc:
        report.update({
            "status": "METRIC_FAILED",
            "classification": "METRIC_FAILED",
            "evidence_level": "METRIC_FAILED",
            "error": str(exc),
            "measurement_runtime_seconds": measurement_runtime,
            "total_runtime_seconds": time.perf_counter() - started,
        })
        return report

    metric_only_classification, classification = _classification_with_integrity(
        metrics, structure["pl_integrity_passed"], structure["warnings"]
    )
    classification_cap_warning_codes = (
        [
            item["code"] for item in structure["warnings"]
            if item.get("code") in {
                "AUDIO_ENCODER_PADDING_VARIATION",
                "AUDIO_ENCODER_PRIMING_VARIATION",
            }
        ]
        if metric_only_classification == "PL_STRONGLY_SUPPORTED"
        and classification == "PL_PLAUSIBLE_WITH_CAVEATS"
        else []
    )
    report.update(metrics)
    report.update({
        "status": "COMPLETE",
        "report_complete": True,
        "measurement_valid": True,
        "metric_only_classification": metric_only_classification,
        "classification_forced_by_pl_integrity": (
            not structure["pl_integrity_passed"]
        ),
        "classification_capped_by_warning_codes": classification_cap_warning_codes,
        "classification": classification,
        "evidence_level": classification,
        "error": None,
        "total_runtime_seconds": time.perf_counter() - started,
    })
    return report


def _validate_cli(args: argparse.Namespace) -> None:
    if not PAIR_ID_RE.fullmatch(args.pair_id):
        raise HarnessFailure("--pair-id must be a 1-64 character anonymized identifier")
    if args.threads < 1:
        raise HarnessFailure("--threads must be at least 1")
    if args.timeout < 0:
        raise HarnessFailure("--timeout cannot be negative")
    if args.chunk_frames < 0:
        raise HarnessFailure("--chunk-frames cannot be negative")
    if not os.path.isfile(args.source):
        raise HarnessFailure("source input is missing or is not a regular file")
    if not os.path.isfile(args.output):
        raise HarnessFailure("output input is missing or is not a regular file")
    if Path(args.report).stem != args.pair_id:
        raise HarnessFailure("report filename stem must exactly match --pair-id")


def _print_summary(report: dict[str, Any]) -> None:
    pair_id = report["pair_id"]
    if report["status"] != "COMPLETE":
        print(f"{pair_id}: {report['status']} - {report.get('error')}", file=sys.stderr)
        for failure in (report.get("structural_validation") or {}).get("hard_failures", []):
            print(f"  {failure['code']}", file=sys.stderr)
        return
    print(
        f"{pair_id}: {report['classification']} | frames={report['frame_count']} "
        f"VMAF mean={report['vmaf_mean']:.3f} 1%low={report['vmaf_1pct_low']:.3f} "
        f"min={report['vmaf_min']:.3f} SSIM={report['ssim_mean']:.6f} "
        f"PSNR-Y={report['psnr_mean']:.3f} dB"
    )


def _load_existing_report(path: str) -> dict[str, Any]:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            report = json.load(handle)
    except FileNotFoundError as exc:
        raise HarnessFailure("--upgrade-existing report does not exist") from exc
    except (OSError, json.JSONDecodeError) as exc:
        raise HarnessFailure("--upgrade-existing report is unreadable or invalid JSON") from exc
    if not isinstance(report, dict):
        raise HarnessFailure("--upgrade-existing report root is not an object")
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, help="private source path (never written to report)")
    parser.add_argument("--output", required=True, help="private output path (never written to report)")
    parser.add_argument("--pair-id", required=True, help="anonymized report/pair identifier")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default=None)
    parser.add_argument("--json", "--report", dest="report", required=True)
    parser.add_argument("--threads", type=int, default=min(4, os.cpu_count() or 1))
    parser.add_argument(
        "--chunk-frames", type=int, default=0,
        help="logical frames per sequential libvmaf chunk; 0 preserves whole-file behavior",
    )
    parser.add_argument(
        "--timeout", type=float, default=0,
        help="per-tool timeout in seconds; 0 means no timeout",
    )
    parser.add_argument(
        "--frames", action="store_true",
        help="deprecated compatibility flag; ordered per-frame metrics are always recorded",
    )
    parser.add_argument(
        "--upgrade-existing", action="store_true",
        help=(
            "audit and upgrade an existing complete schema-2 report without rerunning libvmaf"
        ),
    )
    args = parser.parse_args(argv)

    if args.upgrade_existing:
        try:
            _validate_cli(args)
            existing = _load_existing_report(args.report)
            audited_metrics = _validate_existing_metrics(
                existing,
                args.pair_id,
                os.path.getsize(args.source),
                os.path.getsize(args.output),
            )
        except HarnessFailure as exc:
            # Preserve the schema-2 report if it cannot pass the pre-upgrade audit.
            print(f"{args.pair_id}: METRIC_FAILED - {exc}", file=sys.stderr)
            return 3
        except Exception as exc:
            print(
                f"{args.pair_id}: METRIC_FAILED - unexpected pre-upgrade failure "
                f"({type(exc).__name__})",
                file=sys.stderr,
            )
            return 3
        try:
            report = upgrade_existing(
                args.ffmpeg, args.source, args.output, args.pair_id, existing,
                timeout=args.timeout, ffprobe=args.ffprobe,
                audited_metrics=audited_metrics,
            )
        except HarnessFailure as exc:
            report = _base_report(args.pair_id)
            report["operation_mode"] = "UPGRADE_EXISTING_NO_LIBVMAF"
            report["error"] = str(exc)
        except Exception as exc:  # Defensive fail-closed boundary; no paths.
            report = _base_report(args.pair_id)
            report["operation_mode"] = "UPGRADE_EXISTING_NO_LIBVMAF"
            report["error"] = f"unexpected harness failure ({type(exc).__name__})"
        try:
            _atomic_json_write(args.report, report)
        except Exception as exc:
            print(f"report write failed ({type(exc).__name__})", file=sys.stderr)
            return 4
        _print_summary(report)
        if report["status"] == "COMPLETE":
            return 0
        return 2 if report["status"] == "INVALID_COMPARISON" else 3

    try:
        _validate_cli(args)
        _atomic_json_write(
            args.report,
            _running_sentinel(args.pair_id, "MEASURE_LIBVMAF"),
        )
        report = measure(
            args.ffmpeg, args.source, args.output, args.pair_id,
            threads=args.threads, timeout=args.timeout, ffprobe=args.ffprobe,
            chunk_frames=args.chunk_frames,
        )
    except HarnessFailure as exc:
        report = _base_report(getattr(args, "pair_id", "invalid"))
        report["error"] = str(exc)
    except Exception as exc:  # Defensive fail-closed boundary; do not expose paths.
        report = _base_report(getattr(args, "pair_id", "invalid"))
        report["error"] = f"unexpected harness failure ({type(exc).__name__})"

    try:
        _atomic_json_write(args.report, report)
    except Exception as exc:
        print(f"report write failed ({type(exc).__name__})", file=sys.stderr)
        return 4
    _print_summary(report)
    if report["status"] == "COMPLETE":
        return 0
    return 2 if report["status"] == "INVALID_COMPARISON" else 3


if __name__ == "__main__":
    raise SystemExit(main())
