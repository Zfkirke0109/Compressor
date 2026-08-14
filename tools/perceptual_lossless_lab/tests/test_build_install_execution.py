from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

from pl_lab.authority import AuthoritySnapshot, fingerprint_snapshot
from pl_lab.errors import EvidenceBlocked, IdentityMismatch, MissingPrerequisite
from pl_lab.experiment import ExperimentStore
from pl_lab.operations.device import execute
from pl_lab.records import RecordStore, validate_record
from pl_lab.sanitize import device_alias


ROOT = Path(__file__).resolve().parents[3]
RUN_ID = "run-build-install-001"
SPEC_SHA256 = "a" * 64
SIGNER_SHA256 = "b" * 64
OTHER_SIGNER_SHA256 = "c" * 64
SERIAL = "FAKE-SERIAL-001"
PACKAGE_ID = "io.github.zfkirke0109.galaxycompressor"
FIRMWARE = "samsung/dm3q/device:16/fake:user/release-keys"
FIRMWARE_SHA256 = hashlib.sha256(FIRMWARE.encode("utf-8")).hexdigest()
REMOTE_APK = "/data/app/~~fake/io.github.zfkirke0109.galaxycompressor/base.apk"


FAKE_TOOL = r'''from __future__ import annotations
import json
from pathlib import Path
import shutil
import sys

tool = sys.argv[1]
state_path = Path(sys.argv[2])
args = sys.argv[3:]
state = json.loads(state_path.read_text(encoding="utf-8"))

if tool == "adb" and args[:2] != ["-s", state["serial"]]:
    print("ADB selector contract violated", file=sys.stderr)
    raise SystemExit(96)

def normalized(value: str) -> str:
    folded = value.replace("\\", "/")
    if folded.endswith("/installed-before.apk"):
        return "<PRE_PULL>"
    if folded.endswith("/installed-after.apk"):
        return "<POST_PULL>"
    if value == state["target_apk"]:
        return "<TARGET>"
    if value == state["remote_apk"]:
        return "<REMOTE>"
    return value

actual = {"tool": tool, "argv": [normalized(value) for value in args]}
position = len(state["calls"])
if position >= len(state["expected_calls"]) or actual != state["expected_calls"][position]:
    print("unexpected call " + json.dumps(actual, sort_keys=True), file=sys.stderr)
    raise SystemExit(97)
state["calls"].append(actual)

installed = bool(state.get("installed"))
if tool == "adb":
    command = args[2:]
    if command == ["devices"]:
        lines = ["List of devices attached", state["serial"] + "\t" + state.get("target_state", "device")]
        if state.get("extra_online"):
            lines.append("ANOTHER-SERIAL\tdevice")
        print("\n".join(lines))
    elif command == ["shell", "am", "get-current-user"]:
        print(state.get("active_user", 0))
    elif command == ["shell", "pm", "list", "packages", "--user", "0", state["package_id"]]:
        if state.get("package_present", True):
            print("package:" + state["package_id"])
    elif command == ["shell", "getprop", "ro.build.fingerprint"]:
        print(state.get("post_firmware", state["firmware"]) if installed else state["firmware"])
    elif command == ["shell", "cmd", "thermalservice", "get-status"]:
        print("Thermal status: " + str(state.get("post_thermal", 0) if installed else state.get("pre_thermal", 0)))
    elif command == ["shell", "pm", "path", "--user", "0", state["package_id"]]:
        print("package:" + state["remote_apk"])
    elif command == ["shell", "stat", "-c", "%s", state["remote_apk"]]:
        source = Path(state["target_apk"] if installed else state["pre_apk"])
        print(source.stat().st_size)
    elif command[:2] == ["pull", state["remote_apk"]] and len(command) == 3:
        source = Path(state["target_apk"] if installed else state["pre_apk"])
        shutil.copyfile(source, Path(command[2]))
        print("1 file pulled")
    elif command == ["install", "--user", "0", "-r", state["target_apk"]]:
        state["installed"] = True
        print("Success")
    else:
        print("unimplemented fake adb argv", file=sys.stderr)
        raise SystemExit(98)
elif tool == "aapt":
    if args[:2] != ["dump", "badging"] or len(args) != 3:
        raise SystemExit(98)
    before = args[2].replace("\\", "/").endswith("/installed-before.apk")
    code = state["pre_version_code"] if before else state["target_version_code"]
    name = state["pre_version_name"] if before else state["target_version_name"]
    print("package: name='%s' versionCode='%s' versionName='%s'" % (state["package_id"], code, name))
elif tool == "apksigner":
    if args[:2] != ["verify", "--print-certs"] or len(args) != 3:
        raise SystemExit(98)
    before = args[2].replace("\\", "/").endswith("/installed-before.apk")
    digest = state["pre_signer"] if before else state.get("post_signer", state["target_signer"])
    print("Signer #1 certificate SHA-256 digest: " + digest)
else:
    raise SystemExit(99)

state_path.write_text(json.dumps(state, sort_keys=True), encoding="utf-8")
'''


def _expected_calls() -> list[dict[str, object]]:
    adb = lambda argv: {"tool": "adb", "argv": ["-s", SERIAL, *argv]}
    snapshot = [
        adb(["devices"]),
        adb(["shell", "am", "get-current-user"]),
        adb(["shell", "pm", "list", "packages", "--user", "0", PACKAGE_ID]),
        adb(["shell", "getprop", "ro.build.fingerprint"]),
        adb(["shell", "cmd", "thermalservice", "get-status"]),
        adb(["shell", "pm", "path", "--user", "0", PACKAGE_ID]),
        adb(["shell", "stat", "-c", "%s", "<REMOTE>"]),
    ]
    return [
        {"tool": "aapt", "argv": ["dump", "badging", "<TARGET>"]},
        {"tool": "apksigner", "argv": ["verify", "--print-certs", "<TARGET>"]},
        *snapshot,
        adb(["pull", "<REMOTE>", "<PRE_PULL>"]),
        {"tool": "aapt", "argv": ["dump", "badging", "<PRE_PULL>"]},
        {"tool": "apksigner", "argv": ["verify", "--print-certs", "<PRE_PULL>"]},
        adb(["install", "--user", "0", "-r", "<TARGET>"]),
        *snapshot,
        adb(["pull", "<REMOTE>", "<POST_PULL>"]),
        {"tool": "aapt", "argv": ["dump", "badging", "<POST_PULL>"]},
        {"tool": "apksigner", "argv": ["verify", "--print-certs", "<POST_PULL>"]},
    ]


def _write_wrapper(root: Path, fake_script: Path, state_path: Path, tool: str) -> Path:
    if os.name == "nt":
        wrapper = root / f"fake-{tool}.cmd"
        wrapper.write_text(
            f'@echo off\r\n"{sys.executable}" "{fake_script}" "{tool}" "{state_path}" %*\r\n',
            encoding="utf-8",
        )
    else:
        wrapper = root / f"fake-{tool}"
        quote = lambda value: "'" + str(value).replace("'", "'\\''") + "'"
        wrapper.write_text(
            "#!/bin/sh\nexec " + quote(sys.executable) + " " + quote(fake_script) + " " + quote(tool) + " " + quote(state_path) + ' "$@"\n',
            encoding="utf-8",
        )
        wrapper.chmod(0o755)
    return wrapper


class FakeTools:
    def __init__(self, root: Path, *, overrides: dict[str, object] | None = None) -> None:
        self.root = root
        self.target_apk = root / "candidate.apk"
        self.target_apk.write_bytes(b"target-apk-content-v2")
        self.pre_apk = root / "installed.apk"
        self.pre_apk.write_bytes(b"installed-apk-content-v1")
        self.script = root / "fake_tool.py"
        self.script.write_text(FAKE_TOOL, encoding="utf-8")
        self.state_path = root / "state.json"
        state: dict[str, object] = {
            "serial": SERIAL,
            "package_id": PACKAGE_ID,
            "firmware": FIRMWARE,
            "remote_apk": REMOTE_APK,
            "target_apk": str(self.target_apk),
            "pre_apk": str(self.pre_apk),
            "target_signer": SIGNER_SHA256,
            "pre_signer": SIGNER_SHA256,
            "target_version_code": 2,
            "target_version_name": "2.0",
            "pre_version_code": 1,
            "pre_version_name": "1.0",
            "calls": [],
            "expected_calls": _expected_calls(),
        }
        state.update(overrides or {})
        self.state_path.write_text(json.dumps(state, sort_keys=True), encoding="utf-8")
        self.adb = _write_wrapper(root, self.script, self.state_path, "adb")
        self.aapt = _write_wrapper(root, self.script, self.state_path, "aapt")
        self.apksigner = _write_wrapper(root, self.script, self.state_path, "apksigner")

    def state(self) -> dict[str, object]:
        return json.loads(self.state_path.read_text(encoding="utf-8"))


def _authority() -> AuthoritySnapshot:
    return AuthoritySnapshot(
        root=ROOT,
        files={"app/build.gradle.kts": b"fake frozen source"},
        missing=(),
        repository_identity={"git_commit_sha256": "d" * 64, "dirty_diff_sha256": "e" * 64, "untracked_identity_sha256": "f" * 64, "untracked_count": 0},
    )


def _payload(tools: FakeTools) -> dict[str, object]:
    return {
        "adb_executable": str(tools.adb),
        "apksigner_executable": str(tools.apksigner),
        "aapt_executable": str(tools.aapt),
        "apk_path": str(tools.target_apk),
        "device_serial": SERIAL,
        "android_user": 0,
        "package_id": PACKAGE_ID,
        "expected_build_identity": {"version_code": 2, "version_name": "2.0"},
        "expected_signer_sha256": SIGNER_SHA256,
        "expected_firmware_sha256": FIRMWARE_SHA256,
        "max_duration_seconds": 30,
        "max_storage_bytes": 1024 * 1024,
    }


def _spec(tools: FakeTools, authority: AuthoritySnapshot) -> dict[str, object]:
    apk_sha256 = hashlib.sha256(tools.target_apk.read_bytes()).hexdigest()
    source_sha256 = fingerprint_snapshot(authority)["authority_sha256"]
    return {
        "run_id": RUN_ID,
        "created_at": "2026-08-14T00:00:00Z",
        "content_sha256": SPEC_SHA256,
        "budgets": {"max_duration_seconds": 30, "max_storage_bytes": 1024 * 1024},
        "device_requirements": {
            "required": True,
            "device_alias": device_alias(SERIAL),
            "android_user": 0,
            "package_id": PACKAGE_ID,
            "build_identity": {
                "version_code": 2,
                "version_name": "2.0",
                "apk_sha256": apk_sha256,
                "source_sha256": source_sha256,
            },
            "signer_sha256": SIGNER_SHA256,
            "firmware_sha256": FIRMWARE_SHA256,
        },
    }


class BuildInstallExecutionTests(unittest.TestCase):
    def _run(self, root: Path, tools: FakeTools) -> dict[str, object]:
        experiment = root / "experiment"
        ExperimentStore(experiment).prepare(execute=True)
        authority = _authority()
        return execute(
            "pl_build_install",
            _payload(tools),
            store=RecordStore(experiment),
            experiment_spec=_spec(tools, authority),
            repository=ROOT,
            authority_snapshot=authority,
        )

    def test_update_only_install_verifies_pre_and_post_identity_with_exact_argv(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            root = Path(temp_name)
            tools = FakeTools(root)
            result = self._run(root, tools)
            record = result["records"][0]
            validate_record(record)
            self.assertEqual(record["payload"]["status"], "COMPLETE")
            self.assertEqual(record["payload"]["confounders"], [])
            self.assertEqual(record["payload"]["build_identity"]["apk_sha256"], hashlib.sha256(tools.target_apk.read_bytes()).hexdigest())
            self.assertEqual(record["payload"]["actual_configuration"]["preinstall_build_identity"]["version_code"], 1)
            self.assertEqual(record["payload"]["actual_configuration"]["postinstall_build_identity"]["version_code"], 2)
            serialized = json.dumps(record, sort_keys=True)
            self.assertNotIn(SERIAL, serialized)
            self.assertNotIn(str(root), serialized)

            state = tools.state()
            self.assertEqual(state["calls"], state["expected_calls"])
            install_calls = [call for call in state["calls"] if call["tool"] == "adb" and call["argv"][2] == "install"]
            self.assertEqual(install_calls, [{"tool": "adb", "argv": ["-s", SERIAL, "install", "--user", "0", "-r", "<TARGET>"]}])
            forbidden = {"uninstall", "clear", "reboot", "root", "disable-verity", "remount", "settings"}
            self.assertFalse(any(forbidden.intersection(call["argv"]) for call in state["calls"]))
            self.assertFalse(any(path.name.startswith(".pl-build-install-") for path in (root / "experiment").iterdir()))

    def test_installed_signer_mismatch_stops_before_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            root = Path(temp_name)
            tools = FakeTools(root, overrides={"pre_signer": OTHER_SIGNER_SHA256})
            with self.assertRaises(IdentityMismatch):
                self._run(root, tools)
            calls = tools.state()["calls"]
            self.assertFalse(any(call["tool"] == "adb" and "install" in call["argv"] for call in calls))

    def test_multiple_online_devices_stop_before_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            root = Path(temp_name)
            tools = FakeTools(root, overrides={"extra_online": True})
            with self.assertRaises(IdentityMismatch):
                self._run(root, tools)
            calls = tools.state()["calls"]
            self.assertEqual(calls[-1]["argv"], ["-s", SERIAL, "devices"])
            self.assertFalse(any("install" in call["argv"] for call in calls))

    def test_missing_existing_package_is_not_replaced_by_fresh_install(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            root = Path(temp_name)
            tools = FakeTools(root, overrides={"package_present": False})
            with self.assertRaises(IdentityMismatch):
                self._run(root, tools)
            self.assertFalse(any("install" in call["argv"] for call in tools.state()["calls"]))

    def test_offline_target_is_blocked_not_treated_as_absent_app(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            root = Path(temp_name)
            tools = FakeTools(root, overrides={"target_state": "offline"})
            with self.assertRaises(EvidenceBlocked):
                self._run(root, tools)
            self.assertFalse(any("install" in call["argv"] for call in tools.state()["calls"]))

    def test_storage_budget_is_checked_before_any_tool_process(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            root = Path(temp_name)
            tools = FakeTools(root)
            payload = _payload(tools)
            payload["max_storage_bytes"] = 1
            experiment = root / "experiment"
            ExperimentStore(experiment).prepare(execute=True)
            authority = _authority()
            with self.assertRaises(EvidenceBlocked):
                execute(
                    "pl_build_install",
                    payload,
                    store=RecordStore(experiment),
                    experiment_spec=_spec(tools, authority),
                    repository=ROOT,
                    authority_snapshot=authority,
                )
            self.assertEqual(tools.state()["calls"], [])

    def test_all_executables_are_resolved_before_any_tool_process(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            root = Path(temp_name)
            tools = FakeTools(root)
            payload = _payload(tools)
            payload["apksigner_executable"] = str(root / "missing-apksigner")
            experiment = root / "experiment"
            ExperimentStore(experiment).prepare(execute=True)
            authority = _authority()
            with self.assertRaises(MissingPrerequisite):
                execute(
                    "pl_build_install",
                    payload,
                    store=RecordStore(experiment),
                    experiment_spec=_spec(tools, authority),
                    repository=ROOT,
                    authority_snapshot=authority,
                )
            self.assertEqual(tools.state()["calls"], [])

    def test_post_install_thermal_change_is_recorded_as_blocked_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            root = Path(temp_name)
            tools = FakeTools(root, overrides={"post_thermal": 2})
            result = self._run(root, tools)
            record = result["records"][0]
            self.assertEqual(record["payload"]["status"], "BLOCKED")
            self.assertEqual(record["payload"]["confounders"], ["post_install_thermal_state"])
            self.assertEqual(record["payload"]["thermals"]["post_install_status"], 2)


if __name__ == "__main__":
    unittest.main()
