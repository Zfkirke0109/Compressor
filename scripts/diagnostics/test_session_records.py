#!/usr/bin/env python3
"""Tests for capture-record decoding.

Logcat is the only transport that crosses the Samsung Secure Folder user boundary, so a decoder
that drops logcat-prefixed lines makes an entire class of capture unreadable while reporting it as
empty. These pin that the prefix is handled, and — just as important — that tolerance stops well
short of inventing records from arbitrary log noise.
"""
from __future__ import annotations

import json
import os
import tempfile

from session_records import decode_record, read_records

JOB = {"type": "job", "nameHash": "abc123", "terminal": "OUTPUT_VALIDATION_FAILED"}
LOGCAT_PREFIX = "08-13 18:18:10.690 25965 25965 I CompressorDiag: "


def test_a_bare_jsonl_line_decodes():
    assert decode_record(json.dumps(JOB)) == JOB


def test_a_logcat_prefixed_line_decodes_to_the_same_record():
    assert decode_record(LOGCAT_PREFIX + json.dumps(JOB)) == JOB


def test_a_record_containing_braces_in_its_values_survives_the_prefix_strip():
    # Salvage keys off the FIRST brace, so any later brace inside a string value is untouched.
    rec = dict(JOB, probeDetail="skipped {see ladder} at 0.65", encoderConfig="mime=a->b")
    assert decode_record(LOGCAT_PREFIX + json.dumps(rec)) == rec


def test_ordinary_log_noise_is_not_mistaken_for_a_record():
    for noise in [
        "08-13 15:01:21.390  3313  3313 E SettingsState: invalid override flag name launcher/X",
        "--------- beginning of main",
        "",
        "   ",
        "not json at all",
        LOGCAT_PREFIX + "{not: valid json}",
    ]:
        assert decode_record(noise) is None, noise


def test_json_without_a_record_type_is_rejected():
    # A bare object is not a capture record. Accepting one would let unrelated JSON logging from
    # any tag inflate the job count.
    assert decode_record('{"hello": "world"}') is None
    assert decode_record(LOGCAT_PREFIX + '{"level": "info", "msg": "starting"}') is None


def test_a_json_array_line_is_rejected():
    assert decode_record("[1, 2, 3]") is None


def test_eventtype_alone_is_enough_for_v1_readers():
    assert decode_record('{"eventType": "job"}') == {"eventType": "job"}


def test_a_mixed_capture_reads_every_record_and_skips_a_truncated_tail():
    fd, path = tempfile.mkstemp(suffix=".txt")
    with os.fdopen(fd, "w") as fh:
        fh.write("--------- beginning of main\n")
        fh.write(LOGCAT_PREFIX + json.dumps({"type": "session_start", "batchId": "b1"}) + "\n")
        fh.write(json.dumps(JOB) + "\n")
        fh.write(LOGCAT_PREFIX + json.dumps(dict(JOB, nameHash="def456")) + "\n")
        fh.write(LOGCAT_PREFIX + '{"type": "job", "nameHash": "trunc')  # power-cut mid-write
    recs = read_records(path)
    assert [r.get("type") for r in recs] == ["session_start", "job", "job"]
    assert [r["nameHash"] for r in recs if r["type"] == "job"] == ["abc123", "def456"]


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


def test_a_diagnostics_zip_reads_every_run_newest_first_and_its_manifest():
    import zipfile

    from session_records import read_manifest, zip_session_entries

    older = {"type": "session_start", "batchId": "batch_1790263711162"}
    older_job = {"type": "job", "batchId": "batch_1790263711162", "nameHash": "a1"}
    newer = {"type": "session_start", "batchId": "batch_1790270611232"}
    newer_job = {"type": "job", "batchId": "batch_1790270611232", "nameHash": "b2"}
    manifest = {"manifestVersion": 1, "appVersionName": "1.6.164", "scope": "ALL_RUNS", "files": []}
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "Compressor-v1.6.164-20260924-140000-AllRuns.zip")
        with zipfile.ZipFile(path, "w") as z:
            # Written oldest first on purpose: order must come from the batch epoch, not the zip.
            z.writestr("runs/batch_1790263711162/session.jsonl", json.dumps(older) + "\n" + json.dumps(older_job) + "\n")
            z.writestr("runs/batch_1790263711162/decisions.log", "09-24 10:28:31 I CompressorProbe: ...\n")
            z.writestr("runs/batch_1790270611232/session.jsonl", json.dumps(newer) + "\n" + json.dumps(newer_job) + "\n")
            z.writestr("crashes/crash-1790273718621.log", "Compressor process exit record\n")
            z.writestr("manifest.json", json.dumps(manifest))
        assert zip_session_entries(path) == [
            "runs/batch_1790270611232/session.jsonl",
            "runs/batch_1790263711162/session.jsonl",
        ]
        recs = read_records(path)
        assert [r.get("batchId") for r in recs] == [
            "batch_1790270611232", "batch_1790270611232", "batch_1790263711162", "batch_1790263711162"
        ]
        assert read_manifest(path)["appVersionName"] == "1.6.164"
        assert read_manifest(os.path.join(tmp, "plain.txt")) is None


def test_runs_come_in_the_order_they_started_when_the_manifest_gives_it():
    import zipfile

    from session_records import zip_session_entries

    # Started in this order: 300, then the clock went back a day and 100 started. The manifest of a
    # build that records each run's place says so; the epochs in the names say the opposite.
    first, second, legacy = "batch_1790000000300", "batch_1789913600100", "batch_1790000000900"
    manifest = {"manifestVersion": 2, "scope": "ALL_RUNS", "files": [], "runs": [
        {"batchId": second, "startedAtMs": 1789913600100, "sequence": 2},
        {"batchId": first, "startedAtMs": 1790000000300, "sequence": 1},
        # A run an earlier build recorded has no place and comes after every run that has one.
        {"batchId": legacy, "startedAtMs": 1790000000900, "sequence": None},
    ]}
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "capture.zip")
        with zipfile.ZipFile(path, "w") as z:
            for batch in (first, second, legacy):
                z.writestr(f"runs/{batch}/session.jsonl", json.dumps({"type": "session_start", "batchId": batch}) + "\n")
            z.writestr("manifest.json", json.dumps(manifest))
        assert zip_session_entries(path) == [f"runs/{b}/session.jsonl" for b in (second, first, legacy)]
        # Without places (an older export, or a manifest that is not an object) the epochs decide.
        with zipfile.ZipFile(path, "w") as z:
            for batch in (first, second, legacy):
                z.writestr(f"runs/{batch}/session.jsonl", json.dumps({"type": "session_start", "batchId": batch}) + "\n")
            z.writestr("manifest.json", json.dumps(["not", "an", "object"]))
        assert zip_session_entries(path) == [f"runs/{b}/session.jsonl" for b in (legacy, first, second)]


def test_a_manifest_that_is_not_an_object_is_no_manifest_and_the_sessions_are_still_reported():
    import subprocess
    import sys
    import zipfile

    from session_records import read_manifest

    start = {"type": "session_start", "batchId": "batch_1790270611232", "buildCommit": "abc1234", "mode": "PL"}
    job = {"type": "job", "batchId": "batch_1790270611232", "nameHash": "b2", "terminal": "TRANSCODED_SMALLER",
           "sourceSize": 1000, "outputSize": 900, "countsAsRealCompression": True, "verified": True}
    summarizer = os.path.join(os.path.dirname(os.path.abspath(__file__)), "parse_session_jsonl.py")
    with tempfile.TemporaryDirectory() as tmp:
        for i, text in enumerate(['["invalid"]', '"a string"', '42', 'null', 'true', '{not json']):
            path = os.path.join(tmp, f"capture{i}.zip")
            with zipfile.ZipFile(path, "w") as z:
                z.writestr("runs/batch_1790270611232/session.jsonl", json.dumps(start) + "\n" + json.dumps(job) + "\n")
                z.writestr("manifest.json", text)
            assert read_manifest(path) is None, text
            # The summariser used to call .get on the list and die before printing anything.
            done = subprocess.run([sys.executable, summarizer, path], capture_output=True, text=True, stdin=subprocess.DEVNULL)
            assert done.returncode == 0, (text, done.stderr[-300:])
            assert "batch_1790270611232" in done.stdout and "archive        :" not in done.stdout, (text, done.stdout[:300])
        # An object is still read.
        path = os.path.join(tmp, "good.zip")
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("runs/batch_1790270611232/session.jsonl", json.dumps(start) + "\n" + json.dumps(job) + "\n")
            z.writestr("manifest.json", json.dumps({"appVersionName": "1.6.293", "files": []}))
        assert read_manifest(path) == {"appVersionName": "1.6.293", "files": []}


def test_zip_member_identity_supplies_legacy_ids_and_rejects_conflicts():
    import zipfile

    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "capture.zip")
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("runs/batch_2/session.jsonl", json.dumps(JOB))
        assert read_records(path) == [dict(JOB, batchId="batch_2")]

        with zipfile.ZipFile(path, "a") as archive:
            archive.writestr("runs/batch_1/session.jsonl", json.dumps(dict(JOB, batchId="batch_2")))
        try:
            read_records(path)
        except ValueError as e:
            assert "runs/batch_1/session.jsonl" in str(e)
        else:
            raise AssertionError("a record must belong to the batch in its archive member path")


if __name__ == "__main__":
    raise SystemExit(_main())
