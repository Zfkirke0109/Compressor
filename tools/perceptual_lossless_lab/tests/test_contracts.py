from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest

from pl_lab.contracts import OPERATION_CONTRACTS, RECORD_AUTHORITIES, operation_contract
from pl_lab.errors import InvalidInput
from pl_lab.schema import SchemaRegistry
from tests.test_schema_parity import operation_payloads


ROOT = Path(__file__).resolve().parents[1]


class OperationContractTests(unittest.TestCase):
    def test_all_stable_operation_ids_have_a_complete_contract(self) -> None:
        expected = {
            "pl_device_inventory",
            "pl_build_install",
            "pl_encode_candidate",
            "pl_score_pair",
            "pl_score_corpus",
            "pl_hdr_compare",
            "pl_pts_compare",
            "pl_run_boundary_suite",
            "pl_optimize_policy",
            "pl_compare_baseline",
            "pl_generate_report",
        }
        self.assertEqual(set(OPERATION_CONTRACTS), expected)
        for operation_id in sorted(expected):
            with self.subTest(operation_id=operation_id):
                contract = operation_contract(operation_id)
                self.assertEqual(contract.operation_id, operation_id)
                self.assertTrue(contract.module.startswith("pl_lab.operations."))
                self.assertTrue(contract.request_schema.endswith(".schema.json"))
                self.assertTrue(contract.result_schema.endswith(".schema.json"))
                self.assertIn(contract.producer_role, RECORD_AUTHORITIES.values())
                self.assertTrue(contract.output_record_types)
                for record_type in contract.output_record_types:
                    self.assertEqual(RECORD_AUTHORITIES[record_type], contract.producer_role)
        self.assertEqual(len({item.request_schema for item in OPERATION_CONTRACTS.values()}), len(expected))
        self.assertEqual(OPERATION_CONTRACTS["pl_generate_report"].output_record_types, ("verdict", "report"))
        self.assertEqual(OPERATION_CONTRACTS["pl_generate_report"].record_type, "report")
        for operation_id, contract in OPERATION_CONTRACTS.items():
            if operation_id != "pl_generate_report":
                self.assertEqual(len(contract.output_record_types), 1)

    def test_record_authority_is_hardcoded_and_candidate_cannot_self_approve(self) -> None:
        self.assertEqual(RECORD_AUTHORITIES["candidate"], "codec-scientist")
        self.assertEqual(RECORD_AUTHORITIES["evaluation"], "perceptual-evaluator")
        self.assertEqual(RECORD_AUTHORITIES["device"], "android-device-lab")
        self.assertEqual(RECORD_AUTHORITIES["comparison"], "deterministic-core")
        self.assertEqual(RECORD_AUTHORITIES["verdict"], "regression-judge")
        self.assertEqual(RECORD_AUTHORITIES["report"], "regression-judge")

        with self.assertRaises(InvalidInput):
            operation_contract("not-an-operation")


class JsonSchemaContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.registry = SchemaRegistry(ROOT / "schemas")

    def test_declared_contract_and_record_schemas_are_loadable(self) -> None:
        contract = json.loads((ROOT / "contracts" / "operations.v1.json").read_text(encoding="utf-8"))
        self.assertEqual(contract["schema_version"], "1")
        self.assertEqual(set(contract["operations"]), set(OPERATION_CONTRACTS))
        schema_names = {
            item.request_schema for item in OPERATION_CONTRACTS.values()
        } | {
            item.result_schema for item in OPERATION_CONTRACTS.values()
        } | {
            "v1/experiment-spec.schema.json",
            "v1/corpus-manifest.schema.json",
            "v1/corpus.schema.json",
            "v1/artifact.schema.json",
            "v1/baseline.schema.json",
            "v1/candidate.schema.json",
            "v1/evaluation.schema.json",
            "v1/device.schema.json",
            "v1/comparison.schema.json",
            "v1/verdict.schema.json",
            "v1/report.schema.json",
            "v1/calibration.schema.json",
            "v1/investigation.schema.json",
            "v1/workflow-plan.schema.json",
            "v1/spec-freeze-request.schema.json",
            "v1/spec-freeze-result.schema.json",
            "v1/workflow-command-result.schema.json",
            "v1/error-result-envelope.schema.json",
            "v1/workflow-step-completion.schema.json",
        }
        for name in sorted(schema_names):
            with self.subTest(schema=name):
                loaded = self.registry.load(name)
                self.assertEqual(loaded["$schema"], "https://json-schema.org/draft/2020-12/schema")

    def test_json_operation_contract_matches_python_contract(self) -> None:
        declared = json.loads((ROOT / "contracts" / "operations.v1.json").read_text(encoding="utf-8"))
        self.assertEqual(declared["schema_version"], "1")
        self.assertEqual(set(declared["operations"]), set(OPERATION_CONTRACTS))
        for operation_id, contract in OPERATION_CONTRACTS.items():
            with self.subTest(operation_id=operation_id):
                operation = declared["operations"][operation_id]
                self.assertEqual(operation["module"], contract.module)
                self.assertEqual(operation["producer_role"], contract.producer_role)
                self.assertEqual(operation["request_schema"], contract.request_schema)
                self.assertEqual(operation["result_schema"], contract.result_schema)
                self.assertEqual(operation["output_record_types"], list(contract.output_record_types))
                self.assertNotIn("record_type", operation)

    def test_json_record_authority_matches_python_authority(self) -> None:
        declared = json.loads((ROOT / "contracts" / "authority.v1.json").read_text(encoding="utf-8"))
        self.assertEqual(declared["schema_version"], "1")
        self.assertEqual(declared["record_authorities"], RECORD_AUTHORITIES)

        verdict_operations = [
            operation_id
            for operation_id, contract in OPERATION_CONTRACTS.items()
            if "verdict" in contract.output_record_types
        ]
        verdict_producers = sorted({OPERATION_CONTRACTS[item].producer_role for item in verdict_operations})
        self.assertEqual(declared["quality_verdict_operations"], verdict_operations)
        self.assertEqual(declared["quality_verdict_producers"], verdict_producers)

    def test_each_operation_request_schema_pins_its_operation_id(self) -> None:
        payloads = operation_payloads()
        for operation_id, contract in OPERATION_CONTRACTS.items():
            valid = {
                "schema_version": "1",
                "operation_id": operation_id,
                "run_id": "run-001",
                "experiment_spec_sha256": "a" * 64,
                "payload": deepcopy(payloads[operation_id]),
            }
            with self.subTest(operation_id=operation_id):
                self.registry.validate(contract.request_schema, valid)
                invalid = dict(valid, operation_id="pl_pts_compare" if operation_id != "pl_pts_compare" else "pl_hdr_compare")
                with self.assertRaises(InvalidInput):
                    self.registry.validate(contract.request_schema, invalid)

    def test_operation_request_is_strict_and_versioned(self) -> None:
        valid = {
            "schema_version": "1",
            "operation_id": "pl_pts_compare",
            "run_id": "run-001",
            "experiment_spec_sha256": "a" * 64,
            "payload": deepcopy(operation_payloads()["pl_pts_compare"]),
        }
        self.registry.validate("v1/operation-request.schema.json", valid)
        for invalid in (
            dict(valid, schema_version="2"),
            dict(valid, operation_id="unknown"),
            dict(valid, unexpected=True),
        ):
            with self.subTest(invalid=invalid):
                with self.assertRaises(InvalidInput):
                    self.registry.validate("v1/operation-request.schema.json", invalid)

    def test_schema_loader_rejects_unknown_validation_keywords(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            path = root / "v1" / "unsupported.schema.json"
            path.parent.mkdir(parents=True)
            path.write_text(
                json.dumps({
                    "$schema": "https://json-schema.org/draft/2020-12/schema",
                    "type": "object",
                    "minProperties": 1,
                }),
                encoding="utf-8",
            )
            registry = SchemaRegistry(root)
            with self.assertRaises(InvalidInput):
                registry.load("v1/unsupported.schema.json")

    def test_schema_pattern_uses_draft_search_semantics(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            path = root / "v1" / "pattern.schema.json"
            path.parent.mkdir(parents=True)
            path.write_text(
                json.dumps({
                    "$schema": "https://json-schema.org/draft/2020-12/schema",
                    "type": "string",
                    "pattern": "middle",
                }),
                encoding="utf-8",
            )
            SchemaRegistry(root).validate("v1/pattern.schema.json", "has-middle-inside")

    def test_candidate_evaluation_device_and_comparison_reject_verdict_fields(self) -> None:
        base = {
            "schema_version": "1",
            "producer_role": "codec-scientist",
            "run_id": "run-001",
            "created_at": "2026-08-13T00:00:00Z",
            "experiment_spec_sha256": "a" * 64,
            "links": {"experiment_spec": "a" * 64},
            "payload": {},
            "content_sha256": "b" * 64,
        }
        cases = (
            ("candidate", "codec-scientist"),
            ("evaluation", "perceptual-evaluator"),
            ("device", "android-device-lab"),
            ("comparison", "deterministic-core"),
        )
        for record_type, role in cases:
            document = dict(base, record_type=record_type, producer_role=role, overall_verdict="PASS")
            with self.subTest(record_type=record_type):
                with self.assertRaises(InvalidInput):
                    self.registry.validate(f"v1/{record_type}.schema.json", document)


if __name__ == "__main__":
    unittest.main()
