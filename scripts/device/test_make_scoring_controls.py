#!/usr/bin/env python3
"""The device scoring controls come out in profiles a phone decodes, and check themselves."""
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import make_scoring_controls as controls


def encoders():
    if not (shutil.which('ffmpeg') and shutil.which('ffprobe')): return ''
    return subprocess.run(['ffmpeg', '-hide_banner', '-encoders'], capture_output=True, text=True, stdin=subprocess.DEVNULL).stdout


HAVE_ENCODERS = all(name in encoders() for name in ('libx264', 'libx265'))
LOSSLESS_CONTROLS = ['lossless.mp4', 'internal_deletion.mp4', 'one_frame_offset.mp4', 'vfr_jitter.mp4', 'chroma_changed.mp4']


class RulesTest(unittest.TestCase):
    def test_the_profile_the_s26_decoder_stopped_on_is_refused(self):
        # What libx264 -qp 0 writes for 4:2:0 input, and what the November 9 parity run could not decode.
        with self.assertRaisesRegex(ValueError, 'High 4:4:4 Predictive'):
            controls.require_decodable('lossless.mp4', dict(codec='h264', profile='High 4:4:4 Predictive', pixFmt='yuv420p'))
        for codec, profile, pix in [('hevc', 'Main 10', 'yuv420p10le'), ('hevc', 'Rext', 'yuv444p'), ('h264', 'High', 'yuv444p'),
                                    ('h264', 'High 10', 'yuv420p10le'), ('hevc', None, 'yuv420p'), ('av1', 'Main', 'yuv420p')]:
            with self.subTest(codec=codec, profile=profile, pix=pix), self.assertRaises(ValueError):
                controls.require_decodable('control.mp4', dict(codec=codec, profile=profile, pixFmt=pix))
        for codec, profile in [('h264', 'High'), ('h264', 'Main'), ('h264', 'Constrained Baseline'), ('hevc', 'Main')]:
            controls.require_decodable('control.mp4', dict(codec=codec, profile=profile, pixFmt='yuv420p'))

    def test_a_control_whose_decoded_frames_are_not_the_expected_ones_is_refused(self):
        controls.require_pixels('lossless.mp4', [(0, 'a'), (33333, 'b')], ['a', 'b'], "the source's frames")
        for frames in ([(0, 'a'), (33333, 'c')], [(0, 'a')], [(0, 'a'), (33333, 'b'), (66667, 'b')], [(0, 'b'), (33333, 'a')]):
            with self.subTest(frames=frames), self.assertRaisesRegex(ValueError, "does not decode to the source's frames"):
                controls.require_pixels('lossless.mp4', frames, ['a', 'b'], "the source's frames")

    def test_the_lossless_recipe_is_not_the_one_that_writes_4_4_4(self):
        self.assertIn('libx265', controls.LOSSLESS)
        self.assertNotIn('libx264', controls.LOSSLESS)
        self.assertIn('yuv420p', controls.LOSSLESS)


@unittest.skipUnless(HAVE_ENCODERS, 'requires ffmpeg and ffprobe with libx264 and libx265')
class GeneratedCorpusTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.tmp.name)
        # make() raises if any profile or decoded frame is not what its control needs.
        cls.manifest = controls.make(cls.root)
        cls.files = {f['name']: f for f in cls.manifest['files']}

    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()

    def test_every_encoded_control_is_in_a_profile_a_phone_decodes(self):
        for name, entry in self.files.items():
            if name == 'corrupt.mp4': continue
            with self.subTest(name=name):
                self.assertIn((entry['codec'], entry['profile']), controls.DECODABLE_PROFILES)
                self.assertEqual('yuv420p', entry['pixFmt'])
                self.assertNotIn('4:4:4', entry['profile'])

    def test_the_lossless_controls_are_hevc_main(self):
        for name in LOSSLESS_CONTROLS:
            self.assertEqual(('hevc', 'Main'), (self.files[name]['codec'], self.files[name]['profile']), name)

    def test_identical_controls_decode_to_the_sources_frames_and_the_others_do_not(self):
        source = self.files['source.mp4']['decodedSha256']
        for name in ('remux.mp4', 'lossless.mp4', 'origin_shift.mp4', 'color_tags.mp4', 'vfr_jitter.mp4'):
            self.assertEqual(source, self.files[name]['decodedSha256'], name)
        for name in ('internal_deletion.mp4', 'one_frame_offset.mp4', 'chroma_changed.mp4'):
            self.assertNotEqual(source, self.files[name]['decodedSha256'], name)
        self.assertEqual((180, 179, 179), tuple(self.files[n]['frames'] for n in ('source.mp4', 'internal_deletion.mp4', 'one_frame_offset.mp4')))

    def test_the_manifest_is_still_what_the_device_test_reads(self):
        self.assertEqual(['identity', 'remux', 'lossless', 'pts-origin', 'internal-deletion', 'one-frame-offset', 'tag-mutation',
                          'vfr-jitter', 'motion-context', 'chroma-mutation', 'corrupt'], [c['id'] for c in self.manifest['cases']])
        self.assertEqual((1000000, 5000000, 250000), (self.manifest['startUs'], self.manifest['endUs'], self.manifest['contextUs']))
        for case in self.manifest['cases']:
            self.assertIn(case['reference'], self.files)
            self.assertIn(case['candidate'], self.files)
        for name, entry in self.files.items():
            data = (self.root / name).read_bytes()
            self.assertEqual((hashlib.sha256(data).hexdigest(), len(data)), (entry['sha256'], entry['bytes']), name)
        self.assertEqual(4096, self.files['corrupt.mp4']['bytes'])
        self.assertNotIn('codec', self.files['corrupt.mp4'])


if __name__ == '__main__': unittest.main()
