#!/usr/bin/env python3
"""Parse a Compressor batch logcat capture into a per-video dataset + aggregate analysis.

Reads the CompressorEncoderPlan / CompressorLearning / CompressorVerification / CompressorBatch
/ CompressorDiag tag lines produced by Galaxy Compressor during a batch run and reconstructs,
per source video, the full decision -> operation -> verification -> terminal-result record the
17-phase diagnostic spec asks for. Works from logcat because the app runs inside Samsung Secure
Folder, whose private files dir cannot be pulled over adb; logcat crosses the user boundary.

Usage:  python parse_batch_logcat.py <logcat_file> [--out <dir>]
Outputs (next to the logcat file unless --out): jobs.jsonl, summary.csv, aggregate.json
Emits NO video frames, NO source bytes, NO Secure Folder paths — only redacted display names
(the app already logs originalName; we hash it here so the dataset carries a stable id, not the
private name) and technical fields.
"""
import sys, os, re, json, csv, hashlib
from collections import defaultdict, Counter

def redact(name):
    if not name:
        return "unknown"
    h = hashlib.sha1(name.encode("utf-8", "replace")).hexdigest()[:12]
    ext = os.path.splitext(name)[1].lower().lstrip(".") or "none"
    return f"vid_{h}.{ext}"

# CompressorEncoderPlan: "mode=..; requestedCodec=..; resolvedOutputMime=..; source=WxH@Ffps; bitrate=..; audioBitrate=..; sourceCodec=..; hdr=..; colorTransfer=..; target=..p@..fps; targetVideoBitrate=..; learnedTargetRatio=..; targetAudioBitrate=..; privacy=.."
def parse_kv(msg):
    out = {}
    for part in msg.split(";"):
        part = part.strip()
        if "=" in part:
            k, v = part.split("=", 1)
            out[k.strip()] = v.strip()
    return out

def num(v):
    if v is None:
        return None
    m = re.search(r"-?\d+(\.\d+)?", str(v))
    return float(m.group()) if m else None

def main():
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(1)
    path = sys.argv[1]
    outdir = os.path.dirname(os.path.abspath(path))
    if "--out" in sys.argv:
        outdir = sys.argv[sys.argv.index("--out") + 1]
    os.makedirs(outdir, exist_ok=True)

    # jobs keyed by redacted name; a batch may re-run a name, keep the last plan/verify seen.
    jobs = defaultdict(lambda: {})
    order = []

    plan_re = re.compile(r"CompressorEncoderPlan\s*:\s*(.*)")
    learn_plan_re = re.compile(r"CompressorLearning\s*:\s*plan;\s*(.*)")
    learn_result_re = re.compile(r"CompressorLearning\s*:\s*result=(\w+);\s*(.*)")
    verify_re = re.compile(r"CompressorVerification\s*:\s*(mode=.*file=.*)")
    batch_re = re.compile(r"CompressorBatch\s*:\s*(.*)")
    diag_re = re.compile(r"CompressorDiag\s*:\s*(\{.*\})\s*$")

    # Track which source-name a plan line belongs to: plan lines carry source= but not name;
    # verification lines carry file=<name>. We associate by interleave order: the plan that
    # immediately precedes a verification is that item's plan. Maintain a pending plan.
    pending_plan = None
    pending_learn_plan = None

    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            m = diag_re.search(line)
            if m:
                # Structured JSON record (present only on the corrected build).
                try:
                    rec = json.loads(m.group(1))
                except Exception:
                    continue
                name = rec.get("name") or rec.get("job") or "unknown"
                rid = redact(name) if not str(name).startswith("vid_") else name
                if rid not in jobs:
                    order.append(rid)
                jobs[rid].update({("diag_" + k): v for k, v in rec.items()})
                continue
            m = plan_re.search(line)
            if m:
                pending_plan = parse_kv(m.group(1)); continue
            m = learn_plan_re.search(line)
            if m:
                pending_learn_plan = parse_kv(m.group(1)); continue
            m = verify_re.search(line)
            if m:
                kv = parse_kv(m.group(1))
                name = kv.get("file", "unknown")
                rid = redact(name)
                if rid not in jobs:
                    order.append(rid)
                j = jobs[rid]
                j["id"] = rid
                j["verdict"] = kv.get("verification")
                j["playable"] = kv.get("playable")
                j["replaceAllowed"] = kv.get("replaceAllowed")
                j["blockReason"] = kv.get("blockReason")
                j["outputSize"] = num(kv.get("outputSize"))
                j["verify_mode"] = kv.get("mode")
                if pending_plan:
                    j["plan_mode"] = pending_plan.get("mode")
                    j["resolvedOutputMime"] = pending_plan.get("resolvedOutputMime")
                    j["source"] = pending_plan.get("source")
                    j["sourceBitrate"] = num(pending_plan.get("bitrate"))
                    j["audioBitrate"] = num(pending_plan.get("audioBitrate"))
                    j["sourceCodec"] = pending_plan.get("sourceCodec")
                    j["hdr"] = pending_plan.get("hdr")
                    j["targetVideoBitrate"] = num(pending_plan.get("targetVideoBitrate"))
                    j["learnedTargetRatio"] = pending_plan.get("learnedTargetRatio")
                if pending_learn_plan:
                    j["profileKey"] = pending_learn_plan.get("profileKey")
                    j["defaultRatio"] = num(pending_learn_plan.get("defaultRatio"))
                    j["floorRatio"] = num(pending_learn_plan.get("floorRatio"))
                    j["preferRemux_plan"] = pending_learn_plan.get("preferRemux")
                    j["remuxReason"] = pending_learn_plan.get("reason")
                pending_plan = None
                pending_learn_plan = None
                continue
            m = learn_result_re.search(line)
            if m:
                # attach to most recent job in order
                if order:
                    j = jobs[order[-1]]
                    kv = parse_kv(m.group(2))
                    j["learn_result"] = m.group(1)
                    j["sizeRatio"] = num(kv.get("sizeRatio"))
                    j["nextRatio"] = num(kv.get("nextRatio"))
                continue
            m = batch_re.search(line)
            if m:
                txt = m.group(1)
                fm = re.search(r"for (.+?):", txt) or re.search(r"for (.+?)\b", txt)
                if fm and order:
                    rid = redact(fm.group(1).strip())
                    if rid in jobs:
                        jobs[rid].setdefault("batch_notes", []).append(txt.strip())

    # Derive terminal classification from what we have.
    def terminal(j):
        v = (j.get("verdict") or "").lower()
        mode = (j.get("verify_mode") or j.get("plan_mode") or "").lower()
        out = j.get("outputSize")
        src = j.get("diag_sourceSize") or j.get("sourceSizeBytes")
        remuxish = "remux" in mode or "remux" in v
        if "verification failed" in v or (j.get("playable") == "failed"):
            return "OUTPUT_VALIDATION_FAILED"
        if "perceptually lossless verified" in v:
            if src and out and out < src * 0.999:
                return "TRANSCODED_SMALLER"
            return "TRANSCODED_NOT_MEANINGFULLY_SMALLER"
        if remuxish and j.get("remuxReason") and "near optimal" in (j.get("remuxReason") or "").lower():
            return "ALREADY_HIGHLY_OPTIMIZED"
        if remuxish:
            return "UNEXPECTED_REMUX"
        return "UNCLASSIFIED"

    rows = []
    for rid in order:
        j = jobs[rid]
        j["terminal"] = terminal(j)
        rows.append(j)

    with open(os.path.join(outdir, "jobs.jsonl"), "w", encoding="utf-8") as f:
        for j in rows:
            f.write(json.dumps(j, ensure_ascii=False) + "\n")

    cols = ["id", "plan_mode", "verify_mode", "source", "sourceCodec", "resolvedOutputMime",
            "hdr", "sourceBitrate", "audioBitrate", "targetVideoBitrate", "defaultRatio",
            "floorRatio", "learnedTargetRatio", "verdict", "playable", "replaceAllowed",
            "outputSize", "sizeRatio", "remuxReason", "blockReason", "terminal"]
    with open(os.path.join(outdir, "summary.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        for j in rows:
            w.writerow(j)

    agg = {
        "total_items": len(rows),
        "by_terminal": dict(Counter(j["terminal"] for j in rows)),
        "by_verdict": dict(Counter((j.get("verdict") or "none") for j in rows)),
        "by_source_codec": dict(Counter((j.get("sourceCodec") or "unknown") for j in rows)),
        "by_plan_mode": dict(Counter((j.get("plan_mode") or "unknown") for j in rows)),
        "remux_reasons": dict(Counter((j.get("remuxReason") or "none") for j in rows)),
        "block_reasons": dict(Counter((j.get("blockReason") or "none") for j in rows)),
    }
    # simple headroom read: how many were routed to remux purely by the near-optimal gate
    agg["near_optimal_remux_count"] = sum(
        1 for j in rows if "near optimal" in (j.get("remuxReason") or "").lower())
    with open(os.path.join(outdir, "aggregate.json"), "w", encoding="utf-8") as f:
        json.dump(agg, f, indent=2)

    print(json.dumps(agg, indent=2))
    print(f"\nWrote jobs.jsonl, summary.csv, aggregate.json to {outdir} ({len(rows)} items)")

if __name__ == "__main__":
    main()
