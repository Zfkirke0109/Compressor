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

from parse_session_jsonl import summarize


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
