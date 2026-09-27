#!/usr/bin/env python3
"""The runner freezes the gate, changes one factor per arm, and counts only accepted bytes."""
from __future__ import annotations

import copy
import json
import os
import tempfile

import run_experiment as r

HERE = os.path.dirname(os.path.abspath(__file__))
MANIFEST = r.load_manifest(os.path.join(HERE, "pilot_manifest.json"))


def test_the_pilot_manifest_is_valid():
    assert r.validate(MANIFEST) == []


def test_a_changed_threshold_or_a_two_factor_arm_is_rejected():
    m = copy.deepcopy(MANIFEST)
    m["frozenGate"]["windowMeanMin"] = 95.0
    m["arms"][1]["settings"]["maxBFrames"] = 0  # long GOP AND B-frames off
    problems = r.validate(m)
    assert any("windowMeanMin" in p for p in problems)
    assert any("one factor at a time" in p for p in problems)


def _capture(batch, jobs):
    fd, path = tempfile.mkstemp(suffix=".jsonl")
    with os.fdopen(fd, "w") as fh:
        fh.write(json.dumps({"type": "session_start", "batchId": batch}) + "\n")
        for j in jobs:
            fh.write(json.dumps({"type": "job", "batchId": batch, **j}) + "\n")
    return path


def test_only_accepted_smaller_outputs_count_and_arms_join_on_matched_sources():
    base = _capture("b0", [
        {"jobId": "job_a", "sourceSize": 1000, "outputSize": 900, "countsAsRealCompression": True, "terminal": "TRANSCODED_SMALLER"},
        {"jobId": "job_b", "sourceSize": 2000, "outputSize": 2000, "countsAsRealCompression": False, "terminal": "UNEXPECTED_REMUX",
         "copyAvoidedBytes": 2000},
        {"jobId": "job_c", "sourceSize": 500, "outputSize": 0, "terminal": "SKIPPED_WOULD_DEGRADE",
         "certificationDecision": "measured_below_bar", "candidateBytes": 480,
         "attempts": "0.85:measured_below_bar:cand=480:encodeMs=1"},
    ])
    arm = _capture("b1", [
        {"jobId": "job_a", "sourceSize": 1000, "outputSize": 850, "countsAsRealCompression": True, "terminal": "TRANSCODED_SMALLER"},
        {"jobId": "job_b", "sourceSize": 2000, "outputSize": 2000, "countsAsRealCompression": False, "terminal": "UNEXPECTED_REMUX"},
        {"jobId": "job_c", "sourceSize": 500, "outputSize": 470, "countsAsRealCompression": True, "terminal": "TRANSCODED_SMALLER",
         "attemptsStarted": 2},
        {"jobId": "job_d", "sourceSize": 9000, "outputSize": 1, "countsAsRealCompression": True, "terminal": "TRANSCODED_SMALLER"},
    ])
    out = tempfile.mkdtemp()
    r.ingest(MANIFEST, "base", base, "b0", out, scope="all")
    r.ingest(MANIFEST, "arm", arm, "b1", out, scope="all")
    results = {n: json.load(open(os.path.join(out, f"{n}.json"))) for n in ("base", "arm")}
    t = r.table(results, "base")
    assert t["matchedSources"] == 3  # job_d is in one arm only: never counted
    assert t["arms"]["base"]["acceptedSavedBytes"] == 100  # the remux and the avoided copy save 0
    assert t["arms"]["arm"]["acceptedSavedBytes"] == 180
    assert t["arms"]["arm"]["incrementalBytesVsBaseline"] == 80
    assert t["arms"]["base"]["measuredFailures"] == 1
    assert t["arms"]["arm"]["jobsRetried"] == 1 and t["arms"]["arm"]["attemptsStarted"] == 2
    assert t["arms"]["base"]["peakCandidateBytes"] == 480
    assert {c["jobId"] for c in t["changedSources"]} == {"job_a", "job_c"}
    assert "| base |" in r.markdown(t)


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"PASS {name}")
            except Exception as exc:  # noqa: BLE001
                failures += 1
                print(f"FAIL {name}: {exc!r}")
    print(f"\n{'FAILED' if failures else 'OK'} ({failures} failure(s))")
    raise SystemExit(1 if failures else 0)
