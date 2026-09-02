#!/usr/bin/env python3
"""Tests for the session summariser, focused on how it reports verification failures.

The summariser exists to make a rejection diagnosable. These pin the two properties that matter:
a named predicate is attributed to the right jobs, and a rejection that names nothing is called
out rather than quietly counted as an ordinary failure.
"""
from __future__ import annotations

import json
import os
import tempfile

from parse_session_jsonl import load_sessions, summarize


def write(records) -> str:
    fd, path = tempfile.mkstemp(suffix=".jsonl")
    with os.fdopen(fd, "w") as fh:
        for r in records:
            fh.write(json.dumps(r) + "\n")
    return path


def job(h, **kw):
    d = {"type": "job", "nameHash": h, "terminal": "OUTPUT_VALIDATION_FAILED",
         "sourceSize": 1000, "outputSize": 900, "countsAsRealCompression": False,
         "verified": False}
    d.update(kw)
    return d


def start(**kw):
    d = {"type": "session_start", "batchId": "b1", "buildCommit": "abc1234", "mode": "PL"}
    d.update(kw)
    return d


def test_a_capture_holding_several_builds_is_never_merged_into_one_total():
    # The in-app export writes EVERY session the recorder holds, newest first, so one file
    # routinely spans several builds. Merging them and labelling the total with whichever
    # session_start happened to be parsed last attributes one build's jobs to another -- the
    # 2026-09-01 capture would have read as 387 jobs of the OLDEST batch when the batch under
    # test contributed 36.
    path = write([
        start(batchId="new", buildCommit="d72cfee"),
        job("a", batchId="new", terminal="ALREADY_HIGHLY_OPTIMIZED", verified=True, failedChecks=[]),
        {"type": "session_summary", "batchId": "new", "elapsedMs": 10},
        start(batchId="old", buildCommit="457edfe"),
        job("b", batchId="old"),
        job("c", batchId="old"),
        {"type": "session_summary", "batchId": "old", "elapsedMs": 20},
    ])
    sessions = load_sessions(path)
    assert list(sessions) == ["new", "old"]
    assert len(sessions["new"]["jobs"]) == 1
    assert len(sessions["old"]["jobs"]) == 2

    # Default is the newest session, reported under ITS OWN build -- not a merged 3-job total.
    s = summarize(path)
    assert s["batchId"] == "new"
    assert s["buildCommit"] == "d72cfee"
    assert s["jobs"] == 1

    # And an older batch stays addressable rather than being lost.
    old = summarize(path, "old")
    assert old["batchId"] == "old" and old["buildCommit"] == "457edfe" and old["jobs"] == 2


def test_records_without_their_own_batch_id_follow_the_session_they_come_after():
    # Older captures stamp batchId only on session_start and rely on block order. Those must
    # still land in the right session rather than collapsing into one "None" bucket.
    path = write([start(batchId="b1"), job("a"), start(batchId="b2"), job("b"), job("c")])
    sessions = load_sessions(path)
    assert [len(v["jobs"]) for v in sessions.values()] == [1, 2]
    assert list(sessions) == ["b1", "b2"]


def test_a_batch_that_recorded_no_summary_is_flagged_as_unfinished():
    # A batch killed mid-run still has job records. Reporting its totals as if the run completed
    # would understate the corpus and hide the fact that it stopped.
    from parse_session_jsonl import render
    path = write([start(batchId="b1"), job("a")])
    s = summarize(path)
    assert s["completed"] is False
    assert "did not finish" in render(s)


def test_failing_predicates_are_counted_and_shared_against_verified_jobs_only():
    # Three jobs carry verification, one is a retained source that never ran the verifier.
    s = summarize(write([
        start(),
        job("a", failedChecks=["mediaStoreDateMatches"]),
        job("b", failedChecks=["mediaStoreDateMatches", "mp4DateMatches"]),
        job("c", failedChecks=[], verified=True, terminal="ALREADY_HIGHLY_OPTIMIZED"),
        job("d", failedChecks=None, terminal="ALREADY_HIGHLY_OPTIMIZED"),
    ]))
    assert s["jobsWithVerification"] == 3
    assert s["failedCheckCounts"] == {"mediaStoreDateMatches": 2, "mp4DateMatches": 1}


def test_a_rejection_naming_no_check_is_reported_as_a_verifier_defect():
    s = summarize(write([start(), job("a", failedChecks=[], verified=False)]))
    assert s["silentRejections"] == 1


def test_a_passing_job_naming_no_check_is_not_a_silent_rejection():
    s = summarize(write([
        start(),
        job("a", failedChecks=[], verified=True, terminal="ALREADY_HIGHLY_OPTIMIZED"),
    ]))
    assert s["silentRejections"] == 0
    assert s["failedCheckCounts"] == {}


def test_a_build_that_never_recorded_the_field_is_distinguished_from_one_with_no_failures():
    # null is "this build cannot tell you", [] is "it told you: nothing failed". Conflating them
    # is how a blind capture gets mistaken for a clean one.
    absent = summarize(write([start(), job("a", terminal="ALREADY_HIGHLY_OPTIMIZED")]))
    present = summarize(write([
        start(),
        job("a", failedChecks=[], verified=True, terminal="ALREADY_HIGHLY_OPTIMIZED"),
    ]))
    assert absent["jobsWithVerification"] == 0
    assert present["jobsWithVerification"] == 1


def test_learned_state_identity_is_surfaced_for_run_comparability():
    s = summarize(write([start(learnedStateIdentity="n=12,h=1a2b"), job("a")]))
    assert s["learnedStateIdentity"] == "n=12,h=1a2b"
    assert summarize(write([start(), job("a")]))["learnedStateIdentity"] is None


def test_a_ladder_that_measured_nothing_is_separated_from_one_that_measured_and_failed():
    # Both report the same terminal, and before the fix both reported the same probeDetail.
    # Only the presence of window scores tells them apart, which is why the summariser checks it.
    s = summarize(write([
        start(),
        job("a", probedRatios="0.90,0.95", probeWindowScores="97.0/93.0/88.0",
            probeDetail="no candidate ratio passed"),
        job("b", probedRatios="0.90,0.95", probeWindowScores=None,
            probeDetail="no candidate ratio passed"),
        job("c", probedRatios=None, probeWindowScores=None),
    ]))
    assert s["laddersRun"] == 2
    assert s["laddersWithNoMeasurement"] == 1
    assert s["laddersMislabelledAsMeasured"] == 1


def test_the_corrected_wording_is_not_counted_as_mislabelled():
    # After the fix the ladder says so itself, so it is no longer a silent misreport.
    s = summarize(write([
        start(),
        job("b", probedRatios="0.90,0.95", probeWindowScores=None,
            probeDetail="no probe rung could be measured (2 not time-alignable, 0 unmeasurable)"
                        " — nothing was scored, so this is NOT evidence the clip resists re-encoding"),
    ]))
    assert s["laddersWithNoMeasurement"] == 1
    assert s["laddersMislabelledAsMeasured"] == 0


def _main():
    fails = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn(); print(f"PASS {name}")
            except Exception as e:
                fails += 1; print(f"FAIL {name}: {e!r}")
    print(f"\n{'FAILED' if fails else 'OK'} ({fails} failure(s))")
    return 1 if fails else 0


if __name__ == "__main__":
    raise SystemExit(_main())


