from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

from pl_lab.canonical import domain_hash
from pl_lab.errors import EvidenceBlocked, MissingPrerequisite
from pl_lab.envelope import validate_envelope
from pl_lab.operations.quality import _execute_score_corpus, _stable_file_bytes
from pl_lab.operations.runtime import execute_operation
from pl_lab.process import ProcessResult
from pl_lab.records import RecordStore
from pl_lab.workflow import execute_named_workflow
from tests.test_execution_paths import CREATED_AT, ROOT, RUN_ID, _definition, _record_chain, _request


def _write_complete_scorer_report(argv: list[str], scorer_sha256: str) -> None:
    report_path = Path(argv[argv.index("--json") + 1])
    report_path.write_text(
        json.dumps(
            {
                "schema_version": 3,
                "pair_id": argv[argv.index("--pair-id") + 1],
                "harness_provenance": {"schema_version": 3, "script_basename": "measure_quality.py", "script_sha256": scorer_sha256},
                "status": "COMPLETE",
                "report_complete": True,
                "measurement_valid": True,
                "privacy": {"input_paths_recorded": False, "input_basenames_recorded": False},
                "tool_provenance": {"ffmpeg": "synthetic ffmpeg version", "ffprobe": "synthetic ffprobe version", "vmaf_model": "version=vmaf_v0.6.1"},
                "structural_validation": {"passed": True, "cadence": {"source": {"decoded_frame_count": 30, "frame_timeline_duration_seconds": 1.0}, "output": {"decoded_frame_count": 30, "frame_timeline_duration_seconds": 1.0}}},
                "vmaf_mean": 98.0,
                "vmaf_5pct_low": 95.0,
                "vmaf_min": 90.0,
                "compared_frame_count": 30,
            },
            allow_nan=False,
        ),
        encoding="utf-8",
        newline="\n",
    )


class ScorePairExecutionTests(unittest.TestCase):
    def test_direct_pair_dry_run_rejects_missing_live_media_without_writing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            experiment, spec, _ = _record_chain(temp)
            payload = {
                "source_path": str(temp / "missing-source.mp4"),
                "output_path": str(temp / "missing-output.mp4"),
                "pair_id": "missing-pair",
                "ffmpeg_executable": str(temp / "missing-ffmpeg.exe"),
                "ffprobe_executable": str(temp / "missing-ffprobe.exe"),
                "model_id": "vmaf_v0.6.1",
                "max_duration_seconds": 30,
                "max_storage_bytes": 10_000,
            }
            before = {
                path.relative_to(experiment).as_posix(): path.read_bytes()
                for path in experiment.rglob("*")
                if path.is_file()
            }
            envelope = execute_operation(
                "pl_score_pair",
                _request("pl_score_pair", spec, payload),
                repository=ROOT,
                experiment=experiment,
                execute=False,
            )
            validate_envelope(envelope)
            self.assertEqual("BLOCKED", envelope["status"])
            self.assertEqual(13, envelope["errors"][0]["exit_code"])
            after = {
                path.relative_to(experiment).as_posix(): path.read_bytes()
                for path in experiment.rglob("*")
                if path.is_file()
            }
            self.assertEqual(before, after)

    def test_calibrate_dry_run_preflights_tools_without_writing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            experiment = temp / "experiment"
            request = {
                "schema_version": "1",
                "workflow_id": "pl-calibrate",
                "run_id": RUN_ID,
                "created_at": CREATED_AT,
                "definition": _definition(temp),
                "payload": {
                    "step_payloads": {
                        "pl_run_boundary_suite": {"mode": "fast"},
                        "pl_score_corpus": {
                            "corpus_manifest_path": str(temp / "corpus.json"),
                            "ffmpeg_executable": str(temp / "missing-ffmpeg.exe"),
                            "ffprobe_executable": sys.executable,
                            "model_id": "vmaf_v0.6.1",
                            "max_candidates": 2,
                            "max_workers": 1,
                            "max_duration_seconds": 120,
                            "max_storage_bytes": 1_000_000,
                        },
                    },
                },
            }
            with self.assertRaises(MissingPrerequisite):
                execute_named_workflow(
                    "calibrate",
                    repository=ROOT,
                    experiment=experiment,
                    request=request,
                    execute=False,
                )
            self.assertFalse(experiment.exists())

    def test_corpus_dry_run_checks_manifest_tools_and_remains_nonmutating(self) -> None:
        for failure in ("missing_manifest", "missing_tool"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temp_dir:
                temp = Path(temp_dir)
                experiment, spec, _ = _record_chain(temp)
                payload = {
                    "corpus_manifest_path": str(temp / "corpus.json"),
                    "ffmpeg_executable": sys.executable,
                    "ffprobe_executable": sys.executable,
                    "model_id": "vmaf_v0.6.1",
                    "max_candidates": 1,
                    "max_workers": 1,
                    "max_duration_seconds": 120,
                    "max_storage_bytes": 1_000_000,
                }
                if failure == "missing_manifest":
                    Path(payload["corpus_manifest_path"]).unlink()
                else:
                    payload["ffmpeg_executable"] = str(temp / "missing-ffmpeg.exe")
                before = {
                    path.relative_to(experiment).as_posix(): path.read_bytes()
                    for path in experiment.rglob("*")
                    if path.is_file()
                }
                envelope = execute_operation(
                    "pl_score_corpus",
                    _request("pl_score_corpus", spec, payload),
                    repository=ROOT,
                    experiment=experiment,
                    execute=False,
                )
                validate_envelope(envelope)
                expected_status = "BLOCKED" if failure == "missing_manifest" else "ERROR"
                expected_exit = 13 if failure == "missing_manifest" else 11
                self.assertEqual(expected_status, envelope["status"])
                self.assertEqual(expected_exit, envelope["errors"][0]["exit_code"])
                after = {
                    path.relative_to(experiment).as_posix(): path.read_bytes()
                    for path in experiment.rglob("*")
                    if path.is_file()
                }
                self.assertEqual(before, after)

    def test_corpus_rejects_pair_media_swap_restored_before_final_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            experiment, spec, records = _record_chain(temp)
            store = RecordStore(experiment)
            before_evaluations = [record["content_sha256"] for record in store.all_of_type("evaluation")]
            payload = {
                "corpus_manifest_path": str(temp / "corpus.json"),
                "ffmpeg_executable": sys.executable,
                "ffprobe_executable": sys.executable,
                "model_id": "vmaf_v0.6.1",
                "max_candidates": 1,
                "max_workers": 1,
                "max_duration_seconds": 120,
                "max_storage_bytes": 1_000_000,
            }

            def swapped_pair(pair_payload: dict, **kwargs: object) -> dict:
                source = Path(pair_payload["source_path"])
                output = Path(pair_payload["output_path"])
                source_bytes = source.read_bytes()
                output_bytes = output.read_bytes()
                swapped_source = b"swapped-source-media\n"
                swapped_output = b"swapped-output-media\n"
                source.write_bytes(swapped_source)
                output.write_bytes(swapped_output)
                source.write_bytes(source_bytes)
                output.write_bytes(output_bytes)
                expected = kwargs["expected_media"]
                self.assertEqual(hashlib.sha256(source_bytes).hexdigest(), expected["source_sha256"])
                self.assertEqual(hashlib.sha256(output_bytes).hexdigest(), expected["output_sha256"])
                return {
                    "records": [{
                        "record_type": "evaluation",
                        "content_sha256": "f" * 64,
                        "links": {"experiment_spec": spec["content_sha256"], "corpus": records["corpus"]["content_sha256"]},
                        "payload": {
                            "evaluation_kind": "corpus_calibration",
                            "subject_sha256": records["corpus"]["content_sha256"],
                            "provenance": {
                                "pair_id": pair_payload["pair_id"],
                                "corpus_manifest_sha256": spec["corpus"]["manifest_sha256"],
                                "corpus_pair_identities_sha256": spec["corpus"]["pair_identities_sha256"],
                                "source_content_sha256": hashlib.sha256(swapped_source).hexdigest(),
                                "output_content_sha256": hashlib.sha256(swapped_output).hexdigest(),
                            },
                        },
                    }],
                    "result": {"scorer_report_size_bytes": 100},
                }

            with patch("pl_lab.operations.quality._execute_score_pair", side_effect=swapped_pair):
                envelope = execute_operation(
                    "pl_score_corpus",
                    _request("pl_score_corpus", spec, payload),
                    repository=ROOT,
                    experiment=experiment,
                    execute=True,
                )
            validate_envelope(envelope)
            self.assertEqual("BLOCKED", envelope["status"])
            self.assertEqual(before_evaluations, [record["content_sha256"] for record in store.all_of_type("evaluation")])

    def test_report_capture_rejects_hardlinked_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            report = Path(temp_dir) / "report.json"
            alias = Path(temp_dir) / "alias.json"
            report.write_bytes(b'{"status":"COMPLETE"}\n')
            try:
                alias.hardlink_to(report)
            except OSError as exc:
                self.skipTest(f"hard links are unavailable: {exc}")
            with self.assertRaises(EvidenceBlocked):
                _stable_file_bytes(report, maximum_bytes=1024, label="scorer report")

    def test_registered_repository_scorer_publishes_lineage_bound_evaluation(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            experiment, spec, records = _record_chain(temp)
            source_path = experiment / "artifacts" / "baseline.mp4"
            output_path = experiment / "artifacts" / "candidate.mp4"
            scorer_sha256 = hashlib.sha256((ROOT / "scripts" / "diagnostics" / "measure_quality.py").read_bytes()).hexdigest()
            python_sha256 = hashlib.sha256(Path(sys.executable).read_bytes()).hexdigest()
            observed_argv: list[str] = []

            def fake_scorer(argv: list[str], **_: object) -> ProcessResult:
                observed_argv.extend(argv)
                report_path = Path(argv[argv.index("--json") + 1])
                report = {
                    "schema_version": 3,
                    "pair_id": "pair-001",
                    "harness_provenance": {
                        "schema_version": 3,
                        "script_basename": "measure_quality.py",
                        "script_sha256": scorer_sha256,
                    },
                    "status": "COMPLETE",
                    "report_complete": True,
                    "measurement_valid": True,
                    "privacy": {"input_paths_recorded": False, "input_basenames_recorded": False},
                    "tool_provenance": {
                        "ffmpeg": "synthetic ffmpeg version",
                        "ffprobe": "synthetic ffprobe version",
                        "vmaf_model": "version=vmaf_v0.6.1",
                    },
                    "structural_validation": {
                        "passed": True,
                        "cadence": {
                            "source": {"decoded_frame_count": 30, "frame_timeline_duration_seconds": 1.0},
                            "output": {"decoded_frame_count": 30, "frame_timeline_duration_seconds": 1.0},
                        },
                    },
                    "vmaf_mean": 98.25,
                    "vmaf_5pct_low": 95.5,
                    "vmaf_min": 91.0,
                    "compared_frame_count": 30,
                }
                report_path.write_text(json.dumps(report, allow_nan=False), encoding="utf-8", newline="\n")
                return ProcessResult(0, "complete", "", 1, python_sha256, False)

            payload = {
                "source_path": str(source_path),
                "output_path": str(output_path),
                "pair_id": "pair-001",
                "ffmpeg_executable": sys.executable,
                "ffprobe_executable": sys.executable,
                "model_id": "vmaf_v0.6.1",
                "max_duration_seconds": 120,
                "max_storage_bytes": 1_000_000,
            }
            with patch("pl_lab.operations.quality.run_bounded", side_effect=fake_scorer):
                envelope = execute_operation(
                    "pl_score_pair",
                    _request("pl_score_pair", spec, payload),
                    repository=ROOT,
                    experiment=experiment,
                    execute=True,
                )
            validate_envelope(envelope)
            self.assertEqual("COMPLETED", envelope["status"])
            evaluation = RecordStore(experiment).find(envelope["output_identities"][0]["sha256"])
            self.assertEqual(records["candidate"]["content_sha256"], evaluation["payload"]["subject_sha256"])
            self.assertEqual(records["candidate_artifact"]["content_sha256"], evaluation["links"]["artifact"])
            self.assertEqual(records["device"]["content_sha256"], evaluation["links"]["device"])
            self.assertEqual(98.25, evaluation["payload"]["metric_results"]["vmaf_mean"])
            self.assertEqual("NOT_APPLICABLE", evaluation["payload"]["alignment"]["status"])
            self.assertEqual("NOT_APPLICABLE", evaluation["payload"]["hdr"]["status"])
            self.assertEqual(scorer_sha256, evaluation["payload"]["provenance"]["scorer_authority_sha256"])
            self.assertEqual("1", observed_argv[observed_argv.index("--threads") + 1])
            self.assertEqual(str(source_path), observed_argv[observed_argv.index("--source") + 1])
            self.assertEqual(str(output_path), observed_argv[observed_argv.index("--output") + 1])

    def test_corpus_scoring_is_serial_and_revalidates_the_frozen_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            experiment, spec, records = _record_chain(temp)
            scorer_sha256 = hashlib.sha256((ROOT / "scripts" / "diagnostics" / "measure_quality.py").read_bytes()).hexdigest()
            python_path = Path(sys.executable).resolve(strict=True)
            python_sha256 = hashlib.sha256(python_path.read_bytes()).hexdigest()
            calls: list[list[str]] = []

            def fake_scorer(argv: list[str], **_: object) -> ProcessResult:
                calls.append(list(argv))
                report_path = Path(argv[argv.index("--json") + 1])
                report_path.write_text(
                    json.dumps(
                        {
                            "schema_version": 3,
                            "pair_id": argv[argv.index("--pair-id") + 1],
                            "harness_provenance": {"schema_version": 3, "script_basename": "measure_quality.py", "script_sha256": scorer_sha256},
                            "status": "COMPLETE",
                            "report_complete": True,
                            "measurement_valid": True,
                            "privacy": {"input_paths_recorded": False, "input_basenames_recorded": False},
                            "tool_provenance": {"ffmpeg": "synthetic ffmpeg version", "ffprobe": "synthetic ffprobe version", "vmaf_model": "version=vmaf_v0.6.1"},
                            "structural_validation": {"passed": True, "cadence": {"source": {"decoded_frame_count": 30, "frame_timeline_duration_seconds": 1.0}, "output": {"decoded_frame_count": 30, "frame_timeline_duration_seconds": 1.0}}},
                            "vmaf_mean": 98.0,
                            "vmaf_5pct_low": 95.0,
                            "vmaf_min": 90.0,
                            "compared_frame_count": 30,
                        },
                        allow_nan=False,
                    ),
                    encoding="utf-8",
                    newline="\n",
                )
                return ProcessResult(0, "complete", "", 1, python_sha256, False)

            payload = {
                "corpus_manifest_path": str(temp / "corpus.json"),
                "ffmpeg_executable": sys.executable,
                "ffprobe_executable": sys.executable,
                "model_id": "vmaf_v0.6.1",
                "max_candidates": 1,
                "max_workers": 1,
                "max_duration_seconds": 120,
                "max_storage_bytes": 1_000_000,
            }
            with (
                patch("pl_lab.operations.quality.run_bounded", side_effect=fake_scorer),
                patch("pl_lab.operations.quality._resolve_tool", return_value=(python_path, python_sha256)),
            ):
                envelope = execute_operation(
                    "pl_score_corpus",
                    _request("pl_score_corpus", spec, payload),
                    repository=ROOT,
                    experiment=experiment,
                    execute=True,
                )
            validate_envelope(envelope)
            self.assertEqual("COMPLETED", envelope["status"])
            self.assertEqual(1, len(calls))
            self.assertEqual("1", calls[0][calls[0].index("--threads") + 1])
            self.assertEqual(["pair-001"], envelope["result"]["pair_ids"])
            evaluation = RecordStore(experiment).find(envelope["output_identities"][0]["sha256"])
            self.assertEqual("corpus_calibration", evaluation["payload"]["evaluation_kind"])
            self.assertEqual(records["corpus"]["content_sha256"], evaluation["payload"]["subject_sha256"])
            self.assertEqual({"experiment_spec": spec["content_sha256"], "corpus": records["corpus"]["content_sha256"]}, evaluation["links"])
            self.assertEqual(spec["corpus"]["manifest_sha256"], evaluation["payload"]["provenance"]["corpus_manifest_sha256"])
            self.assertEqual(spec["corpus"]["pair_identities_sha256"], evaluation["payload"]["provenance"]["corpus_pair_identities_sha256"])
            self.assertEqual(records["corpus"]["content_sha256"], envelope["result"]["corpus_record_sha256"])
            self.assertTrue(any(item["tool_id"] == "measure_quality" for item in envelope["versions"]["tools"]))

    def test_calibrate_publishes_pending_then_complete_calibration_and_resumes(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            experiment = temp / "experiment"
            scorer_sha256 = hashlib.sha256((ROOT / "scripts" / "diagnostics" / "measure_quality.py").read_bytes()).hexdigest()
            python_sha256 = hashlib.sha256(Path(sys.executable).read_bytes()).hexdigest()

            def fake_scorer(argv: list[str], **_: object) -> ProcessResult:
                _write_complete_scorer_report(argv, scorer_sha256)
                return ProcessResult(0, "complete", "", 1, python_sha256, False)

            request = {
                "schema_version": "1",
                "workflow_id": "pl-calibrate",
                "run_id": RUN_ID,
                "created_at": CREATED_AT,
                "definition": _definition(temp),
                "payload": {
                    "step_payloads": {
                        "pl_run_boundary_suite": {"mode": "fast"},
                        "pl_score_corpus": {
                            "corpus_manifest_path": str(temp / "corpus.json"),
                            "ffmpeg_executable": sys.executable,
                            "ffprobe_executable": sys.executable,
                            "model_id": "vmaf_v0.6.1",
                            "max_candidates": 2,
                            "max_workers": 1,
                            "max_duration_seconds": 120,
                            "max_storage_bytes": 1_000_000,
                        },
                    },
                },
            }
            with patch("pl_lab.operations.quality.run_bounded", side_effect=fake_scorer):
                result = execute_named_workflow(
                    "calibrate",
                    repository=ROOT,
                    experiment=experiment,
                    request=request,
                    execute=True,
                )
            self.assertEqual("COMPLETED", result["status"])
            self.assertEqual(0, result["exit_code"])
            store = RecordStore(experiment)
            pending = store.find(result["spec_freeze"]["calibration_sha256"])
            complete = store.find(result["calibration_identity"]["sha256"])
            self.assertEqual("BLOCKED", pending["payload"]["status"])
            self.assertEqual(["corpus_evaluation_pending"], pending["payload"]["confounders"])
            self.assertEqual("COMPLETE", complete["payload"]["status"])
            self.assertEqual(pending["content_sha256"], complete["supersedes_sha256"])
            self.assertEqual(1, len(complete["links"]["evaluations"]))
            evaluation = store.find(complete["links"]["evaluations"][0])
            self.assertEqual("corpus_calibration", evaluation["payload"]["evaluation_kind"])
            self.assertEqual(complete["links"]["corpus"], evaluation["links"]["corpus"])

            with patch("pl_lab.operations.quality.run_bounded") as resumed_scorer:
                resumed = execute_named_workflow(
                    "calibrate",
                    repository=ROOT,
                    experiment=experiment,
                    request=request,
                    execute=True,
                )
            resumed_scorer.assert_not_called()
            self.assertEqual(result["calibration_identity"], resumed["calibration_identity"])
            self.assertTrue(all(step["skipped"] for step in resumed["step_results"]))

    def test_corpus_resolves_one_toolchain_and_passes_remaining_budgets(self) -> None:
        items = [
            {"pair_id": "pair-001", "source_path": "source.mp4", "source_sha256": "1" * 64, "source_size_bytes": 100, "output_path": "one.mp4", "output_sha256": "2" * 64, "output_size_bytes": 80},
            {"pair_id": "pair-002", "source_path": "source.mp4", "source_sha256": "1" * 64, "source_size_bytes": 100, "output_path": "two.mp4", "output_sha256": "3" * 64, "output_size_bytes": 70},
        ]
        identity = {
            "manifest_alias": "corpus.json",
            "manifest_sha256": "a" * 64,
            "source_identities_sha256": "b" * 64,
            "pair_identities_sha256": "c" * 64,
            "item_count": 2,
        }
        store = MagicMock()
        tool_path = Path(sys.executable).resolve(strict=True)
        tool_sha256 = hashlib.sha256(tool_path.read_bytes()).hexdigest()
        observed_tool_sets: list[object] = []
        observed_storage_budgets: list[int] = []
        observed_deadlines: list[float] = []

        def fake_pair(payload: dict, **kwargs: object) -> dict:
            observed_tool_sets.append(kwargs["resolved_tools"])
            observed_storage_budgets.append(payload["max_storage_bytes"])
            observed_deadlines.append(kwargs["deadline"])
            index = len(observed_tool_sets)
            item = items[index - 1]
            return {
                "records": [{
                    "content_sha256": f"{index:064x}",
                    "record_type": "evaluation",
                    "links": {"experiment_spec": experiment_spec["content_sha256"], "corpus": "e" * 64},
                    "payload": {
                        "evaluation_kind": "corpus_calibration",
                        "subject_sha256": "e" * 64,
                        "provenance": {
                            "pair_id": item["pair_id"],
                            "corpus_manifest_sha256": identity["manifest_sha256"],
                            "corpus_pair_identities_sha256": identity["pair_identities_sha256"],
                            "source_content_sha256": item["source_sha256"],
                            "source_path": item["source_path"],
                            "source_size_bytes": item["source_size_bytes"],
                            "output_content_sha256": item["output_sha256"],
                            "output_path": item["output_path"],
                            "output_size_bytes": item["output_size_bytes"],
                        },
                    },
                }],
                "result": {"scorer_report_size_bytes": 100},
            }

        payload = {
            "corpus_manifest_path": "corpus.json",
            "ffmpeg_executable": "ffmpeg",
            "ffprobe_executable": "ffprobe",
            "model_id": "vmaf_v0.6.1",
            "max_candidates": 2,
            "max_workers": 1,
            "max_duration_seconds": 30,
            "max_storage_bytes": 10_000,
        }
        scorer_sha256 = hashlib.sha256((ROOT / "scripts" / "diagnostics" / "measure_quality.py").read_bytes()).hexdigest()
        model_provenance = {"host_scorer_sha256": scorer_sha256, "model_selector": "version=vmaf_v0.6.1", "harness_schema_version": 3}
        experiment_spec = {
            "run_id": "run-score-corpus",
            "content_sha256": "d" * 64,
            "device_requirements": {"required": False},
            "budgets": {key: payload[key] for key in ("max_candidates", "max_workers", "max_duration_seconds", "max_storage_bytes")},
            "metrics": [{"metric_id": "vmaf", "version": "3.0.0", "model_id": "vmaf_v0.6.1", "provenance": model_provenance, "model_sha256": domain_hash("host-libvmaf-model", {"model_id": "vmaf_v0.6.1", **model_provenance})}],
            "corpus": identity,
        }
        store.all_of_type.return_value = [{
            "record_type": "corpus",
            "content_sha256": "e" * 64,
            "run_id": experiment_spec["run_id"],
            "experiment_spec_sha256": experiment_spec["content_sha256"],
            "links": {"experiment_spec": experiment_spec["content_sha256"]},
            "payload": identity,
        }]
        with (
            patch("pl_lab.operations.quality.corpus_manifest_snapshot", side_effect=[(identity, items), (identity, items)]) as snapshot,
            patch("pl_lab.operations.quality._resolve_tool", return_value=(tool_path, tool_sha256)) as resolve_tool,
            patch("pl_lab.operations.quality._execute_score_pair", side_effect=fake_pair),
        ):
            result = _execute_score_corpus(
                payload,
                store=store,
                experiment_spec=experiment_spec,
                repository=ROOT,
                authority_snapshot=SimpleNamespace(files={"scripts/diagnostics/measure_quality.py": (ROOT / "scripts" / "diagnostics" / "measure_quality.py").read_bytes()}),
            )

        self.assertEqual(["pair-001", "pair-002"], result["result"]["pair_ids"])
        self.assertEqual(3, resolve_tool.call_count)
        self.assertIs(observed_tool_sets[0], observed_tool_sets[1])
        self.assertEqual(1, len(set(observed_deadlines)))
        self.assertGreater(observed_storage_budgets[0], observed_storage_budgets[1])
        self.assertEqual(2, snapshot.call_count)
        for call in snapshot.call_args_list:
            self.assertEqual(2, call.kwargs["max_items"])
            self.assertEqual(10_000, call.kwargs["max_total_bytes"])
            self.assertGreater(call.kwargs["max_duration_seconds"], 0)

    def test_corpus_storage_accounting_includes_media_and_stages_before_publish(self) -> None:
        identity = {
            "manifest_alias": "corpus.json",
            "manifest_sha256": "a" * 64,
            "source_identities_sha256": "b" * 64,
            "pair_identities_sha256": "c" * 64,
            "item_count": 1,
        }
        item = {
            "pair_id": "pair-001",
            "source_path": "source.mp4",
            "source_sha256": "1" * 64,
            "source_size_bytes": 100,
            "output_path": "output.mp4",
            "output_sha256": "2" * 64,
            "output_size_bytes": 100,
        }
        payload = {
            "corpus_manifest_path": "corpus.json",
            "ffmpeg_executable": "ffmpeg",
            "ffprobe_executable": "ffprobe",
            "model_id": "vmaf_v0.6.1",
            "max_candidates": 1,
            "max_workers": 1,
            "max_duration_seconds": 30,
            "max_storage_bytes": 6_000,
        }
        scorer_sha256 = hashlib.sha256((ROOT / "scripts" / "diagnostics" / "measure_quality.py").read_bytes()).hexdigest()
        model_provenance = {"host_scorer_sha256": scorer_sha256, "model_selector": "version=vmaf_v0.6.1", "harness_schema_version": 3}
        experiment_spec = {
            "run_id": "run-score-corpus",
            "content_sha256": "d" * 64,
            "device_requirements": {"required": False},
            "budgets": {key: payload[key] for key in ("max_candidates", "max_workers", "max_duration_seconds", "max_storage_bytes")},
            "metrics": [{"metric_id": "vmaf", "version": "3.0.0", "model_id": "vmaf_v0.6.1", "provenance": model_provenance, "model_sha256": domain_hash("host-libvmaf-model", {"model_id": "vmaf_v0.6.1", **model_provenance})}],
            "corpus": identity,
        }
        corpus_record = {
            "record_type": "corpus",
            "content_sha256": "e" * 64,
            "run_id": experiment_spec["run_id"],
            "experiment_spec_sha256": experiment_spec["content_sha256"],
            "links": {"experiment_spec": experiment_spec["content_sha256"]},
            "payload": identity,
        }
        store = MagicMock()
        store.all_of_type.return_value = [corpus_record]
        oversized_record = {
            "content_sha256": "f" * 64,
            "record_type": "evaluation",
            "links": {"experiment_spec": experiment_spec["content_sha256"], "corpus": corpus_record["content_sha256"]},
            "payload": {
                "evaluation_kind": "corpus_calibration",
                "subject_sha256": corpus_record["content_sha256"],
                "provenance": {
                    "pair_id": item["pair_id"],
                    "corpus_manifest_sha256": identity["manifest_sha256"],
                    "corpus_pair_identities_sha256": identity["pair_identities_sha256"],
                    "source_content_sha256": item["source_sha256"],
                    "output_content_sha256": item["output_sha256"],
                },
                "evidence": "x" * 6_000,
            },
        }
        tool_path = Path(sys.executable).resolve(strict=True)
        tool_sha256 = hashlib.sha256(tool_path.read_bytes()).hexdigest()
        with (
            patch("pl_lab.operations.quality.corpus_manifest_snapshot", return_value=(identity, [item])),
            patch("pl_lab.operations.quality._resolve_tool", return_value=(tool_path, tool_sha256)),
            patch("pl_lab.operations.quality._execute_score_pair", return_value={"records": [oversized_record], "result": {"scorer_report_size_bytes": 1}}),
        ):
            with self.assertRaises(EvidenceBlocked):
                _execute_score_corpus(
                    payload,
                    store=store,
                    experiment_spec=experiment_spec,
                    repository=ROOT,
                    authority_snapshot=SimpleNamespace(files={"scripts/diagnostics/measure_quality.py": (ROOT / "scripts" / "diagnostics" / "measure_quality.py").read_bytes()}),
                )
        store.put.assert_not_called()


if __name__ == "__main__":
    unittest.main()
