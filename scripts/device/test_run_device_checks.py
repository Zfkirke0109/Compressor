#!/usr/bin/env python3
"""Exercise device-check reporting with a fake adb and Gradle."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class DeviceChecksTest(unittest.TestCase):
    def run_checks(self, result, relative=False, installed=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            repo = root / "repo"
            script = repo / "scripts" / "device" / "run-device-checks.sh"
            script.parent.mkdir(parents=True)
            shutil.copyfile(Path(__file__).with_name(script.name), script)
            gradle = repo / "gradlew"
            gradle.write_text('#!/bin/bash\nprintf "gradle\\n" >> "$CALLS"\n')
            gradle.chmod(0o755)
            adb = root / "adb"
            adb.write_text(
                '#!/bin/bash\n'
                'printf "%s\\n" "$*" >> "$CALLS"\n'
                'if [[ "$1" == push && ! -f "$2" ]]; then exit 1; fi\n'
                'if [[ "$1" == install && "$ALREADY_INSTALLED" == 1 ]]; then\n'
                '  echo INSTALL_FAILED_VERSION_DOWNGRADE >&2; exit 1\n'
                'fi\n'
                'if [[ "$1 $2" == "shell date +%s.%N" ]]; then printf "1794198030.123456789\\r\\n"; fi\n'
                'if [[ "$1 $2 $3" == "shell am instrument" ]]; then printf "%s\\n" "$TEST_RESULT"; fi\n'
            )
            adb.chmod(0o755)
            clip = root / "camera clips" / "test.mp4"
            clip.parent.mkdir()
            clip.write_bytes(b"synthetic clip")
            arg = str(clip.relative_to(root) if relative else clip)
            calls = root / "calls.txt"
            args = ["--installed", arg, "150"] if installed else [arg]
            proc = subprocess.run(
                ["bash", str(script), *args], cwd=root,
                env={
                    **os.environ, "PATH": f"{root}:{os.environ['PATH']}", "TEST_RESULT": result,
                    "CALLS": str(calls), "ALREADY_INSTALLED": "1" if installed else "0",
                },
                text=True, capture_output=True, check=False
            )
            report = clip.with_suffix(".device-checks.txt")
            return (
                proc, report.read_text() if report.exists() else None,
                hashlib.sha256(clip.read_bytes()).hexdigest()[:16],
                calls.read_text().splitlines() if calls.exists() else [],
            )

    def test_relative_clip_is_hashed_and_report_is_written_beside_it(self):
        proc, report, digest, _ = self.run_checks("OK (1 test)", relative=True)
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn(f"sha256={digest}", report)

    def test_assumption_ignored_and_zero_test_runs_are_incomplete(self):
        for result in ("INSTRUMENTATION_STATUS_CODE: -4\nOK (1 test)",
                       "INSTRUMENTATION_STATUS_CODE: -3\nOK (1 test)", "OK (0 tests)"):
            with self.subTest(result=result):
                proc, report, _, _ = self.run_checks(result)
                self.assertEqual(2, proc.returncode, proc.stdout + proc.stderr)
                self.assertIn(result, report)
                self.assertNotIn("device checks passed", proc.stdout)

    def test_installed_mode_uses_the_selected_profile_without_rebuilding_or_installing(self):
        proc, _, _, calls = self.run_checks("OK (1 test)", relative=True, installed=True)
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        self.assertNotIn("gradle", calls)
        self.assertFalse(any(call.startswith("install ") for call in calls))
        self.assertTrue(any(call.startswith("shell am instrument -w -r --user 150 ") for call in calls))

    def test_checks_run_native_packing_without_erasing_device_logs(self):
        proc, _, _, calls = self.run_checks("OK (1 test)")
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        instrument = next(call for call in calls if call.startswith("shell am instrument "))
        self.assertIn("compress.joshattic.us.FramePackingDeviceTest", instrument)
        self.assertNotIn("logcat -c", calls)
        self.assertTrue(any("logcat -t 1794198030.123456789 " in call for call in calls))


if __name__ == "__main__":
    unittest.main()
