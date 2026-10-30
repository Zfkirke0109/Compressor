#!/usr/bin/env python3
"""Self-running tests for make_replay_fixture.py (no pytest needed)."""

from __future__ import annotations

import csv
import json
import os
import sys
import tempfile

from make_replay_fixture import COLUMNS, factors_at, gate_lines, main


def _write(path: str, text: str) -> str:
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    return path


def test_the_factors_of_the_proven_rung_only_are_taken():
    diag = ("0.90=rate[req=826kbps,win=996kbps,I=1x13kB,P=14x10kB,steady=873kbps,x1.058]|"
            "rate[req=826kbps,win=1195kbps,I=1x18kB,P=13x12kB,steady=1027kbps,x1.243];"
            "0.85=rate[req=780kbps,win=944kbps,I=1x11kB,P=14x10kB,steady=830kbps,x1.064]")
    assert factors_at(diag, 0.85) == "1.064"
    assert factors_at(diag, "0.90") == "1.058,1.243"
    assert factors_at(diag, 0.70) == ""
    assert factors_at(None, 0.85) == ""


def test_gate_lines_keep_the_learned_value_the_gate_saw():
    with tempfile.TemporaryDirectory() as d:
        log = _write(os.path.join(d, "decisions.log"),
                     "09-26 09:15:15.373 I CompressorProbe: size gate; job=job_d127463b57d6; proven=0.75; "
                     "predictedBytes=247616391; sourceBytes=322388270; "
                     "measuredOvershoot=1.015(n=3,bound=0.885),learned=1.000,used=0.885; verdict=encode\n")
        gates = gate_lines(log)
    assert gates["job_d127463b57d6"] == {
        "gateLearned": "1.000", "gateVerdict": "encode", "gatePredicted": "247616391", "proven": "0.75"}


def test_a_fixture_is_written_without_inventing_absent_values():
    job = {"jobId": "job_a", "terminal": "TRANSCODED_SMALLER", "verified": True, "outputSize": 5,
           "probeRateDiag": "0.75=rate[req=1kbps,win=1kbps,I=1x1kB,P=1x1kB,steady=1kbps,x1.010]"}
    with tempfile.TemporaryDirectory() as d:
        fx = _write(os.path.join(d, "fx.json"), json.dumps(
            {"fidelity": "PARTIAL_OBSERVATIONAL_REPLAY", "jobs": [job]}))
        targets = _write(os.path.join(d, "t.csv"), "jobId,class\njob_a,certified_smaller\n")
        log = _write(os.path.join(d, "decisions.log"),
                     "size gate; job=job_a; proven=0.75; predictedBytes=4; sourceBytes=9; "
                     "measuredOvershoot=1.010(n=1,too few windows),learned=1.020,used=1.020; verdict=encode\n")
        out = os.path.join(d, "out.tsv")
        assert main([fx, targets, "--decisions", log, "--out", out]) == 0
        with open(out, encoding="utf-8") as f:
            lines = [l for l in f.read().splitlines() if not l.startswith("#")]
    rows = list(csv.DictReader(lines, delimiter="\t"))
    assert list(rows[0].keys()) == COLUMNS
    assert rows[0]["caseClass"] == "certified_smaller"
    assert rows[0]["verified"] == "true"
    assert rows[0]["gateFactors"] == "1.010"
    # Never recorded, so left empty rather than filled in.
    assert rows[0]["certWindowScores"] == ""
    assert rows[0]["sourceSize"] == ""


def test_a_pack_of_another_fidelity_is_refused():
    with tempfile.TemporaryDirectory() as d:
        fx = _write(os.path.join(d, "fx.json"), json.dumps({"fidelity": "EXACT", "jobs": []}))
        targets = _write(os.path.join(d, "t.csv"), "jobId,class\n")
        assert main([fx, targets, "--out", os.path.join(d, "o.tsv")]) == 2


def _main() -> int:
    failed = 0
    tests = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_") and callable(f)]
    for name, fn in tests:
        try:
            fn()
            print(f"PASS {name}")
        except Exception as e:  # noqa: BLE001
            failed += 1
            print(f"FAIL {name}: {type(e).__name__}: {e}")
    print(f"{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(_main())
