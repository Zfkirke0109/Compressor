from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "sync_plugin_payloads.py"
SPEC = importlib.util.spec_from_file_location("pl_lab_sync_plugin_payloads", SCRIPT)
if SPEC is None or SPEC.loader is None:  # pragma: no cover - import machinery failure
    raise RuntimeError(f"cannot load {SCRIPT}")
SYNC = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SYNC)


class SyncGeneratorTests(unittest.TestCase):
    def test_generated_wrappers_disable_bytecode_before_runtime_import(self) -> None:
        for wrapper in (SYNC.PLUGIN_ENTRYPOINT, SYNC.PLUGIN_HOOK_ENTRYPOINT):
            text = wrapper.decode("utf-8")
            self.assertIn("sys.dont_write_bytecode = True", text)
            self.assertLess(
                text.index("sys.dont_write_bytecode = True"),
                text.index("from pl_lab."),
            )

    def test_stable_capture_rejects_hard_linked_source(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.txt"
            linked = root / "linked.txt"
            source.write_bytes(b"immutable")
            os.link(source, linked)
            with self.assertRaisesRegex(RuntimeError, "single-link regular file"):
                SYNC._stable_bytes(source, root=root)

    def test_safe_chain_rejects_reparse_or_symlink_component(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            outside = root / "outside"
            outside.mkdir()
            link = root / "link"
            try:
                link.symlink_to(outside, target_is_directory=True)
            except (OSError, NotImplementedError):
                self.skipTest("directory symlinks are unavailable to this account")
            with self.assertRaisesRegex(RuntimeError, "reparse point"):
                SYNC._assert_safe_chain(root, link / "payload.json", require_target=False)

    def test_expected_payload_capture_is_stable(self) -> None:
        first = SYNC._expected()
        second = SYNC._expected()
        self.assertEqual(first, second)
        self.assertIn(Path("scripts/pl_lab.py"), first)
        self.assertIn(Path("scripts/hook_dispatch.py"), first)
        self.assertIn(Path("docs/DESIGN.md"), first)
        self.assertIn(Path("docs/SCHEMA_VERIFICATION.md"), first)
        self.assertIn(Path("docs/PHASE5_VERIFICATION.md"), first)


if __name__ == "__main__":
    unittest.main()
