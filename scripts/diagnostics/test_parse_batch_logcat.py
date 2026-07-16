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


    # ---- v2 envelope (sequence / eventId / profile) ----
    def _run(self, records):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            log = root / "logcat.txt"
            out = root / "out"
            log.write_text("\n".join(records), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(SCRIPT), str(log), "--out", str(out)],
                check=False, capture_output=True, text=True,
            )
            agg = None
            jobs = []
            if (out / "aggregate.json").exists():
                agg = json.loads((out / "aggregate.json").read_text(encoding="utf-8"))
            if (out / "jobs.jsonl").exists():
                jobs = [json.loads(l) for l in (out / "jobs.jsonl").read_text(encoding="utf-8").splitlines()]
            return result, agg, jobs

    @staticmethod
    def _v2(batch, etype, seq, event, jobid=None, profile="normal", **extra):
        rec = {
            "schemaVersion": 2, "batchId": batch, "type": etype, "eventType": etype,
            "eventId": event, "sequence": seq, "timestampMs": 1000 + seq,
            "androidUserId": 0 if profile == "normal" else 150, "profileKind": profile,
        }
        if jobid:
            rec["jobId"] = jobid
        rec.update(extra)
        return "I CompressorDiag: " + json.dumps(rec)

    def _v2_batch(self, batch, profile="normal", jobseq_start=1):
        return [
            self._v2(batch, "session_start", 0, "evt_" + "0" * 16, profile=profile, selectedCount=1),
            self._v2(batch, "job", 1, "evt_" + "1" * 16, jobid="job_aaaaaaaaaaaa", profile=profile,
                     id="job_aaaaaaaaaaaa", terminal="TRANSCODED_SMALLER", countsAsRealCompression=True,
                     sourceSize=100, outputSize=70, savedBytes=30),
            self._v2(batch, "session_summary", 2, "evt_" + "2" * 16, profile=profile, processed=1),
        ]

    def test_fallback_reason_survives_capture_and_is_aggregated(self):
        # An UNEXPECTED_REMUX job (PL encode attempted, then discarded for a remux) must keep its
        # fallbackReason + discardedVideoBitrate in the structured artifacts — they are NOT dropped as
        # ignored fields — so a privacy-mode capture can still explain WHY the encode was discarded.
        records = [
            self._v2("bf", "session_start", 0, "evt_" + "a" * 16, selectedCount=1),
            self._v2(
                "bf", "job", 1, "evt_" + "b" * 16, jobid="job_ffffffffffff",
                id="job_ffffffffffff", terminal="UNEXPECTED_REMUX", countsAsRealCompression=False,
                sourceSize=100, outputSize=100, wasStreamCopy=True,
                verdict="Remux Verified", verified=True,
                fallbackReason="perceptually lossless output bitrate fell below the verified safety threshold",
                discardedVideoBitrate=3200000,
                probedRatios="0.70,0.80,0.90,0.95",
                pixelProvenRatio=0.80,
                probeDetail="windows passed at 0.80",
                probeWindowScores="96.2/92.0/85.1;97.0/93.4/88.8",
                certWindowScores="95.9/91.5/84.6;96.7/92.8/87.1",
                thermalStart="nominal",
                thermalEnd="light",
                precedingCooldownMs=10000,
            ),
            self._v2("bf", "session_summary", 2, "evt_" + "c" * 16, processed=1),
        ]
        result, agg, jobs = self._run(records)
        self.assertEqual(0, result.returncode)
        self.assertEqual(1, len(jobs))
        self.assertEqual(3200000, jobs[0]["discardedVideoBitrate"])
        self.assertIn("fell below the verified safety threshold", jobs[0]["fallbackReason"])
        self.assertIn(
            "perceptually lossless output bitrate fell below the verified safety threshold",
            agg["fallback_reasons"],
        )
        # The probe-ladder trace is a first-class structured field set: it must survive the
        # capture (not be dropped as ignored fields) so every job can prove whether a trial
        # encode happened and which ratios were measured.
        self.assertEqual("0.70,0.80,0.90,0.95", jobs[0]["probedRatios"])
        self.assertEqual(0.80, jobs[0]["pixelProvenRatio"])
        self.assertEqual("windows passed at 0.80", jobs[0]["probeDetail"])
        self.assertEqual("96.2/92.0/85.1;97.0/93.4/88.8", jobs[0]["probeWindowScores"])
        self.assertEqual("95.9/91.5/84.6;96.7/92.8/87.1", jobs[0]["certWindowScores"])
        self.assertEqual("nominal", jobs[0]["thermalStart"])
        self.assertEqual("light", jobs[0]["thermalEnd"])
        self.assertEqual(10000, jobs[0]["precedingCooldownMs"])
        self.assertEqual(0, agg["structured_ignored_field_count"])

    def test_v2_happy_path_reports_profile_sequence_and_events(self):
        result, agg, jobs = self._run(self._v2_batch("bv2", profile="normal"))
        self.assertEqual(0, result.returncode)
        self.assertEqual(1, len(jobs))
        self.assertEqual({"normal": 1}, agg["by_profile"])
        self.assertEqual([2], agg["structured_schema_versions"])
        self.assertEqual(3, agg["unique_structured_events"])
        self.assertEqual(0, agg["first_sequence"])
        self.assertEqual(2, agg["last_sequence"])
        self.assertEqual({}, agg["missing_sequences"])

    def test_v2_secondary_profile_is_reported(self):
        _, agg, _ = self._run(self._v2_batch("bsec", profile="secondary_profile"))
        self.assertEqual({"secondary_profile": 1}, agg["by_profile"])

    def test_v2_reconnect_replay_is_deduped_but_raw_kept(self):
        recs = self._v2_batch("brep")
        # A reconnect can re-emit the same event with a different line prefix (timestamp/format),
        # so it is NOT an exact-line duplicate; the (batchId,eventId) layer must still dedup it.
        replayed = recs[:2] + ["07-11 09:00:00.000  " + recs[1]] + recs[2:]
        result, agg, jobs = self._run(replayed)
        self.assertEqual(0, result.returncode)
        self.assertEqual(1, len(jobs))               # deduped to a single job
        self.assertGreaterEqual(agg["replay_event_count"], 1)

    def test_v2_duplicate_sequence_different_event_is_integrity_error(self):
        recs = self._v2_batch("bdup")
        # A second event reusing sequence 1 with a different eventId.
        recs.insert(2, self._v2("bdup", "job", 1, "evt_" + "9" * 16, jobid="job_bbbbbbbbbbbb",
                                id="job_bbbbbbbbbbbb", terminal="EXPLICIT_REMUX",
                                countsAsRealCompression=False, sourceSize=100, outputSize=100))
        result, _, _ = self._run(recs)
        self.assertEqual(2, result.returncode)
        self.assertIn("duplicate sequence", result.stderr)

    def test_v2_missing_sequence_in_summary_fails_closed(self):
        recs = self._v2_batch("bgap")
        recs[2] = self._v2("bgap", "session_summary", 3, "evt_" + "3" * 16, processed=1)  # skip seq 2
        result, _, _ = self._run(recs)
        self.assertEqual(2, result.returncode)
        self.assertIn("missing event sequence", result.stderr)

    def test_v2_malformed_event_id_fails_closed(self):
        recs = self._v2_batch("bbad")
        recs[1] = self._v2("bbad", "job", 1, "NOT_A_VALID_ID", jobid="job_aaaaaaaaaaaa",
                           id="job_aaaaaaaaaaaa", terminal="TRANSCODED_SMALLER",
                           countsAsRealCompression=True, sourceSize=100, outputSize=70)
        result, _, _ = self._run(recs)
        self.assertEqual(2, result.returncode)

    def test_v2_session_cancelled_is_a_valid_terminal(self):
        recs = [
            self._v2("bcan", "session_start", 0, "evt_" + "0" * 16, selectedCount=3, profile="normal"),
            self._v2("bcan", "job", 1, "evt_" + "1" * 16, jobid="job_aaaaaaaaaaaa",
                     id="job_aaaaaaaaaaaa", terminal="TRANSCODED_SMALLER",
                     countsAsRealCompression=True, sourceSize=100, outputSize=70),
            self._v2("bcan", "session_cancelled", 2, "evt_" + "2" * 16, processed=1, reason="user_cancelled"),
        ]
        result, agg, jobs = self._run(recs)
        self.assertEqual(0, result.returncode)   # cancelled early is not an error
        self.assertEqual(1, len(jobs))
        self.assertEqual({"session_cancelled": 1}, agg["session_terminal_types"])


if __name__ == "__main__":
    unittest.main()
