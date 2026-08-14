"""Repo-contained entrypoint for Compressor PL Lab host hooks."""

from __future__ import annotations

from pathlib import Path
import sys


LAB_ROOT = Path(__file__).resolve().parents[1]
if str(LAB_ROOT) not in sys.path:
    sys.path.insert(0, str(LAB_ROOT))

from pl_lab.hooks import dispatch_main  # noqa: E402


if __name__ == "__main__":
    raise SystemExit(dispatch_main())
