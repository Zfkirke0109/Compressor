#!/usr/bin/env python3
"""A sample table and an edit list can count different frames in one file; both are reported."""
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest

import mp4_edit_timeline as timeline


def box(kind, payload=b''):
    return struct.pack('>I4s', 8 + len(payload), kind) + payload


def full(kind, version, payload):
    return box(kind, bytes([version, 0, 0, 0]) + payload)


def movie(path, timestamps, timescale=90000, movie_timescale=1_000_000, edits=None, sync=None, ctts_version=0,
          handler=b'vide', movie_duration=0, extra_tracks=b''):
    """A moov-only MP4 whose one track has these presentation timestamps, in stored (decode) order.

    Decode times are the sorted timestamps spread on a fixed grid; each sample's composition offset
    makes up the difference, as a real B-frame stream's does.
    """
    step = 1500
    decode = [i * step for i in range(len(timestamps))]
    stts = full(b'stts', 0, struct.pack('>III', 1, len(timestamps), step))
    ctts = full(b'ctts', ctts_version, struct.pack('>I', len(timestamps)) + b''.join(
        struct.pack('>Ii', 1, t - d) for t, d in zip(timestamps, decode)))
    stss = b'' if sync is None else full(b'stss', 0, struct.pack('>I', len(sync)) + b''.join(struct.pack('>I', i + 1) for i in sync))
    mdhd = full(b'mdhd', 0, struct.pack('>IIII', 0, 0, timescale, max(timestamps) + step if timestamps else 0) + b'\0\0\0\0')
    hdlr = full(b'hdlr', 0, b'\0\0\0\0' + handler + b'\0' * 13)
    mdia = box(b'mdia', mdhd + hdlr + box(b'minf', box(b'stbl', stts + ctts + stss)))
    tkhd = full(b'tkhd', 0, b'\0' * 80)
    edts = b''
    if edits is not None:
        edts = box(b'edts', full(b'elst', 1, struct.pack('>I', len(edits)) + b''.join(
            struct.pack('>QqI', duration, media_time, 0x00010000) for duration, media_time in edits)))
    mvhd = full(b'mvhd', 0, struct.pack('>IIII', 0, 0, movie_timescale, movie_duration) + b'\0' * 80)
    with open(path, 'wb') as f:
        f.write(box(b'ftyp', b'isom\0\0\0\0isom'))
        # mdat first, as a camera writes it: the reader has to skip it to reach moov.
        f.write(box(b'mdat', b'\0' * 64))
        f.write(box(b'moov', mvhd + box(b'trak', tkhd + edts + mdia) + extra_tracks))


def ticks(us, timescale=90000):
    return us * timescale // 1_000_000


class EditTimelineTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir, True)

    def path(self, name='m.mp4'):
        return os.path.join(self.dir, name)

    def test_without_an_edit_list_every_sample_is_presented(self):
        movie(self.path(), [0, 3000, 6000, 9000])
        e = timeline.evidence(self.path())
        self.assertEqual((e['tableSamples'], e['presentedSamples'], e['samplesAfterEditEnd']), (4, 4, 0))
        self.assertEqual((e['tableFirstUs'], e['tableLastUs'], e['presentedFirstUs']), (0, 100000, 0))
        self.assertEqual(e['edits'], [])
        self.assertEqual(e['tableDigest'], e['presentedDigest'])

    def test_an_empty_edit_delays_the_track_without_removing_anything(self):
        # b287 job_4e26f86286b1: source video starts 14,277 us after the audio; 1,236 frames in and out.
        stamps = [i * 3000 for i in range(12)]
        movie(self.path(), stamps, edits=[(14277, -1), (400000, 0)])
        e = timeline.evidence(self.path())
        self.assertEqual((e['tableSamples'], e['presentedSamples'], e['samplesAfterEditEnd']), (12, 12, 0))
        self.assertEqual((e['initialEmptyEditUs'], e['presentedFirstUs'], e['tableFirstUs']), (14277, 14277, 0))
        self.assertEqual(e['tableDigest'], e['presentedDigest'])

    def test_samples_that_start_after_the_edit_ends_are_in_the_table_and_not_in_the_presentation(self):
        # The shape that gives b287 job_782a717d3217's numbers: 168 samples about 39 ms apart, the
        # video delayed 97,488 us, and the presentation ending between the 166th and 167th frames.
        stamps = [ticks(i * 39_300) for i in range(168)]
        end_us = (165 * 39_300 + 166 * 39_300) // 2
        movie(self.path(), stamps, edits=[(97_488, -1), (end_us, 0)])
        e = timeline.evidence(self.path())
        self.assertEqual((e['tableSamples'], e['presentedSamples'], e['samplesAfterEditEnd']), (168, 166, 2))
        self.assertEqual((e['initialEmptyEditUs'], e['presentedFirstUs']), (97_488, 97_488))
        self.assertNotEqual(e['tableDigest'], e['presentedDigest'])
        self.assertEqual(e['presentedLastUs'], 97_488 + 165 * 39_300)

    def test_a_sample_starting_exactly_at_the_edit_end_is_kept(self):
        # Media3 keeps a clipped final frame: only a sample that starts AFTER the end is left out.
        stamps = [0, 3000, 6000, 9000]
        for end_ticks, kept in [(6000, 3), (5999, 2), (6001, 3), (9000, 4), (8999, 3)]:
            movie(self.path(), stamps, movie_timescale=90000, edits=[(end_ticks, 0)])
            self.assertEqual(timeline.evidence(self.path())['presentedSamples'], kept, end_ticks)

    def test_frames_stored_out_of_order_are_judged_by_their_presentation_time(self):
        # I P B B | P B B | P B B stored in decode order. The edit ends after frame 6 by time.
        stored = [0, 9000, 3000, 6000, 18000, 12000, 15000, 27000, 21000, 24000]
        movie(self.path(), stored, movie_timescale=90000, edits=[(18500, 0)], sync=[0])
        e = timeline.evidence(self.path())
        self.assertTrue(e['storedOutOfOrder'])
        # Presented: 0..18000 (seven frames). 21000, 24000 and 27000 start after the end.
        self.assertEqual((e['tableSamples'], e['presentedSamples'], e['samplesAfterEditEnd']), (10, 7, 3))
        self.assertEqual(e['presentedLastUs'], 200000)

    def test_one_edit_of_zero_duration_keeps_every_sample(self):
        movie(self.path(), [3000, 6000, 9000], edits=[(0, 3000)])
        e = timeline.evidence(self.path())
        self.assertEqual((e['presentedSamples'], e['presentedFirstUs']), (3, 0))

    def test_an_edit_that_starts_inside_a_group_of_pictures_keeps_the_frames_needed_to_decode_it(self):
        stamps = [i * 3000 for i in range(10)]
        movie(self.path(), stamps, movie_timescale=90000, edits=[(15000, 12000)], sync=[0, 6])
        e = timeline.evidence(self.path())
        # From the sync sample at or before the start (index 0) to the last sample starting by the end (index 9).
        self.assertEqual(e['presentedSamples'], 10)
        self.assertLess(e['presentedFirstUs'], 0)

    def test_the_first_changed_stage_says_whether_the_missing_frames_were_ever_presented(self):
        stamps = [ticks(i * 39_300) for i in range(168)]
        end_us = (165 * 39_300 + 166 * 39_300) // 2
        movie(self.path('source.mp4'), stamps, edits=[(97_488, -1), (end_us, 0)])
        # What a Transformer export of it holds: the 166 presented frames, the first one at zero
        # (Media3's muxer), the rest at their presentation times.
        exported = [0] + [ticks(97_488 + i * 39_300) for i in range(1, 166)]
        movie(self.path('media3.mp4'), exported)
        movie(self.path('remux.mp4'), exported)
        records = [timeline.evidence(self.path(n)) for n in ('source.mp4', 'media3.mp4', 'remux.mp4')]
        stage, reason = timeline.first_change(records)
        self.assertEqual(stage, 1)
        self.assertIn('168 samples in the table before, 166 after', reason)
        self.assertIn("equal to what the earlier file's edit list presents (166)", reason)
        # The same counts from a source that presents all 168: two frames really are gone.
        movie(self.path('whole.mp4'), stamps, edits=[(97_488, -1), (7_000_000, 0)])
        stage, reason = timeline.first_change([timeline.evidence(self.path('whole.mp4')), records[1]])
        self.assertEqual(stage, 1)
        self.assertNotIn('edit list presents', reason)
        # Lost in the remux instead: the first two stages agree, the third does not.
        movie(self.path('short.mp4'), exported[:-2])
        stage, reason = timeline.first_change([records[1], records[2], timeline.evidence(self.path('short.mp4'))])
        self.assertEqual((stage, reason), (2, '166 samples in the table before, 164 after'))
        self.assertIsNone(timeline.first_change([records[1], records[2]]))

    def test_a_first_frame_moved_to_zero_and_a_uniform_shift_are_told_apart(self):
        stamps = [ticks(14_277 + i * 33_366) for i in range(30)]
        movie(self.path('a.mp4'), stamps)
        movie(self.path('zeroed.mp4'), [0] + stamps[1:])
        movie(self.path('shifted.mp4'), [t - stamps[0] for t in stamps])
        a = timeline.evidence(self.path('a.mp4'))
        stage, reason = timeline.first_change([a, timeline.evidence(self.path('zeroed.mp4'))])
        self.assertEqual(stage, 1)
        # 14,277 us on a 90 kHz clock is 1,284 ticks, which reads back as 14,266 us.
        self.assertEqual(reason, 'only the first timestamp differs (14266 us before, 0 us after); every later frame is where it was')
        stage, reason = timeline.first_change([a, timeline.evidence(self.path('shifted.mp4'))])
        self.assertEqual(stage, 1)
        self.assertIn('same frames, origin moved by -142', reason)
        # A frame moved in the middle is neither.
        movie(self.path('retimed.mp4'), stamps[:10] + [stamps[10] + 900] + stamps[11:])
        stage, reason = timeline.first_change([a, timeline.evidence(self.path('retimed.mp4'))])
        self.assertEqual((stage, reason), (1, 'same count, frame 10 retimed by 10000 us relative to the first frame'))
        # The same file in another timescale differs only by rounding: no change.
        movie(self.path('micro.mp4'), [t * 1_000_000 // 90000 for t in stamps], timescale=1_000_000)
        self.assertIsNone(timeline.first_change([a, timeline.evidence(self.path('micro.mp4'))]))

    def test_the_video_track_is_found_among_others_and_two_are_refused(self):
        audio = box(b'trak', full(b'tkhd', 0, b'\0' * 80) + box(b'mdia', full(b'mdhd', 0, struct.pack('>IIII', 0, 0, 48000, 0) + b'\0\0\0\0')
                    + full(b'hdlr', 0, b'\0\0\0\0soun' + b'\0' * 13) + box(b'minf', box(b'stbl', full(b'stts', 0, struct.pack('>III', 1, 3, 1024))))))
        movie(self.path(), [0, 3000], extra_tracks=audio)
        self.assertEqual(timeline.evidence(self.path())['tableSamples'], 2)
        movie(self.path('audio_only.mp4'), [0, 3000], handler=b'soun')
        with self.assertRaises(ValueError): timeline.evidence(self.path('audio_only.mp4'))
        with open(self.path('empty.mp4'), 'wb') as f: f.write(box(b'ftyp', b'isom'))
        with self.assertRaises(ValueError): timeline.evidence(self.path('empty.mp4'))

    def test_the_cli_prints_one_record_per_file_and_the_first_change(self):
        movie(self.path('a.mp4'), [0, 3000, 6000])
        movie(self.path('b.mp4'), [0, 3000])
        script = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'mp4_edit_timeline.py')
        done = subprocess.run([sys.executable, script, '--compare', self.path('a.mp4'), self.path('b.mp4')],
                              capture_output=True, text=True, stdin=subprocess.DEVNULL)
        self.assertEqual(done.returncode, 0, done.stderr)
        lines = [json.loads(line) for line in done.stdout.splitlines()]
        self.assertEqual([line.get('tableSamples') for line in lines[:2]], [3, 2])
        self.assertEqual(lines[2]['firstChangedStage'], 1)
        # Counts, timestamps and digests only: nothing of the file's name or location.
        self.assertNotIn(self.dir, done.stdout)

    def test_the_generated_fixture_is_read_the_same_way_by_ffmpeg(self):
        if not (shutil.which('ffmpeg') and shutil.which('ffprobe')):
            self.skipTest('ffmpeg/ffprobe not on PATH')
        encoders = subprocess.run(['ffmpeg', '-hide_banner', '-encoders'], capture_output=True, text=True, stdin=subprocess.DEVNULL).stdout
        if 'libx265' not in encoders:
            self.skipTest('ffmpeg has no libx265')
        generator = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'device', 'make_edit_list_timeline_fixture.py')
        done = subprocess.run([sys.executable, generator, self.dir], capture_output=True, text=True, stdin=subprocess.DEVNULL)
        self.assertEqual(done.returncode, 0, done.stderr)
        records = {r['file']: r for r in map(json.loads, done.stdout.splitlines())}
        whole, tail = records['edit_whole_168.mp4'], records['edit_tail_168.mp4']
        self.assertEqual((whole['tableSamples'], whole['presentedSamples']), (168, 168))
        self.assertEqual((tail['tableSamples'], tail['presentedSamples'], tail['samplesAfterEditEnd']), (168, 166, 2))
        self.assertEqual((tail['initialEmptyEditUs'], tail['presentedFirstUs']), (97_488, 97_488))
        self.assertTrue(tail['storedOutOfOrder'])
        # ffmpeg, an independent reader: every packet is in the table; with the edit list applied
        # two are flagged for discard and its decoder outputs what this tool calls presented.
        applied, ignored = tail['ffprobe']['editListApplied'], tail['ffprobe']['editListIgnored']
        self.assertEqual((applied['packets'], applied['packetsFlaggedDiscard'], applied['decodedFrames']), (168, 2, 166))
        self.assertEqual((ignored['packets'], ignored['decodedFrames']), (168, 168))
        self.assertEqual(whole['ffprobe']['editListApplied']['decodedFrames'], 168)


if __name__ == '__main__': unittest.main()
