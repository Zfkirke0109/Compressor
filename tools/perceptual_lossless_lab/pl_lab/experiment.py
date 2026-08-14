"""Experiment ownership and immutable run directory guards."""

from __future__ import annotations

import os
from pathlib import Path

from .canonical import domain_hash, seal_document, verify_sealed_document
from .errors import ImmutableConflict, InvalidInput
from .jsonio import canonical_json_file_bytes, load_json
from .paths import assert_contained, assert_no_reparse_points
from .storage import atomic_publish_new


OWNER_MARKER = ".pl-lab-owner.json"


class ExperimentStore:
    def __init__(self, root: Path) -> None:
        self.root = Path(root).absolute()

    @property
    def marker_path(self) -> Path:
        return self.root / OWNER_MARKER

    @property
    def experiment_id(self) -> str:
        canonical_root = str(self.root).casefold() if os.name == "nt" else str(self.root)
        return "experiment-" + domain_hash("experiment-location", {"root": canonical_root})[:24]

    def _marker(self) -> dict[str, object]:
        return seal_document(
            "experiment-owner-marker",
            {"schema_version": "1", "owner": "compressor-perceptual-lossless-lab", "experiment_id": self.experiment_id},
        )

    def _validate_marker(self) -> None:
        assert_no_reparse_points(self.marker_path, stop_at=self.root)
        marker = load_json(self.marker_path)
        if not isinstance(marker, dict) or set(marker) != {"schema_version", "owner", "experiment_id", "content_sha256"}:
            raise ImmutableConflict("experiment directory has an invalid ownership marker")
        if marker.get("schema_version") != "1" or marker.get("owner") != "compressor-perceptual-lossless-lab" or marker.get("experiment_id") != self.experiment_id:
            raise ImmutableConflict("experiment directory has an invalid ownership marker")
        if not verify_sealed_document("experiment-owner-marker", marker):
            raise ImmutableConflict("experiment ownership marker hash mismatch")

    def verified_root(self) -> Path:
        """Return the canonical root of an existing lab-owned experiment."""

        if not self.root.is_dir() or not self.marker_path.is_file():
            raise ImmutableConflict("experiment directory is not lab-owned")
        assert_no_reparse_points(self.root)
        self._validate_marker()
        try:
            return self.root.resolve(strict=True)
        except OSError as exc:
            raise ImmutableConflict("cannot resolve experiment directory") from exc

    def prepare(self, *, execute: bool) -> bool:
        """Validate ownership; create only when execute is explicitly true."""

        assert_no_reparse_points(self.root.parent)
        if self.root.exists():
            assert_no_reparse_points(self.root, stop_at=self.root)
            if self.marker_path.is_file():
                self._validate_marker()
                return False
            try:
                has_entries = next(self.root.iterdir(), None) is not None
            except OSError as exc:
                raise ImmutableConflict("cannot inspect experiment directory") from exc
            if has_entries:
                raise ImmutableConflict("refusing to adopt a nonempty unowned experiment directory")
            if not execute:
                return False
        elif not execute:
            return False
        try:
            self.root.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            raise ImmutableConflict("cannot create experiment directory") from exc
        assert_contained(self.root, self.marker_path)
        return atomic_publish_new(self.marker_path, canonical_json_file_bytes(self._marker()), root=self.root)

    def owned_path(self, *segments: str) -> Path:
        if not self.marker_path.is_file():
            raise ImmutableConflict("experiment directory is not lab-owned")
        self._validate_marker()
        if not segments or any(not segment or segment in (".", "..") or "/" in segment or "\\" in segment for segment in segments):
            raise InvalidInput("unsafe experiment path segment")
        return assert_contained(self.root, self.root.joinpath(*segments))
