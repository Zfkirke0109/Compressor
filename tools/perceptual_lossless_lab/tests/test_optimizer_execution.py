from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from pl_lab.authority import AuthoritySnapshot
from pl_lab.errors import EvidenceBlocked, InvalidInput
from pl_lab.experiment import ExperimentStore
from pl_lab.operations.codec import candidate_id_for, execute
from pl_lab.records import RecordStore, validate_record


HEX_A = "a" * 64
CREATED_AT = "2026-08-14T00:00:00Z"
RUN_ID = "run-optimizer-001"


def _spec(**budget_changes: int) -> dict:
    budgets = {
        "max_candidates": 3,
        "max_workers": 1,
        "max_duration_seconds": 120,
        "max_storage_bytes": 100_000,
    }
    budgets.update(budget_changes)
    return {
        "content_sha256": HEX_A,
        "run_id": RUN_ID,
        "created_at": CREATED_AT,
        "budgets": budgets,
        "threshold_provenance": {
            "resolved_constraints": [
                {"metric": "vmaf_mean", "operator": ">=", "value": 95.5},
                {"metric": "vmaf_p5", "operator": ">=", "value": 91.0},
            ]
        },
    }


def _payload(spec: dict, configurations: list[dict] | None = None, **changes: object) -> dict:
    configurations = configurations or [
        {"codec": "video/hevc", "bitrate_mode": "VBR", "bitrate_ratio": 0.9},
    ]
    payload: dict[str, object] = {
        "proposals": [
            {"candidate_id": candidate_id_for(spec, configuration), "configuration": configuration}
            for configuration in configurations
        ],
        "max_candidates": len(configurations),
        "max_workers": 1,
        "max_duration_seconds": 60,
        "max_storage_bytes": 50_000,
    }
    payload.update(changes)
    return payload


def _snapshot(repository: Path) -> AuthoritySnapshot:
    return AuthoritySnapshot(root=repository, files=(), missing=(), repository_identity={})


class OptimizerExecutionTests(unittest.TestCase):
    def test_proposals_are_spec_bound_sorted_immutable_and_retry_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = root / "repository"
            repository.mkdir()
            policy = repository / "policy.kt"
            policy.write_bytes(b"authoritative production policy\n")
            experiment = root / "experiment"
            ExperimentStore(experiment).prepare(execute=True)
            store = RecordStore(experiment)
            spec = _spec()
            configurations = [
                {"vendor_profile": "samsung", "codec": "video/hevc", "preserve_hdr": True},
                {"target_bitrate_bps": 5_000_000, "codec": "video/av01", "max_b_frames": 2},
            ]
            first = execute(
                "pl_optimize_policy",
                _payload(spec, configurations),
                store=store,
                experiment_spec=spec,
                repository=repository,
                authority_snapshot=_snapshot(repository),
            )
            second = execute(
                "pl_optimize_policy",
                _payload(spec, list(reversed(configurations))),
                store=store,
                experiment_spec=spec,
                repository=repository,
                authority_snapshot=_snapshot(repository),
            )

            self.assertEqual(first, second)
            self.assertEqual(policy.read_bytes(), b"authoritative production policy\n")
            self.assertFalse(first["result"]["policy_mutation"])
            self.assertFalse(first["result"]["encoded_media_produced"])
            self.assertEqual(first["result"]["candidate_ids"], sorted(first["result"]["candidate_ids"]))
            self.assertEqual(len(first["records"]), 2)
            self.assertFalse((experiment / "records" / "artifact").exists())

            for record, artifact_identity in zip(first["records"], first["result"]["proposal_artifacts"]):
                validate_record(record)
                self.assertEqual(record["producer_role"], "codec-scientist")
                self.assertEqual(record["run_id"], RUN_ID)
                self.assertEqual(record["experiment_spec_sha256"], HEX_A)
                self.assertEqual(record["payload"]["status"], "COMPLETE")
                self.assertEqual(record["payload"]["warnings"], ["proposal_only_no_encoded_media"])
                self.assertIn("constraint_set_sha256", record["payload"]["configuration"])
                self.assertNotIn("thresholds", json.dumps(record["payload"]["configuration"]))
                artifact = experiment.joinpath(*artifact_identity["logical_path"].split("/"))
                data = artifact.read_bytes()
                self.assertEqual(hashlib.sha256(data).hexdigest(), artifact_identity["sha256"])
                self.assertEqual(record["payload"]["artifact_sha256"], artifact_identity["sha256"])
                document = json.loads(data.decode("utf-8"))
                self.assertFalse(document["encoded_media_produced"])
                self.assertFalse(document["policy_mutation"])

    def test_proposal_schema_types_ids_and_authority_smuggling_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repository = Path(temp_dir)
            experiment = repository / "experiment"
            ExperimentStore(experiment).prepare(execute=True)
            spec = _spec()
            malformed = []

            extra_proposal = _payload(spec)
            extra_proposal["proposals"][0]["rank"] = 1
            malformed.append(extra_proposal)
            invalid_alias = _payload(spec)
            invalid_alias["proposals"][0]["candidate_id"] = "../candidate"
            malformed.append(invalid_alias)
            mismatched_identity = _payload(spec)
            mismatched_identity["proposals"][0]["candidate_id"] = "candidate-wrong"
            malformed.append(mismatched_identity)
            for configuration in (
                {},
                {"codec": "video/vp9"},
                {"codec": "video/hevc", "quality_threshold": 90},
                {"codec": "video/hevc", "overallVerdict": "PASS"},
                {"codec": "video/hevc", "max_b_frames": True},
                {"codec": "video/hevc", "output_height": 721},
                {"codec": "video/hevc", "bitrate_ratio": 1.1},
                {"codec": "video/hevc", "preserve_hdr": 1},
            ):
                candidate_id = "candidate-input"
                malformed.append({**_payload(spec), "proposals": [{"candidate_id": candidate_id, "configuration": configuration}]})

            for payload in malformed:
                with self.subTest(payload=payload):
                    with self.assertRaises(InvalidInput):
                        execute(
                            "pl_optimize_policy",
                            payload,
                            store=RecordStore(experiment),
                            experiment_spec=spec,
                            repository=repository,
                            authority_snapshot=_snapshot(repository),
                        )
            self.assertFalse((experiment / "records").exists())
            self.assertFalse((experiment / "artifacts").exists())

    def test_budgets_count_duplicates_and_storage_are_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repository = Path(temp_dir)
            experiment = repository / "experiment"
            ExperimentStore(experiment).prepare(execute=True)
            store = RecordStore(experiment)
            spec = _spec()
            configuration = {"codec": "video/hevc"}
            duplicate = _payload(spec, [configuration, configuration])
            cases = [
                {**_payload(spec), "max_candidates": 0},
                {**_payload(spec), "max_workers": 2},
                {**_payload(spec), "max_duration_seconds": 0},
                {**_payload(spec), "max_duration_seconds": 121},
                {**_payload(spec), "max_storage_bytes": 1},
                {**_payload(spec), "max_candidates": 4},
                duplicate,
                {**_payload(spec), "proposals": []},
            ]
            for payload in cases:
                with self.subTest(payload=payload):
                    with self.assertRaises(InvalidInput):
                        execute(
                            "pl_optimize_policy",
                            payload,
                            store=store,
                            experiment_spec=spec,
                            repository=repository,
                            authority_snapshot=_snapshot(repository),
                        )
            self.assertFalse((experiment / "records").exists())
            self.assertFalse((experiment / "artifacts").exists())

    def test_encode_candidate_remains_blocked_without_a_tested_adapter(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repository = Path(temp_dir)
            experiment = repository / "experiment"
            ExperimentStore(experiment).prepare(execute=True)
            with self.assertRaises(EvidenceBlocked):
                execute(
                    "pl_encode_candidate",
                    {},
                    store=RecordStore(experiment),
                    experiment_spec=_spec(),
                    repository=repository,
                    authority_snapshot=_snapshot(repository),
                )


if __name__ == "__main__":
    unittest.main()
