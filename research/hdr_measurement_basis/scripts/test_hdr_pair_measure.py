#!/usr/bin/env python3
"""Tests for the HDR extraction layer's pure logic (no ffmpeg/ffprobe needed).

The decode path itself is covered by `hdr_pair_measure.py --self-test`, which synthesizes a
PQ pair and measures it. These tests cover the decisions made *around* the decode — the
fail-closed validation and the exact ffmpeg invocation — because those are where a silent
wrong answer would come from.

Run: python3 test_hdr_pair_measure.py
"""

from __future__ import annotations

from hdr_pair_measure import (
    HarnessFailure,
    VideoMeta,
    Window,
    decode_cmd,
    plan_windows,
    validate_pair,
)


def pq(width: int = 3840, height: int = 2160, duration: float = 30.0) -> VideoMeta:
    return VideoMeta(width, height, "smpte2084", "bt2020", "bt2020nc", duration)


def expect_failure(fn, *, must_mention: str):
    try:
        fn()
    except HarnessFailure as exc:
        assert must_mention.lower() in str(exc).lower(), f"wrong reason: {exc}"
        return
    raise AssertionError(f"expected HarnessFailure mentioning {must_mention!r}")


def test_matching_pq_pair_validates():
    validate_pair(pq(), pq())


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
    b = VideoMeta(3840, 2160, "smpte2084", "bt709", "bt2020nc", 30.0)
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


def test_decode_cmd_uses_the_matrix_name_ffmpeg_accepts():
    # Regression guard for a real bug: the scale filter takes `bt2020`; `bt2020nc` (the
    # -colorspace / ffprobe spelling) fails with "Undefined constant".
    cmd = decode_cmd("ffmpeg", "in.mp4", Window(4.0, 1.2), pq())
    vf = cmd[cmd.index("-vf") + 1]
    assert "in_color_matrix=bt2020" in vf
    assert "bt2020nc" not in vf


def test_decode_cmd_never_tone_maps_or_converts_transfer():
    # Any of these would fold an assumption into the measurement and quietly change the answer.
    cmd = decode_cmd("ffmpeg", "in.mp4", Window(4.0, 1.2), pq())
    joined = " ".join(cmd)
    for forbidden in ("tonemap", "zscale", "transfer=", "-vf scale=w=", "hable", "reinhard"):
        assert forbidden not in joined, f"decode must not use {forbidden!r}"
    assert "rgb48le" in joined
    assert "rawvideo" in joined


def test_decode_cmd_seeks_and_bounds_the_window():
    cmd = decode_cmd("ffmpeg", "in.mp4", Window(12.5, 1.2), pq())
    assert cmd[cmd.index("-ss") + 1].startswith("12.5")
    assert cmd[cmd.index("-t") + 1].startswith("1.2")
    # Seek before -i so it is an input seek, not a slow decode-and-discard.
    assert cmd.index("-ss") < cmd.index("-i")


def _main() -> int:
    failures = 0
    for name, fn in sorted(globals().items()):
        if not name.startswith("test_") or not callable(fn):
            continue
        try:
            fn()
            print(f"PASS {name}")
        except Exception as exc:  # noqa: BLE001 - test harness
            failures += 1
            print(f"FAIL {name}: {exc!r}")
    print(f"\n{'FAILED' if failures else 'OK'} ({failures} failure(s))")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(_main())
