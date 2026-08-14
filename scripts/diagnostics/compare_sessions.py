#!/usr/bin/env python3
"""Paired per-file comparison of two DiagnosticsRecorder session captures.

Aggregate totals hide the thing that matters. Two runs can post identical headline numbers while
different files changed outcome in opposite directions, so this joins the two captures per source
(on the stable `nameHash`) and reports what happened to each file.

It is deliberately hard to use for marketing:

  * It refuses causal language. Learned-profile state, thermal history and execution order are
    confounders; unless a caller proves they were controlled, differences are reported as
    observations, never as effects of a code change.
  * Only files present in BOTH captures are compared. A file that appears in one run cannot
    contribute to a paired difference.
  * A "win" requires a genuinely smaller output, not merely a changed terminal state.

Usage:
    python3 compare_sessions.py BASELINE.jsonl CANDIDATE.jsonl [--json out.json]

Exit codes: 0 comparison produced, 2 unusable input, 3 no overlapping files.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from typing import Any

from session_records import read_records

# Terminal states in which no output file is produced, so no byte saving is possible.
NON_PRODUCING = {
    "ALREADY_HIGHLY_OPTIMIZED",
    "REMUX_PREFERRED_BY_EVIDENCE",
    "SKIPPED_WOULD_DEGRADE",
    "SKIPPED_ALREADY_COMPRESSED",
}


def load_jobs(path: str) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Accepts both capture transports; see session_records.decode_record."""
    start: dict[str, Any] = {}
    jobs: list[dict[str, Any]] = []
    for rec in read_records(path):
        kind = rec.get("type") or rec.get("eventType")
        if kind == "session_start":
            start = rec
        elif kind == "job":
            jobs.append(rec)
    return start, jobs


def saved_bytes(job: dict[str, Any]) -> int:
    """Bytes genuinely saved: a real compression whose output really is smaller. Else zero."""
    if job.get("countsAsRealCompression") is not True:
        return 0
    src = job.get("sourceSize") or 0
    out = job.get("outputSize") or 0
    return src - out if 0 < out < src else 0


def compare(baseline: list[dict[str, Any]], candidate: list[dict[str, Any]]) -> dict[str, Any]:
    """Pure paired comparison. Keyed on nameHash, which is stable across runs of the same source."""
    a = {j.get("nameHash"): j for j in baseline if j.get("nameHash")}
    b = {j.get("nameHash"): j for j in candidate if j.get("nameHash")}
    shared = sorted(set(a) & set(b))

    changed: list[dict[str, Any]] = []
    improved = regressed = 0
    for h in shared:
        ja, jb = a[h], b[h]
        sa, sb = saved_bytes(ja), saved_bytes(jb)
        ta, tb = str(ja.get("terminal")), str(jb.get("terminal"))
        if sa == sb and ta == tb:
            continue
        if sb > sa:
            improved += 1
        elif sb < sa:
            regressed += 1
        changed.append({
            "nameHash": h,
            "terminal": [ta, tb],
            "savedBytes": [sa, sb],
            "verdict": [ja.get("verdict"), jb.get("verdict")],
            "certificationStatus": [ja.get("certificationStatus"), jb.get("certificationStatus")],
            "failedChecks": [ja.get("failedChecks"), jb.get("failedChecks")],
            "encoderConfig": [ja.get("encoderConfig"), jb.get("encoderConfig")],
        })

    return {
        "schema": "compare_sessions/1",
        "pairedFiles": len(shared),
        "baselineOnly": sorted(set(a) - set(b)),
        "candidateOnly": sorted(set(b) - set(a)),
        "baselineSavedBytes": sum(saved_bytes(j) for j in a.values()),
        "candidateSavedBytes": sum(saved_bytes(j) for j in b.values()),
        "pairedBaselineSavedBytes": sum(saved_bytes(a[h]) for h in shared),
        "pairedCandidateSavedBytes": sum(saved_bytes(b[h]) for h in shared),
        "filesImproved": improved,
        "filesRegressed": regressed,
        "filesChangedOutcome": len(changed),
        "changed": changed,
        "baselineTerminals": dict(Counter(str(j.get("terminal")) for j in a.values()).most_common()),
        "candidateTerminals": dict(Counter(str(j.get("terminal")) for j in b.values()).most_common()),
    }


def controls_present(start_a: dict[str, Any], start_b: dict[str, Any]) -> list[str]:
    """Which experimental controls the captures can actually evidence. Usually none, honestly."""
    missing = []
    if not (start_a.get("learnedStateIdentity") and start_b.get("learnedStateIdentity")):
        missing.append("learned-profile state (not recorded in either capture)")
    elif start_a.get("learnedStateIdentity") != start_b.get("learnedStateIdentity"):
        missing.append(
            f"learned-profile state differs "
            f"({start_a.get('learnedStateIdentity')} vs {start_b.get('learnedStateIdentity')})"
        )
    if not (start_a.get("thermalStart") and start_b.get("thermalStart")):
        missing.append("thermal starting state")
    missing.append("execution order / counterbalancing (single run per arm)")
    return missing


def render(result: dict[str, Any], missing: list[str], a_id: str, b_id: str) -> str:
    lines = [
        f"# Paired comparison: {a_id} -> {b_id}",
        "",
        f"- paired files: {result['pairedFiles']}"
        f"  (baseline-only {len(result['baselineOnly'])}, candidate-only {len(result['candidateOnly'])})",
        f"- paired saved bytes: {result['pairedBaselineSavedBytes']:,} -> {result['pairedCandidateSavedBytes']:,}"
        f"  (delta {result['pairedCandidateSavedBytes'] - result['pairedBaselineSavedBytes']:+,})",
        f"- files improved: {result['filesImproved']}   regressed: {result['filesRegressed']}"
        f"   changed outcome: {result['filesChangedOutcome']}",
        "",
        "## Verdict",
    ]
    delta = result["pairedCandidateSavedBytes"] - result["pairedBaselineSavedBytes"]
    if missing:
        lines += [
            "**BLOCKED — not a controlled comparison.** Uncontrolled: "
            + "; ".join(missing)
            + ".",
            "",
            "Differences above are observations. No cause may be attributed to any code change.",
        ]
    elif delta > 0:
        lines.append(f"Candidate saved {delta:,} more paired bytes with controls evidenced.")
    else:
        lines.append("Candidate did not improve paired byte savings.")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("baseline")
    ap.add_argument("candidate")
    ap.add_argument("--json")
    args = ap.parse_args()

    start_a, jobs_a = load_jobs(args.baseline)
    start_b, jobs_b = load_jobs(args.candidate)
    if not jobs_a or not jobs_b:
        print("error: one or both captures contain no job records", file=sys.stderr)
        return 2

    result = compare(jobs_a, jobs_b)
    if result["pairedFiles"] == 0:
        print("error: the two captures share no files; nothing can be paired", file=sys.stderr)
        return 3

    missing = controls_present(start_a, start_b)
    result["uncontrolled"] = missing
    result["baselineBuild"] = start_a.get("buildCommit")
    result["candidateBuild"] = start_b.get("buildCommit")

    print(render(result, missing,
                 str(start_a.get("buildCommit")), str(start_b.get("buildCommit"))))
    if args.json:
        with open(args.json, "w") as fh:
            json.dump(result, fh, indent=2)
        print(f"\nwrote {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
