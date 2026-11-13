#!/usr/bin/env python3
import struct
import unittest
from verify_no_test_veto import ALLOWED_PREFIX, VETO, audit, dex_classes, verdict

NONE = ALLOWED_PREFIX + '$ExternalSyntheticLambda0;'


def dex(classes):
    """A minimal little-endian DEX defining [classes]: (descriptor, [interface descriptors])."""
    strings = sorted({d for name, interfaces in classes for d in [name, *interfaces]})
    index = {s: i for i, s in enumerate(strings)}
    string_ids = 112
    type_ids = string_ids + 4 * len(strings)
    class_defs = type_ids + 4 * len(strings)
    data = class_defs + 32 * len(classes)
    blob = bytearray(data)
    lists = {}
    for name, interfaces in classes:
        if not interfaces: continue
        if len(blob) % 4: blob += b'\0' * (4 - len(blob) % 4)
        lists[name] = len(blob)
        blob += struct.pack('<I', len(interfaces)) + b''.join(struct.pack('<H', index[i]) for i in interfaces)
    offsets = []
    for s in strings:
        offsets.append(len(blob))
        blob += bytes([len(s)]) + s.encode() + b'\0'
    blob[:8] = b'dex\n035\0'
    struct.pack_into('<III', blob, 32, len(blob), 112, 0x12345678)
    struct.pack_into('<II', blob, 56, len(strings), string_ids)
    struct.pack_into('<II', blob, 64, len(strings), type_ids)
    struct.pack_into('<II', blob, 96, len(classes), class_defs)
    for i, off in enumerate(offsets):
        struct.pack_into('<I', blob, string_ids + 4 * i, off)
        struct.pack_into('<I', blob, type_ids + 4 * i, i)  # type i is string i
    for c, (name, _) in enumerate(classes):
        struct.pack_into('<I', blob, class_defs + 32 * c, index[name])
        struct.pack_into('<I', blob, class_defs + 32 * c + 12, lists.get(name, 0))
    return bytes(blob)


APP = [(VETO, []), ('Lcompress/joshattic/us/CandidateVeto$Companion;', []), (NONE, [VETO]),
       ('Lcompress/joshattic/us/BatchCompressorViewModel;', []), ('Lexternal/Runner;', ['Ljava/lang/Runnable;'])]


class NoTestVetoTest(unittest.TestCase):
    def check(self, classes):
        return verdict(audit(dex_classes(dex(classes))))

    def test_the_app_as_built_has_one_veto_and_it_is_none(self):
        self.assertEqual(sorted(dex_classes(dex(APP))), sorted(APP))
        report = audit(dex_classes(dex(APP)))
        self.assertEqual({'vetoDeclared': True, 'implementers': [NONE], 'unexpected': []}, report)
        self.assertIsNone(verdict(report))

    def test_a_second_veto_anywhere_in_the_app_fails(self):
        for intruder in ('Lcompress/joshattic/us/BatchMainActivity$$ExternalSyntheticLambda7;',
                         'Lcompress/joshattic/us/RefusingVeto;', 'Lthird/party/Hook;',
                         # Named like the test's own, as it would be if a test source leaked into main.
                         'Lcompress/joshattic/us/SaferRungFallbackDeviceTest$$ExternalSyntheticLambda0;'):
            with self.subTest(intruder=intruder):
                failure = self.check(APP + [(intruder, [VETO])])
                self.assertIn('other than NONE', failure)
                self.assertIn(intruder, failure)

    def test_a_second_lambda_beside_none_fails_too(self):
        failure = self.check(APP + [(ALLOWED_PREFIX + '$ExternalSyntheticLambda1;', [VETO])])
        self.assertIn('expected exactly one candidate veto (NONE), found 2', failure)

    def test_an_apk_without_the_veto_is_not_a_silent_pass(self):
        self.assertIn('not in this APK', self.check([c for c in APP if 'CandidateVeto' not in c[0]]))
        self.assertIn('found 0', self.check([c for c in APP if c[0] != NONE]))

    def test_an_implementation_is_found_among_several_interfaces(self):
        failure = self.check(APP + [('Lcompress/joshattic/us/Both;', ['Ljava/lang/Runnable;', VETO])])
        self.assertIn('Lcompress/joshattic/us/Both;', failure)

    def test_a_damaged_dex_is_not_a_silent_pass(self):
        with self.assertRaises(ValueError): dex_classes(dex(APP)[:150])
        with self.assertRaises(ValueError): dex_classes(b'not dex')


if __name__ == '__main__': unittest.main()
