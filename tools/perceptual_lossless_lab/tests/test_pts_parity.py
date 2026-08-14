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

    def test_tolerance_uses_smallest_interval_observed_so_far(self) -> None:
        self.assertEqual(align([0], [0])["tolerance_us"], FLOOR_US)
        result = align([0, 33_367, 66_734], [0, 33_367, 41_700])
        self.assertEqual(result["tolerance_us"], 8_333 // 2)


if __name__ == "__main__":
    unittest.main()
