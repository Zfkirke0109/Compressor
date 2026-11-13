#!/usr/bin/env python3
"""Generate small encoded controls; no raw frames or private media. Requires FFmpeg with libx264 and libx265.

The lossless controls are HEVC Main (x265 lossless). libx264's lossless mode writes High 4:4:4
Predictive even for 4:2:0 input, and phone hardware decoders reject that profile: the S26 parity
run of November 9 stopped on those files and passed only after they were swapped by hand for
HEVC Main lossless ones. This generator now writes that kind itself and checks its own output,
so a corpus the phone cannot decode, or whose "identical" control is not identical, fails here
and not on the device:

  - every encoded file must be 8-bit 4:2:0 in a profile from DECODABLE_PROFILES;
  - every control's decoded frames are compared with the source's, frame by frame, with FFmpeg's
    decoder: the lossless and stream-copy controls must match exactly, the deletion and offset
    controls must be the source without the frame they drop, the chroma control must differ.

These are host checks. The phone's decoders are a separate gate (ScoringParityDeviceTest).
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

# What a phone's hardware decoders are required to handle. Not High 4:4:4 Predictive.
DECODABLE_PROFILES = {('h264', 'Constrained Baseline'), ('h264', 'Main'), ('h264', 'High'), ('hevc', 'Main')}
LOSSLESS = ['-c:v', 'libx265', '-x265-params', 'lossless=1:log-level=error', '-pix_fmt', 'yuv420p', '-tag:v', 'hvc1']
DELETED_FRAME = 90
# Every command here finishes in about a second: the source is 192x128 and six seconds long. A
# tool still running after this long is not going to finish, and a caller that waits for it holds
# its CI job until someone cancels the run.
TOOL_TIMEOUT_S = 120


def run(tool, *args):
    command = [tool, *map(str, args)]
    try:
        return subprocess.run(command, check=True, stdin=subprocess.DEVNULL, capture_output=True, text=True,
                              timeout=TOOL_TIMEOUT_S).stdout
    except subprocess.TimeoutExpired:
        # subprocess.run has killed and reaped the tool. Name the command: the log is all a CI run leaves.
        raise TimeoutError('%s did not finish within %g s: %s' % (tool, TOOL_TIMEOUT_S, ' '.join(command))) from None


def video_stream(path):
    """Codec, profile and pixel format of the file's video track."""
    stream = json.loads(run('ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
                            'stream=codec_name,profile,pix_fmt', '-of', 'json', path))['streams'][0]
    return dict(codec=stream['codec_name'], profile=stream.get('profile'), pixFmt=stream['pix_fmt'])


def require_decodable(name, stream):
    """Fail on a file a phone hardware decoder is not required to decode."""
    if stream['pixFmt'] != 'yuv420p' or (stream['codec'], stream['profile']) not in DECODABLE_PROFILES:
        raise ValueError('%s is %s %s %s: not a profile the device controls may use' % (
            name, stream['codec'], stream['profile'], stream['pixFmt']))


def decoded_frames(path):
    """(presentation time in microseconds, hash of the decoded frame) in presentation order."""
    rows = []
    # -copyts: without it FFmpeg rebases the output to zero and the origin-shift control reads as unshifted.
    for line in run('ffmpeg', '-nostdin', '-v', 'error', '-copyts', '-i', path, '-map', '0:v:0', '-vsync', '0',
                    '-enc_time_base', '1:1000000', '-f', 'framehash', '-hash', 'sha256', '-').splitlines():
        if line.startswith('#') or not line.strip(): continue
        parts = [p.strip() for p in line.split(',')]
        rows.append((int(parts[2]), parts[-1]))
    return sorted(rows)


def require_pixels(name, actual, expected, what):
    if [h for _, h in actual] != expected:
        raise ValueError('%s does not decode to %s (%d frames against %d expected)' % (name, what, len(actual), len(expected)))


def make(out):
    out.mkdir(parents=True,exist_ok=True)
    def ff(*args): run('ffmpeg','-nostdin','-v','error','-y',*args)
    source=out/'source.mp4'
    ff('-f','lavfi','-i','testsrc2=size=192x128:rate=30:duration=6','-an','-c:v','libx264','-crf','8',
       '-pix_fmt','yuv420p','-g','30','-color_primaries','bt709','-color_trc','bt709','-colorspace','bt709',source)
    ff('-i',source,'-c','copy',out/'remux.mp4')
    ff('-i',source,'-an',*LOSSLESS,out/'lossless.mp4')
    ff('-i',source,'-c','copy','-output_ts_offset','7',out/'origin_shift.mp4')
    ff('-i',source,'-vf',r'select=not(eq(n\,%d))' % DELETED_FRAME,'-vsync','0',*LOSSLESS,out/'internal_deletion.mp4')
    ff('-i',source,'-vf',r'select=gte(n\,1),setpts=PTS-STARTPTS','-vsync','0',*LOSSLESS,out/'one_frame_offset.mp4')
    ff('-i',source,'-c','copy','-bsf:v','h264_metadata=colour_primaries=6:transfer_characteristics=6:matrix_coefficients=6',
       '-color_primaries','smpte170m','-color_trc','smpte170m','-colorspace','smpte170m',out/'color_tags.mp4')
    ff('-i',source,'-vf',r'setpts=PTS+mod(N\,2)*0.002/TB','-vsync','0','-video_track_timescale','1000000',
       '-enc_time_base','1:1000000',*LOSSLESS,out/'vfr_jitter.mp4')
    ff('-i',source,'-vf','lutyuv=u=val+12:v=val-12',*LOSSLESS,out/'chroma_changed.mp4')
    (out/'corrupt.mp4').write_bytes(bytes(4096))

    # The corpus checks itself before anything is pushed to a phone.
    encoded=sorted(p for p in out.glob('*.mp4') if p.name!='corrupt.mp4')
    streams={p.name:video_stream(p) for p in encoded}
    for name,stream in streams.items(): require_decodable(name,stream)
    frames={p.name:decoded_frames(p) for p in encoded}
    original=[h for _,h in frames['source.mp4']]
    for name in ('remux.mp4','lossless.mp4','origin_shift.mp4','color_tags.mp4','vfr_jitter.mp4'):
        require_pixels(name,frames[name],original,"the source's frames")
    require_pixels('internal_deletion.mp4',frames['internal_deletion.mp4'],original[:DELETED_FRAME]+original[DELETED_FRAME+1:],
                   'the source without frame %d' % DELETED_FRAME)
    require_pixels('one_frame_offset.mp4',frames['one_frame_offset.mp4'],original[1:],'the source without its first frame')
    changed=[h for _,h in frames['chroma_changed.mp4']]
    if len(changed)!=len(original) or any(a==b for a,b in zip(changed,original)):
        raise ValueError('chroma_changed.mp4 must differ from the source in every frame')
    # The jitter control moves every second frame 2 ms later and no other.
    steps=[b[0]-a[0] for a,b in zip(frames['vfr_jitter.mp4'],frames['vfr_jitter.mp4'][1:])]
    if len(set(steps))<2 or frames['origin_shift.mp4'][0][0]-frames['source.mp4'][0][0]!=7000000:
        raise ValueError('the timing controls do not carry the timing they are named for')

    cases=[
        dict(id='identity',reference='source.mp4',candidate='source.mp4',expect='identical'),
        dict(id='remux',reference='source.mp4',candidate='remux.mp4',expect='identical'),
        dict(id='lossless',reference='source.mp4',candidate='lossless.mp4',expect='identical'),
        dict(id='pts-origin',reference='source.mp4',candidate='origin_shift.mp4',distOffsetUs=7000000,expect='identical'),
        dict(id='internal-deletion',reference='source.mp4',candidate='internal_deletion.mp4',expect='misaligned'),
        dict(id='one-frame-offset',reference='source.mp4',candidate='one_frame_offset.mp4',expect='different'),
        dict(id='tag-mutation',reference='source.mp4',candidate='color_tags.mp4',expect='identical-color-tags-differ'),
        dict(id='vfr-jitter',reference='vfr_jitter.mp4',candidate='vfr_jitter.mp4',expect='identical'),
        dict(id='motion-context',reference='source.mp4',candidate='source.mp4',expect='identical'),
        dict(id='chroma-mutation',reference='source.mp4',candidate='chroma_changed.mp4',expect='different'),
        dict(id='corrupt',reference='source.mp4',candidate='corrupt.mp4',expect='unavailable')]
    def described(p):
        entry=dict(name=p.name,sha256=hashlib.sha256(p.read_bytes()).hexdigest(),bytes=p.stat().st_size)
        if p.name in streams:
            entry.update(streams[p.name],frames=len(frames[p.name]),
                         decodedSha256=hashlib.sha256(''.join(h for _,h in frames[p.name]).encode()).hexdigest())
        return entry
    manifest=dict(schemaVersion=1,synthetic=True,startUs=1000000,endUs=5000000,contextUs=250000,
                  cases=cases,files=[described(p) for p in sorted(out.glob('*.mp4'))],
                  hostChecks='profiles and decoded frames verified with FFmpeg when generated; not a device result',
                  ffmpegVersion=run('ffmpeg','-version').splitlines()[0])
    (out/'controls.json').write_text(json.dumps(manifest,indent=2)+'\n')
    return manifest

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('--out',required=True,type=Path)
    args=p.parse_args(); print(json.dumps(make(args.out),indent=2))
