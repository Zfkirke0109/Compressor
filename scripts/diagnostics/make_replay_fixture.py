#!/usr/bin/env python3
"""Build the observational replay fixture the Kotlin unit tests read.

Input: the review pack's `observational-fixtures.json` (raw b169 job records for the 44 target
cases), its `target-device-set.csv` (the case class of each job), and optionally the batch's
`decisions.log` (for the size-gate lines, which job records do not carry).

Output: a tab-separated file, one row per job, with only the fields the replay uses. It is a
PARTIAL OBSERVATIONAL replay: scores are the records' three-decimal roundings, certification
window frame counts were never recorded, and the learned state the batch started from was never
captured (only its identity hash). Nothing here is filled in: an absent value stays empty and the
replay reports it as a coverage gap.

Usage:
    python3 make_replay_fixture.py observational-fixtures.json target-device-set.csv \
        [--decisions decisions.log] --out app/src/test/resources/replay/b169_targets.tsv
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys

COLUMNS = [
    "jobId", "caseClass", "terminal", "verdict", "verified", "replacementSafe", "pixelCertified",
    "outputSize", "sourceSize", "savedBytes", "countsAsRealCompression", "w", "h", "fps",
    "durationMs", "sourceTotalBitrate", "audioBitrate", "audioMime", "sourceMime",
    "plannedOutputMime", "plannedTargetRatio", "plannedTargetVideoBitrate", "pixelProvenRatio",
    "probedRatios", "probeDetail", "probeWindowScores", "certWindowScores", "certificationStatus",
    "probeRateDiag", "fallbackReason", "gateLearned", "gateFactors", "gateVerdict", "gatePredicted",
]

GATE = re.compile(
    r"size gate; job=(?P<job>job_[0-9a-f]+); proven=(?P<proven>[0-9.]+); predictedBytes=(?P<pred>\d+); "
    r"sourceBytes=\d+; measuredOvershoot=(?P<meas>[^;]*?),learned=(?P<learned>[0-9.]+),used=[0-9.]+; "
    r"verdict=(?P<verdict>encode|keep original)"
)


def clean(value) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value).replace("\t", " ").replace("\n", " ")


def gate_lines(path: str | None) -> dict[str, dict[str, str]]:
    """The last size-gate line per job: its learned overshoot, the window factors, verdict, prediction.

    The factors come from the probe rate telemetry in the job record; the gate line logs only their
    mean, so this keeps the learned value and verdict and reads the factors from the record.
    """
    out: dict[str, dict[str, str]] = {}
    if not path:
        return out
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = GATE.search(line)
            if m:
                out[m["job"]] = {
                    "gateLearned": m["learned"],
                    "gateVerdict": m["verdict"],
                    "gatePredicted": m["pred"],
                    "proven": m["proven"],
                }
    return out


def factors_at(rate_diag: str | None, ratio) -> str:
    """The `x1.234` window factors of the rung at [ratio] in a probeRateDiag string."""
    if not rate_diag or ratio is None:
        return ""
    for part in str(rate_diag).split(";"):
        key, _, value = part.partition("=")
        try:
            if abs(float(key) - float(ratio)) < 1e-6:
                return ",".join(re.findall(r",x([0-9.]+)\]", value))
        except ValueError:
            continue
    return ""


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("fixtures")
    ap.add_argument("targets")
    ap.add_argument("--decisions")
    ap.add_argument("--out", required=True)
    args = ap.parse_args(argv)

    with open(args.fixtures, encoding="utf-8") as f:
        pack = json.load(f)
    if pack.get("fidelity") != "PARTIAL_OBSERVATIONAL_REPLAY":
        print(f"error: unexpected fidelity {pack.get('fidelity')!r}", file=sys.stderr)
        return 2
    with open(args.targets, encoding="utf-8", newline="") as f:
        classes = {row["jobId"]: row["class"] for row in csv.DictReader(f)}
    gates = gate_lines(args.decisions)

    rows = []
    for job in pack["jobs"]:
        jid = job.get("jobId") or job.get("id")
        row = {c: clean(job.get(c)) for c in COLUMNS}
        row["jobId"] = jid
        row["caseClass"] = classes.get(jid, "")
        gate = gates.get(jid)
        if gate:
            row.update({k: gate[k] for k in ("gateLearned", "gateVerdict", "gatePredicted")})
            row["gateFactors"] = factors_at(job.get("probeRateDiag"), gate["proven"])
        rows.append(row)

    with open(args.out, "w", encoding="utf-8", newline="") as f:
        f.write("# PARTIAL_OBSERVATIONAL_REPLAY b169 batch_1790428395761; scores rounded to 3 dp; "
                "cert frame counts and the initial learned state were never recorded\n")
        f.write("\t".join(COLUMNS) + "\n")
        for row in rows:
            f.write("\t".join(row[c] for c in COLUMNS) + "\n")
    print(f"wrote {len(rows)} rows ({sum(1 for r in rows if r['gateVerdict'])} with a size-gate line) to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
