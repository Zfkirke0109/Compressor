import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile
from audit_pl_capture import load_run, window_rows, classify, source_split


class AuditTest(unittest.TestCase):
    def test_overlapping_exports_are_one_run_and_conflicts_fail(self):
        with tempfile.TemporaryDirectory() as d:
            paths = [Path(d) / (str(i) + '.zip') for i in range(2)]
            records = [{'type': 'session_start', 'mode': 'Perceptually Lossless'}, {'type': 'session_summary'}]
            for p in paths:
                with ZipFile(p, 'w') as z:
                    z.writestr('runs/batch_1/session.jsonl', '\n'.join(map(json.dumps, records)))
            self.assertEqual(len(load_run(paths, 'batch_1')[0]), 2)
            with ZipFile(paths[1], 'w') as z:
                z.writestr('runs/batch_1/session.jsonl', '{}')
            with self.assertRaises(ValueError): load_run(paths, 'batch_1')

    def test_missing_frame_counts_do_not_become_measurements(self):
        stage = dict(stage='probe_rung', probeMean='94', probeP5='93', probeMin='90')
        self.assertEqual(window_rows(stage), [])
        self.assertEqual(classify({'terminal': 'SKIPPED_WOULD_DEGRADE'}, [], []), 'unexplained')

    def test_window_series_length_mismatch_fails(self):
        with self.assertRaises(ValueError):
            window_rows(dict(stage='probe_rung', probeMean='94;95', probeP5='93', probeMin='90', probeFrames='36'))

    def test_source_disjoint_split_is_stable(self):
        self.assertEqual(source_split('a'*64), source_split('a'*64))
        self.assertIsNone(source_split(None))


if __name__ == '__main__': unittest.main()
