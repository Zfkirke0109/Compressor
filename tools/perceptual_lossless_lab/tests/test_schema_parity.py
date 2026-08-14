from __future__ import annotations

from copy import deepcopy
import json
import math
from pathlib import Path
import sys
import tempfile
import unittest

from pl_lab.canonical import seal_document
from pl_lab.contracts import OPERATION_CONTRACTS, RECORD_AUTHORITIES
from pl_lab.envelope import build_envelope, validate_envelope
from pl_lab.errors import InvalidInput
from pl_lab.freeze import freeze_specification
from pl_lab.operations.payloads import validate_operation_payload
from pl_lab.records import create_record, validate_record
from pl_lab.schema import SchemaRegistry
from pl_lab.specification import ExperimentSpecStore, validate_experiment_spec
from pl_lab.workflow import WORKFLOW_STEPS, _validate_completion, _validate_plan, execute_named_workflow, plan_workflow
from tests.fixtures import write_corpus_manifest


ROOT = Path(__file__).resolve().parents[3]
LAB_ROOT = Path(__file__).resolve().parents[1]
SCHEMA_ROOT = LAB_ROOT / "schemas"
RUN_ID = "schema-parity-001"
CREATED_AT = "2026-08-14T00:00:00Z"
HEX_A = "a" * 64
HEX_B = "b" * 64
HEX_C = "c" * 64
HEX_D = "d" * 64


def operation_payloads() -> dict[str, dict]:
    hdr = {
        "standard": 6,
        "transfer": 7,
        "range": 1,
        "bit_depth": 10,
        "dynamic_metadata_sha256": HEX_A,
    }
    return {
        "pl_device_inventory": {
            "adb_executable": "adb",
            "device_serial": "SERIAL-EXAMPLE",
            "android_user": 0,
            "package_id": "example.package",
            "expected_firmware_sha256": HEX_A,
        },
        "pl_build_install": {
            "adb_executable": "adb",
            "apksigner_executable": "apksigner",
            "aapt_executable": "aapt",
            "apk_path": "input/app.apk",
            "device_serial": "SERIAL-EXAMPLE",
            "android_user": 0,
            "package_id": "example.package",
            "expected_build_identity": {"version_code": 1, "version_name": "1.0"},
            "expected_signer_sha256": HEX_A,
            "expected_firmware_sha256": HEX_B,
            "max_duration_seconds": 120,
            "max_storage_bytes": 1_000_000,
        },
        "pl_encode_candidate": {
            "candidate_id": "candidate-001",
            "source_path": "input/source.mp4",
            "output_logical_path": "artifacts/candidate.mp4",
            "requested_configuration": {"codec": "video/hevc"},
            "adapter_id": "android-media3",
            "max_duration_seconds": 120,
            "max_storage_bytes": 1_000_000,
        },
        "pl_score_pair": {
            "source_path": "input/source.mp4",
            "output_path": "input/output.mp4",
            "pair_id": "pair-001",
            "ffmpeg_executable": "ffmpeg",
            "ffprobe_executable": "ffprobe",
            "model_id": "vmaf_v0.6.1",
            "max_duration_seconds": 120,
            "max_storage_bytes": 1_000_000,
        },
        "pl_score_corpus": {
            "corpus_manifest_path": "input/corpus.json",
            "ffmpeg_executable": "ffmpeg",
            "ffprobe_executable": "ffprobe",
            "model_id": "vmaf_v0.6.1",
            "max_candidates": 2,
            "max_workers": 1,
            "max_duration_seconds": 120,
            "max_storage_bytes": 1_000_000,
        },
        "pl_hdr_compare": {
            "input_evaluation_sha256": HEX_A,
            "reference": deepcopy(hdr),
            "candidate": deepcopy(hdr),
        },
        "pl_pts_compare": {
            "input_evaluation_sha256": HEX_A,
            "reference_pts_us": [0, 33_333, 66_666],
            "candidate_pts_us": [0, 33_333, 66_666],
        },
        "pl_run_boundary_suite": {"mode": "fast"},
        "pl_optimize_policy": {
            "proposals": [{"candidate_id": "candidate-001", "configuration": {"codec": "video/hevc"}}],
            "max_candidates": 1,
            "max_workers": 1,
            "max_duration_seconds": 120,
            "max_storage_bytes": 1_000_000,
        },
        "pl_compare_baseline": {"record_sha256s": [HEX_A]},
        "pl_generate_report": {"record_sha256s": [HEX_A]},
    }


def experiment_definition(temp: Path) -> dict:
    manifest = write_corpus_manifest(temp)
    return {
        "corpus": {"manifest_path": str(manifest)},
        "metrics": [
            {
                "metric_id": "vmaf",
                "version": "3.0.0",
                "model_id": "vmaf_v0.6.1",
                "model_source": "host_libvmaf_builtin",
            }
        ],
        "pts": {"mode": "leading_drop_only", "timebase": "microseconds"},
        "hdr": {"comparison": "metadata_exact", "tone_map_substitution": False},
        "tolerances": {
            "size_ratio": 0.03,
            "duration_absolute_ms": 500,
            "duration_fraction": 0.02,
            "frame_count_absolute": 2,
            "frame_count_fraction": 0.01,
        },
        "device_requirements": {"required": False},
        "budgets": {
            "max_candidates": 2,
            "max_workers": 1,
            "max_duration_seconds": 120,
            "max_storage_bytes": 1_000_000,
        },
        "constraints": [
            {"metric": "vmaf_mean", "operator": ">=", "authority_group": "quality_probe", "authority_name": "WINDOW_MEAN_MIN"},
            {"metric": "vmaf_p5", "operator": ">=", "authority_group": "quality_probe", "authority_name": "WINDOW_P5_MIN"},
            {"metric": "vmaf_min", "operator": ">=", "authority_group": "quality_probe", "authority_name": "WINDOW_MIN_MIN"},
            {"metric": "compared_frames", "operator": ">=", "authority_group": "quality_probe", "authority_name": "MIN_COMPARED_FRAMES_PER_WINDOW"},
        ],
    }


def record_inputs(record_type: str) -> tuple[dict, dict]:
    links: dict = {"experiment_spec": HEX_A}
    payloads: dict[str, dict] = {
        "corpus": {
            "manifest_sha256": HEX_B,
            "source_identities_sha256": HEX_C,
            "pair_identities_sha256": HEX_D,
            "item_count": 1,
            "manifest_alias": "corpus.json",
        },
        "artifact": {
            "artifact_id": "artifact-001",
            "logical_path": "artifacts/output.mp4",
            "sha256": HEX_B,
            "size_bytes": 100,
            "status": "COMPLETE",
        },
        "baseline": {
            "baseline_id": "baseline-001",
            "artifact_sha256": HEX_B,
            "status": "COMPLETE",
            "confounders": [],
        },
        "candidate": {
            "candidate_id": "candidate-001",
            "configuration": {"codec": "video/hevc"},
            "artifact_sha256": HEX_B,
            "status": "COMPLETE",
            "warnings": [],
        },
        "evaluation": {
            "evaluation_kind": "subject_quality",
            "evaluation_id": "evaluation-001",
            "subject_sha256": HEX_B,
            "metric_results": {"vmaf_mean": 99.0, "vmaf_p5": 95.0, "vmaf_min": 90.0, "compared_frames": 30},
            "alignment": {"status": "ALIGNED"},
            "hdr": {"status": "MATCH"},
            "provenance": {"metric_model_sha256": HEX_C},
            "status": "COMPLETE",
            "confounders": [],
        },
        "device": {
            "device_alias": "device-0123456789ab",
            "android_user": 0,
            "package_id": "example.package",
            "build_identity": {"version_code": 1, "version_name": "1.0", "apk_sha256": HEX_B, "source_sha256": HEX_C},
            "signer_sha256": HEX_B,
            "firmware_fingerprint_sha256": HEX_C,
            "requested_configuration": {"codec": "video/hevc"},
            "actual_configuration": {"codec": "video/hevc"},
            "thermals": {"status": "NOMINAL"},
            "status": "COMPLETE",
            "confounders": [],
        },
        "comparison": {
            "baseline_sha256": HEX_B,
            "candidate_sha256": HEX_C,
            "baseline_evaluation_sha256s": [HEX_B],
            "candidate_evaluation_sha256s": [HEX_C],
            "device_sha256": HEX_D,
            "deltas": {"vmaf_mean": 0.0},
            "constraint_checks": [],
            "status": "COMPLETE",
            "confounders": [],
        },
        "calibration": {
            "calibration_id": "calibration-001",
            "resolved_constraints": [{"metric": "vmaf_mean"}],
            "authority_sha256": HEX_B,
            "status": "COMPLETE",
            "confounders": [],
        },
        "investigation": {
            "investigation_id": "investigation-001",
            "subject_sha256": HEX_B,
            "finding_codes": ["target_verdict_reproduced"],
            "status": "COMPLETE",
            "confounders": [],
        },
        "verdict": {
            "verdict": "PASS",
            "reasons": ["all_frozen_constraints_satisfied"],
            "evidence_sha256s": [HEX_B, HEX_C],
            "constraint_checks": [{"metric": "vmaf_mean"}],
        },
        "report": {
            "verdict_sha256": HEX_B,
            "verdict": "PASS",
            "summary": {"evidence_record_count": 2},
            "evidence_sha256s": [HEX_C, HEX_D],
        },
    }
    if record_type == "baseline":
        links.update({"artifact": HEX_B, "device": HEX_D})
    elif record_type == "candidate":
        links.update({"artifact": HEX_B, "device": HEX_D, "source_artifact": HEX_C})
    elif record_type == "evaluation":
        links.update({"subject": HEX_B, "artifact": HEX_C, "device": HEX_D})
    elif record_type == "device":
        links["artifacts"] = [HEX_B]
    elif record_type == "comparison":
        links.update(
            {
                "baseline": HEX_B,
                "candidate": HEX_C,
                "baseline_evaluations": [HEX_B],
                "candidate_evaluations": [HEX_C],
                "device": HEX_D,
            }
        )
    elif record_type == "calibration":
        links.update({"corpus": HEX_B, "evaluations": [HEX_C], "boundary_artifact": HEX_D})
    elif record_type == "investigation":
        links.update({"subject": HEX_B, "evidence": [HEX_C]})
    elif record_type == "verdict":
        links.update(
            {
                "corpus": HEX_B,
                "calibration": HEX_C,
                "calibration_evaluations": [HEX_D],
                "boundary_artifact": HEX_A,
                "baseline": HEX_B,
                "candidate": HEX_C,
                "baseline_evaluations": [HEX_B],
                "candidate_evaluations": [HEX_C],
                "device": HEX_D,
                "comparison": HEX_B,
            }
        )
    elif record_type == "report":
        links["verdict"] = HEX_B
    return links, payloads[record_type]


class OperationSchemaParityTests(unittest.TestCase):
    def setUp(self) -> None:
        self.registry = SchemaRegistry(SCHEMA_ROOT)

    def test_all_operation_payloads_are_python_and_schema_valid(self) -> None:
        payloads = operation_payloads()
        self.assertEqual(set(payloads), set(OPERATION_CONTRACTS))
        for operation_id, payload in payloads.items():
            request = {
                "schema_version": "1",
                "operation_id": operation_id,
                "run_id": RUN_ID,
                "experiment_spec_sha256": HEX_A,
                "payload": payload,
            }
            with self.subTest(operation_id=operation_id):
                validate_operation_payload(operation_id, payload, execute=False)
                self.registry.validate(OPERATION_CONTRACTS[operation_id].request_schema, request)
                self.registry.validate("v1/operation-request.schema.json", request)

    def test_empty_mismatched_and_unknown_nested_payloads_are_rejected(self) -> None:
        for operation_id in OPERATION_CONTRACTS:
            empty = {
                "schema_version": "1",
                "operation_id": operation_id,
                "run_id": RUN_ID,
                "experiment_spec_sha256": HEX_A,
                "payload": {},
            }
            with self.subTest(operation_id=operation_id):
                with self.assertRaises(InvalidInput):
                    self.registry.validate(OPERATION_CONTRACTS[operation_id].request_schema, empty)
                with self.assertRaises(InvalidInput):
                    self.registry.validate("v1/operation-request.schema.json", empty)

        requests = operation_payloads()
        mismatched = {
            "schema_version": "1",
            "operation_id": "pl_pts_compare",
            "run_id": RUN_ID,
            "experiment_spec_sha256": HEX_A,
            "payload": requests["pl_hdr_compare"],
        }
        with self.assertRaises(InvalidInput):
            self.registry.validate("v1/operation-request.schema.json", mismatched)

        nested = {
            "schema_version": "1",
            "operation_id": "pl_build_install",
            "run_id": RUN_ID,
            "experiment_spec_sha256": HEX_A,
            "payload": deepcopy(requests["pl_build_install"]),
        }
        nested["payload"]["expected_build_identity"]["unknown"] = True
        with self.assertRaises(InvalidInput):
            self.registry.validate("v1/requests/pl_build_install.schema.json", nested)


class RecordSchemaParityTests(unittest.TestCase):
    def setUp(self) -> None:
        self.registry = SchemaRegistry(SCHEMA_ROOT)

    def test_every_record_shape_is_python_and_schema_valid(self) -> None:
        record_types = set(RECORD_AUTHORITIES) - {"experiment_spec"}
        for record_type in sorted(record_types):
            links, payload = record_inputs(record_type)
            record = create_record(
                record_type,
                producer_role=RECORD_AUTHORITIES[record_type],
                run_id=RUN_ID,
                created_at=CREATED_AT,
                experiment_spec_sha256=HEX_A,
                links=links,
                payload=payload,
                supersedes_sha256=HEX_D if record_type == "calibration" else None,
            )
            with self.subTest(record_type=record_type):
                validate_record(record)
                self.registry.validate(f"v1/{record_type}.schema.json", record)

    def test_record_links_and_payloads_reject_empty_or_unknown_fields(self) -> None:
        for record_type in sorted(set(RECORD_AUTHORITIES) - {"experiment_spec"}):
            links, payload = record_inputs(record_type)
            record = create_record(
                record_type,
                producer_role=RECORD_AUTHORITIES[record_type],
                run_id=RUN_ID,
                created_at=CREATED_AT,
                experiment_spec_sha256=HEX_A,
                links=links,
                payload=payload,
                supersedes_sha256=HEX_D if record_type == "calibration" else None,
            )
            with self.subTest(record_type=record_type, case="empty"):
                empty = deepcopy(record)
                empty["links"] = {}
                empty["payload"] = {}
                with self.assertRaises(InvalidInput):
                    self.registry.validate(f"v1/{record_type}.schema.json", empty)
            with self.subTest(record_type=record_type, case="unknown"):
                unknown = deepcopy(record)
                unknown["links"]["unknown"] = HEX_D
                unknown["payload"]["unknown"] = True
                with self.assertRaises(InvalidInput):
                    self.registry.validate(f"v1/{record_type}.schema.json", unknown)

    def test_calibration_cannot_self_declare_completion_without_pending_evidence(self) -> None:
        links, payload = record_inputs("calibration")
        with self.assertRaises(InvalidInput):
            create_record(
                "calibration",
                producer_role="deterministic-core",
                run_id=RUN_ID,
                created_at=CREATED_AT,
                experiment_spec_sha256=HEX_A,
                links=links,
                payload=payload,
            )

        blocked = deepcopy(payload)
        blocked["status"] = "BLOCKED"
        blocked["confounders"] = ["corpus_evaluation_pending"]
        with self.assertRaises(InvalidInput):
            create_record(
                "calibration",
                producer_role="deterministic-core",
                run_id=RUN_ID,
                created_at=CREATED_AT,
                experiment_spec_sha256=HEX_A,
                links={"experiment_spec": HEX_A, "corpus": HEX_B},
                payload=blocked,
                supersedes_sha256=HEX_D,
            )

        pending = create_record(
            "calibration",
            producer_role="deterministic-core",
            run_id=RUN_ID,
            created_at=CREATED_AT,
            experiment_spec_sha256=HEX_A,
            links={"experiment_spec": HEX_A, "corpus": HEX_B},
            payload=blocked,
        )
        malformed = deepcopy(pending)
        malformed["links"]["boundary_artifact"] = HEX_C
        with self.assertRaises(InvalidInput):
            self.registry.validate("v1/calibration.schema.json", malformed)
        with self.assertRaises(InvalidInput):
            create_record(
                "calibration",
                producer_role="deterministic-core",
                run_id=RUN_ID,
                created_at=CREATED_AT,
                experiment_spec_sha256=HEX_A,
                links=malformed["links"],
                payload=blocked,
            )


class SpecEnvelopeAndWorkflowSchemaParityTests(unittest.TestCase):
    def setUp(self) -> None:
        self.registry = SchemaRegistry(SCHEMA_ROOT)

    def test_spec_freeze_and_result_envelope_match_python_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            temp = Path(temp_name)
            definition = experiment_definition(temp)
            freeze_request = {
                "schema_version": "1",
                "run_id": RUN_ID,
                "created_at": CREATED_AT,
                "definition": definition,
            }
            self.registry.validate("v1/spec-freeze-request.schema.json", freeze_request)
            experiment = temp / "experiment"
            freeze_result = freeze_specification(ROOT, experiment, freeze_request, execute=True)
            spec = ExperimentSpecStore(experiment).get(freeze_result["experiment_spec_sha256"])
            validate_experiment_spec(spec)
            self.registry.validate("v1/experiment-spec.schema.json", spec)
            self.registry.validate("v1/spec-freeze-result.schema.json", freeze_result)

            nested_unknown = deepcopy(spec)
            nested_unknown["repository_authority"]["repository_identity"]["unknown"] = True
            with self.assertRaises(InvalidInput):
                self.registry.validate("v1/experiment-spec.schema.json", nested_unknown)

            envelope = build_envelope(
                operation_id="pl_pts_compare",
                run_id=RUN_ID,
                status="DRY_RUN",
                dry_run=True,
                started_at=CREATED_AT,
                finished_at="2026-08-14T00:00:01Z",
                request_sha256=HEX_B,
                repository_authority=spec["repository_authority"],
                experiment_spec_sha256=spec["content_sha256"],
                result={"planned": True},
            )
            validate_envelope(envelope)
            self.registry.validate("v1/result-envelope.schema.json", envelope)

            contradictory = deepcopy(envelope)
            contradictory["dry_run"] = False
            contradictory = seal_document(
                "result-envelope",
                {key: value for key, value in contradictory.items() if key != "content_sha256"},
            )
            with self.assertRaises(InvalidInput):
                validate_envelope(contradictory)
            with self.assertRaises(InvalidInput):
                self.registry.validate("v1/result-envelope.schema.json", contradictory)

            unknown_authority = deepcopy(envelope)
            unknown_authority["repository_authority"]["repository_identity"]["unknown"] = True
            with self.assertRaises(InvalidInput):
                self.registry.validate("v1/result-envelope.schema.json", unknown_authority)

    def test_workflow_plan_completion_and_dry_result_are_strict(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            experiment = Path(temp_name) / "experiment"
            for workflow_id, operations in WORKFLOW_STEPS.items():
                request = {
                    "schema_version": "1",
                    "workflow_id": workflow_id,
                    "run_id": RUN_ID,
                    "experiment_spec_sha256": HEX_A,
                    "payload": {},
                }
                plan = plan_workflow(experiment, request, execute=False, created_at=CREATED_AT)
                _validate_plan(plan)
                self.registry.validate("v1/workflow-plan.schema.json", plan)
                first = plan["steps"][0]
                completion = seal_document(
                    "workflow-step-completion",
                    {
                        "schema_version": "1",
                        "workflow_id": workflow_id,
                        "run_id": RUN_ID,
                        "plan_sha256": plan["content_sha256"],
                        "step_index": first["index"],
                        "operation_id": first["operation_id"],
                        "operation_request_sha256": HEX_B,
                        "output_sha256s": [HEX_C],
                        "completed_at": "2026-08-14T00:00:01Z",
                    },
                )
                _validate_completion(completion, plan=plan, step=first)
                self.registry.validate("v1/workflow-step-completion.schema.json", completion)

                if workflow_id == "pl-benchmark":
                    step_results = [
                        {
                            "operation_id": step["operation_id"],
                            "status": "DRY_RUN",
                            "skipped": False,
                            "request_sha256": HEX_B,
                            "output_sha256s": [],
                            "envelope_sha256": None,
                        }
                        for step in plan["steps"]
                    ]
                    command_result = seal_document(
                        "workflow-command-result",
                        {
                            "schema_version": "1",
                            "workflow_id": workflow_id,
                            "status": "DRY_RUN",
                            "dry_run": True,
                            "final_gate": False,
                            "experiment_spec_sha256": HEX_A,
                            "plan": plan,
                            "step_results": step_results,
                            "errors": [],
                            "exit_code": 0,
                        },
                    )
                    self.registry.validate("v1/workflow-command-result.schema.json", command_result)

                malformed = deepcopy(plan)
                malformed["steps"][0]["unknown"] = True
                with self.assertRaises(InvalidInput):
                    self.registry.validate("v1/workflow-plan.schema.json", malformed)

    def test_named_workflow_requests_match_lineage_templates_and_contract_registry(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            temp = Path(temp_name)
            payloads = operation_payloads()
            hdr_template = deepcopy(payloads["pl_hdr_compare"])
            del hdr_template["input_evaluation_sha256"]
            pts_template = deepcopy(payloads["pl_pts_compare"])
            del pts_template["input_evaluation_sha256"]
            calibrate_definition = experiment_definition(temp)
            calibrate_corpus_payload = deepcopy(payloads["pl_score_corpus"])
            calibrate_corpus_payload.update({
                "corpus_manifest_path": calibrate_definition["corpus"]["manifest_path"],
                "ffmpeg_executable": sys.executable,
                "ffprobe_executable": sys.executable,
            })
            requests = {
                "pl-calibrate": {
                    "schema_version": "1",
                    "workflow_id": "pl-calibrate",
                    "run_id": RUN_ID,
                    "created_at": CREATED_AT,
                    "definition": calibrate_definition,
                    "payload": {
                        "step_payloads": {
                            "pl_run_boundary_suite": deepcopy(payloads["pl_run_boundary_suite"]),
                            "pl_score_corpus": calibrate_corpus_payload,
                        }
                    },
                },
                "pl-benchmark": {
                    "schema_version": "1",
                    "workflow_id": "pl-benchmark",
                    "run_id": RUN_ID,
                    "created_at": CREATED_AT,
                    "experiment_spec_sha256": HEX_A,
                    "payload": {
                        "initial_evidence": {
                            "corpus_sha256": HEX_A,
                            "calibration_sha256": HEX_A,
                            "baseline_sha256": HEX_B,
                            "baseline_artifact_sha256": HEX_C,
                            "baseline_evaluation_sha256": HEX_D,
                        },
                        "step_payloads": {
                            "pl_device_inventory": deepcopy(payloads["pl_device_inventory"]),
                            "pl_build_install": deepcopy(payloads["pl_build_install"]),
                            "pl_encode_candidate": deepcopy(payloads["pl_encode_candidate"]),
                            "pl_score_pair": deepcopy(payloads["pl_score_pair"]),
                            "pl_hdr_compare": hdr_template,
                            "pl_pts_compare": pts_template,
                            "pl_compare_baseline": {},
                            "pl_generate_report": {},
                        },
                    },
                },
                "pl-investigate": {
                    "schema_version": "1",
                    "workflow_id": "pl-investigate",
                    "run_id": RUN_ID,
                    "created_at": CREATED_AT,
                    "experiment_spec_sha256": HEX_A,
                    "payload": {
                        "target_verdict_sha256": HEX_B,
                        "step_payloads": {
                            "pl_compare_baseline": {},
                            "pl_run_boundary_suite": deepcopy(payloads["pl_run_boundary_suite"]),
                        },
                    },
                },
            }

            for workflow_id, request in requests.items():
                schema_name = f"v1/{workflow_id}-request.schema.json"
                with self.subTest(workflow_id=workflow_id):
                    self.registry.validate(schema_name, request)
                    stored_request = {
                        "schema_version": "1",
                        "workflow_id": workflow_id,
                        "run_id": RUN_ID,
                        "experiment_spec_sha256": request.get("experiment_spec_sha256", HEX_A),
                        "payload": deepcopy(request["payload"]),
                    }
                    stored_request["payload"]["final_gate"] = False
                    self.registry.validate("v1/workflow-request.schema.json", stored_request)

            calibrate_result = execute_named_workflow(
                "calibrate",
                repository=ROOT,
                experiment=temp / "calibrate-experiment",
                request=requests["pl-calibrate"],
                execute=False,
            )
            self.registry.validate("v1/workflow-command-result.schema.json", calibrate_result)

            malformed = deepcopy(requests["pl-calibrate"])
            malformed["payload"]["step_payloads"]["pl_score_corpus"]["unknown"] = True
            with self.assertRaises(InvalidInput):
                self.registry.validate("v1/pl-calibrate-request.schema.json", malformed)
            malformed = deepcopy(requests["pl-benchmark"])
            malformed["payload"]["initial_evidence"]["unknown"] = HEX_A
            with self.assertRaises(InvalidInput):
                self.registry.validate("v1/pl-benchmark-request.schema.json", malformed)
            malformed = deepcopy(requests["pl-investigate"])
            malformed["payload"]["step_payloads"]["pl_compare_baseline"]["unknown"] = True
            with self.assertRaises(InvalidInput):
                self.registry.validate("v1/pl-investigate-request.schema.json", malformed)

            declared = json.loads((LAB_ROOT / "contracts" / "workflows.v1.json").read_text(encoding="utf-8"))
            self.assertEqual(declared["schema_version"], "1")
            self.assertEqual(set(declared["workflows"]), set(WORKFLOW_STEPS))
            for workflow_id, steps in WORKFLOW_STEPS.items():
                contract = declared["workflows"][workflow_id]
                self.assertEqual(contract["steps"], list(steps))
                self.assertEqual(contract["request_schema"], f"v1/{workflow_id}-request.schema.json")
                self.assertEqual(contract["result_schema"], "v1/workflow-command-result.schema.json")
                self.registry.load(contract["request_schema"])
                self.registry.load(contract["result_schema"])


class SchemaRegistryParityRegressionTests(unittest.TestCase):
    def test_unique_items_is_json_semantic_not_dict_insertion_order(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            root = Path(temp_name)
            path = root / "array.schema.json"
            path.write_text(
                json.dumps({"type": "array", "uniqueItems": True, "items": {"type": "object"}}),
                encoding="utf-8",
            )
            duplicate_objects = [{"a": 1, "b": 2}, {"b": 2, "a": 1}]
            with self.assertRaises(InvalidInput):
                SchemaRegistry(root).validate("array.schema.json", duplicate_objects)

    def test_nonfinite_programmatic_numbers_are_not_json_numbers(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            root = Path(temp_name)
            path = root / "number.schema.json"
            path.write_text(json.dumps({"type": "number"}), encoding="utf-8")
            registry = SchemaRegistry(root)
            for value in (math.nan, math.inf, -math.inf):
                with self.subTest(value=value):
                    with self.assertRaises(InvalidInput):
                        registry.validate("number.schema.json", value)


if __name__ == "__main__":
    unittest.main()
