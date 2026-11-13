#!/usr/bin/env python3
"""Gate generated app methods, including Kotlin suspend state machines, by DEX code units.

10,000 is a repository regression budget, not a claimed Android/ART specification limit.
Checks the actual packaged DEX, so a source-level line count cannot hide compiler growth.
"""
import argparse
import json
import re
import struct
import zipfile


def dex_methods(blob):
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
        # Identifiers here are ASCII in Compressor; preserve any MUTF-8 diagnostics safely.
        return blob[off:end].decode('utf-8', errors='backslashreplace')
    types_count, types_off = u32(64), u32(68)
    def descriptor(index):
        if index >= types_count: raise ValueError('invalid DEX type index')
        return string(u32(types_off + index * 4))
    methods_count, methods_off = u32(88), u32(92)
    rows = []
    for c in range(u32(96)):
        off = u32(100) + c * 32
        owner = descriptor(u32(off))
        cursor = u32(off + 24)
        if not cursor: continue
        sizes = []
        for _ in range(4):
            n, cursor = uleb(cursor); sizes.append(n)
        for _ in range(sizes[0] + sizes[1]):
            _, cursor = uleb(cursor); _, cursor = uleb(cursor)
        for count in sizes[2:]:
            index = 0  # delta encoding restarts for virtual methods
            for _ in range(count):
                delta, cursor = uleb(cursor); index += delta
                _, cursor = uleb(cursor); code, cursor = uleb(cursor)
                if index >= methods_count: raise ValueError('invalid DEX method index')
                if not code: continue
                units = u32(code + 12)
                if code + 16 + units * 2 > len(blob): raise ValueError('truncated DEX code item')
                name = string(u32(methods_off + index * 8 + 4))
                rows.append({'method': owner + '->' + name, 'codeUnits': units})
    return rows


def violations(rows, limit):
    return [r for r in rows if r['method'].startswith('Lcompress/joshattic/us/') and r['codeUnits'] > limit]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', nargs='+'); parser.add_argument('--limit', type=int, default=10000)
    args = parser.parse_args()
    bad = False
    for apk in args.apk:
        with zipfile.ZipFile(apk) as archive:
            names = [n for n in archive.namelist() if re.fullmatch(r'classes\d*\.dex', n)]
            if not names: raise ValueError('APK has no DEX files')
            rows = [dict(r, dex=n) for n in names for r in dex_methods(archive.read(n))]
        own = sorted((r for r in rows if r['method'].startswith('Lcompress/joshattic/us/')), key=lambda r: -r['codeUnits'])
        if not own: raise ValueError('no Compressor methods found')
        failed = violations(own, args.limit); bad |= bool(failed)
        print(json.dumps({'apk': apk, 'limitCodeUnits': args.limit, 'appMethods': len(own), 'largest': own[:15], 'violations': failed}, indent=2))
    return int(bad)

if __name__ == '__main__': raise SystemExit(main())
