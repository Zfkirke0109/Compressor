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
    def run_checks(self, result, relative=False, installed=False, options=(), instrument_status=0, logcat="",
                   settings_result="OK (1 test)"):
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
                'if [[ "$*" == *TestOwnedSettingsDeviceTest* ]]; then printf "%s\\n" "$SETTINGS_RESULT"; exit 0; fi\n'
                'if [[ "$1 $2 $3" == "shell am instrument" ]]; then printf "%s\\n" "$TEST_RESULT"; exit "$INSTRUMENT_STATUS"; fi\n'
                'if [[ "$1" == logcat ]]; then printf "%s\\n" "$LOGCAT_OUTPUT"; fi\n'
            )
            adb.chmod(0o755)
            clip = root / "camera clips" / "test.mp4"
            clip.parent.mkdir()
            clip.write_bytes(b"synthetic clip")
            arg = str(clip.relative_to(root) if relative else clip)
            calls = root / "calls.txt"
            args = [*options, "--installed", arg, "150"] if installed else [*options, arg]
            proc = subprocess.run(
                ["bash", str(script), *args], cwd=root,
                env={
                    **os.environ, "PATH": f"{root}:{os.environ['PATH']}", "TEST_RESULT": result,
                    "CALLS": str(calls), "ALREADY_INSTALLED": "1" if installed else "0",
                    "INSTRUMENT_STATUS": str(instrument_status), "LOGCAT_OUTPUT": logcat,
                    "SETTINGS_RESULT": settings_result,
                },
                text=True, capture_output=True, check=False
            )
            report = clip.with_suffix(".fallback-checks.txt" if "--fallback" in options else ".device-checks.txt")
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
        # The clip-free timeline localization and gallery publication run with the default checks.
        self.assertIn("compress.joshattic.us.EditListTimelineDeviceTest", instrument)
        self.assertIn("compress.joshattic.us.GalleryPublicationDeviceTest", instrument)
        self.assertIn("compress.joshattic.us.BatchItemCardDeviceTest", instrument)
        self.assertNotIn("logcat -c", calls)
        self.assertTrue(any("logcat -t 1794198030.123456789 " in call for call in calls))


    def test_fallback_mode_runs_only_the_fallback_test_and_writes_its_own_report(self):
        proc, report, digest, calls = self.run_checks("OK (3 tests)", installed=True, options=("--fallback",))
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        instrument = next(call for call in calls if call.startswith("shell am instrument "))
        self.assertIn("-e class compress.joshattic.us.SaferRungFallbackDeviceTest -e sourceVideo ", instrument)
        self.assertNotIn("PipelineDeviceTest", instrument)
        self.assertNotIn("EditListTimelineDeviceTest", instrument)
        self.assertNotIn("GalleryPublicationDeviceTest", instrument)
        self.assertIn("--user 150", instrument)
        self.assertIn(f"sha256={digest}", report)
        self.assertFalse(any(call.startswith("install ") for call in calls))
        # The switch the test changed is put back in a run of its own, for the same user, after it.
        instruments = [call for call in calls if call.startswith("shell am instrument ")]
        self.assertEqual(2, len(instruments))
        self.assertIn("-e class compress.joshattic.us.TestOwnedSettingsDeviceTest ", instruments[1])
        self.assertIn("--user 150", instruments[1])
        self.assertIn("# putting back test-owned settings", report)

    def test_the_default_run_does_not_include_the_fallback_test(self):
        _, _, _, calls = self.run_checks("OK (1 test)")
        instrument = next(call for call in calls if call.startswith("shell am instrument "))
        self.assertNotIn("SaferRungFallbackDeviceTest", instrument)
        self.assertFalse(any("TestOwnedSettingsDeviceTest" in call for call in calls))

    def test_a_fallback_run_whose_settings_were_not_put_back_fails(self):
        proc, report, _, _ = self.run_checks("OK (3 tests)", options=("--fallback",),
                                             settings_result="FAILURES!!!\nTests run: 1,  Failures: 1")
        self.assertEqual(1, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("may not be the user's", proc.stdout)
        self.assertIn("FAILURES!!!", report)

    def test_the_settings_are_put_back_even_when_the_fallback_run_died(self):
        # An ADB drop or a crash ends the fallback run; the switch is still put back after it.
        proc, report, _, calls = self.run_checks("INSTRUMENTATION_RESULT: shortMsg=Process crashed.",
                                                 options=("--fallback",), instrument_status=1)
        self.assertEqual(2, proc.returncode, proc.stdout + proc.stderr)
        self.assertTrue(any("TestOwnedSettingsDeviceTest" in call for call in calls))
        self.assertIn("# putting back test-owned settings\nOK (1 test)", report)

    def test_a_fallback_run_without_a_marginal_trial_is_incomplete_not_passed(self):
        # The test skips (assumption) when the clip's ladder selects no marginal trial.
        proc, report, _, _ = self.run_checks("INSTRUMENTATION_STATUS_CODE: -4\nOK (3 tests)", options=("--fallback",))
        self.assertEqual(2, proc.returncode, proc.stdout + proc.stderr)
        self.assertIsNotNone(report)

    def test_a_run_the_device_refuses_to_start_ends_incomplete_and_says_why(self):
        # What the S26 printed when asked to run the tests in the Secure Folder user. am exited
        # non-zero, and the script used to stop there without a verdict or an exit code of its own.
        refused = ("java.lang.SecurityException: Shell does not have permission to access user 150\n"
                   "onError: commandError=true message=Shell does not have permission to access user 150")
        proc, report, _, _ = self.run_checks(refused, installed=True, instrument_status=1)
        self.assertEqual(2, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("Shell does not have permission to access user 150", report)
        self.assertIn("the tests did not start", proc.stdout)
        self.assertIn("device checks incomplete", proc.stdout)
        self.assertNotIn("device checks passed", proc.stdout)

    def test_a_failing_run_is_reported_as_failed_whatever_am_returns(self):
        proc, report, _, _ = self.run_checks("FAILURES!!!\nTests run: 1,  Failures: 1", instrument_status=1)
        self.assertEqual(1, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("FAILURES!!!", report)
        self.assertIn("device checks failed", proc.stdout)

    def test_an_incomplete_run_keeps_what_the_tests_printed(self):
        # The fallback test prints each batch's rungs before it skips, which is what says which clip
        # to try next, so an incomplete run collects those lines as a passing one does.
        printed = ("11-12 21:41:30.000  1  2 I System.out: b287-fallback saferRetry=true refuse=[1] rungs=0.75:passed\n"
                   "11-12 21:41:31.000  1  2 I System.out: something another test printed")
        proc, report, _, _ = self.run_checks("INSTRUMENTATION_STATUS_CODE: -4\nOK (3 tests)", options=("--fallback",), logcat=printed)
        self.assertEqual(2, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("b287-fallback saferRetry=true refuse=[1] rungs=0.75:passed", report)
        self.assertNotIn("something another test printed", report)
        self.assertIn("device checks incomplete", proc.stdout)

    def test_a_failed_run_keeps_what_the_tests_printed(self):
        printed = ("11-12 21:41:30.000  1  2 I System.out: b287-timeline edit_tail_168.mp4 stage=source_edit_list\n"
                   "11-12 21:41:31.000  1  2 I System.out: gallery-publication: publish0 -> PUBLISH; rowRemoved=true")
        proc, report, _, _ = self.run_checks("FAILURES!!!\nTests run: 1,  Failures: 1", logcat=printed)
        self.assertEqual(1, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("b287-timeline edit_tail_168.mp4 stage=source_edit_list", report)
        self.assertIn("gallery-publication: publish0 -> PUBLISH; rowRemoved=true", report)
        self.assertIn("device checks failed", proc.stdout)

    def test_an_unknown_option_is_refused_before_anything_runs(self):
        proc, report, _, calls = self.run_checks("OK (1 test)", options=("--falback",))
        self.assertEqual(64, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("unknown option: --falback", proc.stderr)
        self.assertEqual([], calls)
        self.assertIsNone(report)


if __name__ == "__main__":
    unittest.main()
