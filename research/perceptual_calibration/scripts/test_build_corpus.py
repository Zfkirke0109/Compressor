#!/usr/bin/env python3
"""Unit tests for the pure (ffmpeg-free) logic in build_corpus.py.

Run: python -m unittest test_build_corpus   (or: python test_build_corpus.py)
These cover the accounting/planning that determines corpus size and diversity; the
ffmpeg/ffprobe/libvmaf paths are integration-only and not exercised here.
"""
import json
import tempfile
import unittest
from pathlib import Path

import build_corpus as bc


class TestBitrateMath(unittest.TestCase):
    def test_bpp_bitrate_roundtrip(self):
        br = bc.target_bitrate_bps(0.05, 1920, 1080, 30.0)
        self.assertEqual(br, round(0.05 * 1920 * 1080 * 30))
        bpp = bc.bpp_from_bitrate(br, 1920, 1080, 30.0)
        self.assertAlmostEqual(bpp, 0.05, places=4)

    def test_bpp_zero_dims(self):
        self.assertIsNone(bc.bpp_from_bitrate(1000, 0, 1080, 30))

    def test_parse_fps(self):
        self.assertAlmostEqual(bc.parse_fps("30000/1001"), 29.97, places=2)
        self.assertEqual(bc.parse_fps("24"), 24.0)
        self.assertIsNone(bc.parse_fps("30/0"))
        self.assertIsNone(bc.parse_fps(None))


class TestParsers(unittest.TestCase):
    def test_resolutions(self):
        self.assertEqual(bc.parse_resolutions("1280x720,1920x1080"), [(1280, 720), (1920, 1080)])
        self.assertEqual(bc.parse_resolutions("keep"), [None])
        self.assertEqual(bc.parse_resolutions(""), [None])

    def test_float_list(self):
        self.assertEqual(bc.parse_float_list("0.03, 0.069 ,0.08"), [0.03, 0.069, 0.08])


class TestSegmentStarts(unittest.TestCase):
    def test_normal_spacing(self):
        starts = bc.segment_starts(60.0, 3, 4.0)
        self.assertEqual(len(starts), 3)
        self.assertTrue(all(0 <= s <= 60 - 4 for s in starts))
        self.assertLess(starts[0], starts[1])
        self.assertLess(starts[1], starts[2])

    def test_non_overlapping(self):
        seg = 4.0
        for dur, n in [(60.0, 3), (20.0, 5), (12.0, 4), (9.0, 3)]:
            starts = bc.segment_starts(dur, n, seg)
            for a, b in zip(starts, starts[1:]):
                self.assertGreaterEqual(b - a, seg - 1e-9,
                                        f"segments overlap for dur={dur} n={n}: {starts}")
            for s in starts:
                self.assertLessEqual(s + seg, dur + 1e-9)

    def test_caps_at_fitting_count(self):
        # 12s master, 4s segments -> at most ~2-3 non-overlapping windows, never 5
        self.assertLessEqual(len(bc.segment_starts(12.0, 5, 4.0)), 3)

    def test_too_short_returns_empty(self):
        self.assertEqual(bc.segment_starts(3.0, 3, 4.0), [])

    def test_tight_returns_single_centered(self):
        starts = bc.segment_starts(4.5, 3, 4.0)
        self.assertEqual(len(starts), 1)
        self.assertTrue(0 <= starts[0] <= 0.5)

    def test_zero_guards(self):
        self.assertEqual(bc.segment_starts(0, 3, 4), [])
        self.assertEqual(bc.segment_starts(60, 0, 4), [])


class TestDisputedBand(unittest.TestCase):
    def test_band_edges(self):
        self.assertFalse(bc.in_disputed_band(0.029))
        self.assertTrue(bc.in_disputed_band(0.03))
        self.assertTrue(bc.in_disputed_band(0.068))
        self.assertFalse(bc.in_disputed_band(0.069))  # half-open [lo, hi)


class TestPlanMaster(unittest.TestCase):
    def _meta(self):
        return {"path": "/m/movie.mp4", "stem": "movie", "width": 1920, "height": 1080,
                "fps": 30.0, "duration_s": 60.0}

    def test_expansion_count(self):
        cfg = bc.BuildConfig(segments_per_master=3, resolutions=[(1280, 720)],
                             bpp_ladder=[0.03, 0.05, 0.08], codecs=["hevc", "h264"])
        specs = bc.plan_master(self._meta(), cfg)
        self.assertEqual(len(specs), 3 * 1 * 2 * 3)
        ids = {s.clip_id for s in specs}
        self.assertEqual(len(ids), len(specs), "clip ids must be unique")

    def test_keep_resolution(self):
        cfg = bc.BuildConfig(segments_per_master=1, resolutions=[None],
                             bpp_ladder=[0.05], codecs=["hevc"])
        spec = bc.plan_master(self._meta(), cfg)[0]
        self.assertEqual((spec.width, spec.height), (1920, 1080))

    def test_fps_override(self):
        cfg = bc.BuildConfig(segments_per_master=1, resolutions=[None],
                             bpp_ladder=[0.05], codecs=["hevc"], fps_override=24.0)
        spec = bc.plan_master(self._meta(), cfg)[0]
        self.assertEqual(spec.fps, 24.0)
        self.assertEqual(spec.target_bitrate_bps, bc.target_bitrate_bps(0.05, 1920, 1080, 24.0))


class TestBudgetSelection(unittest.TestCase):
    def _specs(self, n_masters=4):
        cfg = bc.BuildConfig(segments_per_master=3, resolutions=[(1280, 720)],
                             bpp_ladder=list(bc.DEFAULT_BPP_LADDER), codecs=["hevc", "h264"])
        specs = []
        for i in range(n_masters):
            meta = {"path": f"/m/movie{i}.mp4", "stem": f"movie{i}", "width": 1920,
                    "height": 1080, "fps": 30.0, "duration_s": 60.0}
            specs.extend(bc.plan_master(meta, cfg))
        return specs

    def test_budget_respected(self):
        specs = self._specs()
        budget = 50 * 1024 * 1024  # 50 MB
        chosen = bc.select_within_budget(specs, budget, 10_000, seed=1)
        self.assertLessEqual(sum(s.estimated_bytes() for s in chosen), budget)
        self.assertGreater(len(chosen), 0)

    def test_max_clips_cap(self):
        specs = self._specs()
        chosen = bc.select_within_budget(specs, 0, 5, seed=1)  # 0 budget => unlimited bytes
        self.assertEqual(len(chosen), 5)

    def test_disputed_band_prioritized(self):
        specs = self._specs()
        chosen = bc.select_within_budget(specs, 0, 12, seed=1)
        # With disputed band ranked first, an early, small selection should be all in-band.
        self.assertTrue(all(s.in_disputed_band() for s in chosen),
                        "small selection should be dominated by the disputed band")

    def test_deterministic(self):
        specs = self._specs()
        a = [s.clip_id for s in bc.select_within_budget(specs, 30 * 1024 * 1024, 100, seed=7)]
        b = [s.clip_id for s in bc.select_within_budget(specs, 30 * 1024 * 1024, 100, seed=7)]
        self.assertEqual(a, b)


class TestMetricsAndLabel(unittest.TestCase):
    def test_find_metric_nested(self):
        report = {"a": {"summary": {"vmaf_mean": 96.1, "other": True}}, "b": [1, {"vmaf_min": 88.0}]}
        self.assertEqual(bc.find_metric(report, "vmaf_mean"), 96.1)
        self.assertEqual(bc.find_metric(report, "vmaf_min"), 88.0)
        self.assertIsNone(bc.find_metric(report, "nonexistent"))

    def test_find_metric_ignores_bool(self):
        self.assertIsNone(bc.find_metric({"vmaf_mean": True}, "vmaf_mean"))

    def test_label_pass(self):
        self.assertTrue(bc.label_from_metrics(96.0, 92.0, 85.0, 0.90))

    def test_label_fail_quality(self):
        self.assertFalse(bc.label_from_metrics(96.0, 90.0, 85.0, 0.90))  # p5 below 91.0

    def test_label_fail_no_size_drop(self):
        self.assertFalse(bc.label_from_metrics(97.0, 93.0, 88.0, 1.0))

    def test_label_unlabeled_on_missing(self):
        self.assertIsNone(bc.label_from_metrics(None, 92.0, 85.0, 0.9))
        self.assertIsNone(bc.label_from_metrics(96.0, 92.0, 85.0, None))


class TestManifestIO(unittest.TestCase):
    def test_roundtrip(self):
        rows = [{"clip_id": "a", "path": "clips/a.mp4", "target_bpp": 0.05, "size_bytes": 123},
                {"clip_id": "b", "path": "clips/b.mp4", "target_bpp": 0.08, "size_bytes": 456}]
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / bc.MANIFEST_NAME
            bc.write_manifest(path, rows, bc.BUILD_COLUMNS)
            back = bc.read_manifest(path)
        self.assertEqual(len(back), 2)
        self.assertEqual(back[0]["clip_id"], "a")
        self.assertEqual(back[1]["target_bpp"], "0.08")  # CSV values read back as strings


if __name__ == "__main__":
    unittest.main(verbosity=2)
