#!/usr/bin/env python3
"""Real FFmpeg/libvmaf harness controls. These DO NOT substitute for Android decoder tests."""
import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import scoring_parity as parity
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'device'))
from make_scoring_controls import make


@unittest.skipUnless(os.environ.get('COMPRESSOR_VMAF_BIN'),'requires pinned libvmaf; not an Android parity result')
class RealParityControls(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory(); cls.root=Path(cls.tmp.name)
        make(cls.root)
        pts,fmt,(w,h),_=parity.inspect_video(cls.root/'source.mp4')
        chosen=[max(i for i,p in enumerate(pts) if p<1000000)]+[i for i,p in enumerate(pts) if 1000000<=p<5000000]
        raw=subprocess.check_output(['ffmpeg','-v','error','-i',str(cls.root/'source.mp4'),'-vf',parity.select_filter(chosen),
            '-vsync','0','-pix_fmt',fmt,'-f','rawvideo','pipe:1'])
        size=w*h*3//2
        cls.header=dict(type='header',schemaVersion=1,model='vmaf_v0.6.1',modelJsonSha256=parity.sha_file(os.environ['COMPRESSOR_VMAF_MODEL']),
            phoneTransform=False,width=w,height=h,p5Method='sorted[floor((n-1)*0.05)]',meanGate=95.5,p5Gate=91.,minGate=84.,minFrames=12,
            measurement={'v0Library':os.environ['COMPRESSOR_VMAF_VERSION']},provenance={'traceOrigin':'offline-synthetic'})
        cls.frames=[]
        for j,i in enumerate(chosen):
            hashes=parity.plane_hashes(raw[j*size:(j+1)*size],w,h)
            cls.frames.append(dict(type='frame',pairIndex=j,sourcePtsUs=pts[i],candidatePtsUs=pts[i],
                context=j==0,scored=j>0,sourceHash=hashes,candidateHash=hashes,vmafV0=99.))
        cls.write_trace()
        cls.run_compare('source.mp4','bootstrap')
        scores=json.loads((cls.root/'bootstrap/libvmaf.json').read_text())['frames']
        for frame,score in zip(cls.frames,scores): frame['vmafV0']=score['metrics']['vmaf']
        cls.write_trace()

    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()

    @classmethod
    def write_trace(cls,frames=None):
        frames=frames or cls.frames; agg=parity.aggregates([f['vmafV0'] for f in frames if f['scored']],cls.header)
        summary=dict(type='summary',complete=True,outcome='SCORED',fedPairs=len(frames),recordedPairs=len(frames),
                     scoredFrames=agg['frames'],windowGatePassed=agg['passed'],**{k:agg[k] for k in ('mean','p5','min')})
        (cls.root/'trace.jsonl').write_text('\n'.join(json.dumps(d) for d in [cls.header]+frames+[summary])+'\n')

    @classmethod
    def run_compare(cls,candidate,out):
        binary=os.environ['COMPRESSOR_VMAF_BIN']
        return parity.compare(argparse.Namespace(trace=cls.root/'trace.jsonl',reference=cls.root/'source.mp4',candidate=cls.root/candidate,
            model=os.environ['COMPRESSOR_VMAF_MODEL'],model_sha256=cls.header['modelJsonSha256'],vmaf_bin=binary,
            lib_version=os.environ['COMPRESSOR_VMAF_VERSION'],tool_version=subprocess.check_output([binary,'--version'],text=True,stderr=subprocess.STDOUT).strip(),
            out=cls.root/out,reference_pts_offset_us=0,candidate_pts_offset_us=0,pts_tolerance_us=1,score_tolerance=.0001))

    def setUp(self): self.write_trace()
    def test_identity_and_remux_replay_the_exact_scores(self):
        for candidate in ('source.mp4','remux.mp4','lossless.mp4'):
            report=self.run_compare(candidate,candidate+'-result')
            self.assertEqual(report['status'],'OFFLINE_CONTROL_REPLAY')
            self.assertTrue(report['decodedPixelsIdentical'])
    def test_artificial_origin_shift_does_not_change_selected_pixels(self):
        frames=copy.deepcopy(self.frames)
        for f in frames: f['candidatePtsUs']+=7000000
        self.write_trace(frames)
        self.assertEqual(self.run_compare('origin_shift.mp4','origin-result')['status'],'OFFLINE_CONTROL_REPLAY')
    def test_internal_missing_frame_is_rejected(self):
        with self.assertRaises(ValueError): self.run_compare('internal_deletion.mp4','deletion-result')
    def test_unchanged_pixels_do_not_hide_changed_color_tags(self):
        report=self.run_compare('color_tags.mp4','color-result')
        self.assertTrue(report['decodedPixelsIdentical']); self.assertFalse(report['colorTagsMatch'])

if __name__=='__main__': unittest.main()
