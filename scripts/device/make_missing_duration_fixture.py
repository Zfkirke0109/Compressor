#!/usr/bin/env python3
"""Build synthetic MP4 fixtures whose container carries no track duration (b177 WP3).

Two files, both generated from ffmpeg's `testsrc2` pattern (no private media):

* ``zero_track_duration.mp4``: a normal MP4 whose ``mvhd``, ``tkhd`` and ``mdhd`` duration fields
  are patched to 0, the shape that made ``MediaExtractorSyncIndex`` clamp every window to 0 before
  75e3b96;
* ``fragmented_no_duration.mp4``: an ``empty_moov`` fragmented MP4, whose moov carries no sample
  table and no durations at all.

``PipelineDeviceTest`` reads them from the test APK's assets and checks that certification windows
land where they should on a real ``MediaExtractor``. JVM tests cannot run the platform extractor,
which is why these are device fixtures.

Usage: python3 make_missing_duration_fixture.py OUT_DIR
"""
from __future__ import annotations

import os
import struct
import subprocess
import sys

CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl", b"edts", b"udta", b"mvex"}


def patch_durations(data: bytearray) -> list[str]:
    """Zero the duration of every mvhd/tkhd/mdhd box in place; returns what was patched."""
    patched = []

    def walk(start: int, end: int) -> None:
        pos = start
        while pos + 8 <= end:
            size, kind = struct.unpack(">I4s", data[pos:pos + 8])
            header = 8
            if size == 1:
                size = struct.unpack(">Q", data[pos + 8:pos + 16])[0]
                header = 16
            elif size == 0:
                size = end - pos
            if size < header:
                return
            body = pos + header
            if kind in CONTAINERS:
                walk(body, pos + size)
            elif kind in (b"mvhd", b"tkhd", b"mdhd"):
                version = data[body]
                if kind == b"tkhd":
                    # version, flags, created, modified, track id, reserved, duration
                    off = body + (4 + 8 + 8 + 4 + 4 if version == 1 else 4 + 4 + 4 + 4 + 4)
                else:
                    # version, flags, created, modified, timescale, duration
                    off = body + (4 + 8 + 8 + 4 if version == 1 else 4 + 4 + 4 + 4)
                width = 8 if version == 1 else 4
                data[off:off + width] = b"\x00" * width
                patched.append(kind.decode())
            pos += size

    walk(0, len(data))
    return patched


def main() -> int:
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    base = os.path.join(out, "_base.mp4")
    src = ["-f", "lavfi", "-i", "testsrc2=size=320x240:rate=10:duration=12"]
    enc = ["-c:v", "libx264", "-preset", "veryfast", "-g", "20", "-pix_fmt", "yuv420p"]
    subprocess.run(["ffmpeg", "-v", "error", "-y", *src, *enc, base], check=True)
    data = bytearray(open(base, "rb").read())
    patched = patch_durations(data)
    if sorted(set(patched)) != ["mdhd", "mvhd", "tkhd"]:
        raise SystemExit(f"unexpected boxes patched: {patched}")
    with open(os.path.join(out, "zero_track_duration.mp4"), "wb") as fh:
        fh.write(data)
    os.remove(base)
    subprocess.run(["ffmpeg", "-v", "error", "-y", *src, *enc, "-movflags", "frag_keyframe+empty_moov",
                    os.path.join(out, "fragmented_no_duration.mp4")], check=True)
    print(f"patched {patched}; wrote fixtures to {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
