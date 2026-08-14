from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

from pl_lab.cli import main
from pl_lab.constants import (
    EXIT_BLOCKED,
    EXIT_COMPLETED,
    EXIT_GATE_FAILED,
    EXIT_IDENTITY_MISMATCH,
    EXIT_INVALID_INPUT,
    EXIT_MISSING_PREREQUISITE,
)
from pl_lab.contracts import OPERATION_CONTRACTS
from pl_lab.envelope import build_envelope, exit_for_envelope, validate_envelope
from pl_lab.error_envelope import validate_error_result
from pl_lab.errors import InvalidInput
from pl_lab.operations.payloads import validate_operation_payload
from tests.test_execution_paths import RUN_ID, _record_chain
from tests.fixtures import write_corpus_manifest


ROOT = Path(__file__).resolve().parents[3]
HEX_A = "a" * 64


def minimal_payload(operation_id: str, temp: Path) -> dict:
    source = temp / "source.mp4"
    output = temp / "output.mp4"
    corpus = temp / "corpus.json"
    if not corpus.is_file():
        corpus = write_corpus_manifest(temp)
    apk = temp / "app.apk"
    for path, data in ((source, b"source"), (output, b"output"), (apk, b"apk")):
        path.write_bytes(data)
    values = {
        "pl_device_inventory": {"adb_executable": "adb", "device_serial": "SERIAL-EXAMPLE", "android_user": 0, "package_id": "example.package", "expected_firmware_sha256": HEX_A},
        "pl_build_install": {"adb_executable": "adb", "apksigner_executable": "apksigner", "aapt_executable": "aapt", "apk_path": str(apk), "device_serial": "SERIAL-EXAMPLE", "android_user": 0, "package_id": "example.package", "expected_build_identity": {"version_code": 1, "version_name": "1.0"}, "expected_signer_sha256": HEX_A, "expected_firmware_sha256": HEX_A, "max_duration_seconds": 120, "max_storage_bytes": 1000000},
        "pl_encode_candidate": {"candidate_id": "candidate-001", "source_path": str(source), "output_logical_path": "artifacts/candidate.mp4", "requested_configuration": {"codec": "video/hevc"}, "adapter_id": "android-media3", "max_duration_seconds": 120, "max_storage_bytes": 1000000},
        "pl_score_pair": {"source_path": str(source), "output_path": str(output), "pair_id": "pair-001", "ffmpeg_executable": "ffmpeg", "ffprobe_executable": "ffprobe", "model_id": "vmaf_v0.6.1", "max_duration_seconds": 120, "max_storage_bytes": 1000000},
        "pl_score_corpus": {"corpus_manifest_path": str(corpus), "ffmpeg_executable": sys.executable, "ffprobe_executable": sys.executable, "model_id": "vmaf_v0.6.1", "max_candidates": 2, "max_workers": 1, "max_duration_seconds": 120, "max_storage_bytes": 1000000},
        "pl_hdr_compare": {"input_evaluation_sha256": HEX_A, "reference": {"standard": 6, "transfer": 7, "range": 1, "bit_depth": 10, "dynamic_metadata_sha256": HEX_A}, "candidate": {"standard": 6, "transfer": 7, "range": 1, "bit_depth": 10, "dynamic_metadata_sha256": HEX_A}},
        "pl_pts_compare": {"input_evaluation_sha256": HEX_A, "reference_pts_us": [0, 33333, 66666], "candidate_pts_us": [0, 33333, 66666]},
        "pl_run_boundary_suite": {"mode": "fast"},
        "pl_optimize_policy": {"proposals": [{"candidate_id": "candidate-001", "configuration": {"codec": "video/hevc"}}], "max_candidates": 1, "max_workers": 1, "max_duration_seconds": 120, "max_storage_bytes": 1000000},
        "pl_compare_baseline": {"record_sha256s": [HEX_A]},
        "pl_generate_report": {"record_sha256s": [HEX_A]},
    }
    return values[operation_id]


class PayloadContractTests(unittest.TestCase):
    def test_each_operation_has_a_strict_minimal_payload(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            temp_path = Path(temp)
            for operation_id in OPERATION_CONTRACTS:
                payload = minimal_payload(operation_id, temp_path)
                with self.subTest(operation_id=operation_id):
                    validate_operation_payload(operation_id, payload, execute=False)
                    with self.assertRaises(InvalidInput):
                        validate_operation_payload(operation_id, dict(payload, unknown=True), execute=False)

    def test_expensive_boundary_requires_explicit_budgets_before_execute(self) -> None:
        with self.assertRaises(InvalidInput):
            validate_operation_payload("pl_run_boundary_suite", {"mode": "expensive"}, execute=True)
        validate_operation_payload("pl_run_boundary_suite", {"mode": "expensive", "max_candidates": 1, "max_workers": 1, "max_duration_seconds": 120, "max_storage_bytes": 1000}, execute=True)

    def test_candidate_payload_cannot_smuggle_threshold_or_verdict(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            payload = minimal_payload("pl_encode_candidate", Path(temp))
            payload["requested_configuration"] = {"policy_thresholds": {"vmaf": 1}, "overallVerdict": "PASS"}
            with self.assertRaises(InvalidInput):
                validate_operation_payload("pl_encode_candidate", payload, execute=False)


class EnvelopeTests(unittest.TestCase):
    def test_cross_field_status_and_verdict_rules_are_enforced(self) -> None:
        envelope = build_envelope(
            operation_id="pl_pts_compare",
            run_id="run-001",
            status="DRY_RUN",
            dry_run=True,
            started_at="2026-08-13T00:00:00Z",
            finished_at="2026-08-13T00:00:01Z",
            request_sha256=HEX_A,
            repository_authority={"schema_version": "1", "authority_sha256": HEX_A, "files": [{"logical_path": "policy.kt", "sha256": HEX_A}], "missing_authorities": []},
            experiment_spec_sha256=HEX_A,
            result={"planned": True},
        )
        validate_envelope(envelope)
        for changes in (
            {"dry_run": False},
            {"quality_verdict": "PASS"},
            {"status": "COMPLETED", "dry_run": False, "output_identities": []},
        ):
            invalid = dict(envelope, **changes)
            invalid.pop("content_sha256", None)
            from pl_lab.canonical import seal_document
            invalid = seal_document("result-envelope", invalid)
            with self.subTest(changes=changes):
                with self.assertRaises(InvalidInput):
                    validate_envelope(invalid)

    def test_exit_matrix_distinguishes_final_gate_fail_blocked_and_prerequisites(self) -> None:
        self.assertEqual(exit_for_envelope("pl_generate_report", "COMPLETED", "FAIL", gate=False), EXIT_COMPLETED)
        self.assertEqual(exit_for_envelope("pl_generate_report", "COMPLETED", "FAIL", gate=True), EXIT_GATE_FAILED)
        self.assertEqual(exit_for_envelope("pl_generate_report", "BLOCKED", "BLOCKED", gate=True), EXIT_BLOCKED)
        with self.assertRaises(InvalidInput):
            exit_for_envelope("pl_score_pair", "COMPLETED", "FAIL", gate=True)


class CliTests(unittest.TestCase):
    def test_every_operation_has_usable_help(self) -> None:
        for operation_id in OPERATION_CONTRACTS:
            stdout = io.StringIO()
            with self.subTest(operation_id=operation_id), redirect_stdout(stdout):
                with self.assertRaises(SystemExit) as caught:
                    main([operation_id, "--help"])
                self.assertEqual(caught.exception.code, 0)
                self.assertIn("--repository", stdout.getvalue())
                self.assertIn("--request", stdout.getvalue())
                self.assertIn("--experiment", stdout.getvalue())

    def test_each_operation_dry_run_is_non_mutating_and_emits_valid_envelope(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            temp_path = Path(temp)
            experiment, spec, _ = _record_chain(temp_path)
            for operation_id in OPERATION_CONTRACTS:
                request = {"schema_version": "1", "operation_id": operation_id, "run_id": RUN_ID, "experiment_spec_sha256": spec["content_sha256"], "payload": minimal_payload(operation_id, temp_path)}
                request_path = temp_path / f"{operation_id}.json"
                request_path.write_text(json.dumps(request), encoding="utf-8")
                before = {
                    path.relative_to(experiment).as_posix(): path.read_bytes()
                    for path in experiment.rglob("*")
                    if path.is_file()
                }
                stdout = io.StringIO()
                with self.subTest(operation_id=operation_id), redirect_stdout(stdout):
                    exit_code = main([operation_id, "--repository", str(ROOT), "--request", str(request_path), "--experiment", str(experiment)])
                    envelope = json.loads(stdout.getvalue())
                    validate_envelope(envelope)
                    if operation_id == "pl_score_pair":
                        self.assertEqual(13, exit_code)
                        self.assertEqual("BLOCKED", envelope["status"])
                        self.assertEqual(13, envelope["errors"][0]["exit_code"])
                    else:
                        self.assertEqual(0, exit_code)
                        self.assertEqual("DRY_RUN", envelope["status"])
                    after = {
                        path.relative_to(experiment).as_posix(): path.read_bytes()
                        for path in experiment.rglob("*")
                        if path.is_file()
                    }
                    self.assertEqual(before, after)
                    self.assertEqual(spec["content_sha256"], envelope["experiment_spec_sha256"])
                    self.assertTrue(envelope["versions"]["metrics"])
                    self.assertTrue(envelope["versions"]["models"])
                    self.assertNotIn("SERIAL-EXAMPLE", stdout.getvalue())

    def test_dry_run_with_missing_frozen_spec_is_blocked_not_success(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            temp_path = Path(temp)
            request = {
                "schema_version": "1",
                "operation_id": "pl_pts_compare",
                "run_id": "run-missing-spec",
                "experiment_spec_sha256": HEX_A,
                "payload": minimal_payload("pl_pts_compare", temp_path),
            }
            request_path = temp_path / "request.json"
            request_path.write_text(json.dumps(request), encoding="utf-8")
            stdout = io.StringIO()
            with redirect_stdout(stdout):
                exit_code = main([
                    "pl_pts_compare",
                    "--repository", str(ROOT),
                    "--request", str(request_path),
                    "--experiment", str(temp_path / "missing-experiment"),
                ])
            self.assertEqual(EXIT_BLOCKED, exit_code)
            envelope = json.loads(stdout.getvalue())
            validate_envelope(envelope)
            self.assertEqual("BLOCKED", envelope["status"])
            self.assertFalse(envelope["dry_run"])

    def test_invalid_request_and_nonfinal_gate_use_invalid_input_exit(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            temp_path = Path(temp)
            request_path = temp_path / "request.json"
            request_path.write_text('{"schema_version":"2"}', encoding="utf-8")
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                self.assertEqual(main(["pl_pts_compare", "--repository", str(ROOT), "--request", str(request_path), "--experiment", str(temp_path / "exp")]), EXIT_INVALID_INPUT)
            validate_error_result(json.loads(stderr.getvalue()))
            with redirect_stderr(io.StringIO()):
                self.assertEqual(main(["pl_pts_compare", "--gate", "--repository", str(ROOT), "--request", str(request_path), "--experiment", str(temp_path / "exp")]), EXIT_INVALID_INPUT)


if __name__ == "__main__":
    unittest.main()
