#!/usr/bin/env python3
"""The log-interval estimate attributes each window to the line that started its work."""
from __future__ import annotations

from window_timing_from_log import summarize, windows

LOG = """\
09-26 15:52:37.303 I CompressorProbe: probe rate; ratio=0.70; window=[5006..6206ms]; rate[...]
09-26 15:53:28.809 I VmafPairScorer: window [5006ms..6206ms] frames=36 mean=97.86 p5=95.18 min=94.96 pairing[...]
09-26 16:08:39.696 I CompressorProbe: window plan; windowMs=1200; windows=[...]
09-26 16:11:29.631 I VmafPairScorer: window [5006ms..6206ms] frames=36 mean=98.57 p5=96.18 min=96.14 pairing[...] banding[cambi=1/2/3] v1shadow[94.333/92.863/92.735 ms=146826]
09-26 16:14:27.843 I VmafPairScorer: window [12515ms..13715ms] frames=36 mean=97.27 p5=94.64 min=94.19 pairing[...] banding[cambi=1/2/3] v1shadow[94.095/90.986/90.179 ms=154516]
""".splitlines()


def test_probe_and_certification_windows_are_timed_from_their_start_lines():
    ws = windows(LOG)
    assert [w["kind"] for w in ws] == ["probe", "cert", "cert"]
    assert abs(ws[0]["seconds"] - 51.506) < 1e-6
    assert abs(ws[1]["seconds"] - 169.935) < 1e-6
    assert abs(ws[2]["seconds"] - 178.212) < 1e-6  # from the previous score line
    s = summarize(ws)
    assert s["cert"]["v1Seconds"] == 301.3
    assert s["probe"]["windows"] == 1
    assert s["probe"]["estimatedWindows"] == 1
    assert "estimate" in s["basis"]


def test_measured_wall_time_and_declared_phase_override_legacy_heuristics():
    log = """\
09-26 15:52:37.303 I CompressorProbe: window plan; windowMs=1200; windows=[...]; phase=cert
09-26 15:53:28.809 I VmafPairScorer: window [5006ms..6206ms] frames=36 mean=98 timing[wall=2500,queue=500]
09-26 15:54:28.809 I VmafPairScorer: window [12515ms..13715ms] frames=36 mean=98 timing[wall=1000,queue=500]
09-26 15:55:00.000 I CompressorProbe: window plan; windowMs=1200; windows=[...]; phase=self-check
09-26 15:56:00.000 I VmafPairScorer: window [5006ms..6206ms] frames=36 mean=98 timing[wall=2200,queue=500]
09-26 15:56:10.000 I CompressorProbe: window plan; windowMs=1200; windows=[...]; phase=probe
09-26 15:57:10.000 I VmafPairScorer: window [5006ms..6206ms] frames=36 mean=98 timing[wall=3100,queue=500]
""".splitlines()
    ws = windows(log)
    assert [w["kind"] for w in ws] == ["cert", "cert", "self-check", "probe"]
    assert [w["seconds"] for w in ws] == [2.5, 1.0, 2.2, 3.1]
    summary = summarize(ws)
    assert summary["cert"]["measuredWindows"] == 2
    assert summary["cert"]["measuredSeconds"] == 3.5
    assert summary["cert"]["estimatedWindows"] == 0


def test_unmatched_legacy_marker_does_not_assign_a_probe_or_duration():
    log = """\
09-26 15:52:37.303 I CompressorProbe: probe rate; ratio=0.70; window=[5006..6206ms]; rate[...]
09-26 15:53:28.809 I VmafPairScorer: window [12515ms..13715ms] frames=36 mean=98
09-26 15:54:28.809 I VmafPairScorer: window [5006ms..6206ms] frames=36 mean=98 timing[wall=1200]
09-26 15:55:28.809 I VmafPairScorer: window [5006ms..6206ms] frames=0 mean=98 timing[wall=900]
""".splitlines()
    ws = windows(log)
    assert [(w["kind"], w["seconds"]) for w in ws] == [("unknown", 1.2)]
    assert summarize(ws)["unknown"]["measuredWindows"] == 1


if __name__ == "__main__":
    test_probe_and_certification_windows_are_timed_from_their_start_lines()
    test_measured_wall_time_and_declared_phase_override_legacy_heuristics()
    test_unmatched_legacy_marker_does_not_assign_a_probe_or_duration()
    print("PASS 3 window timing regressions\n\nOK (0 failure(s))")
