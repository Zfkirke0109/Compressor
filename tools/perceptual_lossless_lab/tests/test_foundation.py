from __future__ import annotations

import math
import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import tempfile
import unittest

from pl_lab.authority import capture_authority_snapshot, extract_authority_thresholds, fingerprint_repository, fingerprint_snapshot
from pl_lab.canonical import (
    canonical_bytes,
    domain_hash,
    seal_document,
    verify_sealed_document,
)
from pl_lab.errors import ImmutableConflict, InvalidInput
from pl_lab.jsonio import load_json, loads_strict
from pl_lab.paths import assert_contained, assert_no_reparse_points, logical_path
from pl_lab.sanitize import assert_sanitized, device_alias
from pl_lab.storage import atomic_publish_new


class CanonicalJsonTests(unittest.TestCase):
    def test_canonical_bytes_are_order_independent_and_utf8(self) -> None:
        left = {"z": 2, "accent": "café", "a": [True, None, 1.5]}
        right = {"a": [True, None, 1.5], "accent": "café", "z": 2}

        self.assertEqual(canonical_bytes(left), canonical_bytes(right))
        self.assertEqual(
            canonical_bytes(left),
            '{"a":[true,null,1.5],"accent":"café","z":2}'.encode("utf-8"),
        )

    def test_non_finite_numbers_are_rejected_at_parse_and_hash_boundaries(self) -> None:
        for value in (math.nan, math.inf, -math.inf):
            with self.subTest(value=value):
                with self.assertRaises(InvalidInput):
                    canonical_bytes({"metric": value})
        with self.assertRaises(InvalidInput):
            loads_strict('{"metric": NaN}')

    def test_hashes_are_domain_separated(self) -> None:
        payload = {"schema_version": "1", "value": 7}
        self.assertNotEqual(
            domain_hash("record:candidate", payload),
            domain_hash("record:evaluation", payload),
        )

    def test_sealed_document_hash_excludes_only_the_self_hash(self) -> None:
        source = {"schema_version": "1", "operation_id": "pl_score_pair", "value": 7}
        sealed = seal_document("result-envelope", source)

        self.assertTrue(verify_sealed_document("result-envelope", sealed))
        self.assertEqual(source, {k: v for k, v in sealed.items() if k != "content_sha256"})
        tampered = dict(sealed, value=8)
        self.assertFalse(verify_sealed_document("result-envelope", tampered))
        with self.assertRaises(InvalidInput):
            seal_document("result-envelope", sealed)


class SanitizationAndPathTests(unittest.TestCase):
    def test_private_absolute_paths_and_raw_serials_are_rejected(self) -> None:
        alias = device_alias("R5CW1234ABC")
        self.assertRegex(alias, r"^device-[0-9a-f]{12}$")
        assert_sanitized({"device_alias": alias, "artifact_path": "artifacts/report.json"})

        unsafe_documents = (
            {"artifact_path": r"C:\\Users\\example\\private.mp4"},
            {"artifact_path": "/home/example/private.mp4"},
            {"notes": "/tmp/private.mp4"},
            {"notes": r"path=C:\\work\\private.mp4"},
            {"notes": r"path=\\server\\share\\private.mp4"},
            {"device_serial": "R5CW1234ABC"},
            {"DeViCe_SeRiAl": "R5CW1234ABC"},
            {"R5CW1234ABC": "hidden in a key"},
            {"notes": "captured from R5CW1234ABC"},
        )
        for document in unsafe_documents:
            with self.subTest(document=document):
                with self.assertRaises(InvalidInput):
                    assert_sanitized(document, sensitive_values=("R5CW1234ABC",))

    def test_invalid_utf8_json_is_classified_as_invalid_input(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "bad.json"
            path.write_bytes(b"{\xff}")
            with self.assertRaises(InvalidInput):
                load_json(path)

    def test_logical_paths_reject_absolute_parent_and_empty_segments(self) -> None:
        self.assertEqual(logical_path("artifacts/report.json"), "artifacts/report.json")
        for value in ("../report.json", "a/../report.json", "/tmp/report.json", "C:/x.json", "a/C:file.json", "a/b?.json", "a/b. ", "a/b\x00.json", "a//b"):
            with self.subTest(value=value):
                with self.assertRaises(InvalidInput):
                    logical_path(value)

    def test_containment_rejects_parent_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "experiment"
            root.mkdir()
            inside = root / "runs" / "r1"
            self.assertEqual(assert_contained(root, inside), inside.resolve(strict=False))
            with self.assertRaises(InvalidInput):
                assert_contained(root, root.parent / "escaped")

    def test_symlink_or_reparse_segment_is_rejected_when_supported(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            target = base / "target"
            target.mkdir()
            link = base / "link"
            try:
                link.symlink_to(target, target_is_directory=True)
            except (OSError, NotImplementedError):
                self.skipTest("symlink creation is unavailable")
            with self.assertRaises(InvalidInput):
                assert_no_reparse_points(link / "child")


class AtomicPublicationTests(unittest.TestCase):
    def test_atomic_publish_is_idempotent_for_identical_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / "record.json"
            self.assertTrue(atomic_publish_new(target, b"same\n"))
            self.assertFalse(atomic_publish_new(target, b"same\n"))
            with self.assertRaises(ImmutableConflict):
                atomic_publish_new(target, b"different\n")

    def test_atomic_publish_race_creates_exactly_one_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / "record.json"
            with ThreadPoolExecutor(max_workers=8) as executor:
                outcomes = list(executor.map(lambda _: atomic_publish_new(target, b"payload\n"), range(32)))

            self.assertEqual(outcomes.count(True), 1)
            self.assertEqual(outcomes.count(False), 31)
            self.assertEqual(target.read_bytes(), b"payload\n")

    def test_atomic_publish_rejects_symlink_parent_when_supported(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            outside = root / "outside"
            outside.mkdir()
            link = root / "link"
            try:
                link.symlink_to(outside, target_is_directory=True)
            except (OSError, NotImplementedError):
                self.skipTest("symlink creation is unavailable")
            with self.assertRaises(InvalidInput):
                atomic_publish_new(link / "escaped.json", b"no\n", root=root)
            self.assertFalse((outside / "escaped.json").exists())


class RepositoryAuthorityTests(unittest.TestCase):
    def _write_authority_fixture(self, root: Path) -> None:
        files = {
            "app/src/main/java/compress/joshattic/us/quality/QualityProbePolicy.kt": """
                object QualityProbePolicy {
                    const val WINDOW_MEAN_MIN = 95.5
                    const val WINDOW_P5_MIN = 91.0
                    const val WINDOW_MIN_MIN = 84.0
                    const val MIN_COMPARED_FRAMES_PER_WINDOW = 12
                    const val HARD_RATIO_FLOOR = 0.60
                    const val SAFEST_RATIO_CEILING = 0.97
                    const val PROBE_MIN_SOURCE_BITS_PER_PIXEL = 0.03
                    const val REFINEMENT_MIN_GAP = 0.06
                }
            """,
            "app/src/main/java/compress/joshattic/us/BatchQualityBitratePolicy.kt": """
                object BatchQualityBitratePolicy {
                    const val PERCEPTUAL_LOSSLESS_SIZE_TOLERANCE = 0.03
                    const val PERCEPTUAL_LOSSLESS_MIN_SOURCE_BITS_PER_PIXEL = 0.08
                    const val MIN_PERCEPTUAL_LOSSLESS_PREDICTED_SAVINGS = 0.005
                    const val MIN_PERCEPTUAL_LOSSLESS_SAVINGS_BYTES = 262_144L
                    const val PERCEPTUAL_LOSSLESS_MAX_TARGET_RATIO = 0.97
                    private const val HDR_120_RATIO_FLOOR = 0.90
                    private const val HDR_4K60_RATIO_FLOOR = 0.80
                    private const val HDR_4K_RATIO_FLOOR = 0.75
                    private const val FPS120_RATIO_FLOOR = 0.88
                    private const val FPS60_4K_RATIO_FLOOR = 0.85
                    private const val FPS60_RATIO_FLOOR = 0.85
                    private const val UHD_RATIO_FLOOR = 0.85
                    private const val DEFAULT_RATIO_FLOOR = 0.85
                }
            """,
            "app/src/main/java/compress/joshattic/us/quality/PtsAligner.kt": """
                class PtsAligner {
                    companion object {
                        const val TOLERANCE_FLOOR_US = 4_000L
                        const val MAX_ALIGNMENT_DROPS = 8
                    }
                }
            """,
            "app/src/main/java/compress/joshattic/us/OutputVerifier.kt": """
                object OutputVerifier {
                    val durationMatches = abs(output - source) <= maxOf(500L, (input.source.durationMs * 0.02).toLong())
                    val frameCountMatches = abs(outputFrames - sourceFrames) <= maxOf(2, (input.sourceFrameCount * 0.01).toInt())
                }
            """,
        }
        for relative, text in files.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")

    def test_thresholds_are_extracted_from_kotlin_authorities(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._write_authority_fixture(root)

            thresholds = extract_authority_thresholds(root)

            self.assertEqual(thresholds["quality_probe"]["WINDOW_MEAN_MIN"], 95.5)
            self.assertEqual(thresholds["bitrate_policy"]["MIN_PERCEPTUAL_LOSSLESS_SAVINGS_BYTES"], 262144)
            self.assertEqual(thresholds["pts_alignment"]["TOLERANCE_FLOOR_US"], 4000)

    def test_missing_or_duplicate_authority_constant_is_invalid(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._write_authority_fixture(root)
            policy = root / "app/src/main/java/compress/joshattic/us/quality/QualityProbePolicy.kt"
            text = policy.read_text(encoding="utf-8")
            policy.write_text(text.replace("const val WINDOW_MEAN_MIN = 95.5\n", ""), encoding="utf-8")
            with self.assertRaises(InvalidInput):
                extract_authority_thresholds(root)

    def test_repository_fingerprint_changes_with_authority_source(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._write_authority_fixture(root)
            first = fingerprint_repository(root, require_all=False)
            policy = root / "app/src/main/java/compress/joshattic/us/quality/QualityProbePolicy.kt"
            policy.write_text(policy.read_text(encoding="utf-8") + "// changed\n", encoding="utf-8")
            second = fingerprint_repository(root, require_all=False)

            self.assertNotEqual(first["authority_sha256"], second["authority_sha256"])
            self.assertNotIn(str(root), str(first))

    def test_one_snapshot_feeds_threshold_parsing_and_file_fingerprints(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._write_authority_fixture(root)
            snapshot = capture_authority_snapshot(root, require_all=False)
            policy = root / "app/src/main/java/compress/joshattic/us/quality/QualityProbePolicy.kt"
            policy.write_text(policy.read_text(encoding="utf-8").replace("95.5", "1.0"), encoding="utf-8")

            thresholds = extract_authority_thresholds(snapshot=snapshot)
            fingerprint = fingerprint_snapshot(snapshot)
            authority_files = {item["logical_path"]: item["sha256"] for item in fingerprint["files"]}
            self.assertEqual(thresholds["quality_probe"]["WINDOW_MEAN_MIN"], 95.5)
            self.assertEqual(thresholds["quality_probe"]["source_sha256"], authority_files[thresholds["quality_probe"]["source_path"]])
            self.assertIn("repository_identity", fingerprint)
            self.assertNotEqual(fingerprint["authority_sha256"], fingerprint_repository(root, require_all=False)["authority_sha256"])


if __name__ == "__main__":
    unittest.main()
