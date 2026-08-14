"""Generated relocation-safe entrypoint for the bundled PL lab hooks."""

from __future__ import annotations

from pathlib import Path
import sys


sys.dont_write_bytecode = True
RUNTIME_ROOT = Path(__file__).resolve().parents[1] / "runtime"
if str(RUNTIME_ROOT) not in sys.path:
    sys.path.insert(0, str(RUNTIME_ROOT))

from pl_lab.hooks import dispatch_main  # noqa: E402


if __name__ == "__main__":
    raise SystemExit(dispatch_main())
