#!/usr/bin/env python3
"""Decoding of DiagnosticsRecorder session records, shared by the summariser and the comparator.

Records reach a workstation by two transports and only one of them is clean:

  * `files/diagnostics/<batchId>/session.jsonl` pulled with `adb run-as` - one bare JSON object
    per line, nothing else.
  * `adb logcat -s CompressorDiag` - the same JSON, prefixed by the logcat header
    (`08-13 18:18:10.690 25965 25965 I CompressorDiag: {...}`).

Logcat is not the fallback; it is the *only* transport for a Samsung Secure Folder run, whose
private files directory cannot be read across users over adb. A strict `json.loads` per line
silently discards every prefixed record, so a Secure Folder capture would parse to zero jobs and
report "no job records" as if the run had never happened. Tolerating the prefix is therefore a
correctness requirement, not a convenience.
"""

from __future__ import annotations

import json
import os
from typing import Any


def decode_record(line: str) -> dict[str, Any] | None:
    """Parse one capture line, with or without a logcat header. None when it is not a record.

    Deliberately conservative: the salvaged text must parse as a JSON object AND carry a record
    type. Anything looser would let an arbitrary log line containing a brace masquerade as data,
    which is a worse failure than dropping it — a capture that quietly gains fabricated rows
    cannot be trusted for anything.
    """
    line = line.strip()
    if not line:
        return None
    try:
        rec = json.loads(line)
    except json.JSONDecodeError:
        start = line.find("{")
        if start <= 0:
            return None
        try:
            rec = json.loads(line[start:])
        except json.JSONDecodeError:
            return None
    if not isinstance(rec, dict):
        return None
    return rec if (rec.get("type") or rec.get("eventType")) else None


def read_records(path: str) -> list[dict[str, Any]]:
    """Every decodable record in a capture, in file order. A truncated tail is skipped, not fatal.

    A capture is either a text file (bare JSONL, or logcat-prefixed lines) or, from b164, a ZIP:
    the diagnostics archive the app exports, where every run keeps its own
    `runs/<batchId>/session.jsonl`, or the device suite's bundle, which holds the same run
    directories under `bundle/runs/`. Runs are read newest first, which is the order the old
    single-file export used, so every caller that picks "the newest session" keeps working unchanged.
    """
    path = os.fspath(path)
    if path.lower().endswith(".zip"):
        return _read_zip_records(path)
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return [rec for rec in (decode_record(line) for line in fh) if rec is not None]


# Where an archive keeps its run directories: the app's diagnostics export puts them at the top,
# beside manifest.json; the device suite's bundle (scripts pulled with run-as) under bundle/.
RUN_ROOTS = ("runs/", "bundle/runs/")
SESSION_FILE = "session.jsonl"


def _batch_of(member: str, root: str) -> str:
    return member[len(root):].split("/", 1)[0]


def _run_epoch(batch: str) -> int:
    suffix = batch.split("_", 1)[1] if batch.startswith("batch_") and "_" in batch else ""
    return int(suffix) if suffix.isdigit() else -1


def run_root(names: list[str]) -> str | None:
    """The one run root an archive's session members use, or None when it holds none.

    Refuses what it cannot read without guessing: session members under both roots (two copies of
    a capture, and nothing says which is the run's), a session member under neither, one nested
    deeper than `<root><batchId>/session.jsonl`, an unsafe path, or the same member twice.
    """
    sessions = [n for n in names if n.rsplit("/", 1)[-1] == SESSION_FILE]
    for n in sessions:
        parts = n.split("/")
        if n.startswith("/") or "\\" in n or any(part in ("", ".", "..") for part in parts):
            raise ValueError(f"unsafe session member: {n!r}")
    used = sorted({root for root in RUN_ROOTS for n in sessions
                   if n.startswith(root) and n == f"{root}{_batch_of(n, root)}/{SESSION_FILE}"})
    if len(used) > 1:
        raise ValueError("session records under both runs/ and bundle/runs/: which copy is the capture's is ambiguous")
    root = used[0] if used else None
    for n in sessions:
        if root is None or n != f"{root}{_batch_of(n, root)}/{SESSION_FILE}":
            raise ValueError(f"a session member outside <root><batchId>/{SESSION_FILE} (roots: {', '.join(RUN_ROOTS)}): {n!r}")
    if len(set(sessions)) != len(sessions):
        raise ValueError("duplicate session members")
    return root


def _run_places(path: str, root: str | None, names: list[str]) -> dict[str, int]:
    """Each run's place in the start order: from the manifest an app export carries, or from the
    `run-sequence` file a bundle keeps in each run directory.

    The epoch in a run's name is the wall clock at its start, and that clock can be set back, so
    builds that record each run's place (RunSequence, from 85e969f) write it in the run directory
    and list it in the manifest. Runs without one, and every run of an older export, are absent.
    Where both are present they must agree.
    """
    import zipfile

    places: dict[str, int] = {}
    for run in (read_manifest(path) or {}).get("runs") or []:
        if isinstance(run, dict) and isinstance(run.get("batchId"), str):
            place = run.get("sequence")
            if type(place) is int and place > 0:
                places[run["batchId"]] = place
    if root is None:
        return places
    with zipfile.ZipFile(path) as archive:
        for n in names:
            if n.startswith(root) and n == f"{root}{_batch_of(n, root)}/run-sequence":
                text = archive.read(n).decode("utf-8", "replace").strip()
                if text.isdigit() and int(text) > 0:
                    batch = _batch_of(n, root)
                    if places.get(batch, int(text)) != int(text):
                        raise ValueError(f"{batch}: the manifest and its run-sequence file give different places")
                    places[batch] = int(text)
    return places


def zip_session_entries(path: str) -> list[str]:
    """`<root><batchId>/session.jsonl` entries of an archive, newest run first.

    Runs with a place in the start order come by that place and before every run without one,
    which an earlier build recorded; those come by the epoch in their names, as the app orders them.
    """
    import zipfile

    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
    root = run_root(names)
    if root is None:
        return []
    places = _run_places(path, root, names)
    members = [n for n in names if n.startswith(root) and n.endswith("/" + SESSION_FILE)]

    def newest_first(name: str) -> tuple[bool, int, int]:
        batch = _batch_of(name, root)
        place = places.get(batch)
        return (place is not None, place or 0, _run_epoch(batch))

    return sorted(members, key=newest_first, reverse=True)


def session_provenance(path: str) -> list[dict[str, Any]]:
    """Which archive member each run's records came from, with its length and SHA-256, newest first."""
    import hashlib
    import zipfile

    out = []
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        root = run_root(names)
        places = _run_places(path, root, names) if root else {}
        for member in zip_session_entries(path):
            data = archive.read(member)
            batch = _batch_of(member, root)
            out.append({"batchId": batch, "member": member, "bytes": len(data),
                        "sha256": hashlib.sha256(data).hexdigest(), "place": places.get(batch)})
    return out


def describe_empty(path: str) -> str:
    """Why an archive yielded no session records, in words that cannot be read as "no failures"."""
    import zipfile

    path = os.fspath(path)
    if not path.lower().endswith(".zip"):
        return f"no session records in {path}"
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
    dirs = sorted({n[len(root):].split("/", 1)[0] for root in RUN_ROOTS for n in names
                   if n.startswith(root) and n[len(root):].split("/", 1)[0]})
    if not dirs:
        return f"no session records in {path}: it holds no run directories (expected runs/<batchId>/ or bundle/runs/<batchId>/)"
    return (f"no session records in {path}: {len(dirs)} run director{'y' if len(dirs) == 1 else 'ies'}"
            f" ({', '.join(dirs[:4])}{', …' if len(dirs) > 4 else ''}), none holding {SESSION_FILE}."
            " Self-check runs record no session. This is not a run without failures.")


def _read_zip_records(path: str) -> list[dict[str, Any]]:
    import zipfile

    records: list[dict[str, Any]] = []
    with zipfile.ZipFile(path) as archive:
        root = run_root(archive.namelist())
        for name in zip_session_entries(path):
            batch_id = _batch_of(name, root)
            with archive.open(name) as fh:
                for raw in fh:
                    rec = decode_record(raw.decode("utf-8", "replace"))
                    if rec is not None:
                        if rec.get("batchId") not in (None, "", batch_id):
                            raise ValueError(f"{path}: batch ID conflicts with {name}")
                        rec["batchId"] = batch_id
                        records.append(rec)
    return records


def read_manifest(path: str) -> dict[str, Any] | None:
    """The archive's manifest.json, or None for a text capture or an archive without a usable one.

    A manifest that is valid JSON but not an object (a list, a string, a number) is not a manifest:
    callers read fields off the result, and the session records are still worth reporting.
    """
    path = os.fspath(path)
    if not path.lower().endswith(".zip"):
        return None
    import zipfile

    with zipfile.ZipFile(path) as archive:
        if "manifest.json" not in archive.namelist():
            return None
        with archive.open("manifest.json") as fh:
            try:
                manifest = json.loads(fh.read().decode("utf-8", "replace"))
            except ValueError:
                return None
            return manifest if isinstance(manifest, dict) else None
