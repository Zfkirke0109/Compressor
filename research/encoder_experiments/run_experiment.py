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
               "windowP5Min": 91.0, "windowMinMin": 84.0,
               "probeSelectionMargins": {"mean": 0.5, "p5": 1.25, "min": 1.0},
               "minComparedFramesPerWindow": 12}
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
    arm_list = manifest.get("arms", [])
    arms = {a["id"]: a for a in arm_list}
    if len(arms) != len(arm_list):
        problems.append("duplicate arm IDs")
    base = next((a for a in arms.values() if a.get("label") == "baseline"), None)
    if base is None:
        problems.append("no baseline arm")
    else:
        for a in arms.values():
            if a is base:
                continue
            if a.get("repeatOf"):
                if a["repeatOf"] != base["id"] or a["settings"] != base["settings"] or a.get("changes"):
                    problems.append(f"arm {a['id']} is not an unchanged baseline repeat")
                continue
            diff = [k for k in SETTINGS if a["settings"].get(k) != base["settings"].get(k)]
            if len(diff) != 1:
                problems.append(f"arm {a['id']} changes {diff or 'nothing'}; the pilot changes one factor at a time")
            elif set(a.get("changes", {})) != set(diff):
                problems.append(f"arm {a['id']} declares changes {sorted(a.get('changes', {}))} but differs in {diff}")
    order = manifest.get("order", [])
    if len(order) != len(set(order)) or set(order) != set(arms):
        problems.append("order must schedule each arm exactly once (give repeats distinct IDs)")
    for arm_id in order:
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
    if len(rows) != len(wanted) or len({r["nameHash"] for r in rows}) != len(rows) or any(not r["sizeMatches"] for r in rows):
        print("error: missing, ambiguous, or size-mismatched originals; do not use this hash sheet as complete", file=sys.stderr)
        return 1
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
        "thermalStart": j.get("thermalStart"),
        "thermalEnd": j.get("thermalEnd"),
        "precedingCooldownMs": j.get("precedingCooldownMs") or 0,
    }


def ingest(manifest: dict[str, Any], arm: str, capture: str, batch: str, out_dir: str, scope: str = "manifest") -> dict[str, Any]:
    """Only admit a completed, identity-checked pilot. Historical logs belong in the summarizer."""
    problems = validate(manifest)
    if problems:
        raise SystemExit("error: invalid manifest: " + "; ".join(problems))
    arms = {a["id"]: a for a in manifest["arms"]}
    if arm not in arms:
        raise SystemExit(f"error: undeclared arm {arm}")
    records = [r for r in read_records(capture) if r.get("batchId") == batch]
    def one(kind: str) -> dict[str, Any]:
        found = [r for r in records if (r.get("type") or r.get("eventType")) == kind]
        if len(found) != 1:
            raise SystemExit(f"error: batch {batch} needs exactly one {kind} (got {len(found)})")
        return found[0]
    start, summary, identity, snapshot = (one(k) for k in
        ("session_start", "session_summary", "run_identity", "learned_state_snapshot"))
    scoring = identity.get("scoring") or {}
    if scoring.get("frozenGate") != manifest["frozenGate"] or scoring.get("frozenGate") != FROZEN_GATE:
        raise SystemExit("error: captured gate differs from frozen production gate or is missing")
    if scoring.get("verdictModel") != FROZEN_GATE["verdictModel"] or scoring.get("verdictPhoneModel") is not False:
        raise SystemExit("error: scoring model or phone transform differs")
    settings = {k: scoring.get(k) for k in SETTINGS}
    if settings != arms[arm]["settings"]:
        raise SystemExit(f"error: captured settings for {arm} differ: {settings}")
    if not str(start.get("buildTag") or "").startswith(manifest["identity"]["requiredBuildTagPrefix"]):
        raise SystemExit("error: unexpected build tag")
    if not start.get("buildCommit") or not snapshot.get("sha256"):
        raise SystemExit("error: build commit or starting learned snapshot missing")
    if start.get("mode") != "Perceptually Lossless":
        raise SystemExit("error: capture is not a Perceptually Lossless batch")
    # Fast mode skips probing where learned history predicts failure, so each arm's learned state
    # would decide which sources were measured at all. Exhaustive measures every source; b177 PL-A
    # and PL-B ran it and probed the same rungs (448 vs 449) despite different learned snapshots.
    if start.get("exhaustivePerceptualLossless") is not True:
        raise SystemExit("error: pilot arms must run with Exhaustive (measure every file) on")
    jobs = [r for r in records if (r.get("type") or r.get("eventType")) == "job"]
    if not jobs:
        raise SystemExit(f"error: no job records for {batch} in {capture}")
    if start.get("selectedCount") != len(jobs) or summary.get("processed") != len(jobs):
        raise SystemExit("error: batch did not record every selected job as processed")
    if summary.get("sourceManifestJobs") != len(jobs) or not summary.get("sourceManifestSha256"):
        raise SystemExit("error: complete source manifest digest is missing")
    if not isinstance(summary.get("totalElapsedMs"), int) or summary["totalElapsedMs"] < 0:
        raise SystemExit("error: batch wall time is missing")
    ids = [j.get("jobId") or j.get("id") for j in jobs]
    if len(ids) != len(set(ids)):
        raise SystemExit("error: duplicate job records")
    source_by_id = {s["jobId"]: s for s in manifest["sources"]}
    if set(ids) != set(source_by_id):
        raise SystemExit("error: pilot sources missing or unexpected")
    for j in jobs:
        expected = source_by_id.get(j.get("jobId") or j.get("id"))
        if (not j.get("sourceFingerprint") or not j.get("nameHash") or
                not isinstance(j.get("sourceSize"), int) or j["sourceSize"] <= 0):
            raise SystemExit("error: job source identity incomplete")
        if expected and (j["nameHash"] != expected["nameHash"] or j["sourceSize"] != expected["sourceBytes"]):
            raise SystemExit(f"error: source name or size differs from manifest: {j['jobId']}")
        if j.get("countsAsRealCompression") is True and j.get("pixelCertified") is not True:
            raise SystemExit(f"error: accepted compression lacks pixel certification: {j['jobId']}")
        if j.get("countsAsRealCompression") is True and (
                j.get("finalAccepted") is not True or j.get("verified") is not True
                or j.get("terminal") != "TRANSCODED_SMALLER"
                or not 0 < (j.get("outputSize") or 0) < j["sourceSize"]):
            raise SystemExit(f"error: savings contradict final acceptance: {j['jobId']}")
    rows = [_row(j) for j in jobs]
    result = {"arm": arm, "batchId": batch, "capture": os.path.basename(capture), "scope": scope,
              "buildCommit": start["buildCommit"], "buildTag": start["buildTag"],
              "frozenGate": scoring["frozenGate"], "settings": settings,
              "learnedSnapshotSha256": snapshot["sha256"],
              "sourceManifestSha256": summary["sourceManifestSha256"],
              "batchWallMs": summary["totalElapsedMs"],
              "exhaustivePerceptualLossless": True,
              "missing": [],
              "rows": rows}
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    target = Path(out_dir) / f"{arm}.json"
    if target.exists():
        raise SystemExit(f"error: {target} already exists; each capture needs its distinct arm ID")
    with open(target, "x") as fh:
        json.dump(result, fh, indent=2)
    return result


def table(results: dict[str, dict[str, Any]], baseline: str, manifest: dict[str, Any] | None = None) -> dict[str, Any]:
    """Per-arm totals on the sources every arm contains; incremental bytes against [baseline]."""
    if not results or baseline not in results:
        raise SystemExit("error: no baseline result")
    if manifest is not None:
        expected = {a["id"]: a for a in manifest["arms"]}
        if set(results) != set(expected):
            raise SystemExit(f"error: missing or unexpected arm results: expected {sorted(expected)}")
        for arm, result in results.items():
            if result.get("arm") != arm or result.get("settings") != expected[arm]["settings"] or result.get("frozenGate") != manifest["frozenGate"]:
                raise SystemExit(f"error: arm {arm} result identity or settings differ from manifest")
            sources = {s["jobId"]: s for s in manifest["sources"]}
            for row in result["rows"]:
                expected_source = sources.get(row["jobId"])
                if expected_source is None or (row["nameHash"], row["sourceBytes"]) != (expected_source["nameHash"], expected_source["sourceBytes"]):
                    raise SystemExit(f"error: arm {arm} source differs from frozen manifest: {row['jobId']}")
        if len({r.get("batchId") for r in results.values()}) != len(results):
            raise SystemExit("error: one batch has been reused under multiple arms")
    builds = {(r.get("buildCommit"), r.get("buildTag")) for r in results.values()}
    gates = {json.dumps(r.get("frozenGate"), sort_keys=True) for r in results.values()}
    if len(builds) != 1 or None in next(iter(builds)) or len(gates) != 1 or next(iter(gates)) == "null":
        raise SystemExit("error: arm builds or gates are missing or differ")
    if any(r.get("exhaustivePerceptualLossless") is not True for r in results.values()):
        raise SystemExit("error: every arm must be an Exhaustive-mode capture")
    source_manifests = {r.get("sourceManifestSha256") for r in results.values()}
    if len(source_manifests) != 1 or not next(iter(source_manifests)):
        raise SystemExit("error: captured source manifest digests differ or are missing")
    common = set.intersection(*[{r["jobId"] for r in res["rows"]} for res in results.values()])
    if not common:
        raise SystemExit("error: no matched sources")
    if any(len(res["rows"]) != len({r["jobId"] for r in res["rows"]}) for res in results.values()):
        raise SystemExit("error: duplicate job rows in arm results")
    if any(set(r["jobId"] for r in res["rows"]) != common for res in results.values()):
        raise SystemExit("error: arms have different source sets; pilot is incomplete")
    if manifest is not None and common != {s["jobId"] for s in manifest["sources"]}:
        raise SystemExit("error: pilot rows differ from frozen manifest sources")
    for jid in common:
        identities = {(r["sourceFingerprint"], r["nameHash"], r["sourceBytes"])
                      for res in results.values() for r in res["rows"] if r["jobId"] == jid}
        if len(identities) != 1 or any(v in (None, "", 0) for v in next(iter(identities))):
            raise SystemExit(f"error: source identity differs across arms: {jid}")
    if manifest is not None:
        baseline_rows = {r["jobId"]: r for r in results[baseline]["rows"]}
        for arm in manifest["arms"]:
            if arm.get("changes") != {"shadowCalibration": True}:
                continue
            for row in results[arm["id"]]["rows"]:
                first = baseline_rows[row["jobId"]]
                if (row["terminal"], row["pixelCertified"]) != (first["terminal"], first["pixelCertified"]):
                    raise SystemExit(f"error: timing-only shadow arm changed acceptance for {row['jobId']}")
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
            "batchWallMs": results[label]["batchWallMs"],
            "summedJobElapsedMs": sum(r["wallMs"] or 0 for r in rows),
            "peakCandidateBytes": max((r["peakCandidateBytes"] or 0 for r in rows), default=0),
            # Wall time includes the app's thermal cooldowns; report them, and the thermal state
            # each measured job started in, beside the timing they confound.
            "summedCooldownMs": sum(r.get("precedingCooldownMs") or 0 for r in rows),
            "thermalAtJobStart": dict(Counter(r.get("thermalStart") for r in rows if r.get("thermalStart")).most_common()),
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
    snapshots = {label: result.get("learnedSnapshotSha256") for label, result in results.items()}
    if any(not value for value in snapshots.values()):
        raise SystemExit("error: starting learned snapshot is missing")
    return {"matchedSources": len(common), "identity": "jobId, manifest name/size, required sampled sourceFingerprint and source-manifest digest",
            "sameStartingLearnedSnapshot": len(set(snapshots.values())) == 1,
            "startingLearnedSnapshots": snapshots, "comparisonBasis": "matched-source observational",
            "arms": arms, "changedSources": changed}


def markdown(t: dict[str, Any]) -> str:
    cols = [("sources", "Sources"), ("sourceBytes", "Source bytes"), ("acceptedOutputs", "Accepted"),
            ("keptOutputBytes", "Kept output bytes"), ("acceptedSavedBytes", "Accepted saved bytes"),
            ("incrementalBytesVsBaseline", "Incremental vs baseline"), ("measuredFailures", "Measured rejections (probe or cert)"),
            ("unavailableEvidence", "Cert evidence not a measurement"), ("attemptsStarted", "Attempts started"),
            ("jobsRetried", "Jobs retried"), ("audioBitIdentical", "Audio bit-identical"),
            ("audioRequestedCopyNotShown", "Accepted, audio copy not shown"),
            ("batchWallMs", "Batch wall ms"), ("summedJobElapsedMs", "Sum of job elapsed ms"),
            ("summedCooldownMs", "Thermal cooldown ms"), ("peakCandidateBytes", "Largest candidate bytes")]
    out = ["| Arm | Batch | " + " | ".join(c[1] for c in cols) + " |",
           "|---|---|" + "---:|" * len(cols)]
    for label, a in t["arms"].items():
        out.append(f"| {label} | `{a['batchId']}` | " + " | ".join(f"{a[k]:,}" for k, _ in cols) + " |")
    out += ["", "Thermal state at the start of each measured job, by arm: "
            + "; ".join(f"{label}: " + (", ".join(f"{k} {v}" for k, v in a["thermalAtJobStart"].items()) or "not recorded")
                        for label, a in t["arms"].items()) + "."]
    out += ["", "Attempts started: pre-b177 records list attempts only for jobs that reached a certification "
            "failure or a retry, so older arms undercount; from b177 every encode attempt is counted (attemptsStarted)."]
    out += ["", "Matched-source results are observational. Starting learned snapshot SHA-256 by arm: "
            + ", ".join(f"{arm}={sha}" for arm, sha in t["startingLearnedSnapshots"].items()) + "."]
    if not t["sameStartingLearnedSnapshot"]:
        out += ["Different starting learned states can change plans and outcomes independently of the arm setting; do not attribute incremental bytes or time to that setting alone."]
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
    t = table(results, args.baseline, manifest)
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
