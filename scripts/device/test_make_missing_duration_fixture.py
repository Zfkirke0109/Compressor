#!/usr/bin/env python3
"""The patcher zeroes exactly the three duration fields (runs ffmpeg/ffprobe when present)."""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import os

from make_missing_duration_fixture import patch_durations


def _box(kind: bytes, body: bytes) -> bytes:
    import struct
    return struct.pack(">I4s", 8 + len(body), kind) + body


def test_patch_zeroes_v0_durations_in_nested_boxes():
    import struct
    mvhd = _box(b"mvhd", b"\x00\x00\x00\x00" + struct.pack(">IIII", 1, 2, 1000, 12000) + b"\x00" * 80)
    tkhd = _box(b"tkhd", b"\x00\x00\x00\x07" + struct.pack(">IIIII", 1, 2, 1, 0, 12000) + b"\x00" * 60)
    mdhd = _box(b"mdhd", b"\x00\x00\x00\x00" + struct.pack(">IIII", 1, 2, 10, 120) + b"\x00" * 4)
    data = bytearray(_box(b"moov", mvhd + _box(b"trak", tkhd + _box(b"mdia", mdhd))))
    assert sorted(patch_durations(data)) == ["mdhd", "mvhd", "tkhd"]
    assert struct.unpack(">I", data[8 + 8 + 16:8 + 8 + 20])[0] == 0  # mvhd duration
    assert b"\x00\x00\x2e\xe0" not in bytes(data)  # 12000 is gone everywhere


def test_generated_fixture_reports_no_track_duration():
    if not (shutil.which("ffmpeg") and shutil.which("ffprobe")):
        print("SKIP test_generated_fixture_reports_no_track_duration: ffmpeg/ffprobe not on PATH")
        return "skip"
    out = tempfile.mkdtemp()
    try:
        subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), "make_missing_duration_fixture.py"), out],
                       check=True, capture_output=True)
        probe = json.loads(subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "stream=duration:format=duration", "-of", "json",
             os.path.join(out, "zero_track_duration.mp4")], capture_output=True, text=True, check=True).stdout)
        stream_d = (probe.get("streams") or [{}])[0].get("duration")
        assert stream_d in (None, "0.000000", "N/A") or float(stream_d) == 0.0, probe
        # The frames are all still there: 12 s at 10 fps.
        frames = subprocess.run(["ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
                                 "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0",
                                 os.path.join(out, "zero_track_duration.mp4")], capture_output=True, text=True, check=True).stdout
        assert int(frames.strip()) == 120, frames
    finally:
        shutil.rmtree(out, ignore_errors=True)


if __name__ == "__main__":
    failures = skips = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                if fn() == "skip":
                    skips += 1
                else:
                    print(f"PASS {name}")
            except Exception as exc:  # noqa: BLE001
                failures += 1
                print(f"FAIL {name}: {exc!r}")
    print(f"\n{'FAILED' if failures else 'OK'} ({failures} failure(s), {skips} skipped)")
    raise SystemExit(1 if failures else 0)
