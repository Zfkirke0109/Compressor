from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from pl_lab.hooks import LOCK_STALE_SECONDS, HookOutcome, _InvocationLock, _lock_directory, affected_paths, is_relevant_path, post_tool_use, stop_hook
from pl_lab.process import ProcessResult


ROOT = Path(__file__).resolve().parents[3]


def _result(code: int = 0) -> ProcessResult:
    return ProcessResult(code, "", "", 1, "0" * 64, False)


class HookTests(unittest.TestCase):
    def test_windows_space_path_and_patch_paths_are_relevant(self) -> None:
        absolute = str(ROOT / "app/src/main/java/compress/joshattic/us/OutputVerifier.kt")
        payload = {
            "tool_input": {
                "file_path": absolute,
                "patch": "*** Update File: app/src/main/cpp/vmaf_jni.c\n",
            }
        }
        paths = affected_paths(payload)
        self.assertEqual(2, len(paths))
        self.assertTrue(all(is_relevant_path(path) for path in paths))

    def test_codex_apply_patch_command_extracts_paths_and_ignores_unsafe_values(self) -> None:
        payload = {
            "tool_name": "apply_patch",
            "tool_input": {
                "command": "*** Begin Patch\n*** Update File: app/src/main/java/compress/joshattic/us/CompressorViewModel.kt\n*** End Patch",
            },
        }
        paths = affected_paths(payload)
        self.assertEqual({"app/src/main/java/compress/joshattic/us/compressorviewmodel.kt"}, paths)
        self.assertTrue(all(is_relevant_path(path) for path in paths))
        self.assertEqual(set(), affected_paths({"tool_input": {"command": ["not", "a", "string"]}}))
        self.assertEqual(set(), affected_paths({"tool_input": {"command": "x" * (2 * 1024 * 1024 + 1)}}))

        moved = affected_paths({"tool_input": {"command": "*** Begin Patch\n*** Update File: notes.tmp\n*** Move to: app/src/main/java/compress/joshattic/us/CompressorViewModel.kt\n*** End Patch"}})
        self.assertIn("app/src/main/java/compress/joshattic/us/compressorviewmodel.kt", moved)

    def test_default_lock_is_host_data_scoped_and_longer_than_hook_budget(self) -> None:
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {"CLAUDE_PLUGIN_DATA": temp}, clear=True):
            lock_root = _lock_directory(repository=ROOT)
        self.assertEqual(Path(temp), lock_root.parent)
        self.assertRegex(lock_root.name, r"^repository-[0-9a-f]{16}$")
        self.assertGreater(LOCK_STALE_SECONDS, 900)

    def test_irrelevant_and_recursive_inputs_immediately_succeed(self) -> None:
        called: list[object] = []

        def runner(argv: object, cwd: Path) -> ProcessResult:
            called.append(argv)
            return _result()

        with tempfile.TemporaryDirectory() as temp:
            outcome = post_tool_use({"cwd": str(ROOT), "tool_input": {"file_path": str(ROOT / "README.md")}}, runner=runner, lock_root=Path(temp))
            self.assertEqual(HookOutcome(), outcome)
            previous = os.environ.get("PL_LAB_HOOK_ACTIVE")
            os.environ["PL_LAB_HOOK_ACTIVE"] = "1"
            try:
                recursive = post_tool_use({"cwd": str(ROOT), "tool_input": {"file_path": str(ROOT / "app/src/main/cpp/vmaf_jni.c")}}, runner=runner, lock_root=Path(temp))
            finally:
                if previous is None:
                    os.environ.pop("PL_LAB_HOOK_ACTIVE", None)
                else:
                    os.environ["PL_LAB_HOOK_ACTIVE"] = previous
            self.assertEqual(HookOutcome(), recursive)
        self.assertEqual([], called)

    def test_relevant_success_runs_exact_fast_slice_and_failure_blocks(self) -> None:
        calls: list[list[str]] = []

        def success(argv: object, cwd: Path) -> ProcessResult:
            calls.append(list(argv))
            self.assertEqual(ROOT, cwd)
            return _result()

        payload = {"cwd": str(ROOT), "tool_input": {"file_path": str(ROOT / "app/src/main/cpp/vmaf_jni.c")}}
        with tempfile.TemporaryDirectory() as temp:
            self.assertEqual(HookOutcome(), post_tool_use(payload, runner=success, lock_root=Path(temp)))
        self.assertEqual(3, len(calls))
        self.assertIn("--fast", calls[0])
        self.assertIn("--check", calls[1])
        self.assertIn(":app:testDebugUnitTest", calls[2])
        self.assertNotIn("connectedAndroidTest", calls[2])

        def failure(argv: object, cwd: Path) -> ProcessResult:
            return _result(1)

        with tempfile.TemporaryDirectory() as temp:
            failed = post_tool_use(payload, runner=failure, lock_root=Path(temp))
        self.assertEqual(2, failed.exit_code)
        self.assertNotIn(str(ROOT), failed.stderr)

    def test_concurrent_relevant_invocation_fails_closed_for_retry(self) -> None:
        payload = {"cwd": str(ROOT), "tool_input": {"file_path": str(ROOT / "app/src/main/cpp/vmaf_jni.c")}}
        with tempfile.TemporaryDirectory() as temp:
            lock_root = Path(temp)
            lock = _InvocationLock(lock_root)
            with lock as acquired:
                self.assertTrue(acquired)
                outcome = post_tool_use(payload, runner=lambda argv, cwd: self.fail("runner should not execute"), lock_root=lock_root)
            self.assertEqual(2, outcome.exit_code)
            self.assertIn("retry", outcome.stderr.casefold())

    def test_stop_active_and_ordinary_progress_do_not_block(self) -> None:
        self.assertEqual(HookOutcome(), stop_hook({"stop_hook_active": True, "last_assistant_message": "Implementation complete."}))
        self.assertEqual(HookOutcome(), stop_hook({"stop_hook_active": False, "last_assistant_message": "Phase 3 is complete; Phase 4 remains."}))
        self.assertEqual(HookOutcome(), stop_hook({"stop_hook_active": False, "last_assistant_message": "The implementation is complete."}))

    def test_stop_rejects_unsupported_success_and_invalid_protocol(self) -> None:
        for claim in (
            "The benchmark passed.",
            "The PL workflow is complete.",
            "The candidate improved quality and passed all checks.",
        ):
            with self.subTest(claim=claim):
                unsupported = stop_hook({"stop_hook_active": False, "last_assistant_message": claim})
                self.assertEqual("block", json.loads(unsupported.stdout)["decision"])
        invalid = stop_hook({"stop_hook_active": False, "last_assistant_message": "PL_STATUS: PASS\n"})
        self.assertEqual("block", json.loads(invalid.stdout)["decision"])

    def test_truthful_blocked_handoff_is_allowed_and_private_path_is_not(self) -> None:
        allowed = stop_hook({"stop_hook_active": False, "last_assistant_message": "PL_STATUS: BLOCKED\nPL_MISSING: Android device access"})
        self.assertEqual(HookOutcome(), allowed)
        private = stop_hook({"stop_hook_active": False, "last_assistant_message": "PL_STATUS: BLOCKED\nPL_MISSING: C:\\Users\\example\\clip.mp4"})
        self.assertEqual("block", json.loads(private.stdout)["decision"])
        posix_private = stop_hook({"stop_hook_active": False, "last_assistant_message": "PL_STATUS: BLOCKED\nPL_MISSING: /tmp/example/private-video.mp4"})
        self.assertEqual("block", json.loads(posix_private.stdout)["decision"])
        extra = stop_hook({"stop_hook_active": False, "last_assistant_message": "PL_STATUS: BLOCKED\nPL_MISSING: Android device access\nPL_PATH: C:\\Users\\example\\clip.mp4"})
        self.assertEqual("block", json.loads(extra.stdout)["decision"])

    def test_pass_fail_protocol_delegates_to_report_validator(self) -> None:
        calls: list[tuple[Path, Path, str, str, str]] = []

        def validator(repository: Path, experiment: Path, logical: str, digest: str, status: str) -> None:
            calls.append((repository, experiment, logical, digest, status))

        message = "\n".join((
            "PL_STATUS: FAIL",
            "PL_EXPERIMENT: validation/pl-lab/fixture",
            "PL_REPORT: records/report/" + "a" * 64 + ".json",
            "PL_REPORT_SHA256: " + "a" * 64,
        ))
        outcome = stop_hook({"cwd": str(ROOT), "stop_hook_active": False, "last_assistant_message": message}, report_validator=validator)
        self.assertEqual(HookOutcome(), outcome)
        self.assertEqual("FAIL", calls[0][-1])

        extra = stop_hook({"cwd": str(ROOT), "stop_hook_active": False, "last_assistant_message": message + "\nPL_NOTE: hidden"}, report_validator=validator)
        self.assertEqual("block", json.loads(extra.stdout)["decision"])


if __name__ == "__main__":
    unittest.main()
