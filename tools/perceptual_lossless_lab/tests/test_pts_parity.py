from __future__ import annotations

import unittest

from pl_lab.operations.quality import _pts_result


FLOOR_US = 4_000
MAX_DROPS = 8


def cfr(count: int, interval_us: int, offset_us: int = 0) -> list[int]:
    return [offset_us + index * interval_us for index in range(count)]


def align(reference: list[int], distorted: list[int]) -> dict:
    return _pts_result(reference, distorted, tolerance_floor_us=FLOOR_US, max_drops=MAX_DROPS)


class PtsAlignerKotlinParityTests(unittest.TestCase):
    def test_aligned_cfr_streams_pair_everything_without_drops(self) -> None:
        pts = cfr(36, 33_367)
        result = align(pts, pts)
        self.assertEqual(result["status"], "ALIGNED")
        self.assertEqual(result["compared_frames"], 36)
        self.assertEqual(result["reference_dropped"] + result["candidate_dropped"], 0)

    def test_measured_leading_distorted_frame_is_repaired(self) -> None:
        result = align(cfr(35, 33_367, 33_367), cfr(35, 33_367))
        self.assertEqual(result["status"], "ALIGNED")
        self.assertEqual(result["candidate_dropped"], 1)
        self.assertEqual(result["reference_dropped"], 0)
        self.assertEqual(result["compared_frames"], 34)

    def test_leading_reference_frame_is_repaired(self) -> None:
        result = align(cfr(35, 33_367), cfr(35, 33_367, 33_367))
        self.assertEqual(result["status"], "ALIGNED")
        self.assertEqual(result["reference_dropped"], 1)
        self.assertEqual(result["compared_frames"], 34)

    def test_matching_vfr_streams_pair_without_drops(self) -> None:
        pts = [0, 16_000, 33_000, 383_000, 400_000, 750_000, 1_100_000]
        result = align(pts, pts)
        self.assertEqual(result["status"], "ALIGNED")
        self.assertEqual(result["compared_frames"], len(pts))
        self.assertEqual(result["reference_dropped"] + result["candidate_dropped"], 0)

    def test_submillisecond_muxer_jitter_pairs(self) -> None:
        reference = cfr(30, 33_367)
        result = align(reference, [value + 900 for value in reference])
        self.assertEqual(result["status"], "ALIGNED")
        self.assertEqual(result["compared_frames"], 30)

    def test_retimed_double_cadence_fails_closed(self) -> None:
        result = align(cfr(30, 33_333), cfr(60, 16_667, 8_000))
        self.assertEqual(result["status"], "MISALIGNED")

    def test_internal_missing_frames_fail_at_first_gap_without_repair(self) -> None:
        reference = cfr(30, 33_333)
        distorted = [value for index, value in enumerate(reference) if index not in range(10, 13)]
        result = align(reference, distorted)
        self.assertEqual(result["status"], "MISALIGNED")
        self.assertEqual(result["compared_frames"], 10)
        self.assertEqual(result["reference_dropped"] + result["candidate_dropped"], 0)

    def test_leading_repair_never_hides_later_internal_gap(self) -> None:
        interval = 33_367
        reference = cfr(30, interval, interval)
        distorted = [value for index, value in enumerate(cfr(30, interval)) if index != 15]
        result = align(reference, distorted)
        self.assertEqual(result["status"], "MISALIGNED")
        self.assertEqual(result["candidate_dropped"], 1)

    def test_tolerance_is_fixed_and_never_widens_with_the_frame_interval(self) -> None:
        # Kotlin parity: PtsAligner.toleranceUs() returns TOLERANCE_FLOOR_US unconditionally.
        # The superseded rule returned half the smallest observed interval, so this asserted
        # 8_333 // 2 here and 20_000 at 25 fps.
        self.assertEqual(align([0], [0])["tolerance_us"], FLOOR_US)
        self.assertEqual(align(cfr(3, 40_000), cfr(3, 40_000))["tolerance_us"], FLOOR_US)
        result = align([0, 33_367, 66_734], [0, 33_367, 41_700])
        self.assertEqual(result["tolerance_us"], FLOOR_US)
        self.assertEqual(result["status"], "MISALIGNED")

    def test_half_frame_grid_offset_at_25fps_fails_closed(self) -> None:
        # Device signature (job 80bff5a136f7, 676x1080 @25fps): heads 20.0 ms apart, exactly
        # half a 40 ms interval, were SCORED by the superseded tolerance and returned VMAF
        # 1.860/0.000/0.000 for an HEVC ratio-0.70 encode. Not a measurement.
        result = align(cfr(30, 40_000), cfr(30, 40_000, 20_000))
        self.assertEqual(result["status"], "MISALIGNED")
        self.assertEqual(result["compared_frames"], 0)
        self.assertEqual(
            result["reference_dropped"] + result["candidate_dropped"], MAX_DROPS
        )
        self.assertEqual(result["reason"], "leading_drop_budget_exhausted")

    def test_half_frame_offset_fails_at_every_rate_the_old_rule_scored(self) -> None:
        # 24 / 25 / 30 fps: superseded tolerance was 20.8 / 20.0 / 16.7 ms.
        for interval in (41_667, 40_000, 33_333):
            with self.subTest(interval_us=interval):
                result = align(cfr(30, interval), cfr(30, interval, interval // 2))
                self.assertEqual(result["status"], "MISALIGNED")
                self.assertEqual(result["compared_frames"], 0)

    def test_worst_case_ms_granular_clip_start_still_pairs(self) -> None:
        # exportClip cuts with setStartPositionMs(startUs / 1000), leaving a constant skew of
        # -(startUs % 1000): at most 999 us at any frame rate. The floor exists to absorb it.
        for interval in (41_667, 40_000, 33_367, 16_667, 8_333):
            with self.subTest(interval_us=interval):
                reference = cfr(30, interval)
                result = align(reference, [value - 999 for value in reference])
                self.assertEqual(result["status"], "ALIGNED")
                self.assertEqual(result["compared_frames"], 30)
                self.assertEqual(
                    result["reference_dropped"] + result["candidate_dropped"], 0
                )

    def test_documented_one_frame_leading_offset_still_repairs(self) -> None:
        # capture batch_20260716_185345: constant 33.2 ms skew at 29.97 fps. One drop, then a
        # -167 us residual that sits inside the 4 ms floor.
        result = align(cfr(35, 33_367, 33_200), cfr(35, 33_367))
        self.assertEqual(result["status"], "ALIGNED")
        self.assertEqual(result["candidate_dropped"], 1)
        self.assertEqual(result["reference_dropped"], 0)
        self.assertEqual(result["compared_frames"], 34)
        self.assertLessEqual(result["max_abs_skew_us"], FLOOR_US)


if __name__ == "__main__":
    unittest.main()
