#!/usr/bin/env python3
"""Summarize one or two DiagnosticsRecorder session.jsonl captures.

Reads the structured records the app writes to
`filesDir/diagnostics/<batchId>/session.jsonl` and reports the totals that matter for
Perceptually Lossless evaluation: real compressions, bytes actually saved, terminal-state
distribution, and the verification failures that need explaining.

Deliberately conservative about what counts as a win. Remuxes, retained originals, skips, and
failures save zero bytes and are never reported as compressions, so this tool cannot flatter a
run. `savedBytes` is summed only from jobs whose own record claims a real compression AND whose
output is genuinely smaller than the source.

Usage:
    python3 parse_session_jsonl.py NEW.jsonl [OLD.jsonl] [--json out.json]

Exit codes: 0 ok, 2 unreadable/empty input.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, OrderedDict
from typing import Any

from session_records import read_records


def load_sessions(path: str) -> "OrderedDict[str, dict[str, Any]]":
    """Group a capture's records by batchId, in the order each batch first appears.

    The app's in-app export writes EVERY session.jsonl the recorder holds, newest first, so one
    file routinely carries a dozen batches from several different builds. An earlier version of
    this loader kept a single `start`/`summary` pair and appended every job to one list, which
    silently produced a merged total labelled with the LAST session_start in the file -- i.e. the
    OLDEST batch's id and build. The capture uploaded on 2026-09-01 would have been reported as
    387 jobs of "batch_1786611119841 build=457edfe" when the batch actually under test contributed
    36 of them and 219 came from a build four revisions older.

    A summary that attributes one build's jobs to another is worse than no summary: it is the
    exact failure this tooling exists to prevent. So sessions are kept separate and the caller has
    to say which one it means.

    Accepts both capture transports (bare JSONL and logcat-prefixed) and tolerates a truncated
    tail; see session_records.decode_record for why the prefix has to be handled.
    """
    sessions: "OrderedDict[str, dict[str, Any]]" = OrderedDict()

    def bucket(batch_id: Any) -> dict[str, Any]:
        key = str(batch_id)
        if key not in sessions:
            sessions[key] = {"batchId": key, "start": None, "summary": None, "jobs": []}
        return sessions[key]

    # Records are attributed by their own batchId where they carry one, and otherwise to the
    # session_start they follow. Both are needed: current builds stamp batchId on every record,
    # while older captures stamp it only on session_start and rely on block order.
    current: Any = None
    for rec in read_records(path):
        kind = rec.get("type") or rec.get("eventType")
        if kind == "session_start":
            current = rec.get("batchId")
            bucket(current)["start"] = rec
        elif kind == "session_summary":
            bucket(rec.get("batchId") or current)["summary"] = rec
        elif kind == "job":
            bucket(rec.get("batchId") or current)["jobs"].append(rec)
    return sessions


def select_session(path: str, batch_id: str | None) -> dict[str, Any]:
    """Pick one batch out of a capture, refusing to guess when the choice is not obvious."""
    sessions = load_sessions(path)
    if not sessions:
        raise SystemExit(f"error: no records in {path}")
    if batch_id is not None:
        if batch_id not in sessions:
            known = ", ".join(sessions) or "(none)"
            raise SystemExit(f"error: {batch_id} is not in {path}; it holds: {known}")
        return sessions[batch_id]
    # Newest first is the exporter's own order, so the first batch is the run just captured.
    chosen = next(iter(sessions.values()))
    if len(sessions) > 1:
        others = [
            f"{sid} ({len(s['jobs'])} jobs, build={(s['start'] or {}).get('buildCommit')})"
            for sid, s in list(sessions.items())[1:]
        ]
        print(
            f"note: {path} holds {len(sessions)} sessions; reporting only the newest,"
            f" {chosen['batchId']}. Also present: {'; '.join(others)}."
            " Use --batch <id> or --all for the others.",
            file=sys.stderr,
        )
    return chosen


def summarize(path: str, batch_id: str | None = None) -> dict[str, Any]:
    session = select_session(path, batch_id)
    start, jobs, summary = session["start"], session["jobs"], session["summary"]
    if not jobs:
        raise SystemExit(
            f"error: batch {session['batchId']} in {path} recorded no job records"
            + ("" if summary else " and no session summary — it did not complete")
        )

    terminals = Counter(str(j.get("terminal")) for j in jobs)
    modes = Counter(str(j.get("effectiveMode")) for j in jobs)
    materialization = Counter(str(j.get("materializationMode")) for j in jobs)

    def num(j: dict[str, Any], key: str) -> int:
        v = j.get(key)
        return int(v) if isinstance(v, (int, float)) else 0

    # A real compression must BOTH be claimed by the record and be genuinely smaller.
    real = [
        j for j in jobs
        if j.get("countsAsRealCompression") is True
        and 0 < num(j, "outputSize") < num(j, "sourceSize")
    ]
    claimed_only = [j for j in jobs if j.get("countsAsRealCompression") is True and j not in real]

    failures = [j for j in jobs if str(j.get("terminal")) == "OUTPUT_VALIDATION_FAILED"]
    unexplained = [
        j for j in failures
        if not j.get("failedChecks") and not j.get("blockReason") and not j.get("fallbackReason")
    ]

    # Which verification predicates actually rejected files. This is the whole point of recording
    # `failedChecks`: a rejection rate is a symptom, the named predicate is the cause. A single
    # predicate at 100% of generated outputs means one gate is rejecting everything, which is a
    # very different problem from a spread across many.
    #
    # Counted over every job that carries the field rather than only OUTPUT_VALIDATION_FAILED,
    # because a failed check can also show up on a job that recovered through a fallback. Jobs from
    # builds predating the field record null and are simply absent from the denominator.

    # Probe ladders that ran but produced no measurement at all. Detectable on ANY capture,
    # including ones predating the fix: the ladder recorded candidate ratios but no window
    # scores. Those jobs were reported as "no candidate ratio passed", which asserts the rungs
    # were measured and rejected — so a reader (or a threshold calibrated from captures) takes
    # measurement failure for pixel evidence that the clip resists re-encoding.
    verified_jobs = [j for j in jobs if isinstance(j.get("failedChecks"), list)]
    ladders = [j for j in jobs if j.get("probedRatios")]
    ladders_unmeasured = [j for j in ladders if not j.get("probeWindowScores")]
    mislabelled = [
        j for j in ladders_unmeasured
        if str(j.get("probeDetail")) == "no candidate ratio passed"
    ]
    check_counts = Counter(
        c for j in verified_jobs for c in j["failedChecks"] if isinstance(c, str)
    )
    silent_rejections = [
        j for j in verified_jobs
        if j.get("verified") is False and not j["failedChecks"]
    ]

    return {
        "path": path,
        "batchId": (start or {}).get("batchId") or session["batchId"],
        "buildCommit": (start or {}).get("buildCommit"),
        "mode": (start or {}).get("mode"),
        "jobs": len(jobs),
        "sourceBytes": sum(num(j, "sourceSize") for j in jobs),
        "realCompressions": len(real),
        "claimedButNotSmaller": len(claimed_only),
        "savedBytes": sum(num(j, "sourceSize") - num(j, "outputSize") for j in real),
        "copyAvoidedBytes": sum(num(j, "copyAvoidedBytes") for j in jobs),
        "generatedOutputBytes": sum(
            num(j, "outputSize") for j in jobs
            if str(j.get("materializationMode")) == "GENERATED_FILE"
        ),
        "pixelCertified": sum(1 for j in jobs if j.get("pixelCertified") is True),
        "pixelProven": sum(1 for j in jobs if j.get("pixelProvenRatio") is not None),
        "withProbeRatios": sum(1 for j in jobs if j.get("probedRatios")),
        "certScoresPresent": sum(1 for j in jobs if j.get("certWindowScores")),
        "bandingPresent": sum(1 for j in jobs if j.get("certBandingDiag")),
        "validationFailures": len(failures),
        "unexplainedFailures": len(unexplained),
        "laddersRun": len(ladders),
        "laddersWithNoMeasurement": len(ladders_unmeasured),
        "laddersMislabelledAsMeasured": len(mislabelled),
        "jobsWithVerification": len(verified_jobs),
        "failedCheckCounts": dict(check_counts.most_common()),
        "silentRejections": len(silent_rejections),
        "learnedStateIdentity": (start or {}).get("learnedStateIdentity"),
        "terminals": dict(terminals.most_common()),
        "effectiveModes": dict(modes.most_common()),
        "materialization": dict(materialization.most_common()),
        "elapsedMs": (summary or {}).get("elapsedMs"),
        "completed": summary is not None,
    }


def render(s: dict[str, Any]) -> str:
    out = [
        f"  batch          : {s['batchId']}  build={s['buildCommit']}  mode={s['mode']}",
        f"  jobs           : {s['jobs']}",
        f"  source bytes   : {s['sourceBytes']:,}",
        f"  REAL compress. : {s['realCompressions']}   savedBytes={s['savedBytes']:,}",
        f"  pixel-certified: {s['pixelCertified']}   pixel-proven={s['pixelProven']}   probed={s['withProbeRatios']}",
        f"  cert scores    : {s['certScoresPresent']}   banding records={s['bandingPresent']}",
        f"  copy avoided   : {s['copyAvoidedBytes']:,}",
        f"  generated bytes: {s['generatedOutputBytes']:,}",
        f"  validation fail: {s['validationFailures']}   unexplained={s['unexplainedFailures']}",
        f"  learned state  : {s['learnedStateIdentity'] or 'NOT RECORDED (runs are not comparable)'}",
        f"  probe ladders  : {s['laddersRun']}   measured nothing: {s['laddersWithNoMeasurement']}",
        f"  terminals      : {s['terminals']}",
        f"  effective modes: {s['effectiveModes']}",
    ]
    if not s["completed"]:
        out.insert(1, "  !! NO session_summary — this batch did not finish; totals are a partial run")
    if s["jobsWithVerification"]:
        out.append(f"  failing checks : ({s['jobsWithVerification']} job(s) carry verification)")
        for name, n in s["failedCheckCounts"].items():
            share = n / s["jobsWithVerification"]
            out.append(f"      {n:5}  {share:6.1%}  {name}")
        if not s["failedCheckCounts"]:
            out.append("      (none — every verified job passed every predicate in scope)")
    else:
        out.append("  failing checks : not recorded by this build; a rejection cannot be diagnosed")
    if s["laddersMislabelledAsMeasured"]:
        out.append(
            f"  !! {s['laddersMislabelledAsMeasured']} ladder(s) report 'no candidate ratio passed'"
            " having measured NOTHING — that wording claims pixel evidence this run does not have"
        )
    if s["silentRejections"]:
        out.append(
            f"  !! {s['silentRejections']} rejected job(s) name NO failing check — a verdict that"
            " identifies nothing is a defect in the verifier, not a result"
        )
    if s["claimedButNotSmaller"]:
        out.append(
            f"  !! {s['claimedButNotSmaller']} job(s) claim a real compression but are not smaller"
        )
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sessions", nargs="+", help="one or two session.jsonl files")
    ap.add_argument("--json", help="write the machine-readable summary here")
    ap.add_argument(
        "--batch",
        help="batchId to report; default is the newest session in the capture. A capture normally"
             " holds every session the recorder has ever written, across several builds.",
    )
    ap.add_argument(
        "--all",
        action="store_true",
        help="report every session in the capture separately (never merged)",
    )
    args = ap.parse_args()

    if args.all:
        summaries = []
        for p in args.sessions:
            for sid, sess in load_sessions(p).items():
                if not sess["jobs"]:
                    print(f"\n=== {p} :: {sid} ===")
                    print(f"  (no job records{'' if sess['summary'] else '; no session summary either'})")
                    continue
                summaries.append(summarize(p, sid))
    else:
        summaries = [summarize(p, args.batch) for p in args.sessions]
    for s in summaries:
        print(f"\n=== {s['path']} ===")
        print(render(s))

    if len(summaries) == 2 and not args.all:
        a, b = summaries
        print("\n=== delta (second minus first) ===")
        for key in ("jobs", "realCompressions", "savedBytes", "validationFailures",
                    "unexplainedFailures", "copyAvoidedBytes", "generatedOutputBytes",
                    "pixelCertified", "withProbeRatios"):
            print(f"  {key:22}: {b[key] - a[key]:+,}")
        # Refuse causal language: these runs are not a controlled experiment.
        print("\n  NOTE: differences are observational only. Learned-profile state, thermal")
        print("  history, and execution order were not controlled, so no cause may be inferred.")

    if args.json:
        with open(args.json, "w") as fh:
            json.dump(summaries, fh, indent=2)
        print(f"\nwrote {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
