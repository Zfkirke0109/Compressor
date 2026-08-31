#!/usr/bin/env python3
"""Fail the build if any packaged native library is not 16 KB page-size compatible.

Android 15 introduced devices with a 16 KB memory page size, and from Android 16 an app whose
native libraries are not 16 KB compatible can fail to load them. Two independent things must hold,
and checking only one gives false confidence:

  1. **ELF segment alignment.** Every PT_LOAD segment must be aligned to at least 16384. NDK r27
     does not do this by default (r28+ does), so this project passes `-Wl,-z,max-page-size=16384`
     explicitly in CMakeLists.txt. A toolchain change that silently dropped that flag would be
     invisible without this check.
  2. **ZIP alignment inside the APK.** The .so must be STORED (uncompressed) and its file data must
     begin on a 16384-byte boundary, so the loader can mmap it straight out of the APK.

This also covers native libraries this project does not build — `libandroidx.graphics.path.so`
arrives transitively through Compose — because a dependency bump can regress the guarantee just as
easily as a toolchain change can.

Usage: python3 verify_native_alignment.py <apk> [<apk> ...]
Exit:  0 all packaged .so files compatible, 1 otherwise, 2 bad input.
"""

from __future__ import annotations

import struct
import sys
import zipfile

PAGE = 16384
ELF_MAGIC = b"\x7fELF"
PT_LOAD = 1


def load_segment_alignments(blob: bytes) -> list[int]:
    """PT_LOAD p_align values from a 64-bit little-endian ELF. Empty if it is not one."""
    if not blob.startswith(ELF_MAGIC) or blob[4] != 2 or blob[5] != 1:
        return []  # only ELF64 LSB is produced for arm64-v8a
    e_phoff = struct.unpack_from("<Q", blob, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", blob, 0x36)[0]
    e_phnum = struct.unpack_from("<H", blob, 0x38)[0]
    aligns = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if struct.unpack_from("<I", blob, off)[0] == PT_LOAD:
            aligns.append(struct.unpack_from("<Q", blob, off + 0x30)[0])
    return aligns


def data_offset(raw: bytes, info: zipfile.ZipInfo) -> int:
    """Absolute offset of an entry's file data, past its local header."""
    h = info.header_offset
    name_len, extra_len = struct.unpack_from("<HH", raw, h + 26)
    return h + 30 + name_len + extra_len


def check(apk_path: str) -> bool:
    raw = open(apk_path, "rb").read()
    ok = True
    with zipfile.ZipFile(apk_path) as z:
        sos = [i for i in z.infolist() if i.filename.endswith(".so")]
        if not sos:
            print("  no native libraries packaged")
            return True
        for info in sos:
            aligns = load_segment_alignments(z.read(info))
            off = data_offset(raw, info)
            zip_ok = off % PAGE == 0
            stored = info.compress_type == zipfile.ZIP_STORED
            entry_ok = bool(aligns) and all(a >= PAGE for a in aligns) and zip_ok and stored
            ok = ok and entry_ok
            print(f"  {'PASS' if entry_ok else 'FAIL'}  {info.filename}")
            print(f"        PT_LOAD align: {[hex(a) for a in aligns] or 'none parsed'}")
            print(f"        zip: {'STORED' if stored else 'DEFLATED'} at {off}"
                  f" ({'16K aligned' if zip_ok else 'NOT 16K aligned'})")
    return ok


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    all_ok = True
    for path in argv[1:]:
        print(f"{path}:")
        all_ok = check(path) and all_ok
    print()
    print("16 KB native compatibility:", "PASS" if all_ok else "FAIL")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
