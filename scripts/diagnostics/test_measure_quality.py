import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


sys.path.insert(0, os.path.dirname(__file__))
import measure_quality as mq  # noqa: E402


def _video(width=64, height=64, rotation=0):
    display_width, display_height = (height, width) if rotation in (90, 270) else (width, height)
    return {
        "width": width,
        "height": height,
        "display_width": display_width,
        "display_height": display_height,
        "rotation_degrees_normalized": rotation,
        "sample_aspect_ratio": "1/1",
        "pix_fmt": "yuv420p",
        "bits_per_raw_sample": "8",
        "color_range": "tv",
        "color_space": "bt709",
        "color_transfer": "bt709",
        "color_primaries": "bt709",
        "side_data": [],
        "time_base": "1/1000",
        "start_time": "0",
    }


def _media(video=None, duration=1.0, audio_streams=None):
    return {
        "format": {"duration": str(duration)},
        "video": video or _video(),
        "audio_streams": audio_streams or [],
    }


def _timeline(points, interlaced_flags=None, top_field_first_flags=None):
    deltas = [points[index] - points[index - 1] for index in range(1, len(points))]
    median = deltas[len(deltas) // 2] if deltas else 0.0
    interlaced_flags = (
        list(interlaced_flags) if interlaced_flags is not None else [0] * len(points)
    )
    top_field_first_flags = (
        list(top_field_first_flags)
        if top_field_first_flags is not None else [0] * len(points)
    )
    unknown = sum(flag not in (0, 1) for flag in interlaced_flags)
    interlaced = sum(flag == 1 for flag in interlaced_flags)
    return {
        "first_pts_seconds": 0.0,
        "normalized_pts": points,
        "decoded_frame_count": len(points),
        "frame_timeline_duration_seconds": points[-1] + median,
        "cadence_kind": "CFR",
        "time_base_seconds": 0.001,
        "delta_min_seconds": min(deltas) if deltas else None,
        "delta_max_seconds": max(deltas) if deltas else None,
        "delta_mean_seconds": sum(deltas) / len(deltas) if deltas else None,
        "delta_median_seconds": median,
        "delta_stddev_seconds": 0.0,
        "measured_fps": 1.0 / median if median else None,
        "interlaced_flags": interlaced_flags,
        "top_field_first_flags": top_field_first_flags,
        "decoded_interlace": {
            "known_frame_count": len(points) - unknown,
            "unknown_frame_count": unknown,
            "progressive_frame_count": sum(flag == 0 for flag in interlaced_flags),
            "interlaced_frame_count": interlaced,
            "interlaced_top_field_unknown_count": sum(
                interlaced_flags[index] == 1
                and top_field_first_flags[index] not in (0, 1)
                for index in range(len(points))
            ),
            "all_frames_proven_progressive": unknown == 0 and interlaced == 0,
        },
    }


def _audio_stream(duration="0.064", sample_rate=48000):
    return {
        "codec_name": "aac",
        "profile": "LC",
        "sample_fmt": "fltp",
        "sample_rate": str(sample_rate),
        "channels": 2,
        "channel_layout": "stereo",
        "duration": duration,
        "bit_rate": "128000",
        "time_base": f"1/{sample_rate}",
    }


def _audio_frames(last_samples=1024, sample_rate=48000, start_pts=0.0):
    return [
        {"pts": start_pts, "duration": 1024 / sample_rate, "nb_samples": 1024},
        {"pts": start_pts + 1024 / sample_rate,
         "duration": 1024 / sample_rate, "nb_samples": 1024},
        {"pts": start_pts + 2048 / sample_rate,
         "duration": last_samples / sample_rate, "nb_samples": last_samples},
    ]


def _schema2_report(pair_id="fixture", source_size=100, output_size=80):
    frames = [
        {"frame_index": 0, "vmaf": 96.0, "ssim": 0.995, "psnr_y": 44.0,
         "psnr_cb": 45.0, "psnr_cr": 46.0},
        {"frame_index": 1, "vmaf": 97.0, "ssim": 0.996, "psnr_y": 45.0,
         "psnr_cb": 46.0, "psnr_cr": 47.0},
    ]
    metrics = mq._summarize_metric_frames(frames, "fixture-libvmaf")
    report = {
        "schema_version": 2,
        "pair_id": pair_id,
        "status": "COMPLETE",
        "report_complete": True,
        "measurement_valid": True,
        "classification": mq._classification(metrics),
        "structural_validation": {"passed": True},
        "metric_input_order": {
            "first_main_distorted": "output",
            "second_reference": "source",
        },
        "filtergraph_provenance": {
            "scale_filter": False,
            "fps_filter": False,
            "explicit_autorotation": True,
            "timestamp_normalization": "settb=AVTB,setpts=PTS-STARTPTS",
            "framesync": {
                "shortest": True,
                "eof_action": "endall",
                "repeatlast": False,
                "ts_sync_mode": "nearest",
            },
            "log_path_windows_drive_colon_escaped": True,
            "trim": {"enabled": False, "mode": None},
        },
        "tool_provenance": {
            "vmaf_model": mq.VMAF_MODEL,
            "features": ["vmaf", "float_ssim", "psnr"],
        },
        "file_sizes": {"source_bytes": source_size, "output_bytes": output_size},
        "metric_chunking": {
            "enabled": False,
            "expected_total_frame_count": 2,
            "retained_total_frame_count": 2,
            "expected_chunk_count": 1,
            "completed_chunk_count": 1,
            "chunks": [{
                "chunk_index": 0,
                "start_frame_inclusive": 0,
                "end_frame_exclusive": 2,
                "expected_frame_count": 2,
                "analysis_start_frame_inclusive": 0,
                "analysis_end_frame_exclusive": 2,
                "expected_analysis_frame_count": 2,
                "analysis_metric_frame_count": 2,
                "retained_metric_frame_count": 2,
                "seek_applied": False,
                "seek_frame_index": 0,
                "local_trim_start_frame": None,
                "local_trim_end_frame": None,
                "status": "COMPLETE",
            }],
        },
    }
    report.update(metrics)
    return report


class MeasureQualityTest(unittest.TestCase):
    def test_windows_filter_path_escapes_drive_colon(self):
        escaped = mq._escape_filter_path(r"C:\temp\quality report.json")
        self.assertIn(r"C\:/", escaped)
        self.assertNotIn("\\", escaped.replace(r"\:", ""))

    def test_harmonic_mean_fails_low_on_zero(self):
        self.assertEqual(0.0, mq._harmonic_mean([99.0, 0.0, 98.0]))

    def test_demanding_pl_classification(self):
        strong = {
            "vmaf_mean": 96.0,
            "vmaf_1pct_low": 91.0,
            "vmaf_min": 82.0,
            "ssim_mean": 0.996,
            "ssim_min": 0.96,
            "psnr_mean": 45.0,
            "psnr_min": 35.0,
        }
        self.assertEqual("PL_STRONGLY_SUPPORTED", mq._classification(strong))
        strong["vmaf_1pct_low"] = 89.99
        self.assertEqual("PL_PLAUSIBLE_WITH_CAVEATS", mq._classification(strong))

    def test_equivalent_rotation_representation_passes_without_scaling(self):
        points = [0.0, 0.04, 0.08]
        source = _media(_video(width=64, height=96, rotation=0), duration=0.12)
        output = _media(_video(width=96, height=64, rotation=270), duration=0.12)
        result = mq._compare_structure(source, output, _timeline(points), _timeline(points))
        self.assertTrue(result["passed"])
        self.assertTrue(result["display_geometry"]["display_equivalent"])
        self.assertFalse(result["display_geometry"]["scaling_applied"])

    def test_progressive_field_order_omission_is_warning_only_when_decoded(self):
        points = [0.0, 0.04, 0.08]
        source_video = _video()
        source_video["field_order"] = "progressive"
        output_video = _video()
        result = mq._compare_structure(
            _media(source_video, 0.12),
            _media(output_video, 0.12),
            _timeline(points),
            _timeline(points),
        )
        self.assertTrue(result["comparison_passed"])
        self.assertTrue(result["pl_integrity_passed"])
        self.assertIn(
            "VIDEO_FIELD_ORDER_METADATA_OMITTED",
            [item["code"] for item in result["warnings"]],
        )

    def test_decoded_interlace_unknown_or_mismatch_invalidates_comparison(self):
        points = [0.0, 0.04, 0.08]
        mismatch = mq._compare_structure(
            _media(duration=0.12),
            _media(duration=0.12),
            _timeline(points, interlaced_flags=[0, 0, 0]),
            _timeline(points, interlaced_flags=[0, 1, 0]),
        )
        self.assertFalse(mismatch["comparison_passed"])
        self.assertFalse(mismatch["pl_integrity_passed"])
        mismatch_codes = [item["code"] for item in mismatch["hard_failures"]]
        self.assertIn("DECODED_INTERLACED_CONTENT_UNSUPPORTED", mismatch_codes)
        self.assertIn("DECODED_INTERLACE_MISMATCH", mismatch_codes)

        unknown = mq._compare_structure(
            _media(duration=0.12),
            _media(duration=0.12),
            _timeline(points),
            _timeline(points, interlaced_flags=[0, None, 0]),
        )
        self.assertFalse(unknown["comparison_passed"])
        self.assertIn(
            "DECODED_INTERLACE_STATUS_UNKNOWN",
            [item["code"] for item in unknown["comparison_failures"]],
        )

    def test_resolution_loss_fails_preflight(self):
        points = [0.0, 0.04, 0.08]
        result = mq._compare_structure(
            _media(_video(64, 64), 0.12),
            _media(_video(32, 32), 0.12),
            _timeline(points),
            _timeline(points),
        )
        self.assertFalse(result["passed"])
        self.assertFalse(result["comparison_passed"])
        self.assertFalse(result["pl_integrity_passed"])
        self.assertIn("DISPLAY_RESOLUTION_MISMATCH", [item["code"] for item in result["hard_failures"]])

    def test_truncation_fails_preflight(self):
        source_points = [0.0, 0.04, 0.08]
        output_points = [0.0, 0.04]
        result = mq._compare_structure(
            _media(duration=0.12),
            _media(duration=0.08),
            _timeline(source_points),
            _timeline(output_points),
        )
        codes = [item["code"] for item in result["hard_failures"]]
        self.assertFalse(result["passed"])
        self.assertIn("DECODED_FRAME_COUNT_MISMATCH", codes)
        self.assertIn("VIDEO_TIMELINE_DURATION_MISMATCH", codes)

    def test_audio_timeline_and_drift_are_fail_closed(self):
        points = [0.0, 0.04, 0.08]
        stream = _audio_stream()
        source_audio = mq._audio_timeline(_audio_frames(), stream, "source", 0)
        output_audio = mq._audio_timeline(_audio_frames(), stream, "output", 0)
        result = mq._compare_structure(
            _media(duration=0.12, audio_streams=[stream]),
            _media(duration=0.12, audio_streams=[stream]),
            _timeline(points),
            _timeline(points),
            [source_audio],
            [output_audio],
        )
        self.assertTrue(result["passed"])
        decoded = result["audio"]["checks"][0]["decoded"]
        self.assertTrue(decoded["decoded_sample_count_match"])
        self.assertEqual(0.0, decoded["av_end_drift_delta_seconds"])

        changed = mq._audio_timeline(
            _audio_frames(last_samples=960, start_pts=0.100), stream, "output", 0
        )
        failed = mq._compare_structure(
            _media(duration=0.12, audio_streams=[stream]),
            _media(duration=0.12, audio_streams=[stream]),
            _timeline(points),
            _timeline(points),
            [source_audio],
            [changed],
        )
        codes = [item["code"] for item in failed["hard_failures"]]
        self.assertIn("AUDIO_DECODED_SAMPLE_COUNT_MISMATCH", codes)
        self.assertIn("AUDIO_FRAME_SAMPLE_LAYOUT_MISMATCH", codes)
        self.assertIn("AUDIO_VIDEO_START_DRIFT", codes)

    def test_one_access_unit_audio_padding_is_warning_with_derived_tolerance(self):
        points = [0.0, 0.04, 0.08]
        sample_rate = 44100
        source_stream = _audio_stream(duration="0.10000", sample_rate=sample_rate)
        output_stream = _audio_stream(duration="0.14644", sample_rate=sample_rate)
        source_audio = mq._audio_timeline(
            _audio_frames(last_samples=960, sample_rate=sample_rate),
            source_stream,
            "source",
            0,
        )
        output_audio = mq._audio_timeline(
            _audio_frames(
                last_samples=1024,
                sample_rate=sample_rate,
                start_pts=0.0,
            ),
            output_stream,
            "output",
            0,
        )
        result = mq._compare_structure(
            _media(duration=0.12, audio_streams=[source_stream]),
            _media(duration=0.12, audio_streams=[output_stream]),
            _timeline(points),
            _timeline(points),
            [source_audio],
            [output_audio],
        )
        self.assertTrue(result["comparison_passed"])
        self.assertTrue(result["pl_integrity_passed"])
        self.assertTrue(result["passed"])
        warning = next(
            item for item in result["warnings"]
            if item["code"] == "AUDIO_ENCODER_PADDING_VARIATION"
        )
        self.assertEqual(1024, warning["access_unit_samples"])
        self.assertAlmostEqual(1024 / sample_rate, warning["derived_padding_tolerance_seconds"])
        self.assertAlmostEqual(0.050, warning["padding_tolerance_cap_seconds"])
        self.assertEqual(64, warning["decoded_sample_count_delta"])
        self.assertLess(warning["max_normalized_pts_delta_seconds"], 1024 / sample_rate)
        self.assertTrue(
            result["audio"]["checks"][0]["decoded"]["padding_variation_accepted"]
        )
        metric_only, final = mq._classification_with_integrity({
            "vmaf_mean": 96.0,
            "vmaf_1pct_low": 91.0,
            "vmaf_min": 82.0,
            "ssim_mean": 0.996,
            "ssim_min": 0.96,
            "psnr_mean": 45.0,
            "psnr_min": 35.0,
        }, True, result["warnings"])
        self.assertEqual("PL_STRONGLY_SUPPORTED", metric_only)
        self.assertEqual("PL_PLAUSIBLE_WITH_CAVEATS", final)

        truncated_audio = mq._audio_timeline(
            _audio_frames(
                last_samples=896,
                sample_rate=sample_rate,
                start_pts=64 / sample_rate,
            ),
            output_stream,
            "output",
            0,
        )
        truncated = mq._compare_structure(
            _media(duration=0.12, audio_streams=[source_stream]),
            _media(duration=0.12, audio_streams=[output_stream]),
            _timeline(points),
            _timeline(points),
            [source_audio],
            [truncated_audio],
        )
        self.assertNotIn(
            "AUDIO_ENCODER_PADDING_VARIATION",
            [item["code"] for item in truncated["warnings"]],
        )
        self.assertIn(
            "AUDIO_DECODED_SAMPLE_COUNT_MISMATCH",
            [item["code"] for item in truncated["pl_integrity_failures"]],
        )

    def test_first_access_unit_encoder_priming_is_normalized_exactly(self):
        points = [0.0, 0.04, 0.08]
        sample_rate = 44100
        tick = 1 / sample_rate
        first_au_delta = 64
        source_first_pts = 0.226667
        output_first_pts = source_first_pts - first_au_delta / sample_rate - tick

        def frames(first_pts, sample_counts):
            result = []
            pts = first_pts
            for samples in sample_counts:
                result.append({
                    "pts": pts,
                    "duration": samples / sample_rate,
                    "nb_samples": samples,
                })
                pts += samples / sample_rate
            return result

        source_stream = _audio_stream(duration="36.45700", sample_rate=sample_rate)
        output_stream = _audio_stream(duration="36.50344", sample_rate=sample_rate)
        source_audio = mq._audio_timeline(
            frames(source_first_pts, [960, 1024, 1024]),
            source_stream,
            "source",
            0,
        )
        output_audio = mq._audio_timeline(
            frames(output_first_pts, [1024, 1024, 1024]),
            output_stream,
            "output",
            0,
        )
        accepted = mq._compare_structure(
            _media(duration=0.12, audio_streams=[source_stream]),
            _media(duration=0.12, audio_streams=[output_stream]),
            _timeline(points),
            _timeline(points),
            [source_audio],
            [output_audio],
        )
        self.assertTrue(accepted["comparison_passed"])
        self.assertTrue(accepted["pl_integrity_passed"])
        warning = next(
            item for item in accepted["warnings"]
            if item["code"] == "AUDIO_ENCODER_PRIMING_VARIATION"
        )
        self.assertEqual(960, warning["source_first_access_unit_samples"])
        self.assertEqual(1024, warning["output_first_access_unit_samples"])
        self.assertEqual(64, warning["first_access_unit_sample_delta"])
        self.assertEqual(1024, warning["established_access_unit_samples"])
        self.assertAlmostEqual(64 / sample_rate,
                               warning["priming_duration_from_sample_delta_seconds"])
        self.assertAlmostEqual(tick, warning["first_pts_priming_residual_seconds"])
        self.assertLessEqual(
            warning["max_absolute_pts_after_first_delta_seconds"],
            warning["base_audio_tolerance_seconds"],
        )
        self.assertLessEqual(
            warning["absolute_effective_end_delta_seconds"],
            warning["base_audio_tolerance_seconds"],
        )
        decoded = accepted["audio"]["checks"][0]["decoded"]
        self.assertTrue(decoded["priming_variation_accepted"])
        self.assertFalse(decoded["padding_variation_accepted"])
        metric_only, final = mq._classification_with_integrity({
            "vmaf_mean": 96.0,
            "vmaf_1pct_low": 91.0,
            "vmaf_min": 82.0,
            "ssim_mean": 0.996,
            "ssim_min": 0.96,
            "psnr_mean": 45.0,
            "psnr_min": 35.0,
        }, True, accepted["warnings"])
        self.assertEqual("PL_STRONGLY_SUPPORTED", metric_only)
        self.assertEqual("PL_PLAUSIBLE_WITH_CAVEATS", final)

        arbitrary_start = mq._audio_timeline(
            frames(output_first_pts - 0.005, [1024, 1024, 1024]),
            output_stream,
            "output",
            0,
        )
        rejected = mq._compare_structure(
            _media(duration=0.12, audio_streams=[source_stream]),
            _media(duration=0.12, audio_streams=[output_stream]),
            _timeline(points),
            _timeline(points),
            [source_audio],
            [arbitrary_start],
        )
        self.assertFalse(rejected["pl_integrity_passed"])
        self.assertNotIn(
            "AUDIO_ENCODER_PRIMING_VARIATION",
            [item["code"] for item in rejected["warnings"]],
        )
        self.assertIn(
            "AUDIO_VIDEO_START_DRIFT",
            [item["code"] for item in rejected["pl_integrity_failures"]],
        )

    def test_interior_audio_repartition_is_not_padding(self):
        points = [0.0, 0.04, 0.08]
        sample_rate = 44100
        stream = _audio_stream(duration=str(3072 / sample_rate), sample_rate=sample_rate)
        source_frames = [
            {"pts": 0.0, "duration": 1024 / sample_rate, "nb_samples": 1024},
            {"pts": 1024 / sample_rate, "duration": 1024 / sample_rate,
             "nb_samples": 1024},
            {"pts": 2048 / sample_rate, "duration": 1024 / sample_rate,
             "nb_samples": 1024},
        ]
        output_frames = [
            {"pts": 0.0, "duration": 512 / sample_rate, "nb_samples": 512},
            {"pts": 512 / sample_rate, "duration": 1536 / sample_rate,
             "nb_samples": 1536},
            {"pts": 2048 / sample_rate, "duration": 1024 / sample_rate,
             "nb_samples": 1024},
        ]
        result = mq._compare_structure(
            _media(duration=0.12, audio_streams=[stream]),
            _media(duration=0.12, audio_streams=[stream]),
            _timeline(points),
            _timeline(points),
            [mq._audio_timeline(source_frames, stream, "source", 0)],
            [mq._audio_timeline(output_frames, stream, "output", 0)],
        )
        self.assertFalse(result["pl_integrity_passed"])
        self.assertNotIn(
            "AUDIO_ENCODER_PADDING_VARIATION",
            [item["code"] for item in result["warnings"]],
        )
        self.assertIn(
            "AUDIO_FRAME_SAMPLE_LAYOUT_MISMATCH",
            [item["code"] for item in result["pl_integrity_failures"]],
        )

    def test_audio_missing_sample_count_is_rejected(self):
        with self.assertRaises(mq.HarnessFailure):
            mq._audio_timeline(
                [{"pts": 0.0, "duration": 0.02}], _audio_stream(), "source", 0
            )

    def test_seek_boundary_signatures_prove_global_frame_identity(self):
        expected = [f"{index:064x}" for index in range(6)]
        plan = {
            "analysis_count": 4,
            "start_frame": 1,
            "end_frame": 3,
            "analysis_start_frame": 0,
            "logical_count": 2,
        }
        proof = mq._verify_signature_boundaries(
            expected, expected[:4], plan, "fixture"
        )
        self.assertTrue(proof["certified"])
        altered = list(expected[:4])
        altered[2] = "f" * 64
        with self.assertRaises(mq.HarnessFailure):
            mq._verify_signature_boundaries(expected, altered, plan, "fixture")

    def test_legacy_chunks_remain_decode_from_start_global_trim(self):
        points = [index * 0.04 for index in range(10)]
        chunks = mq._metric_chunks(10, 4)
        records = []
        for index, chunk in enumerate(chunks):
            logical = chunk["end_frame"] - chunk["start_frame"]
            analysis = chunk["analysis_end_frame"] - chunk["analysis_start_frame"]
            records.append({
                "chunk_index": index,
                "start_frame_inclusive": chunk["start_frame"],
                "end_frame_exclusive": chunk["end_frame"],
                "expected_frame_count": logical,
                "analysis_start_frame_inclusive": chunk["analysis_start_frame"],
                "analysis_end_frame_exclusive": chunk["analysis_end_frame"],
                "expected_analysis_frame_count": analysis,
                "analysis_metric_frame_count": analysis,
                "retained_metric_frame_count": logical,
                "status": "COMPLETE",
            })
        existing = {
            "filtergraph_provenance": {
                "trim": {"enabled": True, "mode": "start_frame/end_frame"},
            },
            "metric_chunking": {
                "enabled": True,
                "expected_chunk_count": len(records),
                "completed_chunk_count": len(records),
                "chunks": records,
            },
        }
        _, plans, seek_enabled = mq._existing_chunk_plans(
            existing, 10, _timeline(points), _timeline(points)
        )
        self.assertFalse(seek_enabled)
        self.assertFalse(plans[1]["seek_applied"])
        self.assertEqual(chunks[1]["analysis_start_frame"], plans[1]["local_trim_start"])

    def test_running_sentinel_survives_interruption_without_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "private-source-name.mp4"
            output = root / "private-output-name.mp4"
            destination = root / "fixture.json"
            source.write_bytes(b"source")
            output.write_bytes(b"output")
            with mock.patch.object(mq, "measure", side_effect=KeyboardInterrupt):
                with self.assertRaises(KeyboardInterrupt):
                    mq.main([
                        "--source", str(source),
                        "--output", str(output),
                        "--pair-id", "fixture",
                        "--report", str(destination),
                    ])
            raw = destination.read_text(encoding="utf-8")
            sentinel = json.loads(raw)
            self.assertEqual("RUNNING", sentinel["status"])
            self.assertEqual("INCOMPLETE", sentinel["classification"])
            self.assertFalse(sentinel["report_complete"])
            self.assertNotIn(source.name, raw)
            self.assertNotIn(output.name, raw)

    def test_schema2_metrics_are_recomputed_before_upgrade(self):
        report = _schema2_report()
        audited = mq._validate_existing_metrics(report, "fixture", 100, 80)
        self.assertEqual(report["vmaf_mean"], audited["vmaf_mean"])
        report["per_frame_metrics"][0]["vmaf"] = 1.0
        with self.assertRaises(mq.HarnessFailure):
            mq._validate_existing_metrics(report, "fixture", 100, 80)

    def test_schema2_legacy_whole_file_without_chunking_is_synthesized_strictly(self):
        report = _schema2_report()
        del report["metric_chunking"]
        audited = mq._validate_existing_metrics(report, "fixture", 100, 80)
        self.assertEqual(2, audited["frame_count"])
        self.assertNotIn("metric_chunking", report)

        points = [0.0, 0.04]
        _, plans, seek_enabled = mq._existing_chunk_plans(
            report, 2, _timeline(points), _timeline(points)
        )
        self.assertFalse(seek_enabled)
        self.assertEqual(1, len(plans))
        self.assertEqual((0, 2), (plans[0]["start_frame"], plans[0]["end_frame"]))
        upgraded = mq._upgraded_chunking(report, plans)
        self.assertEqual(
            "SYNTHESIZED_LEGACY_WHOLE_FILE_NO_TRIM",
            upgraded["original_chunking_provenance"],
        )
        self.assertEqual("WHOLE_FILE_NO_TRIM", upgraded["chunks"][0]["execution_mode"])

        ambiguous = _schema2_report()
        del ambiguous["metric_chunking"]
        ambiguous["filtergraph_provenance"]["trim"] = {
            "enabled": True,
            "mode": "start_frame/end_frame",
        }
        with self.assertRaises(mq.HarnessFailure):
            mq._validate_existing_metrics(ambiguous, "fixture", 100, 80)

    def test_language_and_private_tags_are_not_sanitized_into_reports(self):
        sanitized = mq._sanitize_stream(
            {
                "codec_name": "aac",
                "tags": {"language": "private-name", "title": "secret-title"},
            },
            mq.AUDIO_FIELDS,
            0,
        )
        encoded = json.dumps(sanitized)
        self.assertNotIn("private-name", encoded)
        self.assertNotIn("secret-title", encoded)

    def test_known_field_order_and_chroma_location_regressions_fail(self):
        points = [0.0, 0.04, 0.08]
        source_video = _video()
        output_video = _video()
        source_video.update({"field_order": "progressive", "chroma_location": "left"})
        output_video.update({"field_order": "tt", "chroma_location": "center"})
        result = mq._compare_structure(
            _media(source_video, 0.12), _media(output_video, 0.12),
            _timeline(points), _timeline(points),
        )
        regressions = [
            item for item in result["hard_failures"]
            if item["code"] == "VIDEO_METADATA_REGRESSION"
        ]
        self.assertEqual({"field_order", "chroma_location"}, {
            item["field"] for item in regressions
        })

    def test_integrity_failure_keeps_video_comparison_valid_but_forces_pl_not_supported(self):
        points = [0.0, 0.04, 0.08]
        source_video = _video()
        output_video = _video()
        output_video["color_range"] = "pc"
        structure = mq._compare_structure(
            _media(source_video, 0.12),
            _media(output_video, 0.12),
            _timeline(points),
            _timeline(points),
        )
        self.assertTrue(structure["comparison_passed"])
        self.assertFalse(structure["pl_integrity_passed"])
        self.assertEqual(
            ["COLOR_METADATA_REGRESSION"],
            [item["code"] for item in structure["pl_disqualifiers"]],
        )
        strong = {
            "vmaf_mean": 96.0,
            "vmaf_1pct_low": 91.0,
            "vmaf_min": 82.0,
            "ssim_mean": 0.996,
            "ssim_min": 0.96,
            "psnr_mean": 45.0,
            "psnr_min": 35.0,
        }
        metric_only, final = mq._classification_with_integrity(strong, False)
        self.assertEqual("PL_STRONGLY_SUPPORTED", metric_only)
        self.assertEqual("PL_NOT_SUPPORTED", final)


if __name__ == "__main__":
    unittest.main()
