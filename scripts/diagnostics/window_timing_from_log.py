#!/usr/bin/env python3
"""Estimate per-window scoring time from a decisions.log (b177 WP2). Standard library only.

Builds before b177 logged no per-window timing, only each window's score line. The interval from
the line that starts a window's work (`probe rate;` for a probe clip, `window plan;` or the
previous score line for certification) to its `VmafPairScorer: window` line bounds that window's
decode + pairing + scoring time. It is an ESTIMATE from log timestamps (1 ms resolution, includes
any logging and scheduling delay), labelled as such. From b177 the score line carries
`timing[...]` measured on the monotonic clock; prefer that when present.

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


def _time(line: str) -> datetime.datetime | None:
    m = _TS.match(line)
    return datetime.datetime.strptime("2026-" + m.group(1), "%Y-%m-%d %H:%M:%S.%f") if m else None


def windows(lines: Iterable[str]) -> list[dict[str, Any]]:
    out, mark = [], None
    for line in lines:
        t = _time(line)
        if t is None:
            continue
        if "VmafPairScorer: window" in line:
            frames = re.search(r"frames=(\d+)", line)
            v1 = re.search(r"v1shadow\[[^\]]*ms=(\d+)\]", line)
            kind = "cert" if ("banding[" in line or "v1shadow[" in line) else "probe"
            if mark is not None and frames:
                out.append({"kind": kind, "seconds": (t - mark).total_seconds(), "frames": int(frames.group(1)),
                            "v1Seconds": int(v1.group(1)) / 1000 if v1 else 0.0})
            mark = t
        elif "probe rate;" in line or "window plan;" in line:
            mark = t
    return out


def summarize(ws: list[dict[str, Any]]) -> dict[str, Any]:
    res: dict[str, Any] = {}
    for kind in ("probe", "cert"):
        xs = [w for w in ws if w["kind"] == kind]
        if not xs:
            continue
        spf = sorted(w["seconds"] / w["frames"] for w in xs)
        res[kind] = {"windows": len(xs), "totalSeconds": round(sum(w["seconds"] for w in xs), 1),
                     "v1Seconds": round(sum(w["v1Seconds"] for w in xs), 1),
                     "secondsPerFrameMedian": round(statistics.median(spf), 3),
                     "secondsPerFrameP90": round(spf[int(len(spf) * 0.9)], 3),
                     "secondsPerFrameMax": round(spf[-1], 3)}
    res["basis"] = "log-timestamp interval estimate (decode + pairing + scoring), not a measured duration"
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
