#!/usr/bin/env python3
"""The source features must describe the video track everything else inspects and scores."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import source_window_features as features

HAVE_FFMPEG = bool(shutil.which('ffmpeg') and shutil.which('ffprobe'))


def ffmpeg(*args):
    subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-y', *map(str, args)], check=True, stdin=subprocess.DEVNULL)


class SourceWindowFeaturesTest(unittest.TestCase):
    def test_the_decode_maps_the_stream_whose_geometry_was_inspected(self):
        command = features.decode_command(Path('clip.mp4'), 160, 90)
        at = command.index('-i')
        # Mapped as an output option, right after the input it selects from.
        self.assertEqual(command[at + 1:at + 4], ['clip.mp4', '-map', '0:v:0'])
        self.assertEqual(command.count('-map'), 1)
        # Never waits on, or consumes, the caller's stdin.
        self.assertIn('-nostdin', command)

    @unittest.skipUnless(HAVE_FFMPEG, 'needs ffmpeg and ffprobe')
    def test_a_larger_second_video_track_does_not_replace_the_first(self):
        # FFmpeg's own choice among several video streams is the one of highest resolution. Here
        # that is a flat grey 640x480 track; the first track, the one ffprobe is asked about and
        # the scorer maps, is a moving 160x120 pattern.
        work = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, work, True)
        first, second, both = work / 'first.mp4', work / 'second.mp4', work / 'both.mp4'
        encode = ['-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-an']
        ffmpeg('-f', 'lavfi', '-i', 'testsrc2=size=160x120:rate=10:duration=6', *encode, first)
        ffmpeg('-f', 'lavfi', '-i', 'color=c=gray:size=640x480:rate=10:duration=6', *encode, second)
        ffmpeg('-i', first, '-i', second, '-map', '0:v:0', '-map', '1:v:0', '-c', 'copy', both)
        streams = subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'v', '-show_entries', 'stream=width',
                                           '-of', 'csv=p=0', str(both)], text=True, stdin=subprocess.DEVNULL).split()
        self.assertEqual(streams, ['160', '640'])

        measured = features.measure(both)
        alone = features.measure(first)
        flat = features.measure(second)
        self.assertEqual(measured['featureRaster'], [160, 120])
        self.assertEqual(measured['windows'], alone['windows'])
        self.assertEqual(measured['selected'], alone['selected'])
        # The other track would have read as a different, motionless clip.
        self.assertNotEqual(measured['windows'], flat['windows'])
        self.assertTrue(all(w['motion'] == 0 for w in flat['windows'][1:]))
        self.assertTrue(any(w['motion'] > 0 for w in measured['windows']))


if __name__ == '__main__':
    unittest.main()
