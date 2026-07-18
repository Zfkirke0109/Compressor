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

DIAGNOSTIC_HASH_SALT = "galaxycompressor-diag-v1"
# Common v2 envelope on every structured record. v1 records simply lack the newer keys, so the
# parser stays backward-compatible by treating them as optional.
ENVELOPE_FIELDS = {
    "schemaVersion", "batchId", "type", "eventType", "eventId", "sequence", "jobId",
    "timestampMs", "androidUserId", "profileKind",
}
STRUCTURED_JOB_FIELDS = ENVELOPE_FIELDS | {
    "id", "nameHash", "ext", "sourceMime",
    "w", "h", "fps", "durationMs", "sourceSize", "sourceTotalBitrate",
    "bitrateMeasuredFromSize", "hdr", "audioMime", "audioBitrate", "requestedMode",
    "effectiveMode", "plannedOutputMime", "plannedTargetRatio",
    "plannedTargetVideoBitrate", "plannedDecisionReason", "wasStreamCopy", "verdict",
    "verified", "pixelCertified", "replacementSafe", "blockReason", "fallbackReason", "discardedVideoBitrate",
    "probedRatios", "pixelProvenRatio", "probeDetail",
    "probeWindowScores", "probePairDiag", "certWindowScores", "thermalStart", "thermalEnd", "precedingCooldownMs",
    "materializationMode", "originalReuseBlockReason", "copyAvoidedBytes",
    "outputSize", "rawByteDelta",
    "savedBytes", "savedPct", "terminal", "countsAsRealCompression", "elapsedMs",
}
SESSION_START_FIELDS = ENVELOPE_FIELDS | {
    "schema", "appVersion", "appVersionName", "appVersionCode", "buildCommit", "buildType",
    "packageName", "processUid", "gitAppId",
    "deviceModel", "manufacturer", "androidRelease", "sdkInt", "mode", "selectedCount",
    "privacy",
}
SESSION_SUMMARY_FIELDS = ENVELOPE_FIELDS | {
    "processed", "failed", "skipped", "cancelled",
    "realCompressions", "nonCompressions", "realCompressionInputBytes",
    "realCompressionOutputBytes", "totalBytesSaved", "totalElapsedMs", "reason",
}
# session_cancelled / session_failed reuse the summary field set (plus reason).
SESSION_TERMINAL_TYPES = {"session_summary", "session_cancelled", "session_failed"}

def whitelisted(record, fields, ignored=None):
    # Copy ONLY explicitly permitted fields, so a future malformed record carrying displayName,
    # sourceUri, or a path can never leak into a derived artifact. Disallowed keys are counted (not
    # copied) so the run can report how much was dropped instead of silently discarding it.
    if ignored is not None:
        for key in record:
            if key not in fields:
                ignored[key] += 1
    return {key: record[key] for key in fields if key in record}

def stable_name_hash(name):
    payload = (DIAGNOSTIC_HASH_SALT + (name or "")).encode("utf-8", "replace")
    return hashlib.sha1(payload).hexdigest()[:12]

def redact(name):
    if not name:
        return "unknown"
    h = stable_name_hash(name)
    ext = os.path.splitext(name)[1].lower().lstrip(".") or "none"
    return f"vid_{h}.{ext}"

def redacted_id_from_diag(rec):
    # Source-derived id is stable and keeps different selected URIs separate even when their
    # display names are identical. It is already a salted hash; no raw URI is emitted.
    return rec.get("id")

def occurrence_id(base_id, occurrence):
    if occurrence == 1:
        return base_id
    stem, dot, ext = base_id.rpartition(".")
    return f"{stem}_{occurrence:02d}{dot}{ext}" if dot else f"{base_id}_{occurrence:02d}"

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

    # Structured jobs are keyed by (batchId, jobId), so a Logcat Extreme export containing
    # multiple complete batches cannot union or overwrite jobs that share a stable source id.
    jobs = defaultdict(lambda: {})
    order = []
    session_records = []
    session_starts = defaultdict(list)
    session_summaries = defaultdict(list)
    structured_job_keys = defaultdict(set)
    structured_errors = []
    structured_ignored = Counter()
    structured_seen = False
    legacy_occurrences = Counter()
    seen_event_lines = set()
    # v2 envelope integrity trackers (v1 records simply lack eventId/sequence).
    session_terminals = defaultdict(list)          # batchId -> [session_summary|cancelled|failed recs]
    event_ids_seen = set()                         # (batchId, eventId) for exact-event dedup
    seq_owner = {}                                 # (batchId, sequence) -> eventId
    sequences_by_batch = defaultdict(set)          # batchId -> {sequence,...}
    duplicate_sequences = []                       # (batchId, sequence) seen with a different eventId
    unique_structured_events = 0
    malformed_event_ids = 0
    replay_events = 0
    profiles_by_batch = {}                         # batchId -> profileKind (from session_start)

    # Only the planning record starts with mode=. Ignore later encodeResult records on the same tag
    # or they overwrite the pending source/target fields before verification.
    plan_re = re.compile(r"CompressorEncoderPlan\s*:\s*(mode=.*)")
    learn_plan_re = re.compile(r"CompressorLearning\s*:\s*plan;\s*(.*)")
    learn_result_re = re.compile(r"CompressorLearning\s*:\s*result=(\w+);\s*(.*)")
    verify_re = re.compile(r"CompressorVerification\s*:\s*(mode=.*(?:file|job)=.*)")
    batch_re = re.compile(r"CompressorBatch\s*:\s*(.*)")
    diag_re = re.compile(r"CompressorDiag\s*:\s*(\{.*\})\s*$")

    # Track which source-name a plan line belongs to: plan lines carry source= but not name;
    # verification lines carry file=<name>. We associate by interleave order: the plan that
    # immediately precedes a verification is that item's plan. Maintain a pending plan.
    pending_plan = None
    pending_learn_plan = None
    pending_learn_result = None
    pending_batch_notes = []

    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line_number, line in enumerate(f, start=1):
            # Wireless reconnects replay the same buffered log line. Ignore exact replays while
            # retaining genuinely repeated processing of the same display name at a new timestamp.
            event_line = line.rstrip("\r\n")
            if event_line in seen_event_lines:
                continue
            seen_event_lines.add(event_line)
            m = diag_re.search(line)
            if "CompressorDiag" in line and not m:
                structured_seen = True
                structured_errors.append(
                    f"line {line_number}: malformed or truncated CompressorDiag JSON")
                continue
            if m:
                # Structured JSON record (present only on the corrected build).
                structured_seen = True
                try:
                    rec = json.loads(m.group(1))
                except Exception as exc:
                    structured_errors.append(
                        f"line {line_number}: invalid CompressorDiag JSON ({exc})")
                    continue
                if not isinstance(rec, dict):
                    structured_errors.append(
                        f"line {line_number}: CompressorDiag record is not an object")
                    continue
                # v2 emits eventType (and mirrors it as type); v1 emits only type.
                record_type = rec.get("eventType") or rec.get("type")
                batch_id = rec.get("batchId")
                if record_type not in ({"session_start", "job"} | SESSION_TERMINAL_TYPES):
                    structured_errors.append(
                        f"line {line_number}: unknown CompressorDiag type {record_type!r}")
                    continue
                if not batch_id:
                    structured_errors.append(
                        f"line {line_number}: CompressorDiag record is missing batchId")
                    continue
                if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", str(batch_id)):
                    structured_errors.append(
                        f"line {line_number}: batchId is not a safe identifier")
                    continue
                if rec.get("timestampMs") is None:
                    structured_errors.append(
                        f"line {line_number}: CompressorDiag record is missing timestampMs")
                    continue

                # --- v2 envelope integrity: eventId dedup + sequence tracking (v1 skips gracefully).
                event_id = rec.get("eventId")
                if event_id is not None:
                    if not re.fullmatch(r"evt_[0-9a-f]{16}", str(event_id)):
                        structured_errors.append(
                            f"line {line_number}: malformed eventId {event_id!r}")
                        malformed_event_ids += 1
                        continue
                    ekey = (str(batch_id), str(event_id))
                    if ekey in event_ids_seen:
                        # Same logical event replayed after a reconnect: dedup derived output,
                        # raw log keeps the duplicate line.
                        replay_events += 1
                        continue
                    event_ids_seen.add(ekey)
                seq = rec.get("sequence")
                if seq is not None:
                    try:
                        seq_int = int(seq)
                        skey = (str(batch_id), seq_int)
                        prev = seq_owner.get(skey)
                        if prev is not None and prev != str(event_id):
                            # Same sequence, different event: a monotonicity/threading integrity error.
                            duplicate_sequences.append([str(batch_id), seq_int])
                        seq_owner[skey] = str(event_id)
                        sequences_by_batch[str(batch_id)].add(seq_int)
                    except (ValueError, TypeError):
                        structured_errors.append(
                            f"line {line_number}: non-integer sequence {seq!r}")
                unique_structured_events += 1
                if record_type == "session_start":
                    profiles_by_batch[str(batch_id)] = rec.get("profileKind")
                    if rec.get("selectedCount") is None:
                        structured_errors.append(
                            f"line {line_number}: session_start is missing selectedCount")
                        continue
                    rec = whitelisted(rec, SESSION_START_FIELDS, structured_ignored)
                    session_starts[batch_id].append(rec)
                    session_records.append(rec)
                    continue
                if record_type in SESSION_TERMINAL_TYPES:
                    if record_type == "session_summary" and rec.get("processed") is None:
                        structured_errors.append(
                            f"line {line_number}: session_summary is missing processed")
                        continue
                    rec = whitelisted(rec, SESSION_SUMMARY_FIELDS, structured_ignored)
                    session_terminals[batch_id].append(rec)
                    if record_type == "session_summary":
                        session_summaries[batch_id].append(rec)
                    session_records.append(rec)
                    continue
                required_job_fields = {
                    "id", "terminal", "countsAsRealCompression", "sourceSize", "outputSize"
                }
                missing = sorted(k for k in required_job_fields if rec.get(k) is None)
                if missing:
                    structured_errors.append(
                        f"line {line_number}: job is missing {', '.join(missing)}")
                    continue
                rid = redacted_id_from_diag(rec)
                if not rid:
                    structured_errors.append(
                        f"line {line_number}: job id is empty")
                    continue
                if not re.fullmatch(r"job_[0-9a-f]{12}", str(rid)):
                    structured_errors.append(
                        f"line {line_number}: job id is not a salted redacted id")
                    continue
                rec = whitelisted(rec, STRUCTURED_JOB_FIELDS, structured_ignored)
                key = (str(batch_id), str(rid))
                if key in jobs and jobs[key].get("diagnostic"):
                    structured_errors.append(
                        f"line {line_number}: duplicate structured job {batch_id}/{rid}")
                    continue
                order.append(key)
                structured_job_keys[batch_id].add(key)
                j = jobs[key]
                # The structured record is authoritative for terminal classification and uses the
                # same salted display-name hash as legacy file= lines, so before/after jobs merge.
                j.update(rec)
                j["id"] = rid
                j["diagnostic"] = True
                j["plan_mode"] = rec.get("requestedMode")
                j["verify_mode"] = rec.get("effectiveMode")
                j["sourceCodec"] = rec.get("sourceMime")
                j["sourceBitrate"] = rec.get("sourceTotalBitrate")
                j["targetVideoBitrate"] = rec.get("plannedTargetVideoBitrate")
                j["remuxReason"] = rec.get("plannedDecisionReason")
                j["resolvedOutputMime"] = rec.get("plannedOutputMime")
                j["learnedTargetRatio"] = rec.get("plannedTargetRatio")
                j["audioBitrate"] = rec.get("audioBitrate")
                j["hdr"] = rec.get("hdr")
                j["replaceAllowed"] = rec.get("replacementSafe")
                if rec.get("w") and rec.get("h"):
                    j["source"] = f'{rec.get("w")}x{rec.get("h")}@{rec.get("fps")}fps'
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
                name = kv.get("job") or kv.get("file", "unknown")
                base_rid = name if str(name).startswith("job_") else redact(name)
                legacy_occurrences[base_rid] += 1
                rid = occurrence_id(base_rid, legacy_occurrences[base_rid])
                key = ("legacy", rid)
                order.append(key)
                j = jobs[key]
                j["id"] = rid
                j["_legacyBaseId"] = base_rid
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
                    j["learnedRatio"] = num(pending_learn_plan.get("learnedRatio"))
                    j["floorRatio"] = num(pending_learn_plan.get("floorRatio"))
                    j["preferRemux_plan"] = pending_learn_plan.get("preferRemux")
                    j["remuxReason"] = pending_learn_plan.get("reason")
                if pending_learn_result:
                    j.update(pending_learn_result)
                if pending_batch_notes:
                    j["batch_notes"] = list(pending_batch_notes)
                pending_plan = None
                pending_learn_plan = None
                pending_learn_result = None
                pending_batch_notes = []
                continue
            m = learn_result_re.search(line)
            if m:
                # Learning is logged before the item's terminal verification record, so keep it
                # pending for that verification instead of attaching it to the previous job.
                kv = parse_kv(m.group(2))
                pending_learn_result = {
                    "learn_result": m.group(1),
                    "sizeRatio": num(kv.get("sizeRatio")),
                    "nextRatio": num(kv.get("nextRatio")),
                }
                continue
            m = batch_re.search(line)
            if m:
                txt = m.group(1)
                fm = re.search(r"for (.+?):", txt) or re.search(r"for (.+?)\b", txt)
                if fm:
                    # Keep the technical reason only; never copy the raw display name into the
                    # redacted dataset.
                    pending_batch_notes.append(txt.rsplit(": ", 1)[-1].strip())

    missing_sequences = {}
    if structured_seen:
        batch_ids = set(session_starts) | set(session_terminals) | set(structured_job_keys)
        for batch_id in sorted(batch_ids):
            starts = session_starts.get(batch_id, [])
            terminals = session_terminals.get(batch_id, [])
            if len(starts) != 1:
                structured_errors.append(
                    f"batch {batch_id}: expected exactly one session_start, found {len(starts)}")
            if len(terminals) != 1:
                structured_errors.append(
                    f"batch {batch_id}: expected exactly one session terminal "
                    f"(summary/cancelled/failed), found {len(terminals)}")
            terminal_type = terminals[0].get("type") if len(terminals) == 1 else None
            # Only a clean session_summary must reconcile counts and be gap-free; a cancelled/failed
            # session legitimately stops early, so partial counts and a truncated tail are expected.
            if len(starts) == 1 and terminal_type == "session_summary":
                selected = int(starts[0]["selectedCount"])
                processed = int(terminals[0]["processed"])
                captured = len(structured_job_keys.get(batch_id, set()))
                if selected != processed or processed != captured:
                    structured_errors.append(
                        f"batch {batch_id}: selected={selected}, processed={processed}, "
                        f"uniqueJobs={captured}")
                seqs = sequences_by_batch.get(batch_id, set())
                if seqs:
                    gaps = sorted(set(range(min(seqs), max(seqs) + 1)) - seqs)
                    if gaps:
                        missing_sequences[batch_id] = gaps
                        structured_errors.append(
                            f"batch {batch_id}: missing event sequence numbers {gaps}")
        # A duplicate sequence with different event ids is always an integrity failure.
        for batch_id, seq in duplicate_sequences:
            structured_errors.append(
                f"batch {batch_id}: duplicate sequence {seq} with differing eventIds")
        if structured_errors:
            print("Structured diagnostics are incomplete or invalid:", file=sys.stderr)
            for error in structured_errors:
                print(f"  - {error}", file=sys.stderr)
            sys.exit(2)

    # Derive terminal classification from what we have.
    def terminal(j):
        if j.get("diagnostic") and j.get("terminal"):
            return j["terminal"]
        v = (j.get("verdict") or "").lower()
        mode = (j.get("verify_mode") or j.get("plan_mode") or "").lower()
        out = j.get("outputSize")
        src = j.get("sourceSize") or j.get("sourceSizeBytes")
        remuxish = "remux" in mode or "remux" in v
        if "verification failed" in v or (j.get("playable") == "failed"):
            return "OUTPUT_VALIDATION_FAILED"
        if "perceptually lossless verified" in v:
            size_ratio = j.get("sizeRatio")
            if (size_ratio is not None and size_ratio <= (1.0 - 0.03)) or (
                src and out and out <= src * (1.0 - 0.03)
            ):
                return "TRANSCODED_SMALLER"
            return "TRANSCODED_NOT_MEANINGFULLY_SMALLER"
        if remuxish and j.get("remuxReason") and "near optimal" in (j.get("remuxReason") or "").lower():
            return "ALREADY_HIGHLY_OPTIMIZED"
        if remuxish:
            return "UNEXPECTED_REMUX"
        return "UNCLASSIFIED"

    # A corrected build also emits human-readable verification lines. Suppress only those legacy
    # rows whose redacted job id exactly matches a structured record; retain unrelated legacy-only
    # batches from a multi-session Logcat Extreme export.
    structured_ids = {
        jobs[key].get("id") for key in order if jobs[key].get("diagnostic")
    }
    rows = []
    for key in order:
        j = jobs[key]
        legacy_base_id = j.pop("_legacyBaseId", None)
        if not j.get("diagnostic") and legacy_base_id in structured_ids:
            continue
        if not j.get("terminal"):
            j["terminal"] = terminal(j)
        if j.get("countsAsRealCompression") is None:
            j["countsAsRealCompression"] = j["terminal"] in {
                "TRANSCODED_SMALLER", "LOSSY_SMALLER"
            }
        if j.get("countsAsRealCompression") and j.get("savedBytes") is None:
            size_ratio = j.get("sizeRatio")
            output_size = j.get("outputSize")
            if size_ratio and output_size and 0 < size_ratio < 1:
                j["savedBytes"] = max(0, round(output_size / size_ratio) - round(output_size))
        rows.append(j)

    with open(os.path.join(outdir, "jobs.jsonl"), "w", encoding="utf-8") as f:
        for j in rows:
            f.write(json.dumps(j, ensure_ascii=False) + "\n")

    cols = ["batchId", "id", "plan_mode", "verify_mode", "source", "sourceCodec", "resolvedOutputMime",
            "hdr", "sourceBitrate", "audioBitrate", "targetVideoBitrate", "defaultRatio",
            "learnedRatio", "floorRatio", "learnedTargetRatio", "verdict", "pixelCertified", "playable", "replaceAllowed",
            "outputSize", "sizeRatio", "remuxReason", "blockReason", "fallbackReason",
            "discardedVideoBitrate", "probedRatios", "pixelProvenRatio", "probeDetail",
            "probeWindowScores", "probePairDiag", "certWindowScores", "thermalStart", "thermalEnd", "precedingCooldownMs",
            "materializationMode", "originalReuseBlockReason", "copyAvoidedBytes", "terminal",
            "countsAsRealCompression", "savedBytes", "rawByteDelta"]
    with open(os.path.join(outdir, "summary.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        for j in rows:
            w.writerow(j)

    agg = {
        "total_items": len(rows),
        "by_batch": dict(Counter((j.get("batchId") or "legacy") for j in rows)),
        "by_terminal": dict(Counter(j["terminal"] for j in rows)),
        "by_verdict": dict(Counter((j.get("verdict") or "none") for j in rows)),
        "by_source_codec": dict(Counter((j.get("sourceCodec") or j.get("sourceMime") or "unknown") for j in rows)),
        "by_plan_mode": dict(Counter((j.get("plan_mode") or j.get("requestedMode") or "unknown") for j in rows)),
        "remux_reasons": dict(Counter((j.get("remuxReason") or "none") for j in rows)),
        "block_reasons": dict(Counter((j.get("blockReason") or "none") for j in rows)),
        # Why a Perceptually Lossless encode was discarded for a remux (survives privacy-mode capture).
        "fallback_reasons": dict(Counter((j.get("fallbackReason") or "none") for j in rows)),
    }
    # simple headroom read: how many were routed to remux purely by the near-optimal gate
    agg["near_optimal_remux_count"] = sum(
        1 for j in rows if "near optimal" in (j.get("remuxReason") or "").lower())
    agg["real_compression_count"] = sum(1 for j in rows if j.get("countsAsRealCompression") is True)
    agg["real_compression_saved_bytes"] = sum(
        int(j.get("savedBytes") or 0) for j in rows if j.get("countsAsRealCompression") is True)
    agg["diagnostic_session_records"] = session_records
    agg["structured_ignored_field_count"] = int(sum(structured_ignored.values()))
    agg["structured_ignored_fields"] = dict(structured_ignored)
    agg["structured_parse_warnings"] = list(structured_errors)
    # v2 envelope integrity + environment reporting.
    all_seqs = sorted({s for seqs in sequences_by_batch.values() for s in seqs})
    agg["by_profile"] = dict(Counter(
        (profiles_by_batch.get(j.get("batchId")) or j.get("profileKind") or "unknown")
        for j in rows))
    agg["structured_schema_versions"] = sorted({
        int(r.get("schemaVersion")) for r in session_records if r.get("schemaVersion") is not None})
    agg["unique_structured_events"] = int(unique_structured_events)
    agg["first_sequence"] = all_seqs[0] if all_seqs else None
    agg["last_sequence"] = all_seqs[-1] if all_seqs else None
    agg["missing_sequences"] = {b: g for b, g in missing_sequences.items()}
    agg["duplicate_sequences"] = [list(x) for x in duplicate_sequences]
    agg["malformed_event_id_count"] = int(malformed_event_ids)
    agg["replay_event_count"] = int(replay_events)
    agg["session_terminal_types"] = dict(Counter(
        t.get("type") for terms in session_terminals.values() for t in terms))
    with open(os.path.join(outdir, "aggregate.json"), "w", encoding="utf-8") as f:
        json.dump(agg, f, indent=2)

    print(json.dumps(agg, indent=2))
    print(f"\nWrote jobs.jsonl, summary.csv, aggregate.json to {outdir} ({len(rows)} items)")

if __name__ == "__main__":
    main()
