"""Repo-contained test runner; no installation or external dependency required."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fast", action="store_true", help="run deterministic offline core/contract boundaries")
    args = parser.parse_args(argv)
    if args.fast:
        suite = unittest.TestSuite()
        for module in (
            "tests.test_foundation",
            "tests.test_contracts",
            "tests.test_corpus_manifest",
            "tests.test_records_spec_judge",
            "tests.test_process_workflow",
            "tests.test_hooks",
            "tests.test_plugin_packages",
        ):
            suite.addTests(unittest.defaultTestLoader.loadTestsFromName(module))
    else:
        suite = unittest.defaultTestLoader.discover(str(ROOT / "tests"), pattern="test_*.py")
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
