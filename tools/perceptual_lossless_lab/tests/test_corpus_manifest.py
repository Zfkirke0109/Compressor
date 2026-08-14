from __future__ import annotations

from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest

from pl_lab.errors import EvidenceBlocked, InvalidInput
from pl_lab.schema import SchemaRegistry
from pl_lab.specification import corpus_manifest_snapshot
from tests.fixtures import write_corpus_manifest


LAB_ROOT = Path(__file__).resolve().parents[1]


def _document(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _rewrite(path: Path, document: dict) -> None:
    path.write_text(
        json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
        newline="",
    )


class CorpusManifestTests(unittest.TestCase):
    def test_snapshot_binds_verified_source_output_and_pair_identities(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            manifest = write_corpus_manifest(Path(temp_name))
            identity, items = corpus_manifest_snapshot(manifest)

            self.assertEqual(identity["manifest_alias"], "corpus.json")
            self.assertEqual(identity["manifest_sha256"], hashlib.sha256(manifest.read_bytes()).hexdigest())
            self.assertEqual(identity["item_count"], 1)
            self.assertRegex(identity["source_identities_sha256"], r"^[0-9a-f]{64}$")
            self.assertRegex(identity["pair_identities_sha256"], r"^[0-9a-f]{64}$")
            self.assertEqual(items[0]["pair_id"], "pair-001")
            self.assertNotIn(temp_name, str(identity))

    def test_tampered_or_missing_media_is_blocked(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            root = Path(temp_name)
            manifest = write_corpus_manifest(root)
            (root / "media/source.mp4").write_bytes(b"tampered\n")
            with self.assertRaises(EvidenceBlocked):
                corpus_manifest_snapshot(manifest)

            manifest = write_corpus_manifest(root)
            (root / "media/output.mp4").unlink()
            with self.assertRaises(EvidenceBlocked):
                corpus_manifest_snapshot(manifest)

    def test_legacy_unknown_traversal_and_duplicate_pairs_are_invalid(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            root = Path(temp_name)
            manifest = write_corpus_manifest(root)

            _rewrite(manifest, {"schema_version": "1", "items": [{"source_id": "legacy"}]})
            with self.assertRaises(InvalidInput):
                corpus_manifest_snapshot(manifest)

            manifest = write_corpus_manifest(root)
            document = _document(manifest)
            document["items"][0]["source_path"] = "../outside.mp4"
            _rewrite(manifest, document)
            with self.assertRaises(InvalidInput):
                corpus_manifest_snapshot(manifest)

            manifest = write_corpus_manifest(root)
            document = _document(manifest)
            document["items"].append(deepcopy(document["items"][0]))
            _rewrite(manifest, document)
            with self.assertRaises(InvalidInput):
                corpus_manifest_snapshot(manifest)

    def test_hard_linked_media_is_not_accepted_as_owned_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            root = Path(temp_name)
            manifest = write_corpus_manifest(root)
            source = root / "media/source.mp4"
            try:
                os.link(source, root / "media/source-alias.mp4")
            except (OSError, NotImplementedError):
                self.skipTest("hard links are unavailable")
            with self.assertRaises(EvidenceBlocked):
                corpus_manifest_snapshot(manifest)

    def test_windows_component_and_case_aliases_cannot_freeze_self_comparison(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            root = Path(temp_name)
            manifest = write_corpus_manifest(root)
            document = _document(manifest)
            item = document["items"][0]
            item["output_path"] = "media/C:source.mp4"
            item["output_sha256"] = item["source_sha256"]
            item["output_size_bytes"] = item["source_size_bytes"]
            _rewrite(manifest, document)
            with self.assertRaises(InvalidInput):
                corpus_manifest_snapshot(manifest)

            manifest = write_corpus_manifest(root)
            document = _document(manifest)
            item = document["items"][0]
            item["output_path"] = "MEDIA/SOURCE.MP4"
            item["output_sha256"] = item["source_sha256"]
            item["output_size_bytes"] = item["source_size_bytes"]
            _rewrite(manifest, document)
            if os.name == "nt":
                with self.assertRaises(InvalidInput):
                    corpus_manifest_snapshot(manifest)

    def test_windows_case_aliases_cannot_count_one_output_twice(self) -> None:
        if os.name != "nt":
            self.skipTest("case-insensitive path alias fixture is Windows-specific")
        with tempfile.TemporaryDirectory() as temp_name:
            root = Path(temp_name)
            manifest = write_corpus_manifest(root)
            document = _document(manifest)
            duplicate = deepcopy(document["items"][0])
            duplicate["pair_id"] = "pair-002"
            duplicate["output_path"] = duplicate["output_path"].upper()
            document["items"].append(duplicate)
            _rewrite(manifest, document)
            with self.assertRaises(InvalidInput):
                corpus_manifest_snapshot(manifest)

    def test_manifest_schema_matches_runtime_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            manifest = write_corpus_manifest(Path(temp_name))
            document = _document(manifest)
            registry = SchemaRegistry(LAB_ROOT / "schemas")
            registry.validate("v1/corpus-manifest.schema.json", document)
            document["items"][0]["unknown"] = True
            with self.assertRaises(InvalidInput):
                registry.validate("v1/corpus-manifest.schema.json", document)
            for unsafe in ("media/C:source.mp4", "media/bad?.mp4", "media/trailing. ", "media/bad\x00.mp4", "media/"):
                malformed = _document(manifest)
                malformed["items"][0]["source_path"] = unsafe
                with self.subTest(unsafe=repr(unsafe)), self.assertRaises(InvalidInput):
                    registry.validate("v1/corpus-manifest.schema.json", malformed)

    def test_snapshot_enforces_item_byte_duration_and_external_path_contracts(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            root = Path(temp_name)
            manifest = write_corpus_manifest(root)
            document = _document(manifest)
            source_two = root / "media/source-002.mp4"
            output_two = root / "media/output-002.mp4"
            source_two.write_bytes(b"source-two\n")
            output_two.write_bytes(b"output-two\n")
            document["items"].append(
                {
                    "pair_id": "pair-002",
                    "source_path": "media/source-002.mp4",
                    "source_sha256": hashlib.sha256(source_two.read_bytes()).hexdigest(),
                    "source_size_bytes": source_two.stat().st_size,
                    "output_path": "media/output-002.mp4",
                    "output_sha256": hashlib.sha256(output_two.read_bytes()).hexdigest(),
                    "output_size_bytes": output_two.stat().st_size,
                }
            )
            _rewrite(manifest, document)
            with self.assertRaises(InvalidInput):
                corpus_manifest_snapshot(manifest, max_items=1, max_total_bytes=1_000_000, max_duration_seconds=30)
            declared_total = sum(item["source_size_bytes"] + item["output_size_bytes"] for item in document["items"])
            with self.assertRaises(InvalidInput):
                corpus_manifest_snapshot(manifest, max_items=2, max_total_bytes=declared_total - 1, max_duration_seconds=30)
            source_two.write_bytes(b"tampered-size-and-identity\n")
            with self.assertRaises(EvidenceBlocked):
                corpus_manifest_snapshot(manifest, max_items=2, max_total_bytes=declared_total, max_duration_seconds=30)

            with self.assertRaises(InvalidInput):
                corpus_manifest_snapshot(Path("bad\x00.json"))

    def test_declared_budget_is_checked_before_any_media_hash(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            root = Path(temp_name)
            manifest = write_corpus_manifest(root)
            document = _document(manifest)
            source_two = root / "media/source-002.mp4"
            output_two = root / "media/output-002.mp4"
            source_two.write_bytes(b"source-two\n")
            output_two.write_bytes(b"output-two\n")
            document["items"].append({
                "pair_id": "pair-002",
                "source_path": "media/source-002.mp4",
                "source_sha256": hashlib.sha256(source_two.read_bytes()).hexdigest(),
                "source_size_bytes": source_two.stat().st_size,
                "output_path": "media/output-002.mp4",
                "output_sha256": hashlib.sha256(output_two.read_bytes()).hexdigest(),
                "output_size_bytes": output_two.stat().st_size,
            })
            _rewrite(manifest, document)
            (root / document["items"][0]["source_path"]).unlink()
            first_pair_bytes = document["items"][0]["source_size_bytes"] + document["items"][0]["output_size_bytes"]
            with self.assertRaisesRegex(InvalidInput, "byte budget"):
                corpus_manifest_snapshot(manifest, max_items=2, max_total_bytes=first_pair_bytes, max_duration_seconds=30)


if __name__ == "__main__":
    unittest.main()
