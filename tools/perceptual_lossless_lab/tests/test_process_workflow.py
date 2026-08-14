from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import tempfile
import time
import tracemalloc
import unittest

from pl_lab.errors import EvidenceBlocked, InvalidInput, MissingPrerequisite
from pl_lab.process import ProcessBudget, run_bounded
from pl_lab.records import RecordStore, create_record
from pl_lab.workflow import plan_workflow, record_operation_completion, resume_workflow


HEX_A = "a" * 64


class BoundedProcessTests(unittest.TestCase):
    def test_only_argv_sequences_are_accepted(self) -> None:
        with self.assertRaises(InvalidInput):
            run_bounded("python --version", budget=ProcessBudget(timeout_seconds=2, max_output_bytes=1024))

    def test_missing_executable_before_work_is_exit_11(self) -> None:
        with self.assertRaises(MissingPrerequisite) as caught:
            run_bounded(["definitely-not-a-real-pl-lab-tool"], budget=ProcessBudget(timeout_seconds=2, max_output_bytes=1024))
        self.assertEqual(caught.exception.exit_code, 11)

    def test_timeout_is_blocked_and_output_is_bounded_and_redacted(self) -> None:
        with self.assertRaises(EvidenceBlocked) as caught:
            run_bounded([sys.executable, "-c", "import time; time.sleep(2)"], budget=ProcessBudget(timeout_seconds=0.05, max_output_bytes=1024))
        self.assertEqual(caught.exception.exit_code, 13)

        result = run_bounded(
            [sys.executable, "-c", "print('SERIAL-EXAMPLE')"],
            budget=ProcessBudget(timeout_seconds=2, max_output_bytes=80),
            sensitive_values=("SERIAL-EXAMPLE",),
        )
        self.assertLessEqual(len(result.stdout.encode("utf-8")), 80)
        self.assertNotIn("SERIAL-EXAMPLE", result.stdout)
        self.assertFalse(result.output_truncated)

        with self.assertRaises(EvidenceBlocked):
            run_bounded(
                [sys.executable, "-c", "print('x' * 10000)"],
                budget=ProcessBudget(timeout_seconds=2, max_output_bytes=80),
            )

    def test_expected_executable_hash_is_checked(self) -> None:
        wrong = "0" * 64
        with self.assertRaises(EvidenceBlocked):
            run_bounded([sys.executable, "--version"], budget=ProcessBudget(timeout_seconds=2, max_output_bytes=1024), expected_executable_sha256=wrong)

    def test_large_streams_are_drained_without_buffering_complete_output(self) -> None:
        writer = "import itertools,sys;p=b'x'*65536;any((sys.stdout.buffer.write(p),sys.stdout.buffer.flush(),False)[2] for _ in itertools.repeat(None))"
        started = time.monotonic()
        tracemalloc.start()
        try:
            with self.assertRaises(EvidenceBlocked):
                run_bounded(
                    [sys.executable, "-c", writer],
                    budget=ProcessBudget(timeout_seconds=10, max_output_bytes=1024),
                )
            _, peak_bytes = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()

        self.assertLess(time.monotonic() - started, 3.0)
        self.assertLess(peak_bytes, 4 * 1024 * 1024)

    def test_timeout_terminates_a_spawned_descendant(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            spawned = Path(temp) / "spawned.txt"
            survived = Path(temp) / "survived.txt"
            child = (
                "import pathlib,time; time.sleep(2); "
                f"pathlib.Path({str(survived)!r}).write_text('survived', encoding='utf-8')"
            )
            parent = (
                "import pathlib,subprocess,sys,time; "
                f"subprocess.Popen([sys.executable, '-c', {child!r}]); "
                f"pathlib.Path({str(spawned)!r}).write_text('spawned', encoding='utf-8'); "
                "time.sleep(10)"
            )

            with self.assertRaises(EvidenceBlocked):
                run_bounded(
                    [sys.executable, "-c", parent],
                    budget=ProcessBudget(timeout_seconds=0.5, max_output_bytes=1024),
                )

            self.assertTrue(spawned.is_file(), "the descendant must exist before timeout cleanup")
            time.sleep(2.25)
            self.assertFalse(survived.exists(), "the timed-out process tree left a descendant running")


class WorkflowTests(unittest.TestCase):
    def _request(self) -> dict:
        return {"schema_version": "1", "workflow_id": "pl-benchmark", "run_id": "run-001", "experiment_spec_sha256": HEX_A, "payload": {}}

    def test_plan_is_dry_by_default_and_execute_is_immutable(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            experiment = Path(temp) / "experiment"
            request = self._request()
            dry = plan_workflow(experiment, request, execute=False, created_at="2026-08-13T00:00:00Z")
            self.assertFalse(experiment.exists())
            self.assertEqual(dry["workflow_id"], "pl-benchmark")
            self.assertEqual(dry["steps"][0]["operation_id"], "pl_device_inventory")

            written = plan_workflow(experiment, request, execute=True, created_at="2026-08-13T00:00:00Z")
            self.assertEqual(written, dry)
            self.assertTrue((experiment / "runs" / "run-001" / "workflows" / "pl-benchmark" / "workflow-plan.json").is_file())
            self.assertEqual(plan_workflow(experiment, request, execute=True, created_at="2026-08-13T00:00:00Z"), written)

    def test_resume_requires_original_request_and_spec_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            experiment = Path(temp) / "experiment"
            request = self._request()
            plan_workflow(experiment, request, execute=True, created_at="2026-08-13T00:00:00Z")
            resumed = resume_workflow(experiment, "run-001", request=request, experiment_spec_sha256=HEX_A)
            self.assertEqual(len(resumed["remaining_steps"]), len(resumed["plan"]["steps"]))
            changed = dict(request, payload={"changed": True})
            with self.assertRaises(EvidenceBlocked):
                resume_workflow(experiment, "run-001", request=changed, experiment_spec_sha256=HEX_A)
            with self.assertRaises(EvidenceBlocked):
                resume_workflow(experiment, "run-001", request=request, experiment_spec_sha256="b" * 64)

    def test_resume_validates_immutable_step_completion_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            experiment = Path(temp) / "experiment"
            request = self._request()
            plan_workflow(experiment, request, execute=True, created_at="2026-08-13T00:00:00Z")
            device = create_record(
                "device",
                producer_role="android-device-lab",
                run_id="run-001",
                created_at="2026-08-13T00:00:00Z",
                experiment_spec_sha256=HEX_A,
                links={"experiment_spec": HEX_A},
                payload={"device_alias": "device-0123456789ab", "android_user": 0, "package_id": "example.package", "build_identity": {"version_code": 1}, "signer_sha256": "b" * 64, "firmware_fingerprint_sha256": "c" * 64, "requested_configuration": {}, "actual_configuration": {}, "thermals": {}, "status": "COMPLETE", "confounders": []},
            )
            RecordStore(experiment).put(device)
            completions = record_operation_completion(
                experiment,
                run_id="run-001",
                experiment_spec_sha256=HEX_A,
                operation_id="pl_device_inventory",
                operation_request_sha256="b" * 64,
                output_sha256s=[device["content_sha256"]],
                completed_at="2026-08-13T00:00:00Z",
            )
            self.assertEqual(len(completions), 1)
            resumed = resume_workflow(experiment, "run-001", request=request, experiment_spec_sha256=HEX_A)
            self.assertEqual(len(resumed["completed_steps"]), 1)
            self.assertEqual(len(resumed["remaining_steps"]), len(resumed["plan"]["steps"]) - 1)

            output_path = experiment / "records" / "device" / f"{device['content_sha256']}.json"
            original_output = output_path.read_bytes()
            output_path.write_bytes(b"{}\n")
            with self.assertRaises(EvidenceBlocked):
                resume_workflow(experiment, "run-001", request=request, experiment_spec_sha256=HEX_A)
            output_path.write_bytes(original_output)

            completion_path = experiment / "runs" / "run-001" / "workflows" / "pl-benchmark" / "steps" / "01-pl_device_inventory.json"
            tampered = json.loads(completion_path.read_text(encoding="utf-8"))
            tampered["output_sha256s"] = ["d" * 64]
            completion_path.write_text(json.dumps(tampered), encoding="utf-8")
            with self.assertRaises(EvidenceBlocked):
                resume_workflow(experiment, "run-001", request=request, experiment_spec_sha256=HEX_A)


if __name__ == "__main__":
    unittest.main()
