#!/usr/bin/env python3
"""The video timeline of an MP4 as its sample table holds it and as its edit list presents it.

Two readers of one file can count different frames. A sample table lists every sample in the
track. An edit list says which stretch of that media is the presentation; samples that start
after an edit ends are not part of it. Android's MediaExtractor returns the sample table.
Media3's MP4 parser (BoxParser.parseStbl, 1.11.0) applies the edit list and leaves such samples
out, so a Transformer export never decodes them.

b287 regular job_782a717d3217 recorded 168 source samples (MediaExtractor) and 166 in the output,
with the source's first video timestamp at 97,488 us. A video track delayed by an initial empty
edit and running two frames past the end of its presentation edit produces exactly that pair of
numbers with no frame lost by the encoder, the muxer or the metadata remux. Whether that file is
built so is not known from the capture: this tool reads the boxes that answer it.

    mp4_edit_timeline.py FILE...             one evidence record per file, as JSON lines
    mp4_edit_timeline.py --compare A B C     the first file whose timeline differs from the one before

Compare the original, the closed Media3 output and the file after the metadata remux, in that
order. Only counts, timestamps and digests are printed; run it where the media already is.
Standard library only. Unfragmented MP4/MOV with one video track.
"""
import argparse
import hashlib
import json
import struct
import sys

CONTAINERS = {b'moov', b'trak', b'mdia', b'minf', b'stbl', b'edts'}


def boxes(data, start=0, end=None):
    """(type, payload start, box end) for each box in data[start:end]."""
    end = len(data) if end is None else end
    pos = start
    while pos + 8 <= end:
        size, kind = struct.unpack_from('>I4s', data, pos)
        header = 8
        if size == 1:
            if pos + 16 > end: break
            size = struct.unpack_from('>Q', data, pos + 8)[0]
            header = 16
        elif size == 0:
            size = end - pos
        if size < header or pos + size > end: break
        yield kind, pos + header, pos + size
        pos += size


def find(data, kind, start, end):
    return next(((s, e) for k, s, e in boxes(data, start, end) if k == kind), None)


def read_moov(path):
    """The moov box's bytes, found by walking top-level box headers (mdat is skipped, not read)."""
    with open(path, 'rb') as f:
        while True:
            header = f.read(8)
            if len(header) < 8: raise ValueError('no moov box')
            size, kind = struct.unpack('>I4s', header)
            consumed = 8
            if size == 1:
                size = struct.unpack('>Q', f.read(8))[0]
                consumed = 16
            if kind == b'moov':
                if size == 0: return f.read()
                if size > 256 * 1024 * 1024: raise ValueError('moov larger than this tool reads')
                return f.read(size - consumed)
            if size == 0: raise ValueError('no moov box')
            f.seek(size - consumed, 1)


def version_and(data, start):
    return data[start], start + 4


def parse_track(moov, start, end):
    mdia = find(moov, b'mdia', start, end)
    if not mdia: return None
    hdlr = find(moov, b'hdlr', *mdia)
    handler = moov[hdlr[0] + 8:hdlr[0] + 12].decode('latin-1') if hdlr else None
    mdhd = find(moov, b'mdhd', *mdia)
    version, p = version_and(moov, mdhd[0])
    timescale, media_duration = struct.unpack_from('>IQ' if version == 1 else '>II', moov, p + (16 if version == 1 else 8))
    minf = find(moov, b'minf', *mdia)
    stbl = find(moov, b'stbl', *minf) if minf else None
    if not stbl: return None
    stts = find(moov, b'stts', *stbl)
    decode_times, t = [], 0
    if stts:
        count = struct.unpack_from('>I', moov, stts[0] + 4)[0]
        for i in range(count):
            n, delta = struct.unpack_from('>II', moov, stts[0] + 8 + 8 * i)
            for _ in range(n):
                decode_times.append(t)
                t += delta
    offsets = [0] * len(decode_times)
    ctts = find(moov, b'ctts', *stbl)
    if ctts:
        version, p = version_and(moov, ctts[0])
        count = struct.unpack_from('>I', moov, p)[0]
        i = 0
        for entry in range(count):
            n, off = struct.unpack_from('>Ii' if version == 1 else '>II', moov, p + 4 + 8 * entry)
            # Version 0 is unsigned by the letter of the spec; writers store negative offsets there too.
            if version == 0 and off >= 1 << 31: off -= 1 << 32
            for _ in range(n):
                if i < len(offsets): offsets[i] = off
                i += 1
    stss = find(moov, b'stss', *stbl)
    sync = None
    if stss:
        count = struct.unpack_from('>I', moov, stss[0] + 4)[0]
        sync = {struct.unpack_from('>I', moov, stss[0] + 8 + 4 * i)[0] - 1 for i in range(count)}
    edits = None
    edts = find(moov, b'edts', start, end)
    elst = find(moov, b'elst', *edts) if edts else None
    if elst:
        version, p = version_and(moov, elst[0])
        count = struct.unpack_from('>I', moov, p)[0]
        edits, p = [], p + 4
        for _ in range(count):
            if version == 1:
                duration, media_time = struct.unpack_from('>Qq', moov, p)
                p += 20
            else:
                duration, media_time = struct.unpack_from('>Ii', moov, p)
                p += 12
            edits.append((duration, media_time))
    return dict(handler=handler, timescale=timescale, mediaDuration=media_duration,
                timestamps=[d + o for d, o in zip(decode_times, offsets)], sync=sync, edits=edits)


def parse(path):
    moov = read_moov(path)
    mvhd = find(moov, b'mvhd', 0, len(moov))
    version, p = version_and(moov, mvhd[0])
    movie_timescale, movie_duration = struct.unpack_from('>IQ' if version == 1 else '>II', moov, p + (16 if version == 1 else 8))
    tracks = [t for k, s, e in boxes(moov) if k == b'trak' for t in [parse_track(moov, s, e)] if t]
    return dict(movieTimescale=movie_timescale, movieDuration=movie_duration, tracks=tracks)


def java_binary_search(array, value):
    """java.util.Arrays.binarySearch: the index of value, or -(insertion point) - 1.

    Media3 runs it on timestamps in STORED order, which B-frames leave unsorted, and then corrects
    for that; following the same steps keeps this the same answer on such a track.
    """
    low, high = 0, len(array) - 1
    while low <= high:
        mid = (low + high) >> 1
        if array[mid] < value: low = mid + 1
        elif array[mid] > value: high = mid - 1
        else: return mid
    return -(low + 1)


def search_floor(array, value):
    """Util.binarySearchFloor(array, value, inclusive=true, stayInBounds=true)."""
    index = java_binary_search(array, value)
    if index < 0:
        index = -(index + 2)
    else:
        index -= 1
        while index >= 0 and array[index] == value: index -= 1
        index += 1
    return max(0, index)


def search_ceil_exclusive(array, value):
    """Util.binarySearchCeil(array, value, inclusive=false, stayInBounds=false): first element past value."""
    index = java_binary_search(array, value)
    if index < 0: return ~index
    index += 1
    while index < len(array) and array[index] == value: index += 1
    return index


def presented(track, movie_timescale, reorder=16):
    """Sample indices Media3 1.11.0 keeps, with each one's presentation time in microseconds.

    BoxParser.parseStbl: with no edit list every sample is kept. One edit of zero duration keeps
    every sample, shifted. Otherwise, per non-empty edit, samples run from the sync sample at or
    before the edit's start to the last sample that starts at or before its end (a sample starting
    exactly at the end is kept; later ones are not, allowing for frames stored out of order), and
    every edit, empty ones included, advances the time at which the next one is presented.
    [reorder] bounds the out-of-order search as the track's maxNumReorderSamples does; 16 is the
    most HEVC and H.264 allow, so it never ends the search early.

    The app's Mp4EditTimeline is the same reading, and its Media3ParserParityTest holds it against
    Media3's own Mp4Extractor, sample for sample. Keep the two in step.
    """
    ts, scale = track['timestamps'], track['timescale']
    def us(value, base):
        # Toward zero, as Media3's Util.scaleLargeTimestamp rounds: a sample kept from before an
        # edit's start has a negative offset, and flooring it would be a microsecond early.
        scaled = abs(value) * 1_000_000 // base
        return -scaled if value < 0 else scaled
    edits = track['edits']
    if edits is None: return [(i, us(t, scale)) for i, t in enumerate(ts)]
    if len(edits) == 1 and edits[0][0] == 0:
        return [(i, us(t - edits[0][1], scale)) for i, t in enumerate(ts)]
    sync = track['sync']
    is_sync = (lambda i: True) if sync is None else (lambda i: i in sync)
    kept, pts = [], 0
    for duration, media_time in edits:
        if media_time != -1 and ts:
            end_time = media_time + duration * scale // movie_timescale
            start = search_floor(ts, media_time)
            first_after = search_ceil_exclusive(ts, end_time)
            last_valid, seen_after = first_after - 1, 0
            for j in range(first_after, len(ts)):
                if ts[j] < end_time:
                    last_valid = j
                else:
                    seen_after += 1
                    if seen_after > reorder: break
            end = last_valid + 1
            initial = start
            while start > 0 and not is_sync(start): start -= 1
            if start == 0 and not is_sync(0):
                start = initial
                while start < end and not is_sync(start): start += 1
            base = us(pts, movie_timescale)
            kept.extend((j, base + us(ts[j] - media_time, scale)) for j in range(start, end))
        pts += duration
    return kept


def digest(times):
    """Twelve hex digits over the sorted presentation times, measured from the first."""
    if not times: return None
    ordered = sorted(times)
    text = ','.join(str(t - ordered[0]) for t in ordered)
    return hashlib.sha256(text.encode()).hexdigest()[:12]


def evidence(path):
    """What this file's one video track holds and what its edit list presents."""
    movie = parse(path)
    videos = [t for t in movie['tracks'] if t['handler'] == 'vide']
    if len(videos) != 1: raise ValueError('exactly one video track required, found %d' % len(videos))
    track = videos[0]
    scale = track['timescale']
    raw = sorted(t * 1_000_000 // scale for t in track['timestamps'])
    kept = presented(track, movie['movieTimescale'])
    kept_times = sorted(t for _, t in kept)
    kept_indices = {i for i, _ in kept}
    edits = track['edits'] or []
    empty_us = sum(d for d, m in edits if m == -1) * 1_000_000 // movie['movieTimescale'] if edits else 0
    ends = [m + d * scale // movie['movieTimescale'] for d, m in edits if m != -1 and d != 0]
    starts = [m for d, m in edits if m != -1]
    return dict(
        tableSamples=len(raw), presentedSamples=len(kept),
        samplesAfterEditEnd=sum(1 for i, t in enumerate(track['timestamps']) if ends and t > max(ends) and i not in kept_indices),
        samplesBeforeEditStart=sum(1 for i, t in enumerate(track['timestamps']) if starts and t < min(starts) and i not in kept_indices),
        tableFirstUs=raw[0] if raw else None, tableLastUs=raw[-1] if raw else None,
        presentedFirstUs=kept_times[0] if kept_times else None, presentedLastUs=kept_times[-1] if kept_times else None,
        tableDigest=digest(raw), presentedDigest=digest(kept_times),
        edits=[dict(durationUs=d * 1_000_000 // movie['movieTimescale'], mediaTimeUs=None if m == -1 else m * 1_000_000 // scale)
               for d, m in edits],
        initialEmptyEditUs=empty_us,
        movieDurationUs=movie['movieDuration'] * 1_000_000 // movie['movieTimescale'],
        mediaDurationUs=track['mediaDuration'] * 1_000_000 // scale,
        storedOutOfOrder=track['timestamps'] != sorted(track['timestamps']),
        # For first_change only; never printed.
        _table=raw)


def first_change(records, tolerance_us=1000):
    """Index of the first record whose timeline differs from the one before it, and why; None when none does.

    A count is compared with the earlier file's table and, when it differs, with what that file
    PRESENTS: a stage that carries exactly the frames the previous file's edit list shows has lost
    nothing a reader of that edit list would see. With equal counts, times are compared within
    [tolerance_us] (two files rarely share a timescale): everything but the first timestamp
    unchanged is Media3's muxer moving the first video frame to zero; every frame moved by the
    same amount is a new origin; anything else is a retimed frame.
    """
    for i in range(1, len(records)):
        before, after = records[i - 1], records[i]
        if after['tableSamples'] != before['tableSamples']:
            same_as_presented = after['tableSamples'] == before['presentedSamples']
            return i, ('%d samples in the table before, %d after%s' % (
                before['tableSamples'], after['tableSamples'],
                '; equal to what the earlier file\'s edit list presents (%d)' % before['presentedSamples'] if same_as_presented else ''))
        a, b = before['_table'], after['_table']
        if not a: continue
        absolute = [abs(x - y) for x, y in zip(a, b)]
        if max(absolute) <= tolerance_us: continue
        if all(d <= tolerance_us for d in absolute[1:]):
            return i, 'only the first timestamp differs (%d us before, %d us after); every later frame is where it was' % (a[0], b[0])
        relative = [abs((x - a[0]) - (y - b[0])) for x, y in zip(a, b)]
        if max(relative) <= tolerance_us:
            return i, 'same frames, origin moved by %d us' % (b[0] - a[0])
        frame = next(k for k, d in enumerate(relative) if d > tolerance_us)
        return i, 'same count, frame %d retimed by %d us relative to the first frame' % (frame, relative[frame])
    return None


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('files', nargs='+')
    ap.add_argument('--compare', action='store_true', help='report the first file whose timeline differs from the one before it')
    a = ap.parse_args(argv)
    records = [evidence(f) for f in a.files]
    for index, record in enumerate(records):
        print(json.dumps(dict(stage=index, **{k: v for k, v in record.items() if not k.startswith('_')}), sort_keys=True))
    if a.compare:
        change = first_change(records)
        print(json.dumps(dict(firstChangedStage=None if change is None else change[0], reason=None if change is None else change[1])))
    return 0


if __name__ == '__main__':
    sys.exit(main())
