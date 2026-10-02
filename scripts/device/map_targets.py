#!/usr/bin/env python3
"""Find the local files behind the b169 target cases, without sending anything anywhere.

The diagnostics never contain file names: each job carries `nameHash`, a salted SHA-1 of the
file's display name (DiagnosticsRecorder.redactedNameHash). This hashes the names of local files
the same way and prints which target case each one is. Names and paths stay on this machine.

    python3 map_targets.py /path/to/originals [--targets b169_targets.csv] [--out mapping.csv]

A name hash identifies a NAME, not content: two different files with the same name match the same
case, and a renamed file matches none. Check duration and size against the target row before
using a match (the output shows both).
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import os
import sys

SALT = "galaxycompressor-diag-v1"  # DiagnosticsRecorder.SALT


def name_hash(display_name: str) -> str:
    return hashlib.sha1(SALT.encode() + display_name.encode("utf-8")).hexdigest()[:12]


def main(argv: list[str]) -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("folder")
    ap.add_argument("--targets", default=os.path.join(here, "b169_targets.csv"))
    ap.add_argument("--out")
    args = ap.parse_args(argv)
    with open(args.targets, encoding="utf-8", newline="") as f:
        targets = {row["nameHash"]: row for row in csv.DictReader(f)}
    found = []
    for root, _dirs, files in os.walk(args.folder):
        for name in files:
            row = targets.get(name_hash(name))
            if row:
                path = os.path.join(root, name)
                found.append({
                    "jobId": row["jobId"], "class": row["class"], "path": path,
                    "localBytes": os.path.getsize(path), "targetBytes": row["sourceBytes"],
                    "sizeMatches": str(os.path.getsize(path) == int(row["sourceBytes"] or -1)),
                })
    for m in found:
        print(f"{m['jobId']}  {m['class']:<32} size {'ok' if m['sizeMatches'] == 'True' else 'DIFFERENT'}  {m['path']}")
    missing = sorted(set(r["jobId"] for r in targets.values()) - {m["jobId"] for m in found})
    print(f"{len(found)} of {len(targets)} target cases found; {len(missing)} missing")
    if args.out:
        with open(args.out, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=["jobId", "class", "path", "localBytes", "targetBytes", "sizeMatches"])
            w.writeheader()
            w.writerows(found)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
