import contextlib
import io
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

import verify_native_alignment as gate


def elf(alignment=16384):
    data = bytearray(128)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<Q', data, 0x20, 64)
    struct.pack_into('<HH', data, 0x36, 56, 1)
    struct.pack_into('<I', data, 64, 1)
    struct.pack_into('<Q', data, 64 + 0x30, alignment)
    return data


class NativeAlignmentTest(unittest.TestCase):
    def check(self, entries):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'app.apk'
            with zipfile.ZipFile(path, 'w') as archive:
                for name, data, compressed in entries:
                    info = zipfile.ZipInfo(name)
                    info.compress_type = zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
                    padding = (-archive.fp.tell() - 30 - len(name.encode())) % gate.PAGE
                    if padding:
                        if padding < 4:
                            padding += gate.PAGE
                        info.extra = struct.pack('<HH', 0xcafe, padding - 4) + bytes(padding - 4)
                    archive.writestr(info, data)
            with contextlib.redirect_stdout(io.StringIO()):
                return gate.check(str(path))

    def test_compose_native_library_does_not_replace_the_production_scorer(self):
        self.assertFalse(self.check([('lib/arm64-v8a/libandroidx.graphics.path.so', elf(), False)]))

    def test_the_complete_aligned_arm64_set_passes(self):
        self.assertTrue(self.check([
            ('lib/arm64-v8a/libcompressorvmaf.so', elf(), False),
            ('lib/arm64-v8a/libandroidx.graphics.path.so', elf(), False)]))

    def test_scorer_in_the_wrong_abi_does_not_satisfy_the_arm64_app(self):
        self.assertFalse(self.check([('lib/x86_64/libcompressorvmaf.so', elf(), False)]))

    def test_a_present_scorer_still_needs_elf_and_zip_compatibility(self):
        self.assertFalse(self.check([('lib/arm64-v8a/libcompressorvmaf.so', elf(4096), False)]))
        self.assertFalse(self.check([('lib/arm64-v8a/libcompressorvmaf.so', elf(), True)]))

    def test_empty_native_packaging_is_not_a_pass(self):
        self.assertFalse(self.check([]))


if __name__ == '__main__':
    unittest.main()
