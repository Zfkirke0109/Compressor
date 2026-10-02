#!/usr/bin/env python3
"""Source-only research window proposals. No quality verdict; original PTS/raster used later for scoring.
Proxy features are motion difference, spatial edges, and prevalence of small nonzero gradients.
They are not calibrated noise, banding, face or scene-cut detectors. Requires FFmpeg/ffprobe.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import subprocess
from calibration_evidence import hard_windows


def features(previous, current, width):
    motion = sum(abs(a-b) for a,b in zip(previous,current))/len(current) if previous else 0.0
    gradients=[abs(current[i]-current[i-1]) for i in range(1,len(current)) if i%width]
    return dict(motion=motion,detail=sum(gradients)/len(gradients),
                bandingRisk=sum(0<g<=3 for g in gradients)/len(gradients))


def measure(source):
    meta=json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0',
        '-show_entries','stream=width,height','-of','json',str(source)],text=True))['streams'][0]
    scale=min(160/meta['width'],160/meta['height'])
    w=max(2,int(meta['width']*scale)//2*2);h=max(2,int(meta['height']*scale)//2*2)
    command=['ffmpeg','-v','error','-noautorotate','-i',str(source),'-an','-vf',f'fps=2,scale={w}:{h},format=gray',
             '-f','rawvideo','-pix_fmt','gray','pipe:1']
    process=subprocess.Popen(command,stdout=subprocess.PIPE)
    previous=None;windows=[];bucket=[];index=0
    try:
        while True:
            frame=process.stdout.read(w*h)
            if not frame:break
            if len(frame)!=w*h:raise ValueError('partial source feature frame')
            bucket.append(features(previous,frame,w));previous=frame;index+=1
            if len(bucket)==6:
                windows.append(dict(startUs=(index-6)*500000,endUs=index*500000,
                    **{k:sum(r[k] for r in bucket)/len(bucket) for k in bucket[0]}));bucket=[]
        if process.wait()!=0:raise RuntimeError('feature decode failed')
    finally:
        process.stdout.close()
        if process.poll() is None:process.terminate();process.wait()
    digest=hashlib.sha256()
    with source.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
    return dict(schemaVersion=1,researchOnly=True,sourceSha256=digest.hexdigest(),sampledFrames=index,
                featureRaster=[w,h],featureRate=2,windows=windows,selected=hard_windows(windows),
                trailingPartialWindowOmittedFrames=len(bucket),
                ffmpegVersion=subprocess.check_output(['ffmpeg','-version'],text=True).splitlines()[0],
                calibration='none; compare with fixed and whole-file audits before Android integration')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('source',type=Path);parser.add_argument('--out',required=True,type=Path)
    args=parser.parse_args();args.out.write_text(json.dumps(measure(args.source),indent=2)+'\n')
