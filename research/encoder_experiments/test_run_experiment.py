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
    assert {a["id"] for a in MANIFEST["arms"]} <= set(MANIFEST["order"])
    assert len(MANIFEST["order"]) == len(set(MANIFEST["order"]))


def test_runner_rejects_unchecked_source_identity_and_repeated_arm_overwrite():
    source = MANIFEST["sources"][0]
    m = copy.deepcopy(MANIFEST)
    m["sources"] = [source]
    path = _capture("b0", [{"jobId": source["jobId"], "nameHash": "wrong-name",
                            "sourceFingerprint": "wrong-source", "sourceSize": source["sourceBytes"] + 1,
                            "outputSize": 1, "countsAsRealCompression": True}])
    out = tempfile.mkdtemp()
    try:
        r.ingest(m, "A0", path, "b0", out)
    except SystemExit:
        pass
    else:
        raise AssertionError("source mismatch was accepted")


def test_a_changed_threshold_or_a_two_factor_arm_is_rejected():
    m = copy.deepcopy(MANIFEST)
    m["frozenGate"]["windowMeanMin"] = 95.0
    m["arms"][1]["settings"]["maxBFrames"] = 0  # long GOP AND B-frames off
    problems = r.validate(m)
    assert any("windowMeanMin" in p for p in problems)
    assert any("one factor at a time" in p for p in problems)


def _capture(batch, jobs, arm="A0", exhaustive=True):
    fd, path = tempfile.mkstemp(suffix=".jsonl")
    with os.fdopen(fd, "w") as fh:
        fh.write(json.dumps({"type": "session_start", "batchId": batch, "mode": "Perceptually Lossless",
                             "selectedCount": len(jobs), "buildTag": "pr44-test", "buildCommit": "test-commit",
                             "exhaustivePerceptualLossless": exhaustive}) + "\n")
        fh.write(json.dumps({"type": "learned_state_snapshot", "batchId": batch, "sha256": "snapshot"}) + "\n")
        settings = next(a["settings"] for a in MANIFEST["arms"] if a["id"] == arm)
        fh.write(json.dumps({"type": "run_identity", "batchId": batch,
                             "scoring": {"frozenGate": MANIFEST["frozenGate"], "verdictModel": "vmaf_v0.6.1",
                                         "verdictPhoneModel": False, **settings}}) + "\n")
        for j in jobs:
            fh.write(json.dumps({"type": "job", "batchId": batch,
                                 "nameHash": "name-" + j["jobId"], "sourceFingerprint": "fingerprint-" + j["jobId"],
                                 "pixelCertified": j.get("countsAsRealCompression") is True,
                                 "finalAccepted": j.get("countsAsRealCompression") is True,
                                 "verified": j.get("countsAsRealCompression") is True, **j}) + "\n")
        fh.write(json.dumps({"type": "session_summary", "batchId": batch, "processed": len(jobs),
                             "sourceManifestJobs": len(jobs), "sourceManifestSha256": "matched-source-manifest",
                             "totalElapsedMs": 1234}) + "\n")
    return path


def test_only_accepted_smaller_outputs_count_and_arms_join_on_matched_sources():
    m = copy.deepcopy(MANIFEST)
    m["sources"] = [{"jobId": jid, "nameHash": "name-" + jid, "sourceBytes": size}
                    for jid, size in (("job_a", 1000), ("job_b", 2000), ("job_c", 500))]
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
    ], "A1")
    out = tempfile.mkdtemp()
    r.ingest(m, "A0", base, "b0", out)
    r.ingest(m, "A1", arm, "b1", out)
    results = {n: json.load(open(os.path.join(out, f"{n}.json"))) for n in ("A0", "A1")}
    t = r.table(results, "A0")
    assert t["matchedSources"] == 3
    assert t["arms"]["A0"]["acceptedSavedBytes"] == 100  # the remux and avoided copy save 0
    assert t["arms"]["A1"]["acceptedSavedBytes"] == 180
    assert t["arms"]["A1"]["incrementalBytesVsBaseline"] == 80
    assert t["arms"]["A0"]["measuredFailures"] == 1
    assert t["arms"]["A1"]["jobsRetried"] == 1 and t["arms"]["A1"]["attemptsStarted"] == 2
    assert t["arms"]["A0"]["peakCandidateBytes"] == 480
    assert t["arms"]["A0"]["batchWallMs"] == 1234
    assert t["arms"]["A0"]["summedJobElapsedMs"] == 0
    assert t["sameStartingLearnedSnapshot"]
    assert {c["jobId"] for c in t["changedSources"]} == {"job_a", "job_c"}
    assert "| A0 |" in r.markdown(t)


def test_ingest_refuses_missing_gate_or_wrong_settings_and_prevents_overwrite():
    source = MANIFEST["sources"][0]
    m = copy.deepcopy(MANIFEST)
    m["sources"] = [source]
    jobs = [{"jobId": source["jobId"], "sourceSize": source["sourceBytes"], "nameHash": source["nameHash"],
             "outputSize": 0, "countsAsRealCompression": False}]
    capture = _capture("b0", jobs)
    out = tempfile.mkdtemp()
    r.ingest(m, "A0", capture, "b0", out)
    for arm in ("A0", "A1"):
        try:
            r.ingest(m, arm, capture, "b0", out)
        except (SystemExit, FileExistsError):
            pass
        else:
            raise AssertionError("repeat overwrite or settings mismatch accepted")

    records = [json.loads(line) for line in open(capture)]
    for mutation in ("missing_gate", "unfinished"):
        changed = copy.deepcopy(records)
        if mutation == "missing_gate":
            next(x for x in changed if x["type"] == "run_identity")["scoring"].pop("frozenGate")
        else:
            changed = [x for x in changed if x["type"] != "session_summary"]
        path = os.path.join(out, mutation + ".jsonl")
        with open(path, "w") as fh:
            fh.write("".join(json.dumps(x) + "\n" for x in changed))
        try:
            r.ingest(m, "A0_REPEAT", path, "b0", out)
        except SystemExit:
            pass
        else:
            raise AssertionError(f"{mutation} capture was ingested")


def test_pilot_arms_must_be_exhaustive_and_report_thermal_and_cooldown():
    # Fast mode lets each arm's learned history decide which sources are probed at all.
    source = MANIFEST["sources"][0]
    m = copy.deepcopy(MANIFEST)
    m["sources"] = [source]
    job = {"jobId": source["jobId"], "sourceSize": source["sourceBytes"], "nameHash": source["nameHash"],
           "outputSize": 0, "countsAsRealCompression": False, "thermalStart": "warm", "precedingCooldownMs": 30_000}
    try:
        r.ingest(m, "A0", _capture("fast", [job], exhaustive=False), "fast", tempfile.mkdtemp())
    except SystemExit as e:
        assert "Exhaustive" in str(e)
    else:
        raise AssertionError("a Fast-mode capture was ingested as a pilot arm")
    out = tempfile.mkdtemp()
    results = {arm: r.ingest(m, arm, _capture("b-" + arm, [job], arm=arm), "b-" + arm, out)
               for arm in ("A0", "A1", "A2", "A3", "A0_REPEAT")}
    t = r.table(results, "A0", m)
    assert t["arms"]["A0"]["summedCooldownMs"] == 30_000
    assert t["arms"]["A0"]["thermalAtJobStart"] == {"warm": 1}
    assert "Thermal state at the start of each measured job" in r.markdown(t)
    results["A1"] = dict(results["A1"], exhaustivePerceptualLossless=None)
    try:
        r.table(results, "A0", m)
    except SystemExit as e:
        assert "Exhaustive" in str(e)
    else:
        raise AssertionError("a table mixed a non-exhaustive arm")


def test_table_rejects_same_job_id_with_different_content_or_missing_arm():
    source = MANIFEST["sources"][0]
    m = copy.deepcopy(MANIFEST)
    m["sources"] = [source]
    job = {"jobId": source["jobId"], "nameHash": source["nameHash"], "sourceSize": source["sourceBytes"],
           "outputSize": 0, "countsAsRealCompression": False}
    out = tempfile.mkdtemp()
    a = r.ingest(m, "A0", _capture("b0", [job]), "b0", out)
    b = r.ingest(m, "A1", _capture("b1", [job], "A1"), "b1", out)
    b["rows"][0]["sourceFingerprint"] = "different-content"
    try:
        r.table({"A0": a, "A1": b}, "A0")
    except SystemExit:
        pass
    else:
        raise AssertionError("different content was called a matched source")
    b["rows"][0]["sourceFingerprint"] = a["rows"][0]["sourceFingerprint"]
    try:
        r.table({"A0": a, "A1": b}, "A0", m)
    except SystemExit:
        pass
    else:
        raise AssertionError("incomplete arm schedule was tabulated")


def test_timing_only_shadow_arm_must_preserve_acceptance():
    source = MANIFEST["sources"][0]
    m = copy.deepcopy(MANIFEST)
    m["sources"] = [source]
    job = {"jobId": source["jobId"], "nameHash": source["nameHash"], "sourceSize": source["sourceBytes"],
           "outputSize": 0, "countsAsRealCompression": False, "terminal": "SKIPPED_WOULD_DEGRADE"}
    out = tempfile.mkdtemp()
    a = r.ingest(m, "A0", _capture("b0", [job]), "b0", out)
    results = {}
    for arm in m["arms"]:
        row = copy.deepcopy(a)
        row["arm"] = arm["id"]
        row["batchId"] = "batch-" + arm["id"]
        row["settings"] = arm["settings"]
        results[arm["id"]] = row
    results["A3"]["rows"][0]["terminal"] = "TRANSCODED_SMALLER"
    try:
        r.table(results, "A0", m)
    except SystemExit as exc:
        assert "shadow arm changed acceptance" in str(exc)
    else:
        raise AssertionError("shadow arm changed acceptance")


def test_ingest_rejects_contradictory_accepted_savings():
    source = MANIFEST["sources"][0]
    manifest = copy.deepcopy(MANIFEST)
    manifest["sources"] = [source]
    job = {"jobId": source["jobId"], "nameHash": source["nameHash"],
           "sourceSize": source["sourceBytes"], "outputSize": source["sourceBytes"] - 100,
           "countsAsRealCompression": True, "pixelCertified": True,
           "terminal": "TRANSCODED_SMALLER"}
    for fields in ({"finalAccepted": False}, {"verified": False},
                   {"terminal": "SKIPPED_WOULD_DEGRADE"}, {"outputSize": source["sourceBytes"]}):
        with tempfile.TemporaryDirectory() as out:
            capture = _capture("invalid-acceptance", [{**job, **fields}])
            try:
                r.ingest(manifest, "A0", capture, "invalid-acceptance", out)
            except SystemExit as exc:
                assert "final acceptance" in str(exc)
            else:
                raise AssertionError(f"contradictory savings admitted: {fields}")


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
