#!/usr/bin/env python3
import struct
import unittest
from verify_dex_method_budget import dex_methods, violations


def dex(units=4, descriptor=b'Lcompress/joshattic/us/Work$run$1;'):
    # Minimal little-endian DEX containing one class, one encoded direct method and code item.
    blob = bytearray(512 + units * 2)
    blob[:8] = b'dex\n035\0'
    struct.pack_into('<III', blob, 32, len(blob), 112, 0x12345678)
    for off, count, where in [(56,2,112),(64,1,120),(88,1,124),(96,1,132)]:
        struct.pack_into('<II',blob,off,count,where)
    struct.pack_into('<II',blob,112,200,260)
    struct.pack_into('<I',blob,120,0)
    struct.pack_into('<HHI',blob,124,0,0,1)
    struct.pack_into('<I',blob,132+24,300)
    blob[200:201+len(descriptor)+1] = bytes([len(descriptor)])+descriptor+b'\0'
    name=b'\x0dinvokeSuspend\0'
    blob[260:260+len(name)] = name
    blob[300:308] = b'\0\0\1\0\0\1\x80\x04'
    # Use code at 384, ULEB 0x80 0x03, with actual instruction bytes in-bounds.
    blob[306:308] = b'\x80\x03'
    struct.pack_into('<I',blob,396,units)
    return bytes(blob)


class DexBudgetTest(unittest.TestCase):
    def test_generated_suspend_method_is_measured_in_code_units(self):
        rows=dex_methods(dex(12000))
        self.assertEqual(rows[0]['codeUnits'],12000)
        self.assertIn('invokeSuspend',rows[0]['method'])
        self.assertEqual(len(violations(rows,10000)),1)
    def test_small_method_and_external_dependencies_are_not_blocked(self):
        self.assertFalse(violations(dex_methods(dex(10)),10000))
        self.assertFalse(violations(dex_methods(dex(12000,b'Lexternal/Library;')),10000))
    def test_truncated_dex_is_not_a_silent_pass(self):
        with self.assertRaises(ValueError): dex_methods(dex()[:150])
    def test_non_dex_is_not_a_silent_pass(self):
        with self.assertRaises(ValueError): dex_methods(b'not dex')

if __name__=='__main__': unittest.main()
