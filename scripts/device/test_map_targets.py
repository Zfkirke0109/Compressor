#!/usr/bin/env python3
"""Self-running tests for map_targets.py."""

from __future__ import annotations

import csv
import io
import os
import sys
import tempfile
from contextlib import redirect_stdout

from map_targets import main, name_hash


def test_the_hash_is_the_recorders_construction():
    # DiagnosticsRecorder.stableHash: SHA-1 over the salt bytes then the UTF-8 name, first 12 hex.
    # Real names are private, so a synthetic name is checked against the same construction.
    import hashlib
    expected = hashlib.sha1(b"galaxycompressor-diag-v1" + "clip.mp4".encode()).hexdigest()[:12]
    assert name_hash("clip.mp4") == expected


def test_local_files_are_matched_by_name_hash_and_size_is_checked():
    with tempfile.TemporaryDirectory() as d:
        targets = os.path.join(d, "t.csv")
        with open(targets, "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=["jobId", "nameHash", "class", "sourceBytes"])
            w.writeheader()
            w.writerow({"jobId": "job_a", "nameHash": name_hash("a.mp4"), "class": "certified_smaller", "sourceBytes": "3"})
            w.writerow({"jobId": "job_b", "nameHash": name_hash("b.mp4"), "class": "certified_smaller", "sourceBytes": "9"})
        os.makedirs(os.path.join(d, "media"))
        with open(os.path.join(d, "media", "a.mp4"), "wb") as f:
            f.write(b"abc")
        out = os.path.join(d, "m.csv")
        buf = io.StringIO()
        with redirect_stdout(buf):
            assert main([os.path.join(d, "media"), "--targets", targets, "--out", out]) == 0
        assert "1 of 2 target cases found; 1 missing" in buf.getvalue()
        rows = list(csv.DictReader(open(out)))
        assert rows[0]["jobId"] == "job_a" and rows[0]["sizeMatches"] == "True"


def _main() -> int:
    failed = 0
    tests = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_") and callable(f)]
    for name, fn in tests:
        try:
            fn()
            print(f"PASS {name}")
        except Exception as e:  # noqa: BLE001
            failed += 1
            print(f"FAIL {name}: {type(e).__name__}: {e}")
    print(f"{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(_main())
