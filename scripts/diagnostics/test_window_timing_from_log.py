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
    assert "estimate" in s["basis"]


if __name__ == "__main__":
    test_probe_and_certification_windows_are_timed_from_their_start_lines()
    print("PASS test_probe_and_certification_windows_are_timed_from_their_start_lines\n\nOK (0 failure(s))")
