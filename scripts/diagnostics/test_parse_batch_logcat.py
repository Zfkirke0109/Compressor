#!/usr/bin/env python3
import importlib.util
import csv
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).with_name("parse_batch_logcat.py")
SPEC = importlib.util.spec_from_file_location("parse_batch_logcat", SCRIPT)
PARSER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PARSER)


class ParseBatchLogcatTest(unittest.TestCase):
    def test_structured_jobs_are_authoritative_and_keep_source_ids_distinct(self):
        legacy_name = "private_clip.mp4"
        legacy_hash = PARSER.stable_name_hash(legacy_name)
        second_hash = legacy_hash
        records = [
            "I CompressorEncoderPlan: mode=Remux Only; source=1920x1080@30fps; sourceCodec=video/avc",
            f"I CompressorVerification: mode=Remux Only; file={legacy_name}; verification=Remux Verified; playable=opens; outputSize=80",
            'I CompressorDiag: {"batchId":"b1","type":"session_start","schema":1,"timestampMs":1,"selectedCount":2}',
            "I CompressorDiag: " + json.dumps({
                "batchId": "b1", "type": "job", "timestampMs": 2, "id": "job_aaaaaaaaaaaa",
                "nameHash": legacy_hash, "ext": "mp4",
                "sourceUri": "content://must-not-leak",
                "sourceMime": "video/avc", "requestedMode": "Remux Only",
                "effectiveMode": "Remux Only", "plannedOutputMime": "stream-copy",
                "sourceSize": 100, "outputSize": 80,
                "terminal": "EXPLICIT_REMUX", "countsAsRealCompression": False,
                "rawByteDelta": 20, "savedBytes": 0,
            }),
            "I CompressorDiag: " + json.dumps({
                "batchId": "b1", "type": "job", "timestampMs": 3, "id": "job_bbbbbbbbbbbb",
                "nameHash": second_hash, "ext": "mp4",
                "sourceMime": "video/avc", "requestedMode": "Perceptually Lossless",
                "effectiveMode": "Perceptually Lossless", "plannedOutputMime": "video/hevc",
                "sourceSize": 100, "outputSize": 80,
                "terminal": "TRANSCODED_SMALLER", "countsAsRealCompression": True,
                "rawByteDelta": 20, "savedBytes": 20,
            }),
            'I CompressorDiag: {"batchId":"b1","type":"session_summary","timestampMs":4,"processed":2,"realCompressions":1}',
            'I CompressorDiag: {"batchId":"b2","type":"session_start","schema":1,"timestampMs":5,"selectedCount":1}',
            "I CompressorDiag: " + json.dumps({
                "batchId": "b2", "type": "job", "timestampMs": 6, "id": "job_aaaaaaaaaaaa",
                "nameHash": legacy_hash, "ext": "mp4",
                "sourceMime": "video/avc", "requestedMode": "Remux Only",
                "effectiveMode": "Remux Only", "plannedOutputMime": "stream-copy",
                "sourceSize": 100, "outputSize": 100,
                "terminal": "EXPLICIT_REMUX", "countsAsRealCompression": False,
                "rawByteDelta": 0, "savedBytes": 0,
            }),
            'I CompressorDiag: {"batchId":"b2","type":"session_summary","timestampMs":7,"processed":1,"realCompressions":0}',
        ]

        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            log = root / "logcat.txt"
            out = root / "out"
            log.write_text("\n".join(records), encoding="utf-8")
            subprocess.run(
                [sys.executable, str(SCRIPT), str(log), "--out", str(out)],
                check=True,
                capture_output=True,
                text=True,
            )

            jobs_text = (out / "jobs.jsonl").read_text(encoding="utf-8")
            jobs = [json.loads(line) for line in jobs_text.splitlines()]
            aggregate = json.loads((out / "aggregate.json").read_text(encoding="utf-8"))
            with (out / "summary.csv").open(newline="", encoding="utf-8") as handle:
                csv_rows = list(csv.DictReader(handle))

        self.assertEqual(4, len(jobs))
        self.assertNotIn(legacy_name, jobs_text)
        self.assertNotIn("content://must-not-leak", jobs_text)
        self.assertEqual(
            {"EXPLICIT_REMUX", "TRANSCODED_SMALLER", "UNEXPECTED_REMUX"},
            {j["terminal"] for j in jobs},
        )
        self.assertEqual(1, aggregate["real_compression_count"])
        self.assertEqual(20, aggregate["real_compression_saved_bytes"])
        self.assertEqual({"legacy": 1, "b1": 2, "b2": 1}, aggregate["by_batch"])
        self.assertEqual(4, len(aggregate["diagnostic_session_records"]))
        self.assertEqual({"", "b1", "b2"}, {row["batchId"] for row in csv_rows})
        self.assertEqual(
            {"", "stream-copy", "video/hevc"},
            {row["resolvedOutputMime"] for row in csv_rows},
        )

    def test_malformed_or_incomplete_structured_capture_fails_closed(self):
        cases = [
            'I CompressorDiag: {"batchId":"b1","type":"job"',
            "\n".join([
                'I CompressorDiag: {"batchId":"b1","type":"session_start","timestampMs":1,"selectedCount":1}',
                'I CompressorDiag: {"batchId":"b1","type":"job","timestampMs":2,"id":"job_111111111111",'
                '"terminal":"EXPLICIT_REMUX","countsAsRealCompression":false,'
                '"sourceSize":100,"outputSize":100}',
            ]),
        ]
        for content in cases:
            with self.subTest(content=content[:40]), tempfile.TemporaryDirectory() as tmp:
                root = pathlib.Path(tmp)
                log = root / "logcat.txt"
                out = root / "out"
                log.write_text(content, encoding="utf-8")
                result = subprocess.run(
                    [sys.executable, str(SCRIPT), str(log), "--out", str(out)],
                    check=False,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(2, result.returncode)
                self.assertIn("incomplete or invalid", result.stderr)
                self.assertFalse((out / "aggregate.json").exists())


    def test_corrected_build_legacy_line_is_deduped_against_matching_structured_job(self):
        # A corrected-build run emits BOTH a structured job record and a human-readable
        # CompressorVerification line carrying the SAME redacted job id. The legacy line must be
        # deduped against the structured record (not double-counted), while structured stays.
        records = [
            'I CompressorDiag: {"batchId":"bx","type":"session_start","schema":1,"timestampMs":1,"selectedCount":1}',
            "I CompressorDiag: " + json.dumps({
                "batchId": "bx", "type": "job", "timestampMs": 2, "id": "job_cccccccccccc",
                "sourceMime": "video/hevc", "requestedMode": "Perceptually Lossless",
                "effectiveMode": "Perceptually Lossless", "plannedOutputMime": "video/hevc",
                "sourceSize": 100, "outputSize": 70,
                "terminal": "TRANSCODED_SMALLER", "countsAsRealCompression": True,
                "rawByteDelta": 30, "savedBytes": 30,
            }),
            "I CompressorVerification: mode=Perceptually Lossless; job=job_cccccccccccc; "
            "verification=Perceptually Lossless Verified; playable=opens; outputSize=70",
            'I CompressorDiag: {"batchId":"bx","type":"session_summary","timestampMs":3,"processed":1,"realCompressions":1}',
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            log = root / "logcat.txt"
            out = root / "out"
            log.write_text("\n".join(records), encoding="utf-8")
            subprocess.run(
                [sys.executable, str(SCRIPT), str(log), "--out", str(out)],
                check=True, capture_output=True, text=True,
            )
            jobs = [json.loads(l) for l in (out / "jobs.jsonl").read_text(encoding="utf-8").splitlines()]
            aggregate = json.loads((out / "aggregate.json").read_text(encoding="utf-8"))
        self.assertEqual(1, len(jobs))
        self.assertTrue(jobs[0].get("diagnostic"))
        self.assertEqual("TRANSCODED_SMALLER", jobs[0]["terminal"])
        self.assertEqual(1, aggregate["real_compression_count"])

    def test_disallowed_structured_fields_are_dropped_and_counted(self):
        # Privacy: a structured record carrying sensitive keys must have them dropped from every
        # derived artifact AND reported as ignored so silent truncation is visible.
        records = [
            'I CompressorDiag: {"batchId":"bz","type":"session_start","schema":1,"timestampMs":1,"selectedCount":1}',
            "I CompressorDiag: " + json.dumps({
                "batchId": "bz", "type": "job", "timestampMs": 2, "id": "job_dddddddddddd",
                "displayName": "MySecretHoliday.mp4", "sourceUri": "content://secret/42",
                "absolutePath": "/storage/emulated/0/secret.mp4",
                "sourceMime": "video/avc", "requestedMode": "Remux Only",
                "effectiveMode": "Remux Only", "plannedOutputMime": "stream-copy",
                "sourceSize": 100, "outputSize": 100,
                "terminal": "EXPLICIT_REMUX", "countsAsRealCompression": False,
            }),
            'I CompressorDiag: {"batchId":"bz","type":"session_summary","timestampMs":3,"processed":1,"realCompressions":0}',
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            log = root / "logcat.txt"
            out = root / "out"
            log.write_text("\n".join(records), encoding="utf-8")
            subprocess.run(
                [sys.executable, str(SCRIPT), str(log), "--out", str(out)],
                check=True, capture_output=True, text=True,
            )
            jobs_text = (out / "jobs.jsonl").read_text(encoding="utf-8")
            aggregate = json.loads((out / "aggregate.json").read_text(encoding="utf-8"))
        for leaked in ("MySecretHoliday", "content://secret/42", "/storage/emulated/0/secret.mp4"):
            self.assertNotIn(leaked, jobs_text)
        self.assertGreaterEqual(aggregate["structured_ignored_field_count"], 3)
        for dropped in ("displayName", "sourceUri", "absolutePath"):
            self.assertIn(dropped, aggregate["structured_ignored_fields"])


if __name__ == "__main__":
    unittest.main()
