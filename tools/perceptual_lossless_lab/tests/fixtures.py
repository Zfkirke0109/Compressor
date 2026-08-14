from __future__ import annotations

import hashlib
import json
from pathlib import Path


def write_corpus_manifest(
    directory: Path,
    *,
    source_bytes: bytes = b"source-video-fixture\n",
    output_bytes: bytes = b"output-video-fixture\n",
) -> Path:
    """Write one strict v1 corpus pair whose declared identities match its bytes."""

    directory.mkdir(parents=True, exist_ok=True)
    media = directory / "media"
    media.mkdir(parents=True, exist_ok=True)
    source = media / "source.mp4"
    output = media / "output.mp4"
    source.write_bytes(source_bytes)
    output.write_bytes(output_bytes)
    manifest = directory / "corpus.json"
    document = {
        "schema_version": "1",
        "items": [
            {
                "pair_id": "pair-001",
                "source_path": "media/source.mp4",
                "source_sha256": hashlib.sha256(source_bytes).hexdigest(),
                "source_size_bytes": len(source_bytes),
                "output_path": "media/output.mp4",
                "output_sha256": hashlib.sha256(output_bytes).hexdigest(),
                "output_size_bytes": len(output_bytes),
            }
        ],
    }
    manifest.write_text(
        json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
        newline="",
    )
    return manifest
