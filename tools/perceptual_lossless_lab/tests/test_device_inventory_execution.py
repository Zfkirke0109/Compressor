from __future__ import annotations

import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from pl_lab.authority import AuthoritySnapshot, fingerprint_snapshot
from pl_lab.errors import EvidenceBlocked, IdentityMismatch
from pl_lab.experiment import ExperimentStore
from pl_lab.operations.device import _Deadline, _online_target, execute
from pl_lab.process import ProcessResult
from pl_lab.records import RecordStore, validate_record
from pl_lab.sanitize import device_alias


RUN_ID = "run-inventory-001"
SPEC_SHA256 = "a" * 64
SERIAL = "SERIAL-EXAMPLE-001"
PACKAGE_ID = "example.package"
FIRMWARE = "example/device:16/release-keys"
FIRMWARE_SHA256 = hashlib.sha256(FIRMWARE.encode("utf-8")).hexdigest()
APK_SHA256 = "b" * 64
SIGNER_SHA256 = "c" * 64
REMOTE_APK = "/data/app/~~example/example.package/base.apk"


def result(stdout: str) -> ProcessResult:
    return ProcessResult(0, stdout, "", 1, "d" * 64, False)


class DeviceInventoryExecutionTests(unittest.TestCase):
    def _payload(self) -> dict:
        return {
            "adb_executable": sys.executable,
            "device_serial": SERIAL,
            "android_user": 0,
            "package_id": PACKAGE_ID,
            "expected_firmware_sha256": FIRMWARE_SHA256,
        }

    def _snapshot_and_spec(self, root: Path) -> tuple[AuthoritySnapshot, dict]:
        identity = {"vcs": "git", "commit_oid": "0" * 40, "dirty": False}
        snapshot = AuthoritySnapshot(root=root, files={"authority.kt": b"authority\n"}, missing=(), repository_identity=identity)
        source_sha256 = fingerprint_snapshot(snapshot)["authority_sha256"]
        spec = {
            "content_sha256": SPEC_SHA256,
            "run_id": RUN_ID,
            "created_at": "2026-08-14T00:00:00Z",
            "budgets": {"max_duration_seconds": 60},
            "device_requirements": {
                "required": True,
                "device_alias": device_alias(SERIAL),
                "android_user": 0,
                "package_id": PACKAGE_ID,
                "build_identity": {"version_code": 7, "version_name": "1.2.3", "apk_sha256": APK_SHA256, "source_sha256": source_sha256},
                "signer_sha256": SIGNER_SHA256,
                "firmware_sha256": FIRMWARE_SHA256,
            },
        }
        return snapshot, spec

    def test_inventory_is_read_only_explicit_serial_user_bound_and_sanitized(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            experiment = root / "experiment"
            ExperimentStore(experiment).prepare(execute=True)
            snapshot, spec = self._snapshot_and_spec(root)
            calls: list[list[str]] = []

            def fake_run(argv: list[str], **kwargs: object) -> ProcessResult:
                calls.append(list(argv))
                self.assertEqual(argv[1:3], ["-s", SERIAL])
                command = argv[3:]
                responses = {
                    ("devices",): f"List of devices attached\n{SERIAL}\tdevice\n",
                    ("shell", "am", "get-current-user"): "0\n",
                    ("shell", "pm", "list", "packages", "--user", "0", PACKAGE_ID): f"package:{PACKAGE_ID}\n",
                    ("shell", "getprop", "ro.build.fingerprint"): FIRMWARE + "\n",
                    ("shell", "pm", "path", "--user", "0", PACKAGE_ID): f"package:{REMOTE_APK}\n",
                    ("shell", "sha256sum", REMOTE_APK): f"{APK_SHA256}  {REMOTE_APK}\n",
                    ("shell", "dumpsys", "package", PACKAGE_ID): f"versionCode=7 minSdk=26\nversionName=1.2.3\nSHA-256 certificate digest: {SIGNER_SHA256}\n",
                    ("shell", "cmd", "thermalservice", "get-status"): "Thermal status: 0\n",
                }
                self.assertIn(tuple(command), responses)
                return result(responses[tuple(command)])

            with patch("pl_lab.operations.device._run", side_effect=fake_run):
                executed = execute(
                    "pl_device_inventory",
                    self._payload(),
                    store=RecordStore(experiment),
                    experiment_spec=spec,
                    repository=root,
                    authority_snapshot=snapshot,
                )
            record = executed["records"][0]
            validate_record(record)
            self.assertEqual(record["payload"]["status"], "COMPLETE")
            self.assertEqual(record["payload"]["device_alias"], device_alias(SERIAL))
            self.assertNotIn(SERIAL, str(record))
            self.assertTrue(any("--user" in call for call in calls))
            mutating = {"install", "uninstall", "clear", "reboot", "push"}
            self.assertFalse(any(mutating & set(call) for call in calls))

    def test_online_target_exit_taxonomy_is_exact(self) -> None:
        payload = self._payload()
        cases = (
            ("List of devices attached\n", EvidenceBlocked),
            ("List of devices attached\nOTHER\tdevice\n", IdentityMismatch),
            (f"List of devices attached\n{SERIAL}\toffline\n", EvidenceBlocked),
            (f"List of devices attached\n{SERIAL}\tdevice\n{SERIAL}\tdevice\n", IdentityMismatch),
            (f"List of devices attached\n{SERIAL}\tdevice\nOTHER\tdevice\n", IdentityMismatch),
        )
        for stdout, expected in cases:
            with self.subTest(stdout=stdout):
                with patch("pl_lab.operations.device._adb", return_value=result(stdout)):
                    with self.assertRaises(expected):
                        _online_target(
                            payload,
                            deadline=_Deadline(10),
                            adb_sha256="d" * 64,
                            sensitive_values=(SERIAL,),
                            cwd=Path.cwd(),
                        )


if __name__ == "__main__":
    unittest.main()
