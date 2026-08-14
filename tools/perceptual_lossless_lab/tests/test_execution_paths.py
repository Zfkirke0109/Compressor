from __future__ import annotations

from contextlib import redirect_stdout
from copy import deepcopy
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from pl_lab.canonical import domain_hash
from pl_lab.cli import main
from pl_lab.constants import EXIT_BLOCKED, EXIT_COMPLETED, EXIT_GATE_FAILED
from pl_lab.envelope import build_envelope, validate_envelope
from pl_lab.errors import EvidenceBlocked
from pl_lab.experiment import ExperimentStore
from pl_lab.freeze import freeze_specification
from pl_lab.operations.runtime import execute_operation
from pl_lab.operations.boundary import expected_boundary_artifact
from pl_lab.records import RecordStore, create_record
from pl_lab.specification import ExperimentSpecStore, build_experiment_spec
from pl_lab.workflow import execute_named_workflow, record_operation_completion
from tests.fixtures import write_corpus_manifest


ROOT = Path(__file__).resolve().parents[3]
HEX_A = "a" * 64
HEX_B = "b" * 64
HEX_C = "c" * 64
CREATED_AT = "2026-08-14T00:00:00Z"
RUN_ID = "run-execute-001"


def _definition(temp: Path) -> dict:
    manifest = write_corpus_manifest(
        temp,
        source_bytes=b"baseline-media-fixture\n",
        output_bytes=b"candidate\n",
    )
    return {
        "corpus": {"manifest_path": str(manifest)},
        "metrics": [{"metric_id": "vmaf", "version": "3.0.0", "model_id": "vmaf_v0.6.1", "model_source": "host_libvmaf_builtin"}],
        "pts": {"mode": "leading_drop_only", "timebase": "microseconds"},
        "hdr": {"comparison": "metadata_exact", "tone_map_substitution": False},
        "tolerances": {"size_ratio": 0.03, "duration_absolute_ms": 500, "duration_fraction": 0.02, "frame_count_absolute": 2, "frame_count_fraction": 0.01},
        "device_requirements": {"required": False},
        "budgets": {"max_candidates": 2, "max_workers": 1, "max_duration_seconds": 120, "max_storage_bytes": 1000000},
        "constraints": [
            {"metric": "vmaf_mean", "operator": ">=", "authority_group": "quality_probe", "authority_name": "WINDOW_MEAN_MIN"},
            {"metric": "vmaf_p5", "operator": ">=", "authority_group": "quality_probe", "authority_name": "WINDOW_P5_MIN"},
            {"metric": "vmaf_min", "operator": ">=", "authority_group": "quality_probe", "authority_name": "WINDOW_MIN_MIN"},
            {"metric": "compared_frames", "operator": ">=", "authority_group": "quality_probe", "authority_name": "MIN_COMPARED_FRAMES_PER_WINDOW"},
        ],
    }


def _record_chain(temp: Path, *, candidate_vmaf: float = 99.0, pending_candidate: bool = True) -> tuple[Path, dict, dict[str, dict]]:
    experiment = temp / "experiment"
    spec = build_experiment_spec(ROOT, run_id=RUN_ID, created_at=CREATED_AT, definition=_definition(temp))
    ExperimentStore(experiment).prepare(execute=True)
    ExperimentSpecStore(experiment).put(spec)
    store = RecordStore(experiment)
    spec_hash = spec["content_sha256"]
    common = dict(run_id=RUN_ID, created_at=CREATED_AT, experiment_spec_sha256=spec_hash)
    corpus = create_record("corpus", producer_role="deterministic-core", links={"experiment_spec": spec_hash}, payload=spec["corpus"], **common)
    device = create_record(
        "device", producer_role="android-device-lab", links={"experiment_spec": spec_hash},
        payload={"device_alias": "device-0123456789ab", "android_user": 0, "package_id": "example.package", "build_identity": {"version_code": 1, "version_name": "1.0", "apk_sha256": HEX_A, "source_sha256": spec["repository_authority"]["authority_sha256"]}, "signer_sha256": HEX_C, "firmware_fingerprint_sha256": HEX_A, "requested_configuration": {"codec": "video/hevc"}, "actual_configuration": {"codec": "video/hevc"}, "thermals": {"status": "NOMINAL", "max_status": 0}, "status": "COMPLETE", "confounders": []}, **common,
    )
    baseline_bytes = b"baseline-media-fixture\n"
    candidate_bytes = b"candidate\n"
    (experiment / "artifacts").mkdir(parents=True, exist_ok=True)
    (experiment / "artifacts" / "baseline.mp4").write_bytes(baseline_bytes)
    (experiment / "artifacts" / "candidate.mp4").write_bytes(candidate_bytes)
    baseline_artifact = create_record("artifact", producer_role="deterministic-core", links={"experiment_spec": spec_hash}, payload={"artifact_id": "baseline-media", "logical_path": "artifacts/baseline.mp4", "sha256": hashlib.sha256(baseline_bytes).hexdigest(), "size_bytes": len(baseline_bytes), "status": "COMPLETE"}, **common)
    candidate_artifact = create_record("artifact", producer_role="deterministic-core", links={"experiment_spec": spec_hash}, payload={"artifact_id": "candidate-media", "logical_path": "artifacts/candidate.mp4", "sha256": hashlib.sha256(candidate_bytes).hexdigest(), "size_bytes": len(candidate_bytes), "status": "COMPLETE"}, **common)
    boundary_payload, boundary_bytes = expected_boundary_artifact(spec)
    (experiment / boundary_payload["logical_path"]).write_bytes(boundary_bytes)
    boundary_artifact = create_record(
        "artifact",
        producer_role="deterministic-core",
        links={"experiment_spec": spec_hash},
        payload=boundary_payload,
        **common,
    )
    baseline = create_record("baseline", producer_role="deterministic-core", links={"experiment_spec": spec_hash, "artifact": baseline_artifact["content_sha256"], "device": device["content_sha256"]}, payload={"baseline_id": "baseline-001", "artifact_sha256": baseline_artifact["content_sha256"], "status": "COMPLETE", "confounders": []}, **common)
    candidate = create_record("candidate", producer_role="codec-scientist", links={"experiment_spec": spec_hash, "artifact": candidate_artifact["content_sha256"], "device": device["content_sha256"]}, payload={"candidate_id": "candidate-001", "configuration": {"codec": "video/hevc"}, "artifact_sha256": candidate_artifact["content_sha256"], "status": "COMPLETE", "warnings": []}, **common)
    scorer_sha256 = next(item["sha256"] for item in spec["repository_authority"]["files"] if item["logical_path"] == "scripts/diagnostics/measure_quality.py")

    def evaluation(identity: str, subject: dict, artifact: dict, value: float, *, pending: bool) -> dict:
        provenance = {
            "metric_model_sha256": spec["metrics"][0]["model_sha256"],
            "metric_id": spec["metrics"][0]["metric_id"],
            "metric_version": spec["metrics"][0]["version"],
            "reference_artifact_sha256": baseline_artifact["content_sha256"],
            "scorer_authority_sha256": scorer_sha256,
            "scorer_report_sha256": HEX_C,
            "python_executable_sha256": HEX_A,
            "ffmpeg_executable_sha256": HEX_A,
            "ffprobe_executable_sha256": HEX_A,
            "ffmpeg_version_sha256": HEX_B,
            "ffprobe_version_sha256": HEX_B,
            "source_content_sha256": baseline_artifact["payload"]["sha256"],
            "output_content_sha256": artifact["payload"]["sha256"],
            "source_frame_count": 30,
            "output_frame_count": 30,
            "source_duration_ms": 1000,
            "output_duration_ms": 1000,
        }
        return create_record(
            "evaluation", producer_role="perceptual-evaluator",
            links={"experiment_spec": spec_hash, "subject": subject["content_sha256"], "artifact": artifact["content_sha256"], "device": device["content_sha256"]},
            payload={"evaluation_kind": "subject_quality", "evaluation_id": identity, "subject_sha256": subject["content_sha256"], "metric_results": {"vmaf_mean": value, "vmaf_p5": value, "vmaf_min": value, "compared_frames": 30}, "alignment": {"status": "BLOCKED" if pending else "ALIGNED"}, "hdr": {"status": "BLOCKED" if pending else "MATCH"}, "provenance": provenance, "status": "BLOCKED" if pending else "COMPLETE", "confounders": []}, **common,
        )

    baseline_evaluation = evaluation("evaluation-baseline", baseline, baseline_artifact, 99.0, pending=False)
    candidate_evaluation = evaluation("evaluation-candidate", candidate, candidate_artifact, candidate_vmaf, pending=pending_candidate)
    calibration_provenance = {
        "metric_model_sha256": spec["metrics"][0]["model_sha256"],
        "metric_id": spec["metrics"][0]["metric_id"],
        "metric_version": spec["metrics"][0]["version"],
        "corpus_manifest_sha256": spec["corpus"]["manifest_sha256"],
        "corpus_pair_identities_sha256": spec["corpus"]["pair_identities_sha256"],
        "pair_id": "pair-001",
        "source_path": "media/source.mp4",
        "source_content_sha256": hashlib.sha256(baseline_bytes).hexdigest(),
        "source_size_bytes": len(baseline_bytes),
        "output_path": "media/output.mp4",
        "output_content_sha256": hashlib.sha256(candidate_bytes).hexdigest(),
        "output_size_bytes": len(candidate_bytes),
        "scorer_authority_sha256": scorer_sha256,
        "scorer_report_sha256": HEX_C,
        "python_executable_sha256": HEX_A,
        "ffmpeg_executable_sha256": HEX_A,
        "ffprobe_executable_sha256": HEX_A,
        "ffmpeg_version_sha256": HEX_B,
        "ffprobe_version_sha256": HEX_B,
        "source_frame_count": 30,
        "output_frame_count": 30,
        "source_duration_ms": 1000,
        "output_duration_ms": 1000,
    }
    calibration_evaluation = create_record(
        "evaluation",
        producer_role="perceptual-evaluator",
        links={"experiment_spec": spec_hash, "corpus": corpus["content_sha256"]},
        payload={
            "evaluation_kind": "corpus_calibration",
            "evaluation_id": "evaluation-calibration-pair-001",
            "subject_sha256": corpus["content_sha256"],
            "metric_results": {"vmaf_mean": 99.0, "vmaf_p5": 99.0, "vmaf_min": 99.0, "compared_frames": 30},
            "alignment": {"status": "NOT_APPLICABLE"},
            "hdr": {"status": "NOT_APPLICABLE"},
            "provenance": calibration_provenance,
            "status": "COMPLETE",
            "confounders": [],
        },
        **common,
    )
    pending_calibration = create_record(
        "calibration",
        producer_role="deterministic-core",
        links={"experiment_spec": spec_hash, "corpus": corpus["content_sha256"]},
        payload={
            "calibration_id": "calibration-" + spec_hash[:16],
            "resolved_constraints": spec["threshold_provenance"]["resolved_constraints"],
            "authority_sha256": spec["repository_authority"]["authority_sha256"],
            "status": "BLOCKED",
            "confounders": ["corpus_evaluation_pending"],
        },
        **common,
    )
    calibration = create_record(
        "calibration",
        producer_role="deterministic-core",
        links={
            "experiment_spec": spec_hash,
            "corpus": corpus["content_sha256"],
            "evaluations": [calibration_evaluation["content_sha256"]],
            "boundary_artifact": boundary_artifact["content_sha256"],
        },
        payload={
            "calibration_id": pending_calibration["payload"]["calibration_id"],
            "resolved_constraints": spec["threshold_provenance"]["resolved_constraints"],
            "authority_sha256": spec["repository_authority"]["authority_sha256"],
            "status": "COMPLETE",
            "confounders": [],
        },
        supersedes_sha256=pending_calibration["content_sha256"],
        **common,
    )
    records = {record["record_type"] + "-" + str(index): record for index, record in enumerate((corpus, device, baseline_artifact, candidate_artifact, boundary_artifact, baseline, candidate, baseline_evaluation, candidate_evaluation, calibration_evaluation, pending_calibration, calibration))}
    for record in records.values():
        store.put(record)
    records.update({"corpus": corpus, "calibration": calibration, "calibration_evaluation": calibration_evaluation, "boundary_artifact": boundary_artifact, "device": device, "baseline_artifact": baseline_artifact, "candidate_artifact": candidate_artifact, "baseline": baseline, "candidate": candidate, "baseline_evaluation": baseline_evaluation, "candidate_evaluation": candidate_evaluation})
    return experiment, spec, records


def _request(operation_id: str, spec: dict, payload: dict) -> dict:
    return {"schema_version": "1", "operation_id": operation_id, "run_id": RUN_ID, "experiment_spec_sha256": spec["content_sha256"], "payload": payload}


def _benchmark_step_templates(temp: Path) -> dict[str, dict]:
    return {
        "pl_device_inventory": {"adb_executable": "adb", "device_serial": "SERIAL-EXAMPLE", "android_user": 0, "package_id": "example.package", "expected_firmware_sha256": HEX_A},
        "pl_build_install": {"adb_executable": "adb", "apksigner_executable": "apksigner", "aapt_executable": "aapt", "apk_path": str(temp / "candidate.apk"), "device_serial": "SERIAL-EXAMPLE", "android_user": 0, "package_id": "example.package", "expected_build_identity": {"version_code": 1, "version_name": "1.0"}, "expected_signer_sha256": HEX_C, "expected_firmware_sha256": HEX_A, "max_duration_seconds": 120, "max_storage_bytes": 1_000_000},
        "pl_encode_candidate": {"candidate_id": "candidate-001", "source_path": str(temp / "source.mp4"), "output_logical_path": "artifacts/candidate.mp4", "requested_configuration": {"codec": "video/hevc"}, "adapter_id": "android-media3", "max_duration_seconds": 120, "max_storage_bytes": 1_000_000},
        "pl_score_pair": {"source_path": str(temp / "source.mp4"), "output_path": str(temp / "candidate.mp4"), "pair_id": "pair-001", "ffmpeg_executable": "ffmpeg", "ffprobe_executable": "ffprobe", "model_id": "vmaf_v0.6.1", "max_duration_seconds": 120, "max_storage_bytes": 1_000_000},
        "pl_hdr_compare": {"reference": {"standard": 6, "transfer": 7, "range": 1, "bit_depth": 10, "dynamic_metadata_sha256": HEX_A}, "candidate": {"standard": 6, "transfer": 7, "range": 1, "bit_depth": 10, "dynamic_metadata_sha256": HEX_A}},
        "pl_pts_compare": {"reference_pts_us": [0, 33_333, 66_666], "candidate_pts_us": [0, 33_333, 66_666]},
        "pl_compare_baseline": {},
        "pl_generate_report": {},
    }


class PureExecutionTests(unittest.TestCase):
    def test_hdr_then_pts_supersede_evaluation_and_publish_valid_envelopes(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            experiment, spec, records = _record_chain(temp)
            hdr_payload = {
                "input_evaluation_sha256": records["candidate_evaluation"]["content_sha256"],
                "reference": {"standard": 6, "transfer": 7, "range": 1, "bit_depth": 10, "dynamic_metadata_sha256": HEX_A},
                "candidate": {"standard": 6, "transfer": 7, "range": 1, "bit_depth": 10, "dynamic_metadata_sha256": HEX_A},
            }
            hdr = execute_operation("pl_hdr_compare", _request("pl_hdr_compare", spec, hdr_payload), repository=ROOT, experiment=experiment, execute=True)
            validate_envelope(hdr)
            self.assertEqual(hdr["status"], "COMPLETED")
            hdr_record = RecordStore(experiment).find(hdr["output_identities"][0]["sha256"])
            self.assertEqual(hdr_record["supersedes_sha256"], records["candidate_evaluation"]["content_sha256"])
            self.assertEqual(hdr_record["payload"]["hdr"]["status"], "MATCH")
            self.assertEqual(hdr_record["payload"]["status"], "COMPLETE")

            pts_payload = {"input_evaluation_sha256": hdr_record["content_sha256"], "reference_pts_us": [0, 33333, 66666], "candidate_pts_us": [0, 33333, 66666]}
            pts = execute_operation("pl_pts_compare", _request("pl_pts_compare", spec, pts_payload), repository=ROOT, experiment=experiment, execute=True)
            validate_envelope(pts)
            pts_record = RecordStore(experiment).find(pts["output_identities"][0]["sha256"])
            self.assertEqual(pts_record["payload"]["alignment"]["status"], "ALIGNED")
            self.assertEqual(pts_record["payload"]["status"], "COMPLETE")

    def test_missing_input_is_a_schema_valid_blocked_envelope_and_exit_13(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            experiment, spec, _ = _record_chain(temp)
            payload = {"input_evaluation_sha256": HEX_A, "reference_pts_us": [0, 33333], "candidate_pts_us": [0, 33333]}
            request_path = temp / "request.json"
            request_path.write_text(json.dumps(_request("pl_pts_compare", spec, payload)), encoding="utf-8")
            stdout = io.StringIO()
            with redirect_stdout(stdout):
                exit_code = main(["pl_pts_compare", "--repository", str(ROOT), "--request", str(request_path), "--experiment", str(experiment), "--execute"])
            self.assertEqual(exit_code, EXIT_BLOCKED)
            envelope = json.loads(stdout.getvalue())
            validate_envelope(envelope)
            self.assertEqual(envelope["status"], "BLOCKED")


class SpecificationFreezeTests(unittest.TestCase):
    def test_freeze_dry_run_is_nonmutating_and_execute_is_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            experiment = temp / "experiment"
            request = {"schema_version": "1", "run_id": RUN_ID, "created_at": CREATED_AT, "definition": _definition(temp)}
            dry = freeze_specification(ROOT, experiment, request, execute=False)
            self.assertEqual(dry["status"], "DRY_RUN")
            self.assertFalse(experiment.exists())
            first = freeze_specification(ROOT, experiment, request, execute=True)
            second = freeze_specification(ROOT, experiment, request, execute=True)
            self.assertEqual(first, second)
            self.assertEqual(first["status"], "COMPLETED")
            self.assertEqual(ExperimentSpecStore(experiment).get(first["experiment_spec_sha256"])["run_id"], RUN_ID)
            calibration = RecordStore(experiment).find(first["calibration_sha256"])
            self.assertEqual(calibration["record_type"], "calibration")
            corpus = RecordStore(experiment).find(first["corpus_sha256"])
            self.assertEqual(corpus["payload"], ExperimentSpecStore(experiment).get(first["experiment_spec_sha256"])["corpus"])

    def test_missing_corpus_blocks_before_experiment_creation(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            experiment = temp / "experiment"
            definition = _definition(temp)
            definition["corpus"]["manifest_path"] = str(temp / "missing.json")
            request = {"schema_version": "1", "run_id": RUN_ID, "created_at": CREATED_AT, "definition": definition}
            with self.assertRaises(EvidenceBlocked):
                freeze_specification(ROOT, experiment, request, execute=True)
            self.assertFalse(experiment.exists())

    def test_owner_markers_bind_unique_experiment_ids_to_their_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            first = ExperimentStore(temp / "one")
            second = ExperimentStore(temp / "two")
            first.prepare(execute=True)
            second.prepare(execute=True)
            first_marker = json.loads(first.marker_path.read_text(encoding="utf-8"))
            second_marker = json.loads(second.marker_path.read_text(encoding="utf-8"))
            self.assertNotEqual(first_marker["experiment_id"], second_marker["experiment_id"])

    def test_named_calibrate_dry_run_and_benchmark_wiring_bind_final_gate(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            experiment = temp / "experiment"
            calibrate = execute_named_workflow(
                "calibrate",
                repository=ROOT,
                experiment=experiment,
                request={"schema_version": "1", "workflow_id": "pl-calibrate", "run_id": RUN_ID, "created_at": CREATED_AT, "definition": _definition(temp), "payload": {"step_payloads": {"pl_run_boundary_suite": {"mode": "fast"}, "pl_score_corpus": {"corpus_manifest_path": str(temp / "corpus.json"), "ffmpeg_executable": sys.executable, "ffprobe_executable": sys.executable, "model_id": "vmaf_v0.6.1", "max_candidates": 2, "max_workers": 1, "max_duration_seconds": 120, "max_storage_bytes": 1_000_000}}}},
                execute=False,
            )
            self.assertEqual(calibrate["status"], "DRY_RUN")
            self.assertEqual(calibrate["plan"]["workflow_id"], "pl-calibrate")
            self.assertFalse(experiment.exists())

            experiment, spec, records = _record_chain(temp)
            benchmark = execute_named_workflow(
                "benchmark",
                repository=ROOT,
                experiment=experiment,
                request={"schema_version": "1", "workflow_id": "pl-benchmark", "run_id": RUN_ID, "created_at": CREATED_AT, "experiment_spec_sha256": spec["content_sha256"], "payload": {"initial_evidence": {"corpus_sha256": records["corpus"]["content_sha256"], "calibration_sha256": records["calibration"]["content_sha256"], "baseline_sha256": records["baseline"]["content_sha256"], "baseline_artifact_sha256": records["baseline_artifact"]["content_sha256"], "baseline_evaluation_sha256": records["baseline_evaluation"]["content_sha256"]}, "step_payloads": _benchmark_step_templates(temp)}},
                execute=False,
                gate=True,
            )
            self.assertTrue(benchmark["final_gate"])
            self.assertTrue(benchmark["plan"]["request_sha256"])
            self.assertEqual(len(benchmark["step_results"]), 8)
            self.assertTrue(all(step["status"] == "DRY_RUN" for step in benchmark["step_results"]))
            self.assertFalse((experiment / "runs" / RUN_ID / "workflows" / "pl-benchmark" / "workflow-plan.json").exists())

    def test_named_benchmark_executes_injected_lineage_to_fail_gate_and_resumes(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            experiment, spec, records = _record_chain(temp, candidate_vmaf=90.0, pending_candidate=True)
            scored_payload = deepcopy(records["candidate_evaluation"]["payload"])
            scored_payload["status"] = "COMPLETE"
            scored = create_record(
                "evaluation",
                producer_role="perceptual-evaluator",
                run_id=RUN_ID,
                created_at=CREATED_AT,
                experiment_spec_sha256=spec["content_sha256"],
                links=deepcopy(records["candidate_evaluation"]["links"]),
                payload=scored_payload,
            )
            RecordStore(experiment).put(scored)
            request = {
                "schema_version": "1",
                "workflow_id": "pl-benchmark",
                "run_id": RUN_ID,
                "created_at": CREATED_AT,
                "experiment_spec_sha256": spec["content_sha256"],
                "payload": {
                    "initial_evidence": {
                        "corpus_sha256": records["corpus"]["content_sha256"],
                        "calibration_sha256": records["calibration"]["content_sha256"],
                        "baseline_sha256": records["baseline"]["content_sha256"],
                        "baseline_artifact_sha256": records["baseline_artifact"]["content_sha256"],
                        "baseline_evaluation_sha256": records["baseline_evaluation"]["content_sha256"],
                    },
                    "step_payloads": _benchmark_step_templates(temp),
                },
            }
            from pl_lab.operations.runtime import execute_operation as real_execute_operation

            synthetic_outputs = {
                "pl_device_inventory": records["device"],
                "pl_build_install": records["device"],
                "pl_encode_candidate": records["candidate"],
                "pl_score_pair": scored,
            }

            def execute_with_synthetic_adapters(operation_id: str, operation_request: dict, **kwargs: object) -> dict:
                if operation_id not in synthetic_outputs:
                    return real_execute_operation(operation_id, operation_request, **kwargs)
                output = synthetic_outputs[operation_id]
                request_sha256 = domain_hash("operation-request", operation_request)
                record_operation_completion(
                    experiment,
                    run_id=RUN_ID,
                    experiment_spec_sha256=spec["content_sha256"],
                    operation_id=operation_id,
                    operation_request_sha256=request_sha256,
                    output_sha256s=[output["content_sha256"]],
                    completed_at=CREATED_AT,
                )
                return build_envelope(
                    operation_id=operation_id,
                    run_id=RUN_ID,
                    status="COMPLETED",
                    dry_run=False,
                    started_at=CREATED_AT,
                    finished_at=CREATED_AT,
                    request_sha256=request_sha256,
                    repository_authority=spec["repository_authority"],
                    experiment_spec_sha256=spec["content_sha256"],
                    output_identities=[{"kind": output["record_type"], "sha256": output["content_sha256"]}],
                    result={"synthetic_adapter_fixture": True},
                )

            with patch("pl_lab.operations.runtime.execute_operation", side_effect=execute_with_synthetic_adapters):
                result = execute_named_workflow("benchmark", repository=ROOT, experiment=experiment, request=request, execute=True, gate=True)
            self.assertEqual(result["status"], "COMPLETED")
            self.assertEqual(result["quality_verdict"], "FAIL")
            self.assertEqual(result["exit_code"], EXIT_GATE_FAILED)
            self.assertEqual(len(result["step_results"]), 8)

            with patch("pl_lab.operations.runtime.execute_operation") as resumed_adapter:
                resumed = execute_named_workflow("benchmark", repository=ROOT, experiment=experiment, request=request, execute=True, gate=True)
            resumed_adapter.assert_not_called()
            self.assertTrue(all(step["skipped"] for step in resumed["step_results"]))
            self.assertEqual(resumed["quality_verdict"], "FAIL")
            self.assertEqual(resumed["exit_code"], EXIT_GATE_FAILED)

    def test_named_benchmark_stops_before_later_steps_on_blocked_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            experiment, spec, records = _record_chain(temp)
            request = {
                "schema_version": "1",
                "workflow_id": "pl-benchmark",
                "run_id": RUN_ID,
                "created_at": CREATED_AT,
                "experiment_spec_sha256": spec["content_sha256"],
                "payload": {
                    "initial_evidence": {
                        "corpus_sha256": records["corpus"]["content_sha256"],
                        "calibration_sha256": records["calibration"]["content_sha256"],
                        "baseline_sha256": records["baseline"]["content_sha256"],
                        "baseline_artifact_sha256": records["baseline_artifact"]["content_sha256"],
                        "baseline_evaluation_sha256": records["baseline_evaluation"]["content_sha256"],
                    },
                    "step_payloads": _benchmark_step_templates(temp),
                },
            }

            def blocked_inventory(operation_id: str, operation_request: dict, **kwargs: object) -> dict:
                error = EvidenceBlocked("device access is unavailable")
                return build_envelope(
                    operation_id=operation_id,
                    run_id=RUN_ID,
                    status="BLOCKED",
                    dry_run=False,
                    started_at=CREATED_AT,
                    finished_at=CREATED_AT,
                    request_sha256=domain_hash("operation-request", operation_request),
                    repository_authority=spec["repository_authority"],
                    experiment_spec_sha256=spec["content_sha256"],
                    errors=[{**error.as_dict(), "exit_code": error.exit_code}],
                    result={"completed": False},
                )

            with patch("pl_lab.operations.runtime.execute_operation", side_effect=blocked_inventory) as adapter:
                result = execute_named_workflow("benchmark", repository=ROOT, experiment=experiment, request=request, execute=True)
            self.assertEqual(adapter.call_count, 1)
            self.assertEqual(result["status"], "BLOCKED")
            self.assertEqual(result["exit_code"], EXIT_BLOCKED)
            self.assertEqual([step["operation_id"] for step in result["step_results"]], ["pl_device_inventory"])


class BoundaryExecutionTests(unittest.TestCase):
    def test_fast_boundary_suite_publishes_hash_valid_truth_table_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            experiment, spec, _ = _record_chain(temp)
            envelope = execute_operation(
                "pl_run_boundary_suite",
                _request("pl_run_boundary_suite", spec, {"mode": "fast"}),
                repository=ROOT,
                experiment=experiment,
                execute=True,
            )
            validate_envelope(envelope)
            self.assertEqual(envelope["status"], "COMPLETED")
            record = RecordStore(experiment).find(envelope["output_identities"][0]["sha256"])
            artifact = ExperimentStore(experiment).owned_path(*record["payload"]["logical_path"].split("/"))
            data = artifact.read_bytes()
            self.assertEqual(len(data), record["payload"]["size_bytes"])
            self.assertEqual(hashlib.sha256(data).hexdigest(), record["payload"]["sha256"])
            report = json.loads(data.decode("utf-8"))
            self.assertEqual(report["outcome"], "COMPLETE")
            self.assertGreaterEqual(envelope["result"]["case_count"], 18)


class CompareAndReportExecutionTests(unittest.TestCase):
    def test_compare_then_report_publish_verdict_before_report_and_gate(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            experiment, spec, records = _record_chain(temp, pending_candidate=False)
            compare_inputs = [records[key]["content_sha256"] for key in ("baseline", "candidate", "device", "baseline_evaluation", "candidate_evaluation")]
            comparison_envelope = execute_operation("pl_compare_baseline", _request("pl_compare_baseline", spec, {"record_sha256s": compare_inputs}), repository=ROOT, experiment=experiment, execute=True)
            validate_envelope(comparison_envelope)
            comparison = RecordStore(experiment).find(comparison_envelope["output_identities"][0]["sha256"])
            report_inputs = [records[key]["content_sha256"] for key in ("corpus", "calibration", "boundary_artifact", "baseline_artifact", "candidate_artifact", "baseline", "candidate", "device", "calibration_evaluation", "baseline_evaluation", "candidate_evaluation")] + [comparison["content_sha256"]]
            report_request = _request("pl_generate_report", spec, {"record_sha256s": report_inputs})
            report_path = temp / "report-request.json"
            report_path.write_text(json.dumps(report_request), encoding="utf-8")
            stdout = io.StringIO()
            with redirect_stdout(stdout):
                exit_code = main(["pl_generate_report", "--repository", str(ROOT), "--request", str(report_path), "--experiment", str(experiment), "--execute", "--gate"])
            self.assertEqual(exit_code, EXIT_COMPLETED)
            envelope = json.loads(stdout.getvalue())
            validate_envelope(envelope)
            self.assertEqual(envelope["quality_verdict"], "PASS")
            self.assertEqual([item["kind"] for item in envelope["output_identities"]], ["verdict", "report"])
            report = RecordStore(experiment).find(envelope["output_identities"][1]["sha256"])
            self.assertEqual(report["links"]["verdict"], envelope["output_identities"][0]["sha256"])

    def test_gate_maps_complete_fail_to_exit_2(self) -> None:
        self.assertEqual(EXIT_GATE_FAILED, 2)


if __name__ == "__main__":
    unittest.main()
