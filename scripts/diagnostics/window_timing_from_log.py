#!/usr/bin/env python3
"""Report scoring-window wall time from a decisions.log. Standard library only.

Use the scorer's monotonic timing when present. Legacy score lines can use a log interval
when their work marker is identifiable. An interval estimate includes logging and scheduling
delay. Older measured rows with ambiguous phase are reported as unknown.

Usage: python3 window_timing_from_log.py decisions.log [more.log ...] [--json out.json]
"""
from __future__ import annotations

import argparse
import datetime
import json
import re
import statistics
from typing import Any, Iterable

_TS = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+) ")
_SCORE = re.compile(r"VmafPairScorer: window \[(\d+)ms\.\.(\d+)ms\] frames=(\d+)\b")
_PROBE_MARK = re.compile(r"CompressorProbe: probe rate;.*?window=\[(\d+)\.\.(\d+)ms\]")
_PHASE = re.compile(r"; phase=(probe|cert|self-check)(?:;|$)")
_WALL = re.compile(r"\btiming\[wall=(\d+)(?:,|\])")


def _time(line: str) -> datetime.datetime | None:
    m = _TS.match(line)
    return datetime.datetime.strptime("2026-" + m.group(1), "%Y-%m-%d %H:%M:%S.%f") if m else None


def windows(lines: Iterable[str]) -> list[dict[str, Any]]:
    out = []
    phase = None
    cert_mark = None
    probe_mark = None
    for line in lines:
        if "CompressorProbe: window plan;" in line:
            declared_phase = _PHASE.search(line)
            phase = declared_phase.group(1) if declared_phase else None
            cert_mark = _time(line)
            probe_mark = None
            continue

        probe = _PROBE_MARK.search(line)
        if probe:
            probe_mark = (probe.groups(), _time(line))
            continue

        score = _SCORE.search(line)
        if not score:
            continue
        probe_match = probe_mark is not None and probe_mark[0] == score.group(1, 2)
        if phase is not None:
            kind = phase
        elif " banding[" in line or " v1shadow[" in line:
            kind = "cert"
        elif probe_match:
            kind = "probe"
        else:
            kind = "unknown"

        t = _time(line)
        mark = None
        if kind == "cert":
            mark = cert_mark
            cert_mark = t
        else:
            cert_mark = None
            if kind == "probe" and probe_match:
                mark = probe_mark[1]
        probe_mark = None

        frames = int(score.group(3))
        if frames == 0:
            continue
        wall = _WALL.search(line)
        if wall:
            seconds = int(wall.group(1)) / 1000
        elif t is not None and mark is not None and t >= mark:
            seconds = (t - mark).total_seconds()
        else:
            continue

        v1 = re.search(r"v1shadow\[[^\]]*ms=(\d+)\]", line)
        out.append({
            "kind": kind,
            "seconds": seconds,
            "measured": wall is not None,
            "frames": frames,
            "v1Seconds": int(v1.group(1)) / 1000 if v1 else 0.0,
        })
    return out


def summarize(ws: list[dict[str, Any]]) -> dict[str, Any]:
    res: dict[str, Any] = {}
    for kind in ("probe", "cert", "self-check", "unknown"):
        xs = [w for w in ws if w["kind"] == kind]
        if not xs:
            continue
        spf = sorted(w["seconds"] / w["frames"] for w in xs)
        measured = [w for w in xs if w["measured"]]
        estimated = [w for w in xs if not w["measured"]]
        res[kind] = {"windows": len(xs), "totalSeconds": round(sum(w["seconds"] for w in xs), 1),
                     "measuredWindows": len(measured),
                     "measuredSeconds": round(sum(w["seconds"] for w in measured), 1),
                     "estimatedWindows": len(estimated),
                     "estimatedSeconds": round(sum(w["seconds"] for w in estimated), 1),
                     "v1Seconds": round(sum(w["v1Seconds"] for w in xs), 1),
                     "secondsPerFrameMedian": round(statistics.median(spf), 3),
                     "secondsPerFrameP90": round(spf[int(len(spf) * 0.9)], 3),
                     "secondsPerFrameMax": round(spf[-1], 3)}
    res["basis"] = "scorer wall time plus any legacy log-timestamp interval estimates, reported separately"
    return res


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("logs", nargs="+")
    ap.add_argument("--json")
    args = ap.parse_args()
    report = {}
    for p in args.logs:
        with open(p, errors="replace") as fh:
            report[p] = summarize(windows(fh))
        print(p, json.dumps(report[p], indent=2))
    if args.json:
        with open(args.json, "w") as fh:
            json.dump(report, fh, indent=2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
