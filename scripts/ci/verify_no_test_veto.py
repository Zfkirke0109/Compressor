#!/usr/bin/env python3
"""Gate the packaged app against a candidate veto that could refuse anything.

CandidateVeto is the seam an instrumented test uses to refuse a first full encode
(SaferRungFallbackDeviceTest). The app has one implementation of it, the lambda that is
CandidateVeto.NONE and refuses nothing. A second implementation in the app's DEX would be a way to
refuse candidates that ships to users, so this fails the build on one.

Reads the packaged DEX, so generated code cannot hide what a source-level check misses. Pass the
APP apk only: the instrumentation apk holds the test's refusing veto by design.
"""
import argparse
import json
import re
import struct
import zipfile

VETO = 'Lcompress/joshattic/us/CandidateVeto;'
# The SAM lambda the compiler synthesizes for `val NONE = CandidateVeto { _, _ -> null }`.
ALLOWED_PREFIX = 'Lcompress/joshattic/us/CandidateVeto$Companion$'
NO_INDEX = 0xFFFFFFFF


def dex_classes(blob):
    """(descriptor, [interface descriptors]) for every class defined in one DEX file."""
    def u16(off):
        if off < 0 or off + 2 > len(blob): raise ValueError('truncated DEX u16')
        return struct.unpack_from('<H', blob, off)[0]
    def u32(off):
        if off < 0 or off + 4 > len(blob): raise ValueError('truncated DEX u32')
        return struct.unpack_from('<I', blob, off)[0]
    def uleb(off):
        value = 0
        for shift in range(0, 35, 7):
            if off >= len(blob): raise ValueError('truncated DEX ULEB')
            byte = blob[off]; off += 1
            value |= (byte & 127) << shift
            if byte < 128: return value, off
        raise ValueError('oversized DEX ULEB')
    if len(blob) < 112 or not re.fullmatch(rb'dex\n0(35|37|38|39|40)\x00', blob[:8]):
        raise ValueError('unsupported or invalid DEX header')
    if u32(32) != len(blob) or u32(40) != 0x12345678:
        raise ValueError('DEX size/endian mismatch')
    string_count, string_off = u32(56), u32(60)
    def string(index):
        if index >= string_count: raise ValueError('invalid DEX string index')
        _, off = uleb(u32(string_off + index * 4))
        end = blob.find(b'\0', off)
        if end < 0: raise ValueError('unterminated DEX string')
        return blob[off:end].decode('utf-8', errors='backslashreplace')
    types_count, types_off = u32(64), u32(68)
    def descriptor(index):
        if index >= types_count: raise ValueError('invalid DEX type index')
        return string(u32(types_off + index * 4))
    classes = []
    for c in range(u32(96)):
        off = u32(100) + c * 32
        interfaces_off = u32(off + 12)
        interfaces = []
        if interfaces_off:
            for i in range(u32(interfaces_off)):
                interfaces.append(descriptor(u16(interfaces_off + 4 + i * 2)))
        classes.append((descriptor(u32(off)), interfaces))
    return classes


def audit(classes):
    """What the app's classes say about the veto: whether it is there, and who implements it."""
    implementers = sorted(name for name, interfaces in classes if VETO in interfaces)
    return {
        'vetoDeclared': any(name == VETO for name, _ in classes),
        'implementers': implementers,
        'unexpected': [name for name in implementers if not name.startswith(ALLOWED_PREFIX)],
    }


def verdict(report):
    """Why this app must not ship, or None. An app without the veto at all is not what was built."""
    if not report['vetoDeclared']:
        return 'CandidateVeto is not in this APK: wrong file, or the seam was renamed without this gate'
    if report['unexpected']:
        return 'the app holds a candidate veto other than NONE: ' + ', '.join(report['unexpected'])
    if len(report['implementers']) != 1:
        return 'expected exactly one candidate veto (NONE), found %d: %s' % (
            len(report['implementers']), ', '.join(report['implementers']) or 'none')
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', nargs='+')
    args = parser.parse_args()
    bad = False
    for apk in args.apk:
        with zipfile.ZipFile(apk) as archive:
            if 'AndroidManifest.xml' not in archive.namelist(): raise ValueError('not an APK: ' + apk)
            names = [n for n in archive.namelist() if re.fullmatch(r'classes\d*\.dex', n)]
            if not names: raise ValueError('APK has no DEX files')
            classes = [c for n in names for c in dex_classes(archive.read(n))]
        report = audit(classes)
        failure = verdict(report)
        bad |= failure is not None
        print(json.dumps(dict(report, apk=apk, classes=len(classes), failure=failure), indent=2))
    return int(bad)


if __name__ == '__main__': raise SystemExit(main())
