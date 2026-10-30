#!/usr/bin/env python3
"""Generate small encoded controls; no raw frames or private media. Requires FFmpeg + libx264."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def make(out):
    out.mkdir(parents=True,exist_ok=True)
    def ff(*args): subprocess.run(['ffmpeg','-v','error','-y',*map(str,args)],check=True)
    source=out/'source.mp4'
    ff('-f','lavfi','-i','testsrc2=size=192x128:rate=30:duration=6','-an','-c:v','libx264','-crf','8',
       '-pix_fmt','yuv420p','-g','30','-color_primaries','bt709','-color_trc','bt709','-colorspace','bt709',source)
    ff('-i',source,'-c','copy',out/'remux.mp4')
    ff('-i',source,'-an','-c:v','libx264','-qp','0','-pix_fmt','yuv420p',out/'lossless.mp4')
    ff('-i',source,'-c','copy','-output_ts_offset','7',out/'origin_shift.mp4')
    ff('-i',source,'-vf',r'select=not(eq(n\,90))','-vsync','0','-c:v','libx264','-qp','0',out/'internal_deletion.mp4')
    ff('-i',source,'-vf',r'select=gte(n\,1),setpts=PTS-STARTPTS','-vsync','0','-c:v','libx264','-qp','0',out/'one_frame_offset.mp4')
    ff('-i',source,'-c','copy','-bsf:v','h264_metadata=colour_primaries=6:transfer_characteristics=6:matrix_coefficients=6',
       '-color_primaries','smpte170m','-color_trc','smpte170m','-colorspace','smpte170m',out/'color_tags.mp4')
    ff('-i',source,'-vf',r'setpts=PTS+mod(N\,2)*0.002/TB','-vsync','0','-video_track_timescale','1000000',
       '-enc_time_base','1:1000000','-c:v','libx264','-qp','0',out/'vfr_jitter.mp4')
    ff('-i',source,'-vf','lutyuv=u=val+12:v=val-12','-c:v','libx264','-qp','0',out/'chroma_changed.mp4')
    (out/'corrupt.mp4').write_bytes(bytes(4096))
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
    manifest=dict(schemaVersion=1,synthetic=True,startUs=1000000,endUs=5000000,contextUs=250000,
                  cases=cases,files=[dict(name=p.name,sha256=hashlib.sha256(p.read_bytes()).hexdigest(),bytes=p.stat().st_size)
                                    for p in sorted(out.glob('*.mp4'))],
                  ffmpegVersion=subprocess.check_output(['ffmpeg','-version'],text=True).splitlines()[0])
    (out/'controls.json').write_text(json.dumps(manifest,indent=2)+'\n')
    return manifest

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('--out',required=True,type=Path)
    args=p.parse_args(); print(json.dumps(make(args.out),indent=2))
