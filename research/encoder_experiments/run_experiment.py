#!/usr/bin/env python3
"""Reproducible runner for encoder operating-point experiments (b177 WP3). Standard library only.

It does not encode anything and never touches media on the phone. The device runs the batches;
this tool freezes what is compared, reads the Diagnostics exports afterwards, and joins arms on
matched sources.

  validate MANIFEST                 check the manifest: frozen gate, one factor per arm, unique sources
  plan MANIFEST                     print the run sheet: arm order, the app settings to set, sources
  hash MANIFEST ORIGINALS_DIR --out PRIVATE.csv
                                    full SHA-256 of each local original matched by nameHash
                                    (private output: paths stay on this machine)
  ingest MANIFEST --arm LABEL --capture ZIP_OR_JSONL --batch BATCH_ID --out RESULTS_DIR
                                    extract the manifest's sources from one arm's capture
  table MANIFEST --results RESULTS_DIR --baseline LABEL [--scope manifest|all] [--markdown OUT]
                                    before/after table on sources present in every arm

Accepted savings are counted exactly as the app's summariser counts them: a job whose record
claims a real compression AND whose kept output is smaller. Retained originals, remuxes, rejected
candidates and avoided copies count zero. Nothing here projects savings to untested sources.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import sys
from collections import Counter
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts" / "diagnostics"))
from session_records import read_records  # noqa: E402

FROZEN_GATE = {"verdictModel": "vmaf_v0.6.1", "phoneModel": False, "windowMeanMin": 95.5,
               "windowP5Min": 91.0, "windowMinMin": 84.0}
SETTINGS = ("maxBFrames", "longGop", "saferRungRetry", "shadowCalibration")


def load_manifest(path: str) -> dict[str, Any]:
    with open(path) as fh:
        return json.load(fh)


def validate(manifest: dict[str, Any]) -> list[str]:
    """Problems with the manifest; empty when it is usable."""
    problems = []
    gate = manifest.get("frozenGate") or {}
    for k, v in FROZEN_GATE.items():
        if gate.get(k) != v:
            problems.append(f"frozenGate.{k} is {gate.get(k)!r}, production is {v!r}: this runner never evaluates another gate")
    ids = [s.get("jobId") for s in manifest.get("sources", [])]
    if len(ids) != len(set(ids)):
        problems.append("duplicate sources")
    arms = {a["id"]: a for a in manifest.get("arms", [])}
    base = next((a for a in arms.values() if a.get("label") == "baseline"), None)
    if base is None:
        problems.append("no baseline arm")
    else:
        for a in arms.values():
            if a is base:
                continue
            diff = [k for k in SETTINGS if a["settings"].get(k) != base["settings"].get(k)]
            if len(diff) != 1:
                problems.append(f"arm {a['id']} changes {diff or 'nothing'}; the pilot changes one factor at a time")
            elif set(a.get("changes", {})) != set(diff):
                problems.append(f"arm {a['id']} declares changes {sorted(a.get('changes', {}))} but differs in {diff}")
    for arm_id in manifest.get("order", []):
        if arm_id not in arms:
            problems.append(f"order names unknown arm {arm_id}")
    return problems


def plan(manifest: dict[str, Any]) -> str:
    arms = {a["id"]: a for a in manifest["arms"]}
    lines = [f"# {manifest['experimentId']}: {manifest['purpose']}", ""]
    for step, arm_id in enumerate(manifest["order"], 1):
        a = arms[arm_id]
        lines.append(f"{step}. arm {arm_id} ({a['label']}): set " + ", ".join(f"{k}={a['settings'][k]}" for k in SETTINGS))
    lines += ["", "Select exactly these sources (nameHash; match locally with scripts/device/map_targets.py):"]
    for s in manifest["sources"]:
        lines.append(f"  {s['nameHash']}  {s['jobId']}  {s['sourceBytes']:>13,} B  {s['geometry']}@{s['fps']}  {s['role']}")
    lines += ["", manifest.get("orderNote", ""), "After each arm: Diagnostics -> Everything; then `ingest` it under the arm's label."]
    return "\n".join(lines)


def hash_originals(manifest: dict[str, Any], root: str, out: str) -> int:
    """Full SHA-256 of local originals whose name hash matches a manifest source. Private output."""
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts" / "device"))
    from map_targets import name_hash  # noqa: E402
    wanted = {s["nameHash"]: s for s in manifest["sources"]}
    rows = []
    for dirpath, _, files in os.walk(root):
        for f in files:
            h = name_hash(f)
            if h in wanted:
                p = os.path.join(dirpath, f)
                md = hashlib.sha256()
                with open(p, "rb") as fh:
                    for chunk in iter(lambda: fh.read(1 << 20), b""):
                        md.update(chunk)
                size = os.path.getsize(p)
                rows.append({"nameHash": h, "jobId": wanted[h]["jobId"], "path": p, "bytes": size,
                             "sizeMatches": size == wanted[h]["sourceBytes"], "sha256": md.hexdigest()})
    with open(out, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=["nameHash", "jobId", "path", "bytes", "sizeMatches", "sha256"])
        w.writeheader()
        w.writerows(rows)
    print(f"hashed {len(rows)} of {len(wanted)} sources into {out} (keep this file private)")
    return 0


def _saved(j: dict[str, Any]) -> int:
    src, out = j.get("sourceSize") or 0, j.get("outputSize") or 0
    return src - out if j.get("countsAsRealCompression") is True and 0 < out < src else 0


def _attempts_started(j: dict[str, Any]) -> int:
    if isinstance(j.get("attemptsStarted"), int):
        return j["attemptsStarted"]
    return len([a for a in str(j.get("attempts") or "").split(";") if a])


def _row(j: dict[str, Any]) -> dict[str, Any]:
    decision = j.get("certificationDecision")
    return {
        "jobId": j.get("jobId") or j.get("id"),
        "nameHash": j.get("nameHash"),
        "sourceFingerprint": j.get("sourceFingerprint"),
        "sourceBytes": j.get("sourceSize") or 0,
        "keptOutputBytes": j.get("outputSize") or 0 if _saved(j) else 0,
        "acceptedSavedBytes": _saved(j),
        "terminal": j.get("terminal"),
        "measuredFailure": decision in ("measured_below_bar", "misaligned") or j.get("terminal") == "SKIPPED_WOULD_DEGRADE",
        "unavailableEvidence": decision in ("unavailable", "insufficient_frames", "partial_unavailable"),
        "attemptsStarted": _attempts_started(j),
        "retried": _attempts_started(j) > 1,
        "audio": j.get("audioPreservation"),
        "audioRequested": j.get("audioRequested") or ("copy" if "audio=copy(" in str(j.get("encodePlan") or "") else None),
        "wallMs": j.get("elapsedMs"),
        "peakCandidateBytes": j.get("candidateBytes"),
        "pixelCertified": j.get("pixelCertified"),
    }


def ingest(manifest: dict[str, Any], arm: str, capture: str, batch: str, out_dir: str, scope: str = "manifest") -> dict[str, Any]:
    wanted = {s["jobId"] for s in manifest["sources"]}
    jobs = [r for r in read_records(capture)
            if (r.get("type") or r.get("eventType")) == "job" and r.get("batchId") == batch]
    if not jobs:
        raise SystemExit(f"error: no job records for {batch} in {capture}")
    rows = [_row(j) for j in jobs if scope == "all" or (j.get("jobId") or j.get("id")) in wanted]
    result = {"arm": arm, "batchId": batch, "capture": os.path.basename(capture), "scope": scope,
              "missing": sorted(wanted - {r["jobId"] for r in rows}) if scope == "manifest" else [],
              "rows": rows}
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    with open(Path(out_dir) / f"{arm}.json", "w") as fh:
        json.dump(result, fh, indent=2)
    return result


def table(results: dict[str, dict[str, Any]], baseline: str) -> dict[str, Any]:
    """Per-arm totals on the sources every arm contains; incremental bytes against [baseline]."""
    common = set.intersection(*[{r["jobId"] for r in res["rows"]} for res in results.values()])
    arms = {}
    base_saved = None
    for label in [baseline] + [k for k in results if k != baseline]:
        rows = [r for r in results[label]["rows"] if r["jobId"] in common]
        saved = sum(r["acceptedSavedBytes"] for r in rows)
        if label == baseline:
            base_saved = saved
        arms[label] = {
            "batchId": results[label]["batchId"],
            "sources": len(rows),
            "sourceBytes": sum(r["sourceBytes"] for r in rows),
            "acceptedOutputs": sum(1 for r in rows if r["acceptedSavedBytes"] > 0),
            "keptOutputBytes": sum(r["keptOutputBytes"] for r in rows),
            "acceptedSavedBytes": saved,
            "incrementalBytesVsBaseline": saved - base_saved,
            "measuredFailures": sum(1 for r in rows if r["measuredFailure"]),
            "unavailableEvidence": sum(1 for r in rows if r["unavailableEvidence"]),
            "attemptsStarted": sum(r["attemptsStarted"] for r in rows),
            "jobsRetried": sum(1 for r in rows if r["retried"]),
            "audioBitIdentical": sum(1 for r in rows if str(r["audio"] or "").startswith("bit-identical")),
            "audioRequestedCopyNotShown": sum(
                1 for r in rows if r["audioRequested"] == "copy" and r["acceptedSavedBytes"] > 0
                and not str(r["audio"] or "").startswith(("bit-identical", "stream copy"))),
            "wallMs": sum(r["wallMs"] or 0 for r in rows),
            "peakCandidateBytes": max((r["peakCandidateBytes"] or 0 for r in rows), default=0),
            "terminals": dict(Counter(r["terminal"] for r in rows).most_common()),
        }
    changed = []
    base_rows = {r["jobId"]: r for r in results[baseline]["rows"]}
    for label, res in results.items():
        if label == baseline:
            continue
        for r in res["rows"]:
            b = base_rows.get(r["jobId"])
            if b and r["jobId"] in common and (b["terminal"] != r["terminal"] or b["acceptedSavedBytes"] != r["acceptedSavedBytes"]):
                changed.append({"arm": label, "jobId": r["jobId"], "baselineTerminal": b["terminal"], "terminal": r["terminal"],
                                "baselineSaved": b["acceptedSavedBytes"], "saved": r["acceptedSavedBytes"]})
    return {"matchedSources": len(common), "identity": "jobId (URI hash); sourceFingerprint where both arms carry it",
            "arms": arms, "changedSources": changed}


def markdown(t: dict[str, Any]) -> str:
    cols = [("sources", "Sources"), ("sourceBytes", "Source bytes"), ("acceptedOutputs", "Accepted"),
            ("keptOutputBytes", "Kept output bytes"), ("acceptedSavedBytes", "Accepted saved bytes"),
            ("incrementalBytesVsBaseline", "Incremental vs baseline"), ("measuredFailures", "Measured rejections (probe or cert)"),
            ("unavailableEvidence", "Cert evidence not a measurement"), ("attemptsStarted", "Attempts started"),
            ("jobsRetried", "Jobs retried"), ("audioBitIdentical", "Audio bit-identical"),
            ("audioRequestedCopyNotShown", "Accepted, audio copy not shown"), ("wallMs", "Wall ms"),
            ("peakCandidateBytes", "Largest candidate bytes")]
    out = ["| Arm | Batch | " + " | ".join(c[1] for c in cols) + " |",
           "|---|---|" + "---:|" * len(cols)]
    for label, a in t["arms"].items():
        out.append(f"| {label} | `{a['batchId']}` | " + " | ".join(f"{a[k]:,}" for k, _ in cols) + " |")
    out += ["", "Attempts started: pre-b177 records list attempts only for jobs that reached a certification "
            "failure or a retry, so older arms undercount; from b177 every encode attempt is counted (attemptsStarted)."]
    if t["changedSources"]:
        out += ["", "Sources whose outcome changed against the baseline:", ""]
        for c in t["changedSources"]:
            out.append(f"- {c['arm']} `{c['jobId']}`: {c['baselineTerminal']} ({c['baselineSaved']:,} B) -> {c['terminal']} ({c['saved']:,} B)")
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("validate", "plan"):
        sp = sub.add_parser(name)
        sp.add_argument("manifest")
    sp = sub.add_parser("hash")
    sp.add_argument("manifest")
    sp.add_argument("originals")
    sp.add_argument("--out", required=True)
    sp = sub.add_parser("ingest")
    sp.add_argument("manifest")
    sp.add_argument("--arm", required=True)
    sp.add_argument("--capture", required=True)
    sp.add_argument("--batch", required=True)
    sp.add_argument("--out", required=True)
    sp.add_argument("--scope", choices=("manifest", "all"), default="manifest")
    sp = sub.add_parser("table")
    sp.add_argument("manifest")
    sp.add_argument("--results", required=True)
    sp.add_argument("--baseline", required=True)
    sp.add_argument("--markdown")
    sp.add_argument("--json")
    args = ap.parse_args()
    manifest = load_manifest(args.manifest)
    if args.cmd == "validate":
        problems = validate(manifest)
        print("\n".join(problems) if problems else "manifest OK")
        return 1 if problems else 0
    if args.cmd == "plan":
        print(plan(manifest))
        return 0
    if args.cmd == "hash":
        return hash_originals(manifest, args.originals, args.out)
    if args.cmd == "ingest":
        res = ingest(manifest, args.arm, args.capture, args.batch, args.out, args.scope)
        print(f"{args.arm}: {len(res['rows'])} source(s)" + (f"; missing {res['missing']}" if res["missing"] else ""))
        return 0
    results = {p.stem: json.load(open(p)) for p in sorted(Path(args.results).glob("*.json"))}
    if args.baseline not in results:
        raise SystemExit(f"error: no results for baseline {args.baseline}")
    t = table(results, args.baseline)
    if args.json:
        with open(args.json, "w") as fh:
            json.dump(t, fh, indent=2)
    text = markdown(t)
    if args.markdown:
        Path(args.markdown).write_text(text + "\n")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
