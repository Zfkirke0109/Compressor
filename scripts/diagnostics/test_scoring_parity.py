#!/usr/bin/env python3
import copy
import hashlib
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from scoring_parity import replay, match_pts, verify_archive, plane_hashes, select_filter


def trace():
    header={'type':'header','schemaVersion':1,'p5Method':'sorted[floor((n-1)*0.05)]',
            'meanGate':95.5,'p5Gate':91.,'minGate':84.,'minFrames':12}
    rows=[dict(type='frame',pairIndex=i,sourcePtsUs=1_000_000+i*33333,candidatePtsUs=i*33333,
               context=i==0,scored=i!=0,vmafV0=1. if i==0 else 99.) for i in range(13)]
    summary=dict(type='summary',complete=True,outcome='SCORED',fedPairs=13,recordedPairs=13,
                 scoredFrames=12,mean=99.,p5=99.,min=99.,windowGatePassed=True)
    return [header]+rows+[summary]


class ParityTest(unittest.TestCase):
    def test_context_is_not_scored_and_p5_is_not_interpolated(self):
        data=trace(); data[1]['vmafV0']=0
        self.assertEqual(replay(data)['min'],99.)
        data[2]['vmafV0']=90.; data[-1].update(mean=98.25,p5=90.,min=90.,windowGatePassed=False)
        self.assertEqual(replay(data)['p5'],90.)
    def test_partial_or_nonfinite_scores_cannot_replay(self):
        for mutate in [lambda d:d[-1].update(complete=False),lambda d:d[4].update(vmafV0=float('nan')),
                       lambda d:d.pop(3),lambda d:d[-1].update(windowGatePassed=False)]:
            data=trace(); mutate(data)
            with self.assertRaises(ValueError): replay(data)
    def test_origin_shift_is_preserved_and_vfr_is_matched_by_pts(self):
        self.assertEqual(match_pts([100,133,171,199],[100,171,199],0),[(0,0),(2,0),(3,0)])
        with self.assertRaises(ValueError): match_pts([0,33,71,99],[100,171,199],0)
    def test_internal_frame_deletion_and_ambiguous_jitter_are_rejected(self):
        with self.assertRaises(ValueError): match_pts([0,33,99],[0,33,66,99],0)
        with self.assertRaises(ValueError): match_pts([0,1,33],[0,33],1)
    def test_chroma_hashes_catch_changed_pixels_without_changed_tags(self):
        a=plane_hashes(bytes([1,2,3,4,5,6]),2,2)
        b=plane_hashes(bytes([1,2,3,4,5,7]),2,2)
        self.assertEqual(a['y'],b['y']); self.assertNotEqual(a['v'],b['v'])
        self.assertEqual(select_filter([0,2]),r'select=eq(n\,0)+eq(n\,2)')
    def test_manifest_tampering_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'capture.zip'
            for payload,valid in [(b'abc',True),(b'abd',False)]:
                with zipfile.ZipFile(path,'w') as z:
                    z.writestr('a',payload)
                    z.writestr('manifest.json',json.dumps(dict(manifestVersion=2,files=[dict(path='a',bytes=3,sha256=hashlib.sha256(b'abc').hexdigest())])))
                if valid: self.assertEqual(verify_archive(path)['verifiedEntries'],1)
                else:
                    with self.assertRaises(ValueError): verify_archive(path)

if __name__=='__main__': unittest.main()
