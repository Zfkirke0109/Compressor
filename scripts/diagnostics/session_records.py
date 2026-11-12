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

    A capture is either a text file (bare JSONL, or logcat-prefixed lines) or, from b164, the
    diagnostics ZIP the app exports. In a ZIP every run keeps its own `runs/<batchId>/session.jsonl`;
    they are read newest run first, which is the order the old single-file export used, so every
    caller that picks "the newest session" keeps working unchanged.
    """
    if path.lower().endswith(".zip"):
        return _read_zip_records(path)
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return [rec for rec in (decode_record(line) for line in fh) if rec is not None]


def _run_epoch(entry_name: str) -> int:
    parts = entry_name.split("/")
    batch = parts[1] if len(parts) > 2 else ""
    suffix = batch.split("_", 1)[1] if batch.startswith("batch_") and "_" in batch else ""
    return int(suffix) if suffix.isdigit() else -1


def _run_places(path: str) -> dict[str, int]:
    """Each run's place in the start order, as the archive's manifest lists it.

    The epoch in a run's name is the wall clock at its start, and that clock can be set back, so
    exports from builds that record each run's place (RunSequence, from 85e969f) list it in the
    manifest. Runs without one, and every run of an older export, are absent here.
    """
    places: dict[str, int] = {}
    for run in (read_manifest(path) or {}).get("runs") or []:
        if isinstance(run, dict) and isinstance(run.get("batchId"), str):
            place = run.get("sequence")
            if type(place) is int and place > 0:
                places[run["batchId"]] = place
    return places


def zip_session_entries(path: str) -> list[str]:
    """`runs/<batchId>/session.jsonl` entries of an archive, newest run first.

    Runs with a place in the start order come by that place and before every run without one,
    which an earlier build recorded; those come by the epoch in their names, as the app orders them.
    """
    import zipfile

    with zipfile.ZipFile(path) as archive:
        names = [n for n in archive.namelist() if n.startswith("runs/") and n.endswith("/session.jsonl")]
    places = _run_places(path)

    def newest_first(name: str) -> tuple[bool, int, int]:
        place = places.get(name.split("/")[1])
        return (place is not None, place or 0, _run_epoch(name))

    return sorted(names, key=newest_first, reverse=True)


def _read_zip_records(path: str) -> list[dict[str, Any]]:
    import zipfile

    records: list[dict[str, Any]] = []
    with zipfile.ZipFile(path) as archive:
        names = zip_session_entries(path)
        if len(set(names)) != len(names):
            raise ValueError(f"{path} contains duplicate session members")
        for name in names:
            batch_id = name.split("/")[1]
            if not batch_id or name != f"runs/{batch_id}/session.jsonl":
                raise ValueError(f"{path} contains an invalid session member: {name}")
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
