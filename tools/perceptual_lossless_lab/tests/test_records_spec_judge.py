from __future__ import annotations

from copy import deepcopy
import hashlib
from pathlib import Path
import tempfile
import unittest

from pl_lab.authority import AuthoritySnapshot, capture_authority_snapshot
from pl_lab.canonical import domain_hash
from pl_lab.errors import EvidenceBlocked, ImmutableConflict, InvalidInput
from pl_lab.experiment import ExperimentStore
from pl_lab.judge import assert_acyclic_graph, judge_evidence
from pl_lab.operations.boundary import expected_boundary_artifact
from pl_lab.records import RecordStore, create_record, validate_record
from pl_lab.specification import ExperimentSpecStore, build_experiment_spec, corpus_manifest_snapshot
from tests.fixtures import write_corpus_manifest


HEX_A = "a" * 64
HEX_B = "b" * 64
HEX_C = "c" * 64


def write_authority_fixture(root: Path) -> None:
    files = {
        "app/src/main/java/compress/joshattic/us/quality/QualityProbePolicy.kt": """
            object QualityProbePolicy {
                const val WINDOW_MEAN_MIN = 95.5
                const val WINDOW_P5_MIN = 91.0
                const val WINDOW_MIN_MIN = 84.0
                const val MIN_COMPARED_FRAMES_PER_WINDOW = 12
                const val HARD_RATIO_FLOOR = 0.60
                const val SAFEST_RATIO_CEILING = 0.97
                const val PROBE_MIN_SOURCE_BITS_PER_PIXEL = 0.03
                const val REFINEMENT_MIN_GAP = 0.06
            }
        """,
        "app/src/main/java/compress/joshattic/us/BatchQualityBitratePolicy.kt": """
            object BatchQualityBitratePolicy {
                const val PERCEPTUAL_LOSSLESS_SIZE_TOLERANCE = 0.03
                const val PERCEPTUAL_LOSSLESS_MIN_SOURCE_BITS_PER_PIXEL = 0.08
                const val MIN_PERCEPTUAL_LOSSLESS_PREDICTED_SAVINGS = 0.005
                const val MIN_PERCEPTUAL_LOSSLESS_SAVINGS_BYTES = 262_144L
                const val PERCEPTUAL_LOSSLESS_MAX_TARGET_RATIO = 0.97
                private const val HDR_120_RATIO_FLOOR = 0.90
                private const val HDR_4K60_RATIO_FLOOR = 0.80
                private const val HDR_4K_RATIO_FLOOR = 0.75
                private const val FPS120_RATIO_FLOOR = 0.88
                private const val FPS60_4K_RATIO_FLOOR = 0.85
                private const val FPS60_RATIO_FLOOR = 0.85
                private const val UHD_RATIO_FLOOR = 0.85
                private const val DEFAULT_RATIO_FLOOR = 0.85
            }
        """,
        "app/src/main/java/compress/joshattic/us/quality/PtsAligner.kt": """
            class PtsAligner { companion object {
                const val TOLERANCE_FLOOR_US = 4_000L
                const val MAX_ALIGNMENT_DROPS = 8
            } }
        """,
        "app/src/main/java/compress/joshattic/us/OutputVerifier.kt": """
            object OutputVerifier {
                val durationMatches = abs(output - source) <= maxOf(500L, (input.source.durationMs * 0.02).toLong())
                val frameCountMatches = abs(outputFrames - sourceFrames) <= maxOf(2, (input.sourceFrameCount * 0.01).toInt())
            }
        """,
        "scripts/diagnostics/measure_quality.py": "# deterministic scorer fixture\n",
    }
    for relative, text in files.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")


def spec_definition(root: Path) -> dict:
    manifest = write_corpus_manifest(root / "fixtures")
    model = root / "fixtures" / "vmaf-model.json"
    model.write_bytes(b'{"model":"vmaf_v0.6.1"}\n')
    return {
        "corpus": {"manifest_path": str(manifest)},
        "metrics": [{
            "metric_id": "vmaf",
            "version": "3.0.0",
            "model_id": "vmaf_v0.6.1",
            "model_path": str(model),
        }],
        "pts": {"mode": "leading_drop_only", "timebase": "microseconds"},
        "hdr": {"comparison": "metadata_exact", "tone_map_substitution": False},
        "tolerances": {"size_ratio": 0.03, "duration_absolute_ms": 500, "duration_fraction": 0.02, "frame_count_absolute": 2, "frame_count_fraction": 0.01},
        "device_requirements": {"required": False, "firmware_sha256": HEX_C},
        "budgets": {
            "max_candidates": 2,
            "max_workers": 1,
            "max_duration_seconds": 120,
            "max_storage_bytes": 100_000_000,
        },
        "constraints": [
            {"metric": "vmaf_mean", "operator": ">=", "authority_group": "quality_probe", "authority_name": "WINDOW_MEAN_MIN"},
            {"metric": "vmaf_p5", "operator": ">=", "authority_group": "quality_probe", "authority_name": "WINDOW_P5_MIN"},
            {"metric": "vmaf_min", "operator": ">=", "authority_group": "quality_probe", "authority_name": "WINDOW_MIN_MIN"},
            {"metric": "compared_frames", "operator": ">=", "authority_group": "quality_probe", "authority_name": "MIN_COMPARED_FRAMES_PER_WINDOW"},
        ],
    }


class FrozenSpecificationTests(unittest.TestCase):
    def test_spec_binds_authority_corpus_metrics_alignment_hdr_device_and_budgets(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_authority_fixture(root)
            definition = spec_definition(root)

            spec = build_experiment_spec(
                root,
                run_id="run-001",
                created_at="2026-08-13T00:00:00Z",
                definition=definition,
                require_all_authorities=False,
            )

            self.assertEqual(spec["record_type"], "experiment_spec")
            self.assertEqual(spec["threshold_provenance"]["resolved_constraints"][0]["value"], 95.5)
            self.assertEqual(spec["corpus"]["manifest_sha256"], hashlib.sha256(Path(definition["corpus"]["manifest_path"]).read_bytes()).hexdigest())
            self.assertEqual(spec["corpus"]["item_count"], 1)
            self.assertRegex(spec["corpus"]["source_identities_sha256"], r"^[0-9a-f]{64}$")
            self.assertRegex(spec["corpus"]["pair_identities_sha256"], r"^[0-9a-f]{64}$")
            self.assertNotIn(str(root), str(spec))
            self.assertEqual(spec["budgets"]["max_workers"], 1)
            self.assertIn("content_sha256", spec)

    def test_caller_cannot_supply_numeric_threshold_or_parallel_workers(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_authority_fixture(root)
            definition = spec_definition(root)
            definition["constraints"][0]["value"] = 0
            with self.assertRaises(InvalidInput):
                build_experiment_spec(root, run_id="r1", created_at="2026-08-13T00:00:00Z", definition=definition, require_all_authorities=False)

            definition = spec_definition(root)
            definition["constraints"][0]["operator"] = "<="
            with self.assertRaises(InvalidInput):
                build_experiment_spec(root, run_id="r1b", created_at="2026-08-13T00:00:00Z", definition=definition, require_all_authorities=False)

            definition = spec_definition(root)
            definition["budgets"]["max_workers"] = 2
            with self.assertRaises(InvalidInput):
                build_experiment_spec(root, run_id="r2", created_at="2026-08-13T00:00:00Z", definition=definition, require_all_authorities=False)

    def test_missing_corpus_or_metric_model_is_blocked_not_missing_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_authority_fixture(root)
            definition = spec_definition(root)
            definition["corpus"]["manifest_path"] = str(root / "missing.json")
            with self.assertRaises(EvidenceBlocked) as caught:
                build_experiment_spec(root, run_id="r1", created_at="2026-08-13T00:00:00Z", definition=definition, require_all_authorities=False)
            self.assertEqual(caught.exception.exit_code, 13)

            definition = spec_definition(root)
            definition["metrics"][0]["model_path"] = str(root / "missing-model.json")
            with self.assertRaises(EvidenceBlocked) as caught:
                build_experiment_spec(root, run_id="r2", created_at="2026-08-13T00:00:00Z", definition=definition, require_all_authorities=False)
            self.assertEqual(caught.exception.exit_code, 13)

    def test_required_device_contract_is_complete(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_authority_fixture(root)
            definition = spec_definition(root)
            definition["device_requirements"] = {"required": True, "device_alias": "device-0123456789ab"}
            with self.assertRaises(InvalidInput):
                build_experiment_spec(root, run_id="r1", created_at="2026-08-13T00:00:00Z", definition=definition, require_all_authorities=False)


class ExperimentAndRecordTests(unittest.TestCase):
    def test_dry_run_does_not_create_experiment_and_execute_marks_ownership(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            experiment = Path(temp) / "new-experiment"
            store = ExperimentStore(experiment)
            self.assertFalse(store.prepare(execute=False))
            self.assertFalse(experiment.exists())
            self.assertTrue(store.prepare(execute=True))
            self.assertTrue((experiment / ".pl-lab-owner.json").is_file())
            self.assertFalse(store.prepare(execute=True))

    def test_nonempty_unowned_experiment_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            experiment = Path(temp) / "existing"
            experiment.mkdir()
            (experiment / "foreign.txt").write_text("preserve", encoding="utf-8")
            with self.assertRaises(ImmutableConflict):
                ExperimentStore(experiment).prepare(execute=True)
            self.assertEqual((experiment / "foreign.txt").read_text(encoding="utf-8"), "preserve")

    def test_symlinked_owner_marker_cannot_adopt_foreign_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            experiment = root / "experiment"
            experiment.mkdir()
            foreign = root / "foreign-marker.json"
            from pl_lab.canonical import seal_document
            from pl_lab.jsonio import canonical_json_file_bytes
            foreign.write_bytes(canonical_json_file_bytes(seal_document("experiment-owner-marker", {"schema_version": "1", "owner": "compressor-perceptual-lossless-lab"})))
            try:
                (experiment / ".pl-lab-owner.json").symlink_to(foreign)
            except (OSError, NotImplementedError):
                self.skipTest("symlink creation is unavailable")
            with self.assertRaises(InvalidInput):
                ExperimentStore(experiment).prepare(execute=True)

    def test_record_storage_is_content_addressed_idempotent_and_tamper_evident(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            experiment = Path(temp) / "exp"
            ExperimentStore(experiment).prepare(execute=True)
            record = create_record(
                "candidate",
                producer_role="codec-scientist",
                run_id="run-001",
                created_at="2026-08-13T00:00:00Z",
                experiment_spec_sha256=HEX_A,
                links={"experiment_spec": HEX_A},
                payload={
                    "candidate_id": "candidate-001",
                    "configuration": {"codec": "video/hevc"},
                    "artifact_sha256": HEX_B,
                    "status": "COMPLETE",
                    "warnings": [],
                },
            )
            records = RecordStore(experiment)
            first = records.put(record)
            second = records.put(record)
            self.assertEqual(first, second)
            self.assertEqual(first.name, f"{record['content_sha256']}.json")

            first.write_text("{}\n", encoding="utf-8")
            with self.assertRaises(ImmutableConflict):
                records.put(record)

    def test_record_role_unknown_fields_nested_verdict_and_bad_supersession_are_rejected(self) -> None:
        valid = {
            "candidate_id": "candidate-001",
            "configuration": {"codec": "video/hevc"},
            "artifact_sha256": HEX_B,
            "status": "COMPLETE",
            "warnings": [],
        }
        with self.assertRaises(InvalidInput):
            create_record("candidate", producer_role="perceptual-evaluator", run_id="r1", created_at="2026-08-13T00:00:00Z", experiment_spec_sha256=HEX_A, links={"experiment_spec": HEX_A}, payload=valid)
        with self.assertRaises(InvalidInput):
            create_record("candidate", producer_role="codec-scientist", run_id="r1", created_at="2026-08-13T00:00:00Z", experiment_spec_sha256=HEX_A, links={"experiment_spec": HEX_A}, payload=dict(valid, unknown=True))
        with self.assertRaises(InvalidInput):
            create_record("candidate", producer_role="codec-scientist", run_id="r1", created_at="2026-08-13T00:00:00Z", experiment_spec_sha256=HEX_A, links={"experiment_spec": HEX_A}, payload=dict(valid, configuration={"overall_verdict": "PASS"}))

        with tempfile.TemporaryDirectory() as temp:
            experiment = Path(temp) / "exp"
            ExperimentStore(experiment).prepare(execute=True)
            record = create_record("candidate", producer_role="codec-scientist", run_id="r1", created_at="2026-08-13T00:00:00Z", experiment_spec_sha256=HEX_A, links={"experiment_spec": HEX_A}, payload=valid, supersedes_sha256=HEX_C)
            with self.assertRaises(ImmutableConflict):
                RecordStore(experiment).put(record)

    def test_impossible_timestamp_is_rejected(self) -> None:
        with self.assertRaises(InvalidInput):
            create_record("candidate", producer_role="codec-scientist", run_id="r1", created_at="2026-99-99T00:00:00Z", experiment_spec_sha256=HEX_A, links={"experiment_spec": HEX_A}, payload={"candidate_id": "candidate-001", "configuration": {}, "artifact_sha256": HEX_B, "status": "COMPLETE", "warnings": []})


def evidence_chain(*, constraint_value: float = 96.0, baseline_value: float = 99.0, confounded: bool = False) -> tuple[dict, list[dict], AuthoritySnapshot, dict]:
    identity_body = {"vcs": "git", "commit_oid": "0" * 40, "dirty": False, "dirty_diff_sha256": HEX_A, "untracked_identity_sha256": HEX_B, "untracked_count": 0}
    repository_identity = {**identity_body, "identity_sha256": domain_hash("repository-workspace-identity", identity_body)}
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        write_authority_fixture(root)
        initial_snapshot = capture_authority_snapshot(root, require_all=False)
        snapshot = AuthoritySnapshot(
            root=root,
            files=initial_snapshot.files,
            missing=(),
            repository_identity=repository_identity,
        )
        definition = spec_definition(root)
        _, corpus_items = corpus_manifest_snapshot(Path(definition["corpus"]["manifest_path"]))
        spec = build_experiment_spec(
            root,
            run_id="run-001",
            created_at="2026-08-13T00:00:00Z",
            definition=definition,
            require_all_authorities=True,
            authority_snapshot=snapshot,
        )
    spec_hash = spec["content_sha256"]
    corpus = create_record("corpus", producer_role="deterministic-core", run_id="run-001", created_at="2026-08-13T00:00:00Z", experiment_spec_sha256=spec_hash, links={"experiment_spec": spec_hash}, payload=spec["corpus"])
    device = create_record("device", producer_role="android-device-lab", run_id="run-001", created_at="2026-08-13T00:00:04Z", experiment_spec_sha256=spec_hash, links={"experiment_spec": spec_hash}, payload={"device_alias": "device-0123456789ab", "android_user": 0, "package_id": "example.package", "build_identity": {"version_code": 1, "version_name": "1.0", "apk_sha256": HEX_A, "source_sha256": spec["repository_authority"]["authority_sha256"]}, "signer_sha256": HEX_C, "firmware_fingerprint_sha256": HEX_A, "requested_configuration": {"codec": "video/hevc"}, "actual_configuration": {"codec": "video/hevc"}, "thermals": {"status": "NOMINAL", "max_status": 0}, "status": "COMPLETE", "confounders": []})
    baseline_artifact = create_record("artifact", producer_role="deterministic-core", run_id="run-001", created_at="2026-08-13T00:00:01Z", experiment_spec_sha256=spec_hash, links={"experiment_spec": spec_hash}, payload={"artifact_id": "baseline-media", "logical_path": "artifacts/baseline.mp4", "sha256": corpus_items[0]["source_sha256"], "size_bytes": corpus_items[0]["source_size_bytes"], "status": "COMPLETE"})
    candidate_artifact = create_record("artifact", producer_role="deterministic-core", run_id="run-001", created_at="2026-08-13T00:00:01Z", experiment_spec_sha256=spec_hash, links={"experiment_spec": spec_hash}, payload={"artifact_id": "candidate-media", "logical_path": "artifacts/candidate.mp4", "sha256": HEX_B, "size_bytes": 20, "status": "COMPLETE"})
    baseline = create_record("baseline", producer_role="deterministic-core", run_id="run-001", created_at="2026-08-13T00:00:02Z", experiment_spec_sha256=spec_hash, links={"experiment_spec": spec_hash, "artifact": baseline_artifact["content_sha256"], "device": device["content_sha256"]}, payload={"baseline_id": "baseline-001", "artifact_sha256": baseline_artifact["content_sha256"], "status": "COMPLETE", "confounders": []})
    candidate = create_record("candidate", producer_role="codec-scientist", run_id="run-001", created_at="2026-08-13T00:00:02Z", experiment_spec_sha256=spec_hash, links={"experiment_spec": spec_hash, "artifact": candidate_artifact["content_sha256"], "device": device["content_sha256"]}, payload={"candidate_id": "candidate-001", "configuration": {"codec": "video/hevc"}, "artifact_sha256": candidate_artifact["content_sha256"], "status": "COMPLETE", "warnings": []})
    metric_model_sha256 = spec["metrics"][0]["model_sha256"]
    scorer_sha256 = next(item["sha256"] for item in spec["repository_authority"]["files"] if item["logical_path"] == "scripts/diagnostics/measure_quality.py")

    def evaluation_provenance(output_artifact: dict) -> dict:
        return {
            "metric_model_sha256": metric_model_sha256,
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
            "output_content_sha256": output_artifact["payload"]["sha256"],
            "source_frame_count": 30,
            "output_frame_count": 30,
            "source_duration_ms": 1000,
            "output_duration_ms": 1000,
        }

    baseline_eval = create_record("evaluation", producer_role="perceptual-evaluator", run_id="run-001", created_at="2026-08-13T00:00:03Z", experiment_spec_sha256=spec_hash, links={"experiment_spec": spec_hash, "subject": baseline["content_sha256"], "artifact": baseline_artifact["content_sha256"], "device": device["content_sha256"]}, payload={"evaluation_kind": "subject_quality", "evaluation_id": "evaluation-baseline", "subject_sha256": baseline["content_sha256"], "metric_results": {"vmaf_mean": baseline_value, "vmaf_p5": baseline_value, "vmaf_min": baseline_value, "compared_frames": 30}, "alignment": {"status": "ALIGNED"}, "hdr": {"status": "MATCH"}, "provenance": evaluation_provenance(baseline_artifact), "status": "COMPLETE", "confounders": []})
    candidate_eval = create_record("evaluation", producer_role="perceptual-evaluator", run_id="run-001", created_at="2026-08-13T00:00:03Z", experiment_spec_sha256=spec_hash, links={"experiment_spec": spec_hash, "subject": candidate["content_sha256"], "artifact": candidate_artifact["content_sha256"], "device": device["content_sha256"]}, payload={"evaluation_kind": "subject_quality", "evaluation_id": "evaluation-candidate", "subject_sha256": candidate["content_sha256"], "metric_results": {"vmaf_mean": constraint_value, "vmaf_p5": 92.0, "vmaf_min": 85.0, "compared_frames": 30}, "alignment": {"status": "ALIGNED"}, "hdr": {"status": "MATCH"}, "provenance": evaluation_provenance(candidate_artifact), "status": "BLOCKED" if confounded else "COMPLETE", "confounders": ["thermal"] if confounded else []})
    corpus_item = corpus_items[0]
    calibration_provenance = {
        key: value
        for key, value in evaluation_provenance(candidate_artifact).items()
        if key != "reference_artifact_sha256"
    }
    calibration_provenance.update({
        "corpus_manifest_sha256": spec["corpus"]["manifest_sha256"],
        "corpus_pair_identities_sha256": spec["corpus"]["pair_identities_sha256"],
        "pair_id": corpus_item["pair_id"],
        "source_path": corpus_item["source_path"],
        "source_content_sha256": corpus_item["source_sha256"],
        "source_size_bytes": corpus_item["source_size_bytes"],
        "output_path": corpus_item["output_path"],
        "output_content_sha256": corpus_item["output_sha256"],
        "output_size_bytes": corpus_item["output_size_bytes"],
    })
    calibration_evaluation = create_record(
        "evaluation",
        producer_role="perceptual-evaluator",
        run_id="run-001",
        created_at="2026-08-13T00:00:03Z",
        experiment_spec_sha256=spec_hash,
        links={"experiment_spec": spec_hash, "corpus": corpus["content_sha256"]},
        payload={
            "evaluation_kind": "corpus_calibration",
            "evaluation_id": "evaluation-corpus-001",
            "subject_sha256": corpus["content_sha256"],
            "metric_results": {"vmaf_mean": 99.0, "vmaf_p5": 99.0, "vmaf_min": 99.0, "compared_frames": 30},
            "alignment": {"status": "NOT_APPLICABLE"},
            "hdr": {"status": "NOT_APPLICABLE"},
            "provenance": calibration_provenance,
            "status": "COMPLETE",
            "confounders": [],
        },
    )
    boundary_payload, _ = expected_boundary_artifact(spec)
    boundary_artifact = create_record(
        "artifact",
        producer_role="deterministic-core",
        run_id="run-001",
        created_at="2026-08-13T00:00:03Z",
        experiment_spec_sha256=spec_hash,
        links={"experiment_spec": spec_hash},
        payload=boundary_payload,
    )
    pending_calibration = create_record(
        "calibration",
        producer_role="deterministic-core",
        run_id="run-001",
        created_at="2026-08-13T00:00:00Z",
        experiment_spec_sha256=spec_hash,
        links={"experiment_spec": spec_hash, "corpus": corpus["content_sha256"]},
        payload={
            "calibration_id": "calibration-001",
            "resolved_constraints": spec["threshold_provenance"]["resolved_constraints"],
            "authority_sha256": spec["repository_authority"]["authority_sha256"],
            "status": "BLOCKED",
            "confounders": ["corpus_evaluation_pending"],
        },
    )
    calibration = create_record(
        "calibration",
        producer_role="deterministic-core",
        run_id="run-001",
        created_at="2026-08-13T00:00:05Z",
        experiment_spec_sha256=spec_hash,
        links={
            "experiment_spec": spec_hash,
            "corpus": corpus["content_sha256"],
            "evaluations": [calibration_evaluation["content_sha256"]],
            "boundary_artifact": boundary_artifact["content_sha256"],
        },
        payload={
            "calibration_id": "calibration-001",
            "resolved_constraints": spec["threshold_provenance"]["resolved_constraints"],
            "authority_sha256": spec["repository_authority"]["authority_sha256"],
            "status": "COMPLETE",
            "confounders": [],
        },
        supersedes_sha256=pending_calibration["content_sha256"],
    )
    baseline_metrics = baseline_eval["payload"]["metric_results"]
    candidate_metrics = candidate_eval["payload"]["metric_results"]
    deltas = {metric: float(candidate_metrics[metric]) - float(baseline_metrics[metric]) for metric in sorted(baseline_metrics)}
    comparison_checks = [{"metric": item["metric"], "operator": item["operator"], "expected": item["value"], "actual": float(candidate_metrics[item["metric"]])} for item in spec["threshold_provenance"]["resolved_constraints"]]
    comparison = create_record("comparison", producer_role="deterministic-core", run_id="run-001", created_at="2026-08-13T00:00:05Z", experiment_spec_sha256=spec_hash, links={"experiment_spec": spec_hash, "baseline": baseline["content_sha256"], "candidate": candidate["content_sha256"], "baseline_evaluations": [baseline_eval["content_sha256"]], "candidate_evaluations": [candidate_eval["content_sha256"]], "device": device["content_sha256"]}, payload={"baseline_sha256": baseline["content_sha256"], "candidate_sha256": candidate["content_sha256"], "baseline_evaluation_sha256s": [baseline_eval["content_sha256"]], "candidate_evaluation_sha256s": [candidate_eval["content_sha256"]], "device_sha256": device["content_sha256"], "deltas": deltas, "constraint_checks": comparison_checks, "status": "COMPLETE", "confounders": []})
    return spec, [corpus, baseline_artifact, candidate_artifact, device, baseline, candidate, baseline_eval, candidate_eval, boundary_artifact, calibration_evaluation, calibration, comparison], snapshot, pending_calibration


class JudgeTests(unittest.TestCase):
    def test_pass_or_fail_verdict_requires_verified_store_publication(self) -> None:
        spec, evidence, snapshot, pending_calibration = evidence_chain(constraint_value=96.0)
        verdict = judge_evidence(spec, evidence, created_at="2026-08-13T00:00:06Z", authority_snapshot=snapshot)
        with tempfile.TemporaryDirectory() as temp:
            experiment = Path(temp) / "experiment"
            ExperimentStore(experiment).prepare(execute=True)
            store = RecordStore(experiment)
            ExperimentSpecStore(experiment).put(spec, authority_snapshot=snapshot)
            _, boundary_bytes = expected_boundary_artifact(spec)
            boundary_path = experiment / evidence[8]["payload"]["logical_path"]
            boundary_path.parent.mkdir(parents=True, exist_ok=True)
            boundary_path.write_bytes(boundary_bytes)
            for record in evidence:
                if record["record_type"] == "calibration":
                    store.put(pending_calibration)
                store.put(record)
            with self.assertRaises(ImmutableConflict):
                store.put(verdict)
            path = store.put_verified_verdict(
                verdict,
                experiment_spec=spec,
                evidence=evidence,
                authority_snapshot=snapshot,
            )
            self.assertEqual(path.name, verdict["content_sha256"] + ".json")

    def test_judge_emits_pass_or_fail_from_frozen_constraints(self) -> None:
        spec, evidence, snapshot, _ = evidence_chain(constraint_value=96.0)
        passed = judge_evidence(spec, evidence, created_at="2026-08-13T00:00:06Z", authority_snapshot=snapshot)
        self.assertEqual(passed["payload"]["verdict"], "PASS")
        self.assertEqual(passed["producer_role"], "regression-judge")

        spec, evidence, snapshot, _ = evidence_chain(constraint_value=90.0)
        failed = judge_evidence(spec, evidence, created_at="2026-08-13T00:00:06Z", authority_snapshot=snapshot)
        self.assertEqual(failed["payload"]["verdict"], "FAIL")

    def test_confounding_is_blocked_not_fail(self) -> None:
        spec, evidence, snapshot, _ = evidence_chain(confounded=True)
        verdict = judge_evidence(spec, evidence, created_at="2026-08-13T00:00:06Z", authority_snapshot=snapshot)
        self.assertEqual(verdict["payload"]["verdict"], "BLOCKED")

    def test_invalid_baseline_blocks_but_candidate_violation_fails(self) -> None:
        spec, evidence, snapshot, _ = evidence_chain(baseline_value=80.0, constraint_value=99.0)
        baseline_invalid = judge_evidence(spec, evidence, created_at="2026-08-13T00:00:06Z", authority_snapshot=snapshot)
        self.assertEqual(baseline_invalid["payload"]["verdict"], "BLOCKED")

        spec, evidence, snapshot, _ = evidence_chain(baseline_value=99.0, constraint_value=80.0)
        candidate_invalid = judge_evidence(spec, evidence, created_at="2026-08-13T00:00:06Z", authority_snapshot=snapshot)
        self.assertEqual(candidate_invalid["payload"]["verdict"], "FAIL")

    def test_judge_recomputes_and_rejects_forged_comparison_numbers(self) -> None:
        spec, evidence, snapshot, _ = evidence_chain()
        comparison = deepcopy(evidence[-1])
        comparison["payload"]["deltas"]["vmaf_mean"] = 1000.0
        comparison.pop("content_sha256")
        from pl_lab.canonical import seal_document
        evidence[-1] = seal_document("record:comparison", comparison)
        with self.assertRaises(EvidenceBlocked):
            judge_evidence(spec, evidence, created_at="2026-08-13T00:00:06Z", authority_snapshot=snapshot)

    def test_judge_rejects_missing_duplicate_cross_spec_cycle_and_self_approval(self) -> None:
        spec, evidence, snapshot, _ = evidence_chain()
        malformed_chains = []
        malformed_chains.append(evidence[:-1])
        malformed_chains.append(evidence + [deepcopy(evidence[0])])

        from pl_lab.canonical import seal_document
        cross_spec = deepcopy(evidence)
        changed = {key: value for key, value in cross_spec[7].items() if key != "content_sha256"}
        changed["experiment_spec_sha256"] = HEX_A
        changed["links"]["experiment_spec"] = HEX_A
        cross_spec[7] = seal_document("record:evaluation", changed)
        malformed_chains.append(cross_spec)

        self_approved = deepcopy(evidence)
        changed = {key: value for key, value in self_approved[5].items() if key != "content_sha256"}
        changed["producer_role"] = "regression-judge"
        self_approved[5] = seal_document("record:candidate", changed)
        malformed_chains.append(self_approved)

        for chain in malformed_chains:
            with self.subTest(size=len(chain)):
                with self.assertRaises(EvidenceBlocked) as caught:
                    judge_evidence(spec, chain, created_at="2026-08-13T00:00:06Z", authority_snapshot=snapshot)
                self.assertEqual(caught.exception.exit_code, 13)

        with self.assertRaises(EvidenceBlocked):
            assert_acyclic_graph({"a": ["b"], "b": ["a"]})


if __name__ == "__main__":
    unittest.main()
