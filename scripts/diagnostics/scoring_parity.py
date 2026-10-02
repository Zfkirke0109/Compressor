#!/usr/bin/env python3
"""Replay Android frame evidence and compare exact PTS-selected frames with pinned libvmaf.

No raw decoded frames are saved. FFmpeg frames stream through named pipes to libvmaf.
Different decoded hashes are reported, never silently called Android/offline parity.
"""
from __future__ import annotations
import argparse
import bisect
from decimal import Decimal, ROUND_HALF_UP
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import zipfile


def sha_file(path):
    h=hashlib.sha256()
    with open(path,'rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''): h.update(block)
    return h.hexdigest()


def aggregates(values, header):
    if not values or any(not math.isfinite(x) or x < 0 for x in values):
        raise ValueError('missing or non-finite metric values')
    ordered=sorted(values)
    mean=sum(values)/len(values); p5=ordered[math.floor((len(values)-1)*.05)]; minimum=ordered[0]
    return dict(frames=len(values),mean=mean,p5=p5,min=minimum,
                passed=len(values)>=header['minFrames'] and mean>=header['meanGate'] and
                p5>=header['p5Gate'] and minimum>=header['minGate'])


def replay(data):
    if not data or data[0].get('type')!='header' or data[-1].get('type')!='summary':
        raise ValueError('trace needs a header and terminal summary')
    header,end=data[0],data[-1]; frames=data[1:-1]
    if header.get('schemaVersion')!=1 or header.get('p5Method')!='sorted[floor((n-1)*0.05)]':
        raise ValueError('unsupported trace schema/p5 method')
    if not end.get('complete') or end.get('outcome')!='SCORED': raise ValueError('incomplete trace')
    if len(frames)!=end['fedPairs'] or len(frames)!=end['recordedPairs']: raise ValueError('frame count mismatch')
    if not frames: raise ValueError('empty trace')
    for i,frame in enumerate(frames):
        if frame.get('type')!='frame' or frame.get('pairIndex')!=i: raise ValueError('pair index discontinuity')
        if frame['scored']==frame['context']: raise ValueError('invalid context/scored flags')
        if frame['context'] and i!=0: raise ValueError('unsupported context placement')
        if frame.get('vmafV0') is None or not math.isfinite(frame['vmafV0']) or frame['vmafV0']<0:
            raise ValueError('invalid per-frame score')
        if i and any(frame[k]<=frames[i-1][k] for k in ('sourcePtsUs','candidatePtsUs')):
            raise ValueError('non-increasing PTS')
    result=aggregates([f['vmafV0'] for f in frames if f['scored']],header)
    if result['frames']!=end['scoredFrames'] or result['passed']!=end['windowGatePassed']:
        raise ValueError('recorded verdict/count does not replay')
    for key in ('mean','p5','min'):
        if not math.isclose(result[key],end[key],rel_tol=0,abs_tol=1e-9):
            raise ValueError(f'recorded {key} does not replay')
    return result


def verify_archive(path):
    with zipfile.ZipFile(path) as z:
        names=z.namelist()
        if len(names)!=len(set(names)): raise ValueError('duplicate ZIP entries')
        manifest=json.loads(z.read('manifest.json'))
        if manifest.get('manifestVersion')!=2: raise ValueError('archive has no verifiable v2 manifest')
        listed=set()
        for entry in manifest['files']:
            name=entry['path']
            if name in listed or name.startswith('/') or '..' in Path(name).parts: raise ValueError('invalid inventory path')
            listed.add(name); digest=hashlib.sha256(); count=0
            with z.open(name) as f:
                for block in iter(lambda:f.read(1024*1024),b''): digest.update(block); count+=len(block)
            if count!=entry['bytes'] or digest.hexdigest()!=entry.get('sha256'):
                raise ValueError(f'checksum/size mismatch: {name}')
        if set(names)-{'manifest.json'}!=listed: raise ValueError('unlisted/missing ZIP payload')
        return {'verifiedEntries':len(listed),'manifestVersion':2}


def match_pts(actual, requested, tolerance=1):
    if any(b<=a for a,b in zip(actual,actual[1:])): raise ValueError('ambiguous/non-increasing decoder PTS')
    result=[]; previous=-1
    for pts in requested:
        lo=bisect.bisect_left(actual,pts-tolerance); hi=bisect.bisect_right(actual,pts+tolerance)
        if hi-lo!=1 or lo<=previous: raise ValueError(f'PTS {pts} missing, repeated or ambiguous ({hi-lo} matches)')
        result.append((lo,actual[lo]-pts)); previous=lo
    return result


def plane_hashes(data,width,height):
    y=width*height
    if width%2 or height%2 or len(data)!=y*3//2: raise ValueError('expected exact even-sized I420 frame')
    return {k:hashlib.sha256(v).hexdigest() for k,v in dict(i420=data,y=data[:y],u=data[y:y+y//4],v=data[y+y//4:]).items()}


def select_filter(indices): return 'select='+'+'.join(f'eq(n\\,{i})' for i in indices)


def inspect_video(path):
    cmd=['ffprobe','-v','error','-select_streams','v:0','-show_frames','-show_streams',
         '-show_entries','frame=best_effort_timestamp_time,width,height,pix_fmt,color_range,color_space,color_transfer,color_primaries:stream=width,height,pix_fmt,color_range,color_space,color_transfer,color_primaries,side_data_list',
         '-of','json',str(path)]
    result=json.loads(subprocess.check_output(cmd,timeout=300))
    frames=result['frames']; pts=[]
    for f in frames:
        if 'best_effort_timestamp_time' not in f: raise ValueError('decoder frame missing PTS')
        pts.append(int((Decimal(f['best_effort_timestamp_time'])*1_000_000).to_integral_value(rounding=ROUND_HALF_UP)))
    formats={f.get('pix_fmt') for f in frames}
    if len(formats)!=1 or not formats<={'yuv420p','yuvj420p'}:
        raise ValueError(f'8-bit 4:2:0 parity only; refuses implicit bit-depth/range conversion: {formats}')
    stream=result['streams'][0]
    rotation=next((d['rotation'] for d in stream.get('side_data_list',[]) if 'rotation' in d),0)
    w,h=stream['width'],stream['height']
    if abs(rotation)%180==90: w,h=h,w
    if any((f['width'],f['height'])!=(stream['width'],stream['height']) for f in frames):
        raise ValueError('dynamic geometry is unsupported')
    return pts,formats.pop(),(w,h),stream


def compare(args):
    reader=gzip.open if str(args.trace).endswith('.gz') else open
    with reader(args.trace,'rt') as f: data=[json.loads(s) for s in f if s.strip()]
    android=replay(data); header=data[0]; pairs=data[1:-1]
    if header.get('model')!='vmaf_v0.6.1' or header.get('phoneTransform') is not False:
        raise ValueError('this parity instrument reproduces v0.6.1 without phone transform only')
    if sha_file(args.model)!=args.model_sha256 or header.get('modelJsonSha256')!=args.model_sha256:
        raise ValueError('model checksum mismatch')
    version=subprocess.check_output([args.vmaf_bin,'--version'],text=True,stderr=subprocess.STDOUT).strip()
    if not args.tool_version: raise ValueError('empty libvmaf tool pin')
    if version!=args.tool_version: raise ValueError(f'pinned libvmaf tool version mismatch: {version}')
    candidate_hash=sha_file(args.candidate)
    expected=header.get('provenance',{}).get('candidateSha256')
    if expected and candidate_hash!=expected: raise ValueError('wrong encoded candidate SHA-256')
    width,height=header['width'],header['height']; evidence={}; errors=[]; processes=[]
    prepared=[]
    for role,path,pts_key,hash_key,offset in [('reference',args.reference,'sourcePtsUs','sourceHash',args.reference_pts_offset_us),
                                        ('candidate',args.candidate,'candidatePtsUs','candidateHash',args.candidate_pts_offset_us)]:
        pts,pix_fmt,geometry,stream=inspect_video(path)
        if geometry!=(width,height): raise ValueError(f'{role} decoded/display geometry mismatch {geometry}')
        mapping=match_pts(pts,[p[pts_key]+offset for p in pairs],args.pts_tolerance_us)
        prepared.append((role,path,pix_fmt,[m[0] for m in mapping],hash_key))
        evidence[role]={'sha256':sha_file(path),'ptsDeltaUs':[m[1] for m in mapping],
                        'appliedOffsetUs':offset,'stream':stream,'frameHashes':[],'androidHashMismatches':[]}
    args.out.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='compressor-parity-') as scratch:
        pipes=[Path(scratch)/f'{role}.yuv' for role, *_ in prepared]
        for pipe in pipes: os.mkfifo(pipe,0o600)
        result_path=args.out/'libvmaf.json'
        cmd=[args.vmaf_bin,'-r',str(pipes[0]),'-d',str(pipes[1]),'-w',str(width),'-h',str(height),
             '-p','420','-b','8','--model',f'path={Path(args.model).resolve()}:name=vmaf',
             '--threads','4','--json','--output',str(result_path),'--quiet']
        native=subprocess.Popen(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        def produce(spec,pipe):
            role,path,pix_fmt,indices,hash_key=spec
            process=None
            try:
                deadline=time.monotonic()+60
                while True:
                    try: fd=os.open(pipe,os.O_WRONLY|os.O_NONBLOCK); break
                    except OSError:
                        if native.poll() is not None or time.monotonic()>deadline: raise ValueError('libvmaf did not open frame pipe')
                        time.sleep(.05)
                os.set_blocking(fd,True)
                ffmpeg=['ffmpeg','-v','error','-threads','1','-i',str(path),'-map','0:v:0','-an',
                        '-vf',select_filter(indices),'-vsync','0','-pix_fmt',pix_fmt,'-f','rawvideo','pipe:1']
                process=subprocess.Popen(ffmpeg,stdout=subprocess.PIPE,stderr=subprocess.PIPE); processes.append(process)
                frame_size=width*height*3//2
                with os.fdopen(fd,'wb') as output:
                    for i in range(len(indices)):
                        frame=process.stdout.read(frame_size)
                        if len(frame)!=frame_size: raise ValueError('FFmpeg decoded frame count/size differs from manifest')
                        hashes=plane_hashes(frame,width,height); evidence[role]['frameHashes'].append(hashes)
                        if hashes!=pairs[i].get(hash_key): evidence[role]['androidHashMismatches'].append(i)
                        output.write(frame)
                    if process.stdout.read(1): raise ValueError('FFmpeg emitted unexpected extra selected frame')
                if process.wait(timeout=30)!=0: raise ValueError(process.stderr.read().decode(errors='replace'))
            except Exception as exc:
                errors.append(f'{role}: {exc}')
                native.terminate()
            finally:
                if process is not None and process.poll() is None: process.kill(); process.wait()
                if process is not None:
                    process.stdout.close(); process.stderr.close()
        threads=[threading.Thread(target=produce,args=(spec,pipe),daemon=True) for spec,pipe in zip(prepared,pipes)]
        for t in threads: t.start()
        try:
            stdout,stderr=native.communicate(timeout=600)
            for t in threads: t.join(timeout=30)
            if any(t.is_alive() for t in threads): raise ValueError('frame producer did not finish')
            if errors or native.returncode: raise ValueError('; '.join(errors)+stderr.decode(errors='replace'))
        finally:
            if native.poll() is None: native.kill(); native.wait()
            for p in processes:
                if p.poll() is None: p.kill(); p.wait()
        raw=json.loads(result_path.read_text())['frames']
        if len(raw)!=len(pairs): raise ValueError('libvmaf frame count mismatch')
        scores=[f['metrics']['vmaf'] for f in raw]
        offline=aggregates([s for s,p in zip(scores,pairs) if p['scored']],header)
        delta=[s-p['vmafV0'] for s,p in zip(scores,pairs)]
        same_pixels=all(not e['androidHashMismatches'] for e in evidence.values())
        same_library=header.get('measurement',{}).get('v0Library')==args.lib_version
        color_keys=('color_range','color_space','color_transfer','color_primaries')
        color_tags_match=all(evidence['reference']['stream'].get(k)==evidence['candidate']['stream'].get(k) for k in color_keys)
        report={'traceId':header.get('traceId'),'policyEpoch':header.get('policyEpoch'),
                'libvmafBuildVersion':args.lib_version,'libvmafToolVersion':version,'libvmafExecutableSha256':sha_file(args.vmaf_bin),
                'modelSha256':args.model_sha256,'ffmpegVersion':subprocess.check_output(['ffmpeg','-version'],text=True).splitlines()[0],
                'android':android,'offline':offline,'perFrameDelta':delta,'maxAbsScoreDelta':max(map(abs,delta)),
                'decodedPixelsIdentical':same_pixels,'sameLibraryVersion':same_library,
                'colorTagsMatch':color_tags_match,
                'traceOrigin':header.get('provenance',{}).get('traceOrigin','Android-capture'),
                'verdictAgreement':android['passed']==offline['passed'],'evidence':evidence,
                'status':'PARITY' if same_pixels and same_library and max(map(abs,delta))<=args.score_tolerance and android['passed']==offline['passed'] else 'DIFFERENCE_REQUIRES_REVIEW',
                'scoreTolerance':args.score_tolerance,'ptsToleranceUs':args.pts_tolerance_us,
                'pixelPipeline':'FFmpeg default metadata autorotation; native 8-bit 420 raster/range; no resize/fps conversion',
                'rawFramesSaved':False}
        if header.get('provenance',{}).get('traceOrigin')=='offline-synthetic' and report['status']=='PARITY':
            report['status']='OFFLINE_CONTROL_REPLAY'
        (args.out/'parity.json').write_text(json.dumps(report,indent=2,allow_nan=False)+'\n')
        return report


def main():
    parser=argparse.ArgumentParser(description=__doc__); sub=parser.add_subparsers(dest='command',required=True)
    p=sub.add_parser('verify-archive'); p.add_argument('archive')
    p=sub.add_parser('replay'); p.add_argument('trace')
    p=sub.add_parser('compare')
    for name in ['trace','reference','candidate','vmaf-bin','model','model-sha256','lib-version','tool-version']: p.add_argument('--'+name,required=True)
    p.add_argument('--out',type=Path,required=True); p.add_argument('--pts-tolerance-us',type=int,default=1)
    p.add_argument('--reference-pts-offset-us',type=int,default=0); p.add_argument('--candidate-pts-offset-us',type=int,default=0)
    p.add_argument('--score-tolerance',type=float,default=.0001)
    args=parser.parse_args()
    if args.command=='verify-archive': result=verify_archive(args.archive)
    elif args.command=='replay':
        reader=gzip.open if args.trace.endswith('.gz') else open
        with reader(args.trace,'rt') as f: result=replay([json.loads(s) for s in f if s.strip()])
    else:
        result=compare(args)
        print(json.dumps({k:v for k,v in result.items() if k!='evidence'},indent=2))
        return int(result['status']!='PARITY')
    print(json.dumps(result,indent=2)); return 0

if __name__=='__main__': raise SystemExit(main())
