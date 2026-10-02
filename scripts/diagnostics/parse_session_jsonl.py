#!/usr/bin/env python3
"""Summarize one or two DiagnosticsRecorder session.jsonl captures.

Reads the structured records the app writes to
`filesDir/diagnostics/<batchId>/session.jsonl` and reports the totals that matter for
Perceptually Lossless evaluation: real compressions, bytes actually saved, terminal-state
distribution, and the verification failures that need explaining.

Deliberately conservative about what counts as a win. Remuxes, retained originals, skips, and
failures save zero bytes and are never reported as compressions, so this tool cannot flatter a
run. `savedBytes` is summed only from jobs whose own record claims a real compression AND whose
output is genuinely smaller than the source.

Usage:
    python3 parse_session_jsonl.py NEW.jsonl [OLD.jsonl] [--json out.json]

Exit codes: 0 ok, 2 unreadable/empty input.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, OrderedDict
from typing import Any

from session_records import read_records, read_manifest


def load_sessions(path: str) -> "OrderedDict[str, dict[str, Any]]":
    """Group a capture's records by batchId, in the order each batch first appears.

    The app's in-app export writes EVERY session.jsonl the recorder holds, newest first, so one
    file routinely carries a dozen batches from several different builds. An earlier version of
    this loader kept a single `start`/`summary` pair and appended every job to one list, which
    silently produced a merged total labelled with the LAST session_start in the file -- i.e. the
    OLDEST batch's id and build. The capture uploaded on 2026-09-01 would have been reported as
    387 jobs of "batch_1786611119841 build=457edfe" when the batch actually under test contributed
    36 of them and 219 came from a build four revisions older.

    A summary that attributes one build's jobs to another is worse than no summary: it is the
    exact failure this tooling exists to prevent. So sessions are kept separate and the caller has
    to say which one it means.

    Accepts both capture transports (bare JSONL and logcat-prefixed) and tolerates a truncated
    tail; see session_records.decode_record for why the prefix has to be handled.
    """
    sessions: "OrderedDict[str, dict[str, Any]]" = OrderedDict()

    def bucket(batch_id: Any) -> dict[str, Any]:
        key = str(batch_id)
        if key not in sessions:
            sessions[key] = {"batchId": key, "start": None, "summary": None, "jobs": [],
                             "stages": [], "learnedSnapshot": None, "learnedUpdates": 0,
                             "learnedUpdatesLinked": 0, "updates": [], "identity": None}
        return sessions[key]

    # Records are attributed by their own batchId where they carry one, and otherwise to the
    # session_start they follow. Both are needed: current builds stamp batchId on every record,
    # while older captures stamp it only on session_start and rely on block order.
    current: Any = None
    for rec in read_records(path):
        kind = rec.get("type") or rec.get("eventType")
        if kind == "session_start":
            current = rec.get("batchId")
            bucket(current)["start"] = rec
        elif kind == "session_summary":
            bucket(rec.get("batchId") or current)["summary"] = rec
        elif kind in ("session_failed", "session_cancelled"):
            bucket(rec.get("batchId") or current)["terminal"] = rec
        elif kind == "job":
            bucket(rec.get("batchId") or current)["jobs"].append(rec)
        # Schema v3: stage events and the learned state the batch started from, with its updates.
        elif kind == "stage":
            bucket(rec.get("batchId") or current)["stages"].append(rec)
        elif kind == "learned_state_snapshot":
            bucket(rec.get("batchId") or current)["learnedSnapshot"] = rec
        elif kind == "learned_state_update":
            b = bucket(rec.get("batchId") or current)
            b["learnedUpdates"] += 1
            b["updates"].append(rec)
            # b177 F4: from this build each update names the job that caused it.
            if rec.get("jobId"):
                b["learnedUpdatesLinked"] += 1
        elif kind == "run_identity":
            bucket(rec.get("batchId") or current)["identity"] = rec
    return sessions


def select_session(path: str, batch_id: str | None) -> dict[str, Any]:
    """Pick one batch out of a capture, refusing to guess when the choice is not obvious."""
    sessions = load_sessions(path)
    if not sessions:
        raise SystemExit(f"error: no records in {path}")
    if batch_id is not None:
        if batch_id not in sessions:
            known = ", ".join(sessions) or "(none)"
            raise SystemExit(f"error: {batch_id} is not in {path}; it holds: {known}")
        return sessions[batch_id]
    # Newest first is the exporter's own order, so the first batch is the run just captured.
    chosen = next(iter(sessions.values()))
    if len(sessions) > 1:
        others = [
            f"{sid} ({len(s['jobs'])} jobs, build={(s['start'] or {}).get('buildCommit')})"
            for sid, s in list(sessions.items())[1:]
        ]
        print(
            f"note: {path} holds {len(sessions)} sessions; reporting only the newest,"
            f" {chosen['batchId']}. Also present: {'; '.join(others)}."
            " Use --batch <id> or --all for the others.",
            file=sys.stderr,
        )
    return chosen


def summarize(path: str, batch_id: str | None = None) -> dict[str, Any]:
    session = select_session(path, batch_id)
    start, jobs, summary = session["start"], session["jobs"], session["summary"]
    if not jobs:
        raise SystemExit(
            f"error: batch {session['batchId']} in {path} recorded no job records"
            + ("" if summary else " and no session summary — it did not complete")
        )

    terminals = Counter(str(j.get("terminal")) for j in jobs)
    modes = Counter(str(j.get("effectiveMode")) for j in jobs)
    materialization = Counter(str(j.get("materializationMode")) for j in jobs)

    def num(j: dict[str, Any], key: str) -> int:
        v = j.get(key)
        return int(v) if isinstance(v, (int, float)) else 0

    # A real compression must BOTH be claimed by the record and be genuinely smaller.
    real = [
        j for j in jobs
        if j.get("countsAsRealCompression") is True
        and 0 < num(j, "outputSize") < num(j, "sourceSize")
    ]
    claimed_only = [j for j in jobs if j.get("countsAsRealCompression") is True and j not in real]

    failures = [j for j in jobs if str(j.get("terminal")) == "OUTPUT_VALIDATION_FAILED"]
    unexplained = [
        j for j in failures
        if not j.get("failedChecks") and not j.get("blockReason") and not j.get("fallbackReason")
    ]

    # Which verification predicates actually rejected files. This is the whole point of recording
    # `failedChecks`: a rejection rate is a symptom, the named predicate is the cause. A single
    # predicate at 100% of generated outputs means one gate is rejecting everything, which is a
    # very different problem from a spread across many.
    #
    # Counted over every job that carries the field rather than only OUTPUT_VALIDATION_FAILED,
    # because a failed check can also show up on a job that recovered through a fallback. Jobs from
    # builds predating the field record null and are simply absent from the denominator.

    # Probe ladders that ran but produced no measurement at all. Detectable on ANY capture,
    # including ones predating the fix: the ladder recorded candidate ratios but no window
    # scores. Those jobs were reported as "no candidate ratio passed", which asserts the rungs
    # were measured and rejected — so a reader (or a threshold calibrated from captures) takes
    # measurement failure for pixel evidence that the clip resists re-encoding.
    verified_jobs = [j for j in jobs if isinstance(j.get("failedChecks"), list)]
    ladders = [j for j in jobs if j.get("probedRatios")]
    ladders_unmeasured = [j for j in ladders if not j.get("probeWindowScores")]
    mislabelled = [
        j for j in ladders_unmeasured
        if str(j.get("probeDetail")) == "no candidate ratio passed"
    ]
    check_counts = Counter(
        c for j in verified_jobs for c in j["failedChecks"] if isinstance(c, str)
    )
    # A rejection that names nothing. From schema v3, a candidate discarded by pixel certification
    # carries verified=false with an empty structural failure list but a fallbackReason (and its
    # structuralVerified=true): that is explained, not silent.
    silent_rejections = [
        j for j in verified_jobs
        if j.get("verified") is False and not j["failedChecks"]
        and not j.get("fallbackReason") and not j.get("blockReason")
    ]

    return {
        "path": path,
        "batchId": (start or {}).get("batchId") or session["batchId"],
        "buildCommit": (start or {}).get("buildCommit"),
        "mode": (start or {}).get("mode"),
        "jobs": len(jobs),
        "sourceBytes": sum(num(j, "sourceSize") for j in jobs),
        "realCompressions": len(real),
        "claimedButNotSmaller": len(claimed_only),
        "savedBytes": sum(num(j, "sourceSize") - num(j, "outputSize") for j in real),
        "copyAvoidedBytes": sum(num(j, "copyAvoidedBytes") for j in jobs),
        "generatedOutputBytes": sum(
            num(j, "outputSize") for j in jobs
            if str(j.get("materializationMode")) == "GENERATED_FILE"
        ),
        "pixelCertified": sum(1 for j in jobs if j.get("pixelCertified") is True),
        "pixelProven": sum(1 for j in jobs if j.get("pixelProvenRatio") is not None),
        "withProbeRatios": sum(1 for j in jobs if j.get("probedRatios")),
        "certScoresPresent": sum(1 for j in jobs if j.get("certWindowScores")),
        "bandingPresent": sum(1 for j in jobs if j.get("certBandingDiag")),
        "validationFailures": len(failures),
        "unexplainedFailures": len(unexplained),
        "laddersRun": len(ladders),
        "laddersWithNoMeasurement": len(ladders_unmeasured),
        "laddersMislabelledAsMeasured": len(mislabelled),
        "jobsWithVerification": len(verified_jobs),
        "failedCheckCounts": dict(check_counts.most_common()),
        "silentRejections": len(silent_rejections),
        "learnedStateIdentity": (start or {}).get("learnedStateIdentity"),
        "terminals": dict(terminals.most_common()),
        "effectiveModes": dict(modes.most_common()),
        "materialization": dict(materialization.most_common()),
        "elapsedMs": (summary or session.get("terminal") or {}).get("elapsedMs"),
        "exhaustivePerceptualLossless": (start or {}).get("exhaustivePerceptualLossless"),
        "buildTag": (start or {}).get("buildTag"),
        "v1ShadowPairs": v1_shadow_pairs(jobs),
        "probeCertDrift": probe_cert_drift(jobs, session.get("stages", [])),
        "marginalAttempts": marginal_attempts(jobs),
        "budgetExhausted": budget_exhausted(jobs),
        "decisionBasis": dict(Counter(basis_kind(j.get("decisionBasis")) for j in jobs if j.get("decisionBasis")).most_common()),
        "media3Input": media3_input(jobs),
        # Ladders left undecided because a window held too few frames (low frame rate): never a
        # quality result, and never allowed to read as "would visibly lose quality".
        "framesUndecided": sum(1 for j in jobs if "a window held fewer than" in str(j.get("probeDetail") or "")),
        "overshootPrediction": overshoot_prediction(jobs),
        "acceptanceContradictions": acceptance_contradictions(jobs),
        "certificationDecisions": dict(Counter(
            str(j.get("certificationDecision")) for j in jobs if j.get("certificationDecision")).most_common()),
        "saferRungRetries": safer_rung_retries(jobs, session.get("stages", [])),
        "audioClaims": audio_claims(jobs),
        "replayCoverage": replay_coverage(session),
        "stageReasons": dict(Counter(
            f"{r.get('stage')}:{r.get('reasonCode')}" for r in session.get("stages", [])).most_common()),
        "learnedSnapshot": (
            {"sha256": session["learnedSnapshot"].get("sha256"),
             "profiles": session["learnedSnapshot"].get("profileCount"),
             "updates": session.get("learnedUpdates", 0)}
            if session.get("learnedSnapshot") else None),
        "completed": summary is not None,
        "sessionEnd": session_end(summary, session.get("terminal")),
    }


_ACCEPTING_TERMINALS = {"TRANSCODED_SMALLER", "LOSSY_SMALLER", "EXPLICIT_REMUX"}


def acceptance_contradictions(jobs: list[dict[str, Any]]) -> dict[str, Any]:
    """Records whose final fields claim an accepted, replaceable output that the job did not keep.

    Before schema v3 the pixel-certification rejection path wrote the structural verifier's report
    as the job's verdict: b169 job_478c2fa19100 and job_c92a4ca7e1be read SKIPPED_WOULD_DEGRADE,
    outputSize=0, pixelCertified=false AND verdict="Perceptually Lossless Verified", verified=true,
    replacementSafe=true. Such a record is flagged here, never counted as an accepted output (the
    savings total already requires a real compression with a smaller kept output).
    """
    flagged = []
    for j in jobs:
        kept = isinstance(j.get("outputSize"), (int, float)) and j["outputSize"] > 0
        accepting = str(j.get("terminal")) in _ACCEPTING_TERMINALS
        claims = j.get("replacementSafe") is True or (
            j.get("verified") is True and j.get("materializationMode") != "REUSED_SOURCE")
        if claims and (not kept or not accepting and j.get("replacementSafe") is True):
            flagged.append({
                "jobId": j.get("jobId") or j.get("id"),
                "terminal": j.get("terminal"),
                "schemaVersion": j.get("schemaVersion"),
            })
    return {"count": len(flagged), "jobs": flagged}


def _attempt_outcomes(attempts: Any) -> list[str]:
    """Outcome of each element of an `attempts` string ("0.85:measured_below_bar:cand=…;…")."""
    out = []
    for a in str(attempts or "").split(";"):
        parts = a.split(":")
        if len(parts) >= 2 and a:
            out.append(parts[1])
    return out


def safer_rung_retries(jobs: list[dict[str, Any]], stages: list[dict[str, Any]] = ()) -> dict[str, Any] | None:
    """Jobs with more than one full-encode attempt (the opt-in SaferRungRetry), and how they ended.

    b177 F3: the denominator is attempts STARTED. From this build the job record carries
    `attemptsStarted` and every attempt's outcome, including an export failure or cancellation
    that left no candidate; a cancelled item writes no job record, so its `encode_cancelled`
    stage event is counted too. Older records only listed attempts that reached certification.
    """
    rows = []
    for j in jobs:
        outcomes = _attempt_outcomes(j.get("attempts"))
        started = j.get("attemptsStarted") if isinstance(j.get("attemptsStarted"), int) else len(outcomes)
        if started > 1:
            rows.append((j.get("jobId") or j.get("id"), outcomes, j))
    recorded = {r[0] for r in rows}
    for ev in stages:
        if ev.get("stage") == "encode" and ev.get("reasonCode") == "encode_cancelled":
            if (ev.get("attemptsStarted") or 0) > 1 and ev.get("jobId") not in recorded:
                rows.append((ev.get("jobId"), _attempt_outcomes(ev.get("attempts")), None))
    if not rows:
        return None
    return {
        "retried": len(rows),
        "certifiedAfterRetry": sum(1 for _, _, j in rows if j is not None and j.get("countsAsRealCompression") is True),
        "secondAttemptOutcomes": dict(Counter(o[1] for _, o, _ in rows if len(o) > 1).most_common()),
        "terminals": dict(Counter(str(j.get("terminal")) if j is not None else "CANCELLED" for _, _, j in rows).most_common()),
        "jobs": [{"jobId": jid, "attempts": (j or {}).get("attempts") if j is not None else "|".join(o)} for jid, o, j in rows],
    }


def audio_claims(jobs: list[dict[str, Any]]) -> dict[str, Any]:
    """What was asked of the audio against what the record says happened (b177 F5).

    `audioRequested` is from this build; older records only carry it inside `encodePlan`
    ("audio=copy(...)"). An older lossy-mode record says "re-encoded (lossy mode)" for every job,
    whatever happened: that label is flagged as a mode name, not an observation.
    """
    contradictions = []
    verified = 0
    for j in jobs:
        requested = j.get("audioRequested")
        if requested is None and "audio=copy(" in str(j.get("encodePlan") or ""):
            requested = "copy"
        observed = str(j.get("audioPreservation") or "")
        if observed.startswith("bit-identical"):
            verified += 1
        if requested == "copy" and (observed.startswith("re-encoded") or "packets differ" in observed):
            contradictions.append({
                "jobId": j.get("jobId"), "nameHash": j.get("nameHash"), "observed": observed,
                "legacyModeLabel": observed == "re-encoded (lossy mode)",
            })
    return {"requestedCopyObservedOther": contradictions, "verifiedCopies": verified}


_REPLAY_STAGES = ("plan", "probe_rung", "size_gate", "encode", "finalize", "verify", "certify", "accept")


def replay_coverage(session: dict[str, Any]) -> dict[str, Any]:
    """Whether the capture could replay its policy decisions, and what is missing if not (b177 F4).

    Coverage is judged per job: each job needs the stages its own record says it went through.
    A stage no job reached is listed (`missingStages`) but is not a gap: b182's 12-source batch,
    every source probe-rejected, has no encode to record and was labelled partial for it.
    """
    stages = session.get("stages", [])
    seen = {ev.get("stage") for ev in stages}
    missing = [st for st in _REPLAY_STAGES if st not in seen]
    # The size gate runs only when the ladder proved (or accepted as marginal) a ratio. The job's
    # probeRungId is not that signal: it names the rung whose windows the record carries, which is
    # the last measured rung even when none passed (b182 job_641d0183abbb, both rungs undecided).
    # In b182 the gate ran for exactly the 29 of 236 jobs with a passed or marginal rung.
    gated: set[Any] = {ev.get("jobId") for ev in stages
                       if ev.get("stage") == "probe_rung" and ev.get("verdict") in ("passed", "marginal")}
    updates = session.get("learnedUpdates", 0)
    jobs = {j.get("jobId") or j.get("id"): j for j in session.get("jobs", [])}
    job_stages: dict[str, set[str]] = {jid: set() for jid in jobs if jid}
    orphan_stages = 0
    for ev in stages:
        jid = ev.get("jobId")
        if jid in job_stages:
            job_stages[jid].add(ev.get("stage"))
        else:
            orphan_stages += 1
    missing_by_job = {}
    for jid, j in jobs.items():
        if not jid:
            continue
        required = {"accept"}
        if (j.get("attemptsStarted") or 0) > 0:
            required.update(("plan", "encode"))
        if j.get("probedRatios"):
            required.add("probe_rung")
        if jid in gated or j.get("pixelProvenRatio") is not None:
            required.add("size_gate")
        if j.get("candidateBytes"):
            required.update(("finalize", "verify"))
        if j.get("certificationDecision") or str(j.get("certificationStatus") or "").startswith("ran_"):
            required.add("certify")
        absent = sorted(required - job_stages.get(jid, set()))
        if absent:
            missing_by_job[jid] = absent
    snapshot = (session.get("learnedSnapshot") or {}).get("sha256")
    captured_updates = session.get("updates", [])
    valid_updates = sum(1 for ev in captured_updates if ev.get("jobId") in jobs
                        and isinstance(ev.get("attemptIndex"), int)
                        and 0 <= ev["attemptIndex"] <= (jobs[ev["jobId"]].get("attemptsStarted") or 0)
                        and ev.get("snapshotSha256") == snapshot and snapshot)
    update_indexes = [ev.get("updateIndex") for ev in captured_updates]
    valid_update_sequence = update_indexes == list(range(updates))
    complete = (not missing_by_job and not orphan_stages and jobs
                and updates == len(captured_updates) == valid_updates and valid_update_sequence
                and session.get("learnedSnapshot") is not None and snapshot
                and session.get("identity") is not None)
    return {
        "label": "REPLAYABLE_RECORD" if complete else "PARTIAL_OBSERVATIONAL_REPLAY",
        "missingStages": missing,
        "missingJobStages": missing_by_job,
        "orphanStages": orphan_stages,
        "learnedUpdates": updates,
        "learnedUpdatesLinked": valid_updates,
        "invalidLearningUpdates": updates - valid_updates if len(captured_updates) == updates and valid_update_sequence else updates,
        "runIdentity": session.get("identity") is not None,
    }


def session_end(summary: dict[str, Any] | None, terminal: dict[str, Any] | None) -> dict[str, Any]:
    """How the batch ended, from its own terminal record.

    A batch the user cancelled writes session_cancelled, and one that failed writes session_failed.
    Reporting either as merely "did not finish" hides which it was: b167's 14-second cancelled
    batch read as an unexplained stop with 221 jobs.
    """
    if summary is not None:
        return {"kind": "completed"}
    if terminal is not None:
        kind = terminal.get("type") or terminal.get("eventType")
        return {
            "kind": "cancelled" if kind == "session_cancelled" else "failed",
            "reason": terminal.get("reason"),
            "elapsedMs": terminal.get("totalElapsedMs", terminal.get("elapsedMs")),
            "cancelledJobs": terminal.get("cancelled"),
        }
    return {"kind": "unfinished"}


def media3_input(jobs: list[dict[str, Any]]) -> dict[str, Any]:
    """Files Media3 could not parse (SourceParseFailure), and what the platform copy did for them.

    `media3Input` is recorded only for such files. The outcome that matters is whether the
    platform-normalised copy let the encode through, so the terminals are counted per kind.
    """
    out: dict[str, Any] = {}
    for j in jobs:
        text = str(j.get("media3Input") or "")
        if not text:
            continue
        if text.startswith("not normalised: the source is damaged"):
            kind = "damaged (zero-filled)"
        elif "damaged" in text:
            kind = "damaged (copy too small)"
        elif text.startswith("platform-normalised copy also unreadable"):
            kind = "copy also unreadable"
        elif text.startswith("platform-normalised copy"):
            kind = "normalised"
        elif text.startswith("normalisation failed"):
            kind = "normalisation failed"
        elif text.startswith("not normalised"):
            kind = "not normalised"
        else:
            kind = "other"
        entry = out.setdefault(kind, {"files": 0, "terminals": {}})
        entry["files"] += 1
        term = str(j.get("terminal"))
        entry["terminals"][term] = entry["terminals"].get(term, 0) + 1
    return out


def overshoot_prediction(jobs: list[dict[str, Any]]) -> dict[str, Any] | None:
    """Actual encoder overshoot against the proven rung's probe prediction, per encoded file.

    The worth-encoding gate trusts the probe prediction only down to MeasuredOvershoot.MARGIN
    (0.13) below its mean. This re-measures the error on every capture: an actual more than the
    margin BELOW the prediction is a file the gate could have skipped wrongly.
    """
    import re
    errors: list[float] = []
    for j in jobs:
        rate, proven, config = j.get("probeRateDiag"), j.get("pixelProvenRatio"), j.get("encoderConfig")
        if not rate or proven is None or not config:
            continue
        m = re.search(r"ratio=([0-9.]+);mode", str(config))
        if not m:
            continue
        section = None
        for part in str(rate).split(";"):
            key, _, value = part.partition("=")
            try:
                if abs(float(key) - float(proven)) < 1e-6:
                    section = value
            except ValueError:
                continue
        factors = [float(x) for x in re.findall(r",x([0-9.]+)\]", section or "")]
        if len(factors) < 2:
            continue
        errors.append(float(m.group(1)) - sum(factors) / len(factors))
    if not errors:
        return None
    return {
        "encodes": len(errors),
        "meanError": sum(errors) / len(errors),
        "worstUnder": min(errors),
        "worstOver": max(errors),
        "beyondMargin": sum(1 for e in errors if e < -0.13),
    }


def _scores(field: Any) -> list[tuple[float, float, float]]:
    out: list[tuple[float, float, float]] = []
    for w in str(field or "").split(";"):
        parts = w.split("/")
        if len(parts) != 3:
            continue
        try:
            out.append((float(parts[0]), float(parts[1]), float(parts[2])))
        except ValueError:
            continue
    return out


_RUNG_IN_DETAIL = re.compile(r"windows passed at (\d+(?:\.\d+)?)")


def _floats(field: Any) -> list[float]:
    out = []
    for v in str(field or "").split(";"):
        try:
            out.append(float(v))
        except ValueError:
            out.append(float("nan"))
    return out


def _window_map(ev: dict[str, Any], prefix: str) -> dict[str, tuple[float, float, float]]:
    ids = [i for i in str(ev.get(f"{prefix}WindowIds") or "").split(";") if i]
    means, p5s, mins = (_floats(ev.get(f"{prefix}{k}")) for k in ("Mean", "P5", "Min"))
    if not ids or not (len(ids) == len(means) == len(p5s) == len(mins)):
        return {}
    return {i: (m, p, n) for i, m, p, n in zip(ids, means, p5s, mins)}


def probe_cert_drift(jobs: list[dict[str, Any]], stages: list[dict[str, Any]] = ()) -> dict[str, Any] | None:
    """How far the finished encode landed from its probe, window by window, per gate.

    A pair is formed only when the probe window and the certification window are the same window
    of the same source, at the same ratio and encoder request (b177 F2). From this build the job
    names its probe rung (`probeRungId` = ratio@config) and its encode config (`encodeConfigId`),
    and the stage events carry full-precision windows with ids: those are joined by window id.
    Older records have neither, so a legacy job is paired by position only when its probe rung
    (from `probeDetail`) is the ratio it encoded at and it made a single attempt. Everything else
    is excluded with the reason, never paired: b177 PL-B's retry recorded .85 probe windows beside
    .90 certification windows, and positional pairing read that as drift.
    """
    rungs: dict[tuple[Any, Any], dict[str, Any]] = {}
    certs: dict[Any, dict[str, Any]] = {}
    for ev in stages:
        if ev.get("stage") == "probe_rung" and ev.get("rungId"):
            rungs[(ev.get("jobId"), ev.get("rungId"))] = ev
        elif ev.get("stage") == "certify" and ev.get("accepted") is True:
            certs[ev.get("jobId")] = ev
    deltas: list[tuple[float, float, float]] = []
    basis: Counter = Counter()
    excluded: list[dict[str, Any]] = []
    unjoined = 0
    for j in jobs:
        if j.get("certificationStatus") != "ran_scored":
            continue
        jid = j.get("jobId") or j.get("id")
        planned = j.get("plannedTargetRatio")
        rung_id, cfg = j.get("probeRungId"), j.get("encodeConfigId")
        if rung_id or cfg:
            ratio_s, _, rung_cfg = str(rung_id or "").partition("@")
            if not rung_id or not cfg:
                excluded.append({"jobId": jid, "reason": "probe rung or encode config not on record"})
                continue
            if rung_cfg != cfg:
                excluded.append({"jobId": jid, "reason": f"probe config {rung_cfg} differs from the encode config {cfg}"})
                continue
            if not isinstance(planned, (int, float)) or abs(float(ratio_s) - planned) > 1e-9:
                excluded.append({"jobId": jid, "reason": f"probe rung {ratio_s} differs from the encoded ratio {planned}"})
                continue
            probe_ev, cert_ev = rungs.get((jid, rung_id)), certs.get(jid)
            p = _window_map(probe_ev, "probe") if probe_ev else {}
            c = _window_map(cert_ev, "cert") if cert_ev else {}
            if not p or not c:
                excluded.append({"jobId": jid, "reason": "no window ids on the probe or certification events"})
                continue
            for wid in c:
                if wid in p:
                    a, b = p[wid], c[wid]
                    deltas.append((b[0] - a[0], b[1] - a[1], b[2] - a[2]))
                    basis["identity_window_id"] += 1
                else:
                    unjoined += 1
            unjoined += sum(1 for wid in p if wid not in c)
            continue
        # Legacy record.
        detail = str(j.get("probeDetail") or "")
        if "safer-rung retry" in detail or len(_attempt_outcomes(j.get("attempts"))) > 1:
            excluded.append({"jobId": jid, "reason": "retry: the record's probe windows belong to another rung"})
            continue
        m = _RUNG_IN_DETAIL.search(detail)
        rung_ratio = float(m.group(1)) if m else None
        if rung_ratio is None or not isinstance(planned, (int, float)) or abs(rung_ratio - planned) > 1e-9:
            excluded.append({"jobId": jid, "reason": f"probe rung {m.group(1) if m else 'unknown'} differs from the encoded ratio {planned}"})
            continue
        p, c = _scores(j.get("probeWindowScores")), _scores(j.get("certWindowScores"))
        if not p or len(p) != len(c):
            excluded.append({"jobId": jid, "reason": "probe and certification window counts differ"})
            continue
        deltas += [(b[0] - a[0], b[1] - a[1], b[2] - a[2]) for a, b in zip(p, c)]
        basis["legacy_same_rung_position"] += len(p)

    def q(values: list[float], frac: float) -> float:
        v = sorted(values)
        return v[max(0, min(len(v) - 1, round(frac * (len(v) - 1))))]

    out: dict[str, Any] = {"windows": len(deltas), "joinBasis": dict(basis), "excluded": excluded,
                           "unjoinedWindows": unjoined}
    if not deltas and not excluded:
        return None
    for i, name in enumerate(("mean", "p5", "min")):
        col = [d[i] for d in deltas]
        out[name] = {"median": q(col, 0.5), "p10": q(col, 0.1), "worst": min(col)} if col else None
    return out


def marginal_attempts(jobs: list[dict[str, Any]]) -> dict[str, int]:
    """Encodes attempted on a probe that cleared the bar but not the selection margin, and how
    they fared. If these certify as often as ordinary passes, the margin is too strict."""
    marginal = [j for j in jobs if "below the selection margin" in str(j.get("probeDetail") or "")]
    return {
        "attempted": len(marginal),
        "certified": sum(1 for j in marginal if j.get("pixelCertified")),
    }


def budget_exhausted(jobs: list[dict[str, Any]]) -> dict[str, int]:
    """Ladders that ran out of time, and how many of those files were then encoded anyway and
    failed certification (a full encode spent with no probe evidence behind it)."""
    out = [j for j in jobs if str(j.get("probeDetail") or "").startswith("probe budget exhausted")]
    return {
        "ladders": len(out),
        "encodedThenFailed": sum(1 for j in out if j.get("certificationStatus") == "ran_scored" and not j.get("pixelCertified")),
    }


def basis_kind(text: Any) -> str:
    t = str(text or "")
    if "the size prediction decided" in t:
        return "probe passed, size predicted"
    if "(learned)" in t:
        return "learned"
    if "cannot be pixel-measured" in t:
        return "cannot be measured"
    if "not pixel-measured" in t:
        return "not probed"
    if "Probes at" in t:
        return "probed, undecided"
    return "other"


def v1_shadow_pairs(jobs: list[dict[str, Any]]) -> list[tuple[float, float]]:
    """(v0.6.1 window min, v1 window min) for every certification window carrying both.

    VMAF v1 is recorded as SHADOW evidence only (the verdict is v0.6.1). Pairing the two per
    window is what a calibration round needs: whether v1 ranks the same windows as failing, and
    where on its own scale the v0.6.1 bars fall. Windows are matched by position, which is how
    the app writes them (one ";"-joined entry per certification window, in window order).
    """
    pairs: list[tuple[float, float]] = []
    for j in jobs:
        v0, v1 = j.get("certWindowScores"), j.get("certV1Scores")
        if not v0 or not v1:
            continue
        a, b = str(v0).split(";"), str(v1).split(";")
        if len(a) != len(b):
            continue
        for x, y in zip(a, b):
            try:
                pairs.append((float(x.split("/")[2]), float(y.split("/")[2])))
            except (IndexError, ValueError):
                continue
    return pairs


def render(s: dict[str, Any]) -> str:
    out = [
        f"  batch          : {s['batchId']}  build={s['buildCommit']}  mode={s['mode']}",
        f"  jobs           : {s['jobs']}",
        f"  source bytes   : {s['sourceBytes']:,}",
        f"  REAL compress. : {s['realCompressions']}   savedBytes={s['savedBytes']:,}",
        f"  pixel-certified: {s['pixelCertified']}   pixel-proven={s['pixelProven']}   probed={s['withProbeRatios']}",
        f"  cert scores    : {s['certScoresPresent']}   banding records={s['bandingPresent']}",
        f"  copy avoided   : {s['copyAvoidedBytes']:,}",
        f"  generated bytes: {s['generatedOutputBytes']:,}",
        f"  validation fail: {s['validationFailures']}   unexplained={s['unexplainedFailures']}",
        f"  learned state  : {s['learnedStateIdentity'] or 'NOT RECORDED (runs are not comparable)'}"
        + (f"   snapshot sha256={s['learnedSnapshot']['sha256'][:16]}… ({s['learnedSnapshot']['profiles']} profiles,"
           f" {s['learnedSnapshot']['updates']} updates)" if s.get("learnedSnapshot") else "   (no snapshot: identity only)"),
        f"  probe ladders  : {s['laddersRun']}   measured nothing: {s['laddersWithNoMeasurement']}",
        f"  terminals      : {s['terminals']}",
        f"  effective modes: {s['effectiveModes']}",
    ]
    if not s["completed"]:
        end = s.get("sessionEnd") or {"kind": "unfinished"}
        seconds = end.get("elapsedMs")
        after = f" after {seconds / 1000:.1f} s" if isinstance(seconds, (int, float)) else ""
        if end["kind"] == "cancelled":
            jobs = end.get("cancelledJobs")
            line = (f"  !! CANCELLED{after} (reason={end.get('reason')}"
                    + (f"; {jobs} job(s) recorded as CANCELLED" if jobs is not None else "")
                    + ") — totals are a partial run")
        elif end["kind"] == "failed":
            line = f"  !! FAILED{after} (reason={end.get('reason')}) — totals are a partial run"
        else:
            line = ("  !! NO session_summary and no cancel/fail record — this batch did not finish"
                    " (the process ended mid-run); totals are a partial run")
        out.insert(1, line)
    if s.get("buildTag") or s.get("exhaustivePerceptualLossless") is not None:
        out.append(f"  build tag      : {s.get('buildTag')}   exhaustive PL: {s.get('exhaustivePerceptualLossless')}")
    drift = s.get("probeCertDrift")
    if drift:
        out.append(f"  probe->cert    : {drift['windows']} window(s) scored on both (full encode minus probe);"
                   f" join {drift.get('joinBasis')}; excluded {len(drift.get('excluded') or [])} job(s)")
        for name in ("mean", "p5", "min"):
            d = drift.get(name)
            if d:
                out.append(f"      {name:<4}  median {d['median']:+.2f}   p10 {d['p10']:+.2f}   worst {d['worst']:+.2f}")
        for e in (drift.get("excluded") or [])[:6]:
            out.append(f"      excluded {e['jobId']}: {e['reason']}")
    marginal = s.get("marginalAttempts") or {}
    if marginal.get("attempted"):
        out.append(f"  marginal passes: {marginal['attempted']} attempted, {marginal['certified']} certified")
    budget = s.get("budgetExhausted") or {}
    if budget.get("ladders"):
        out.append(f"  budget exhausted: {budget['ladders']} ladder(s), {budget['encodedThenFailed']} then encoded and failed certification")
    if s.get("decisionBasis"):
        out.append(f"  keep-original basis: {s['decisionBasis']}")
    if s.get("media3Input"):
        out.append("  media3 input   : files Media3 could not parse, by what the platform copy did")
        for kind, entry in s["media3Input"].items():
            out.append(f"      {kind:<22} {entry['files']:3} file(s)  {entry['terminals']}")
    if s.get("framesUndecided"):
        out.append(f"  frames-limited : {s['framesUndecided']} ladder(s) undecided on too few frames per window (not a quality result)")
    contra = s.get("acceptanceContradictions") or {}
    if contra.get("count"):
        out.append(
            f"  !! contradictory acceptance: {contra['count']} record(s) claim a verified/replaceable output"
            " the job did not keep (flagged, not counted): "
            + ", ".join(f"{c['jobId']}({c['terminal']})" for c in contra["jobs"][:8])
        )
    if s.get("stageReasons"):
        out.append(f"  stage events   : {s['stageReasons']}")
    if s.get("certificationDecisions"):
        out.append(f"  cert decisions : {s['certificationDecisions']}")
    retries = s.get("saferRungRetries")
    if retries:
        out.append(
            f"  safer-rung retry: {retries['retried']} job(s) retried, {retries['certifiedAfterRetry']} certified after retry;"
            f" second attempts {retries.get('secondAttemptOutcomes')}; {retries['terminals']}"
        )
    audio = s.get("audioClaims") or {}
    if audio.get("requestedCopyObservedOther"):
        n = len(audio["requestedCopyObservedOther"])
        legacy = sum(1 for a in audio["requestedCopyObservedOther"] if a.get("legacyModeLabel"))
        out.append(f"  !! audio        : {n} job(s) requested a copy but are recorded otherwise"
                   f" ({legacy} with the pre-b177 mode label, which is not an observation)")
    cov = s.get("replayCoverage")
    if cov:
        out.append(f"  replay         : {cov['label']}; jobs missing stages {len(cov['missingJobStages'])}"
                   f" {dict(list(cov['missingJobStages'].items())[:3]) or ''}; orphan stages {cov['orphanStages']};"
                   f" stages no job reached {cov['missingStages'] or 'none'};"
                   f" learned updates linked {cov['learnedUpdatesLinked']}/{cov['learnedUpdates']};"
                   f" run identity {'yes' if cov['runIdentity'] else 'no'}")
    over = s.get("overshootPrediction")
    if over:
        out.append(
            f"  overshoot pred.: {over['encodes']} encode(s); actual minus probe-predicted mean"
            f" {over['meanError']:+.3f}, range {over['worstUnder']:+.3f}..{over['worstOver']:+.3f};"
            f" {over['beyondMargin']} below the -0.13 margin"
        )
    pairs = s.get("v1ShadowPairs") or []
    if pairs:
        out.append(f"  VMAF v1 shadow : {len(pairs)} certification window(s) scored by both models (min score)")
        for v0, v1 in pairs[:12]:
            out.append(f"      v0.6.1 {v0:6.2f}   v1 {v1:6.2f}   delta {v1 - v0:+6.2f}")
    if s["jobsWithVerification"]:
        out.append(f"  failing checks : ({s['jobsWithVerification']} job(s) carry verification)")
        for name, n in s["failedCheckCounts"].items():
            share = n / s["jobsWithVerification"]
            out.append(f"      {n:5}  {share:6.1%}  {name}")
        if not s["failedCheckCounts"]:
            out.append("      (none — every verified job passed every predicate in scope)")
    else:
        out.append("  failing checks : not recorded by this build; a rejection cannot be diagnosed")
    if s["laddersMislabelledAsMeasured"]:
        out.append(
            f"  !! {s['laddersMislabelledAsMeasured']} ladder(s) report 'no candidate ratio passed'"
            " having measured NOTHING — that wording claims pixel evidence this run does not have"
        )
    if s["silentRejections"]:
        out.append(
            f"  !! {s['silentRejections']} rejected job(s) name NO failing check — a verdict that"
            " identifies nothing is a defect in the verifier, not a result"
        )
    if s["claimedButNotSmaller"]:
        out.append(
            f"  !! {s['claimedButNotSmaller']} job(s) claim a real compression but are not smaller"
        )
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sessions", nargs="+", help="one or two session.jsonl files")
    ap.add_argument("--json", help="write the machine-readable summary here")
    ap.add_argument(
        "--batch",
        help="batchId to report; default is the newest session in the capture. A capture normally"
             " holds every session the recorder has ever written, across several builds.",
    )
    ap.add_argument(
        "--all",
        action="store_true",
        help="report every session in the capture separately (never merged)",
    )
    args = ap.parse_args()

    if args.all:
        summaries = []
        for p in args.sessions:
            for sid, sess in load_sessions(p).items():
                if not sess["jobs"]:
                    print(f"\n=== {p} :: {sid} ===")
                    print(f"  (no job records{'' if sess['summary'] else '; no session summary either'})")
                    continue
                summaries.append(summarize(p, sid))
    else:
        summaries = [summarize(p, args.batch) for p in args.sessions]
    for s in summaries:
        print(f"\n=== {s['path']} ===")
        manifest = read_manifest(s["path"])
        if manifest:
            print(
                f"  archive        : {manifest.get('scopeLabel')} · app {manifest.get('appVersionName')}"
                f" ({manifest.get('buildTag')}, {manifest.get('buildCommit')}) · {manifest.get('deviceModel')}"
                f" Android {manifest.get('androidRelease')} · exported {manifest.get('exportedAt')}"
                f" · {len(manifest.get('files') or [])} files"
            )
        print(render(s))

    if len(summaries) == 2 and not args.all:
        a, b = summaries
        print("\n=== delta (second minus first) ===")
        for key in ("jobs", "realCompressions", "savedBytes", "validationFailures",
                    "unexplainedFailures", "copyAvoidedBytes", "generatedOutputBytes",
                    "pixelCertified", "withProbeRatios"):
            print(f"  {key:22}: {b[key] - a[key]:+,}")
        # Refuse causal language: these runs are not a controlled experiment.
        print("\n  NOTE: differences are observational only. Learned-profile state, thermal")
        print("  history, and execution order were not controlled, so no cause may be inferred.")

    if args.json:
        with open(args.json, "w") as fh:
            json.dump(summaries, fh, indent=2)
        print(f"\nwrote {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
