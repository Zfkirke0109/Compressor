#!/usr/bin/env python3
"""Synthetic sources for the edit-list timeline case. No private media: ffmpeg test patterns only.

b287 regular job_782a717d3217: 168 video samples in the source (MediaExtractor), 166 in the
re-encode, source first video timestamp 97,488 us. The original is not available, so this builds a
file with the structure that produces those numbers without any frame being lost in the pipeline:

  edit_tail_168.mp4   portrait (90 degree rotation tag), HEVC with B-frames, variable frame rate
                      (about 24 fps on a 60 fps grid), AAC. The video track starts after an
                      initial empty edit of 97,488 us and its media runs two frames past the end
                      of its presentation edit and of the movie: 168 samples in the table, 166
                      presented.
  edit_whole_168.mp4  the control: the same streams, every sample inside the edit (168 and 168).

The tail is made by shortening the audio so the movie ends between the 166th and 167th video
frames, then patching the video edit's duration and the track and movie durations to match. No
sample data is touched.

    make_edit_list_timeline_fixture.py OUT_DIR

Prints what each file holds and presents (scripts/diagnostics/mp4_edit_timeline.py) and, when
ffprobe is available, what ffmpeg's demuxer and decoder make of it with the edit list applied and
ignored. Requires ffmpeg with libx265 and aac.

The app keeps the two files in app/src/androidTest/assets (the device test, and
Media3ParserParityTest, which runs Media3's own parser over them on the build machine) and their
moov boxes in app/src/test/resources/edit_*.moov (the JVM reader tests). The parity test fails
unless those are the same boxes, so after regenerating replace both: copy the files to the
assets, and write mp4_edit_timeline.read_moov(file) for each to the resources.
"""
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'diagnostics'))
import mp4_edit_timeline as timeline  # noqa: E402

FRAMES = 168
VIDEO_DELAY_US = 97_488
FFMPEG = ['ffmpeg', '-nostdin', '-v', 'error', '-y']


def run(args):
    subprocess.run(args, check=True, stdin=subprocess.DEVNULL)


def build_streams(work, audio_seconds):
    """A video-only and an audio-only file; the same two are muxed into both fixtures."""
    video, audio = os.path.join(work, 'video.mp4'), os.path.join(work, 'audio.m4a')
    if not os.path.exists(video):
        # Two of every five frames of a 60 fps pattern: steps of 2 and 3 ticks, about 24 fps.
        keep = "select='eq(mod(n\\,5)\\,0)+eq(mod(n\\,5)\\,2)'"
        run([*FFMPEG, '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=60:duration=8', '-vf', keep,
             '-fps_mode', 'vfr', '-frames:v', str(FRAMES), '-c:v', 'libx265', '-preset', 'veryfast',
             '-x265-params', 'bframes=2:keyint=48:log-level=error', '-pix_fmt', 'yuv420p', '-tag:v', 'hvc1',
             '-video_track_timescale', '90000', video])
    run([*FFMPEG, '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000:duration=%.6f' % audio_seconds,
         '-ac', '2', '-c:a', 'aac', '-b:a', '256k', audio])
    return video, audio


def mux(video, audio, out):
    run([*FFMPEG, '-display_rotation:v:0', '90', '-itsoffset', '%.6f' % (VIDEO_DELAY_US / 1e6), '-i', video, '-i', audio,
         '-map', '0:v:0', '-map', '1:a:0', '-c', 'copy', '-movflags', '+faststart',
         # Microsecond movie timescale, so the empty edit is 97,488 us exactly and not 97 ms.
         '-movie_timescale', '1000000', out])


def patch_durations(path, presented_us):
    """End the video's presentation edit, its track and the movie at [presented_us]. Sizes are unchanged."""
    data = bytearray(open(path, 'rb').read())

    def walk(start, end, inside_video, movie_timescale, patched):
        for kind, s, e in timeline.boxes(data, start, end):
            if kind == b'mvhd':
                version = data[s]
                fmt, at = ('>Q', s + 4 + 20) if version == 1 else ('>I', s + 4 + 12)
                struct.pack_into(fmt, data, at, presented_us * movie_timescale // 1_000_000)
                patched.append('mvhd')
            elif kind == b'trak':
                mdia = timeline.find(data, b'mdia', s, e)
                hdlr = timeline.find(data, b'hdlr', *mdia)
                walk(s, e, bytes(data[hdlr[0] + 8:hdlr[0] + 12]) == b'vide', movie_timescale, patched)
            elif kind == b'tkhd' and inside_video:
                version = data[s]
                fmt, at = ('>Q', s + 4 + 24) if version == 1 else ('>I', s + 4 + 16)
                struct.pack_into(fmt, data, at, presented_us * movie_timescale // 1_000_000)
                patched.append('tkhd')
            elif kind == b'edts' and inside_video:
                elst = timeline.find(data, b'elst', s, e)
                version, count = data[elst[0]], struct.unpack_from('>I', data, elst[0] + 4)[0]
                p = elst[0] + 8
                for _ in range(count):
                    media_time = struct.unpack_from('>q' if version == 1 else '>i', data, p + (8 if version == 1 else 4))[0]
                    if media_time != -1:
                        struct.pack_into('>Q' if version == 1 else '>I', data, p,
                                         (presented_us - VIDEO_DELAY_US) * movie_timescale // 1_000_000)
                        patched.append('elst')
                    p += 20 if version == 1 else 12

    moov = next((s, e) for k, s, e in timeline.boxes(data) if k == b'moov')
    mvhd = timeline.find(data, b'mvhd', *moov)
    version = data[mvhd[0]]
    movie_timescale = struct.unpack_from('>I', data, mvhd[0] + 4 + (16 if version == 1 else 8))[0]
    patched = []
    walk(moov[0], moov[1], False, movie_timescale, patched)
    if sorted(patched) != ['elst', 'mvhd', 'tkhd']: raise SystemExit('unexpected boxes patched: %s' % patched)
    open(path, 'wb').write(data)


def ffprobe_counts(path):
    """What ffmpeg, an independent reader, makes of the video track; None without ffprobe.

    With the edit list applied its demuxer still returns every packet but flags those outside the
    presentation for discard, and its decoder outputs only the rest. Ignoring the edit list gives
    the sample table's view.
    """
    if not shutil.which('ffprobe'): return None

    def probe(extra):
        out = subprocess.run(['ffprobe', '-v', 'error', *extra, '-select_streams', 'v:0', '-count_packets', '-count_frames',
                              '-show_entries', 'stream=nb_read_packets,nb_read_frames:packet=flags', '-of', 'json', path],
                             capture_output=True, text=True, check=True, stdin=subprocess.DEVNULL).stdout
        data = json.loads(out)
        stream = data['streams'][0]
        return dict(packets=int(stream['nb_read_packets']), decodedFrames=int(stream['nb_read_frames']),
                    packetsFlaggedDiscard=sum('D' in p.get('flags', '') for p in data.get('packets', [])))

    return dict(editListApplied=probe([]), editListIgnored=probe(['-ignore_editlist', '1']))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else '.'
    os.makedirs(out, exist_ok=True)
    work = tempfile.mkdtemp()
    try:
        whole, tail = os.path.join(out, 'edit_whole_168.mp4'), os.path.join(out, 'edit_tail_168.mp4')
        video, audio = build_streams(work, 8.0)
        mux(video, audio, whole)
        times = sorted(t for _, t in timeline.presented(
            next(t for t in timeline.parse(whole)['tracks'] if t['handler'] == 'vide'), timeline.parse(whole)['movieTimescale']))
        if len(times) != FRAMES: raise SystemExit('expected %d presented frames in the control, found %d' % (FRAMES, len(times)))
        # The movie ends between the 166th and 167th frames: the last two start after it.
        presented_us = (times[FRAMES - 3] + times[FRAMES - 2]) // 2
        video, audio = build_streams(work, presented_us / 1e6)
        mux(video, audio, tail)
        patch_durations(tail, presented_us)
        for path in (whole, tail):
            record = timeline.evidence(path)
            record = {k: v for k, v in record.items() if not k.startswith('_')}
            print(json.dumps(dict(file=os.path.basename(path), ffprobe=ffprobe_counts(path), **record), sort_keys=True))
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
