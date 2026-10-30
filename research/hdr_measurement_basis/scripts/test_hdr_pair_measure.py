#!/usr/bin/env python3
"""Tests for the HDR extraction layer's pure logic (no ffmpeg/ffprobe needed).

The decode path itself is covered by `hdr_pair_measure.py --self-test`, which synthesizes a
PQ pair and measures it. These tests cover the decisions made *around* the decode — the
fail-closed validation and the exact ffmpeg invocation — because those are where a silent
wrong answer would come from.

Run: python3 test_hdr_pair_measure.py
"""

from __future__ import annotations

import dataclasses
import os
import shutil
import subprocess
import tempfile

import numpy as np

import hdr_pair_measure as h
from hdr_pair_measure import (
    DecodedFrame,
    HarnessFailure,
    VideoMeta,
    Window,
    check_durations,
    decode_cmd,
    measure_window,
    pair_by_pts,
    plan_windows,
    validate_pair,
)


class Skip(Exception):
    """Raised by a test whose prerequisite (ffmpeg/ffprobe) is missing. Counted, never a pass."""


def pq(width: int = 3840, height: int = 2160, duration: float = 30.0) -> VideoMeta:
    return VideoMeta(width, height, "smpte2084", "bt2020", "bt2020nc", duration,
                     color_range="tv", bit_depth=10, fps=30.0)


def expect_failure(fn, *, must_mention: str):
    try:
        fn()
    except HarnessFailure as exc:
        assert must_mention.lower() in str(exc).lower(), f"wrong reason: {exc}"
        return
    raise AssertionError(f"expected HarnessFailure mentioning {must_mention!r}")


def test_matching_pq_pair_validates():
    validate_pair(pq(), pq())


def test_mismatched_supported_pixel_layouts_fail_without_implicit_conversion():
    ref = dataclasses.replace(pq(), pix_fmt="yuv444p10le")
    dist = dataclasses.replace(pq(), pix_fmt="yuv420p10le")
    expect_failure(lambda: validate_pair(ref, dist), must_mention="pixel format")


def test_geometry_mismatch_is_rejected():
    # Rescaling to compare would measure the scaler, not the encode.
    expect_failure(lambda: validate_pair(pq(3840, 2160), pq(1920, 1080)), must_mention="geometry")


def test_hlg_is_rejected_rather_than_measured():
    # The critical one: HLG has a different EOTF. Applying the PQ curve would yield
    # confident, wrong numbers instead of an error.
    hlg = VideoMeta(3840, 2160, "arib-std-b67", "bt2020", "bt2020nc", 30.0)
    expect_failure(lambda: validate_pair(hlg, hlg), must_mention="HLG")
    expect_failure(lambda: validate_pair(pq(), hlg), must_mention="HLG")


def test_sdr_is_routed_to_the_validated_harness():
    sdr = VideoMeta(1920, 1080, "bt709", "bt709", "bt709", 30.0)
    expect_failure(lambda: validate_pair(sdr, sdr), must_mention="measure_quality.py")


def test_unknown_transfer_is_rejected():
    unknown = VideoMeta(1920, 1080, "unknown", "bt2020", "bt2020nc", 30.0)
    expect_failure(lambda: validate_pair(unknown, unknown), must_mention="not PQ")


def test_primaries_mismatch_is_rejected():
    a = pq()
    b = dataclasses.replace(pq(), color_primaries="bt709")
    # b fails the PQ-primaries-independent checks first only if transfer differs; here transfer
    # matches, so the primaries check is what must catch it.
    expect_failure(lambda: validate_pair(a, b), must_mention="primaries")


def test_degenerate_geometry_is_rejected():
    expect_failure(lambda: validate_pair(pq(0, 0), pq(0, 0)), must_mention="geometry")


def test_window_plan_matches_the_app_sampling_shape():
    # Too short to sample honestly.
    assert plan_windows(1.5) == []
    # Short clips get one centred window.
    short = plan_windows(6.0)
    assert len(short) == 1
    assert abs(short[0].start_s - (6.0 - 1.2) / 2.0) < 1e-9
    # Long clips sample at 20/50/80%, same as QualityProbePolicy.probeWindows.
    long = plan_windows(30.0)
    assert len(long) == 3
    assert [round(w.start_s, 3) for w in long] == [6.0, 15.0, 24.0]


def test_windows_never_run_past_the_end():
    for duration in (10.0, 10.5, 12.0, 100.0):
        for w in plan_windows(duration):
            assert w.start_s + w.duration_s <= duration + 1e-9, (duration, w)


def test_decode_cmd_hands_back_native_samples_with_no_scaler():
    # b177 F7: swscale's 10-bit limited-range expansion is inexact (940 -> 0.99615), so the
    # matrix and range are applied in yuv_to_rgb_prime, and ffmpeg must not convert anything.
    cmd = decode_cmd("ffmpeg", "in.mp4", Window(4.0, 1.2), pq())
    joined = " ".join(cmd)
    assert cmd[cmd.index("-pix_fmt") + 1] == "yuv420p10le"
    assert cmd[cmd.index("-vf") + 1] == "showinfo"
    for forbidden in ("scale", "tonemap", "zscale", "transfer=", "hable", "reinhard", "rgb48"):
        assert forbidden not in joined, f"decode must not use {forbidden!r}"
    assert "rawvideo" in joined


def test_decode_cmd_seeks_and_bounds_the_window():
    cmd = decode_cmd("ffmpeg", "in.mp4", Window(12.5, 1.2), pq())
    assert cmd[cmd.index("-ss") + 1].startswith("12.5")
    assert cmd[cmd.index("-t") + 1].startswith("1.2")
    # Seek before -i so it is an input seek, not a slow decode-and-discard.
    assert cmd.index("-ss") < cmd.index("-i")


# ---- b177 F7: colour interpretation must be known, supported and identical -----------------

def test_unknown_primaries_on_both_sides_are_rejected():
    # Matching "unknown" is not matching BT.2020: the harness would assume BT.2020 anyway.
    u = dataclasses.replace(pq(), color_primaries="unknown")
    expect_failure(lambda: validate_pair(u, u), must_mention="primaries")


def test_matrix_mismatch_is_rejected():
    expect_failure(lambda: validate_pair(pq(), dataclasses.replace(pq(), color_space="bt709")), must_mention="matrix")


def test_unknown_matrix_is_rejected():
    u = dataclasses.replace(pq(), color_space="unknown")
    expect_failure(lambda: validate_pair(u, u), must_mention="matrix")


def test_constant_luminance_matrix_is_not_silently_decoded_as_ncl():
    cl = dataclasses.replace(pq(), color_space="bt2020c")
    expect_failure(lambda: validate_pair(cl, cl), must_mention="matrix")


def test_unknown_or_mismatched_range_is_rejected():
    u = dataclasses.replace(pq(), color_range="unknown")
    expect_failure(lambda: validate_pair(u, u), must_mention="range")
    expect_failure(lambda: validate_pair(pq(), dataclasses.replace(pq(), color_range="pc")), must_mention="range")


def test_bit_depth_must_be_known_at_least_ten_and_equal():
    expect_failure(lambda: validate_pair(pq(), dataclasses.replace(pq(), bit_depth=8)), must_mention="bit depth")
    u = dataclasses.replace(pq(), bit_depth=0)
    expect_failure(lambda: validate_pair(u, u), must_mention="bit depth")
    expect_failure(lambda: validate_pair(pq(), dataclasses.replace(pq(), bit_depth=12)), must_mention="bit depth")


def test_pq_and_hlg_are_told_apart():
    hlg = dataclasses.replace(pq(), color_transfer="arib-std-b67")
    expect_failure(lambda: validate_pair(hlg, pq()), must_mention="HLG")
    assert h.transfer_family("smpte2084") == "PQ"
    assert h.transfer_family("arib-std-b67") == "HLG"
    assert h.transfer_family("bt709") == "other"


def test_unknown_frame_rate_is_rejected():
    u = dataclasses.replace(pq(), fps=0.0)
    expect_failure(lambda: validate_pair(u, u), must_mention="frame rate")


def test_a_shorter_output_is_rejected_not_measured_up_to_its_end():
    # measure_pair used to sample min(duration): the missing tail was never looked at.
    expect_failure(lambda: check_durations(pq(duration=30.0), pq(duration=27.0)), must_mention="duration")
    check_durations(pq(duration=30.0), pq(duration=30.0 + 1.0 / 30.0))  # within one frame


def test_decode_cmd_keeps_timestamps_and_fails_on_decoder_errors():
    cmd = decode_cmd("ffmpeg", "in.mp4", Window(4.0, 1.2), pq())
    assert "showinfo" in cmd[cmd.index("-vf") + 1], "per-frame timestamps come from showinfo"
    assert "-copyts" in cmd and "-start_at_zero" in cmd
    assert "-xerror" in cmd


def test_unsupported_pixel_formats_are_rejected():
    p010 = dataclasses.replace(pq(), pix_fmt="p010le")
    expect_failure(lambda: validate_pair(p010, p010), must_mention="pixel format")


# ---- b177 F7: the matrix and range, against BT.2100 code values (pure, no ffmpeg) -----------

def _one(y, cb, cr, meta):
    a = lambda v: np.array([[v]], dtype=np.uint16)
    return h.yuv_to_rgb_prime(a(y), a(cb), a(cr), meta)[0, 0]


def test_limited_range_codes_decode_exactly():
    m = pq()
    assert np.allclose(_one(940, 512, 512, m), 1.0, atol=1e-12)
    assert np.allclose(_one(64, 512, 512, m), 0.0, atol=1e-12)
    assert np.allclose(_one(502, 512, 512, m), 0.5, atol=1e-12)


def test_full_range_codes_decode_exactly_and_differ_from_limited():
    full = dataclasses.replace(pq(), color_range="pc")
    assert np.allclose(_one(1023, 512, 512, full), 1.0, atol=1e-12)
    assert np.allclose(_one(512, 512, 512, full), 512 / 1023, atol=1e-12)
    assert abs(_one(512, 512, 512, pq())[0] - (512 - 64) / 876) < 1e-12


def test_bt2020_ncl_primaries_round_trip():
    kr, kb = 0.2627, 0.0593
    for rgb in ((1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 1.0), (0.25, 0.5, 0.75)):
        r, g, b = rgb
        yp = kr * r + (1 - kr - kb) * g + kb * b
        cb = (b - yp) / (2 * (1 - kb))
        cr = (r - yp) / (2 * (1 - kr))
        codes = (64 + 876 * yp, 512 + 896 * cb, 512 + 896 * cr)
        # Unrounded codes invert exactly; the matrix is the one declared, not BT.709.
        got = h.yuv_to_rgb_prime(*(np.array([[c]]) for c in codes), pq())[0, 0]
        assert np.allclose(got, rgb, atol=1e-9), (rgb, got)


def test_twelve_bit_limited_range_uses_its_own_quantisation():
    m12 = dataclasses.replace(pq(), bit_depth=12, pix_fmt="yuv420p12le")
    assert np.allclose(_one(3760, 2048, 2048, m12), 1.0, atol=1e-12)  # 235 * 16
    assert np.allclose(_one(256, 2048, 2048, m12), 0.0, atol=1e-12)   # 16 * 16


# ---- b177 F7: frames are paired by timestamp, and anything unpaired fails the window -------

def frames(pts_list, value=100.0):
    for pts in pts_list:
        yield DecodedFrame(pts, np.full((2, 2, 3), value))


def test_identical_timelines_pair_every_frame():
    cov = h.Coverage()
    pairs = list(pair_by_pts(frames([0.0, 0.1, 0.2]), frames([0.0, 0.1, 0.2]), 0.05, cov))
    assert len(pairs) == 3 and cov.complete and cov.matched == 3


def test_a_one_frame_skew_is_incomplete_not_a_shifted_comparison():
    cov = h.Coverage()
    pairs = list(pair_by_pts(frames([0.0, 0.1, 0.2]), frames([0.1, 0.2, 0.3]), 0.05, cov))
    # zip() would have compared ref[0] with dist[0] (0.1 s apart) three times.
    assert len(pairs) == 2
    assert not cov.complete
    assert cov.ref_unmatched == [0.0] and cov.dist_unmatched == [0.3]


def test_a_missing_tail_is_incomplete():
    cov = h.Coverage()
    list(pair_by_pts(frames([0.0, 0.1, 0.2, 0.3]), frames([0.0, 0.1]), 0.05, cov))
    assert not cov.complete and cov.ref_unmatched == [0.2, 0.3]


def test_a_decoder_error_after_valid_frames_propagates():
    def failing():
        yield DecodedFrame(0.0, np.zeros((2, 2, 3)))
        raise HarnessFailure("decoder failed: corrupt slice")
    try:
        list(pair_by_pts(frames([0.0, 0.1]), failing(), 0.05, h.Coverage()))
    except HarnessFailure as exc:
        assert "decoder failed" in str(exc)
        return
    raise AssertionError("a decoder error after valid frames was swallowed")


def test_non_monotonic_timestamps_fail():
    expect_failure(lambda: list(pair_by_pts(frames([0.0, 0.2, 0.1]), frames([0.0, 0.2, 0.1]), 0.05, h.Coverage())),
                   must_mention="monotonic")


def _with_stub_decoder(ref_pts, dist_pts, fn):
    original = h.decode_window

    def stub(ffmpeg, path, window, meta):
        return frames(ref_pts if path == "reference" else dist_pts)

    h.decode_window = stub
    try:
        return fn()
    finally:
        h.decode_window = original


def test_three_reference_frames_against_one_is_rejected():
    # The handoff reproduction: this used to return a normal one-frame measurement with DeltaE 0.
    meta = dataclasses.replace(pq(2, 2), fps=2.5)
    expect_failure(lambda: _with_stub_decoder([0.0, 0.4, 0.8], [0.0], lambda: measure_window(
        "unused", "reference", "distorted", Window(0.0, 1.2), meta)), must_mention="coverage")


def test_identity_control_measures_zero_with_full_coverage():
    meta = dataclasses.replace(pq(2, 2), fps=2.5)
    result = _with_stub_decoder([0.0, 0.4, 0.8], [0.0, 0.4, 0.8], lambda: measure_window(
        "unused", "reference", "distorted", Window(0.0, 1.2), meta))
    assert result["frames"] == 3
    assert result["coverage"] == {"expected": 3, "matched": 3, "ref_unmatched": 0, "dist_unmatched": 0}
    assert result["delta_e_itp"]["worst_frame_max"] == 0.0


def test_a_reference_window_short_of_its_expected_frames_is_rejected():
    meta = dataclasses.replace(pq(2, 2), fps=10.0)  # 1.2 s at 10 fps: 12 frames expected
    expect_failure(lambda: _with_stub_decoder([0.0, 0.1, 0.2], [0.0, 0.1, 0.2], lambda: measure_window(
        "unused", "reference", "distorted", Window(0.0, 1.2), meta)), must_mention="expected")


def test_frames_without_timestamps_cannot_be_paired():
    def bare(ffmpeg, path, window, meta):
        yield np.ones((2, 2, 3))
    original = h.decode_window
    h.decode_window = bare
    try:
        expect_failure(lambda: measure_window("unused", "r", "d", Window(0.0, 1.2), dataclasses.replace(pq(2, 2), fps=2.5)),
                       must_mention="timestamp")
    finally:
        h.decode_window = original


# ---- b177 F7: the conversion itself, against known code values (needs ffmpeg + ffprobe) ------

def _encode_known_frames(tmp, name, y, cb, cr, color_range, frames_n=6, fps=5, w=16, hgt=16):
    """A lossless FFV1 clip of flat 10-bit 4:2:0 frames with the given code values and tags."""
    luma = np.full((hgt, w), y, dtype="<u2")
    chroma_b = np.full((hgt // 2, w // 2), cb, dtype="<u2")
    chroma_r = np.full((hgt // 2, w // 2), cr, dtype="<u2")
    raw = (luma.tobytes() + chroma_b.tobytes() + chroma_r.tobytes()) * frames_n
    path = os.path.join(tmp, name)
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "yuv420p10le", "-s", f"{w}x{hgt}",
         "-r", str(fps), "-i", "-", "-c:v", "ffv1", "-color_primaries", "bt2020", "-color_trc", "smpte2084",
         "-colorspace", "bt2020nc", "-color_range", color_range, path],
        input=raw, check=True, capture_output=True, timeout=60)
    return path


def _needs_ffmpeg():
    if not (shutil.which("ffmpeg") and shutil.which("ffprobe")):
        raise Skip("ffmpeg/ffprobe not on PATH")


def _decoded_rgb_prime(y, cb, cr, color_range):
    """PQ-encoded R'G'B' in [0,1] that the harness's real decode path produces for these codes."""
    tmp = tempfile.mkdtemp(prefix="hdrvec_")
    try:
        path = _encode_known_frames(tmp, "v.mkv", y, cb, cr, color_range)
        meta = h.probe("ffprobe", path)
        validate_pair(meta, meta)
        got = list(h.decode_window("ffmpeg", path, Window(0.0, 1.0), meta))
        assert len(got) == 5, f"expected 5 frames in 1.0 s at 5 fps, got {len(got)}"
        assert [round(f.pts_s, 3) for f in got] == [0.0, 0.2, 0.4, 0.6, 0.8]
        return h.pq_inverse_eotf(got[2].rgb[8, 8]), meta
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def test_known_vectors_limited_range_white_black_and_mid_grey():
    _needs_ffmpeg()
    white, meta = _decoded_rgb_prime(940, 512, 512, "tv")
    assert meta.color_range == "tv" and meta.bit_depth == 10 and abs(meta.fps - 5.0) < 1e-9
    assert np.allclose(white, 1.0, atol=1e-9), white
    black, _ = _decoded_rgb_prime(64, 512, 512, "tv")
    # PQ's inverse EOTF of 0 cd/m^2 is c1^m2 = 7.3e-7, not 0: that is the round trip, not the decode.
    assert np.allclose(black, 0.0, atol=1e-6), black
    grey, _ = _decoded_rgb_prime(502, 512, 512, "tv")  # 64 + 0.5 * 876
    assert np.allclose(grey, 0.5, atol=1e-9), grey


def test_known_vector_full_range_is_not_read_as_limited():
    _needs_ffmpeg()
    grey, meta = _decoded_rgb_prime(512, 512, 512, "pc")
    assert meta.color_range == "pc"
    # 512/1023 = 0.5005 full range; read as limited it would be (512-64)/876 = 0.5114.
    assert np.allclose(grey, 512 / 1023, atol=1e-9), grey


def test_known_vector_bt2020_ncl_red_decodes_through_the_bt2020_matrix():
    _needs_ffmpeg()
    # R'=1, G'=0, B'=0 in BT.2020 NCL: Y'=0.2627, Cb=-0.1396, Cr=0.5 -> limited 10-bit codes.
    red, _ = _decoded_rgb_prime(294, 387, 960, "tv")
    assert red[0] > 0.99 and red[1] < 0.01 and red[2] < 0.01, red


def _main() -> int:
    failures = 0
    skips = 0
    for name, fn in sorted(globals().items()):
        if not name.startswith("test_") or not callable(fn):
            continue
        try:
            fn()
            print(f"PASS {name}")
        except Skip as exc:
            skips += 1
            print(f"SKIP {name}: {exc}")
        except Exception as exc:  # noqa: BLE001 - test harness
            failures += 1
            print(f"FAIL {name}: {exc!r}")
    print(f"\n{'FAILED' if failures else 'OK'} ({failures} failure(s), {skips} skipped)")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(_main())
