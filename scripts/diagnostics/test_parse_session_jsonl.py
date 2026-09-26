#!/usr/bin/env python3
"""Tests for the session summariser, focused on how it reports verification failures.

The summariser exists to make a rejection diagnosable. These pin the two properties that matter:
a named predicate is attributed to the right jobs, and a rejection that names nothing is called
out rather than quietly counted as an ordinary failure.
"""
from __future__ import annotations

import json
import os
import tempfile

from parse_session_jsonl import load_sessions, summarize


def write(records) -> str:
    fd, path = tempfile.mkstemp(suffix=".jsonl")
    with os.fdopen(fd, "w") as fh:
        for r in records:
            fh.write(json.dumps(r) + "\n")
    return path


def job(h, **kw):
    d = {"type": "job", "nameHash": h, "terminal": "OUTPUT_VALIDATION_FAILED",
         "sourceSize": 1000, "outputSize": 900, "countsAsRealCompression": False,
         "verified": False}
    d.update(kw)
    return d


def start(**kw):
    d = {"type": "session_start", "batchId": "b1", "buildCommit": "abc1234", "mode": "PL"}
    d.update(kw)
    return d


def test_a_capture_holding_several_builds_is_never_merged_into_one_total():
    # The in-app export writes EVERY session the recorder holds, newest first, so one file
    # routinely spans several builds. Merging them and labelling the total with whichever
    # session_start happened to be parsed last attributes one build's jobs to another -- the
    # 2026-09-01 capture would have read as 387 jobs of the OLDEST batch when the batch under
    # test contributed 36.
    path = write([
        start(batchId="new", buildCommit="d72cfee"),
        job("a", batchId="new", terminal="ALREADY_HIGHLY_OPTIMIZED", verified=True, failedChecks=[]),
        {"type": "session_summary", "batchId": "new", "elapsedMs": 10},
        start(batchId="old", buildCommit="457edfe"),
        job("b", batchId="old"),
        job("c", batchId="old"),
        {"type": "session_summary", "batchId": "old", "elapsedMs": 20},
    ])
    sessions = load_sessions(path)
    assert list(sessions) == ["new", "old"]
    assert len(sessions["new"]["jobs"]) == 1
    assert len(sessions["old"]["jobs"]) == 2

    # Default is the newest session, reported under ITS OWN build -- not a merged 3-job total.
    s = summarize(path)
    assert s["batchId"] == "new"
    assert s["buildCommit"] == "d72cfee"
    assert s["jobs"] == 1

    # And an older batch stays addressable rather than being lost.
    old = summarize(path, "old")
    assert old["batchId"] == "old" and old["buildCommit"] == "457edfe" and old["jobs"] == 2


def test_records_without_their_own_batch_id_follow_the_session_they_come_after():
    # Older captures stamp batchId only on session_start and rely on block order. Those must
    # still land in the right session rather than collapsing into one "None" bucket.
    path = write([start(batchId="b1"), job("a"), start(batchId="b2"), job("b"), job("c")])
    sessions = load_sessions(path)
    assert [len(v["jobs"]) for v in sessions.values()] == [1, 2]
    assert list(sessions) == ["b1", "b2"]


def test_a_batch_that_recorded_no_summary_is_flagged_as_unfinished():
    # A batch killed mid-run still has job records. Reporting its totals as if the run completed
    # would understate the corpus and hide the fact that it stopped.
    from parse_session_jsonl import render
    path = write([start(batchId="b1"), job("a")])
    s = summarize(path)
    assert s["completed"] is False
    assert "did not finish" in render(s)


def test_v1_shadow_scores_are_paired_with_the_verdict_scores_window_by_window():
    s = summarize(write([
        start(),
        job("a", certWindowScores="99.0/98.0/97.0;90.0/85.0/80.0",
            certV1Scores="98.0/97.0/96.5;88.0/82.0/75.0"),
        job("b", certWindowScores="99.0/98.0/97.0", certV1Scores=None),
        # Mismatched window counts are dropped, never guessed into alignment.
        job("c", certWindowScores="99.0/98.0/97.0;90.0/85.0/80.0", certV1Scores="98.0/97.0/96.0"),
    ]))
    assert s["v1ShadowPairs"] == [(97.0, 96.5), (80.0, 75.0)]


def test_failing_predicates_are_counted_and_shared_against_verified_jobs_only():
    # Three jobs carry verification, one is a retained source that never ran the verifier.
    s = summarize(write([
        start(),
        job("a", failedChecks=["mediaStoreDateMatches"]),
        job("b", failedChecks=["mediaStoreDateMatches", "mp4DateMatches"]),
        job("c", failedChecks=[], verified=True, terminal="ALREADY_HIGHLY_OPTIMIZED"),
        job("d", failedChecks=None, terminal="ALREADY_HIGHLY_OPTIMIZED"),
    ]))
    assert s["jobsWithVerification"] == 3
    assert s["failedCheckCounts"] == {"mediaStoreDateMatches": 2, "mp4DateMatches": 1}


def test_a_rejection_naming_no_check_is_reported_as_a_verifier_defect():
    s = summarize(write([start(), job("a", failedChecks=[], verified=False)]))
    assert s["silentRejections"] == 1


def test_a_passing_job_naming_no_check_is_not_a_silent_rejection():
    s = summarize(write([
        start(),
        job("a", failedChecks=[], verified=True, terminal="ALREADY_HIGHLY_OPTIMIZED"),
    ]))
    assert s["silentRejections"] == 0
    assert s["failedCheckCounts"] == {}


def test_a_build_that_never_recorded_the_field_is_distinguished_from_one_with_no_failures():
    # null is "this build cannot tell you", [] is "it told you: nothing failed". Conflating them
    # is how a blind capture gets mistaken for a clean one.
    absent = summarize(write([start(), job("a", terminal="ALREADY_HIGHLY_OPTIMIZED")]))
    present = summarize(write([
        start(),
        job("a", failedChecks=[], verified=True, terminal="ALREADY_HIGHLY_OPTIMIZED"),
    ]))
    assert absent["jobsWithVerification"] == 0
    assert present["jobsWithVerification"] == 1


def test_learned_state_identity_is_surfaced_for_run_comparability():
    s = summarize(write([start(learnedStateIdentity="n=12,h=1a2b"), job("a")]))
    assert s["learnedStateIdentity"] == "n=12,h=1a2b"
    assert summarize(write([start(), job("a")]))["learnedStateIdentity"] is None


def test_a_ladder_that_measured_nothing_is_separated_from_one_that_measured_and_failed():
    # Both report the same terminal, and before the fix both reported the same probeDetail.
    # Only the presence of window scores tells them apart, which is why the summariser checks it.
    s = summarize(write([
        start(),
        job("a", probedRatios="0.90,0.95", probeWindowScores="97.0/93.0/88.0",
            probeDetail="no candidate ratio passed"),
        job("b", probedRatios="0.90,0.95", probeWindowScores=None,
            probeDetail="no candidate ratio passed"),
        job("c", probedRatios=None, probeWindowScores=None),
    ]))
    assert s["laddersRun"] == 2
    assert s["laddersWithNoMeasurement"] == 1
    assert s["laddersMislabelledAsMeasured"] == 1


def test_the_corrected_wording_is_not_counted_as_mislabelled():
    # After the fix the ladder says so itself, so it is no longer a silent misreport.
    s = summarize(write([
        start(),
        job("b", probedRatios="0.90,0.95", probeWindowScores=None,
            probeDetail="no probe rung could be measured (2 not time-alignable, 0 unmeasurable)"
                        " — nothing was scored, so this is NOT evidence the clip resists re-encoding"),
    ]))
    assert s["laddersWithNoMeasurement"] == 1
    assert s["laddersMislabelledAsMeasured"] == 0


def _main():
    fails = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn(); print(f"PASS {name}")
            except Exception as e:
                fails += 1; print(f"FAIL {name}: {e!r}")
    print(f"\n{'FAILED' if fails else 'OK'} ({fails} failure(s))")
    return 1 if fails else 0


def test_probe_to_certification_drift_and_marginal_attempts_are_reported():
    path = write([
        {"type": "session_start", "batchId": "b1"},
        job("a", certificationStatus="ran_scored", pixelCertified=True,
            probeWindowScores="97.0/93.0/88.0;96.0/92.0/86.0", certWindowScores="96.5/92.0/87.5;96.0/91.0/85.0",
            probeDetail="windows passed at 0.70"),
        job("b", certificationStatus="ran_scored", pixelCertified=False,
            probeWindowScores="95.8/91.2/84.3", certWindowScores="95.0/90.0/83.0",
            probeDetail="windows passed at 0.90 by only 0.20, below the selection margin (0.5/1.25/1.0); certification decides"),
        job("c", decisionBasis="Basis: heuristic. This file cannot be pixel-measured on this device: its resolution is above the 4K scoring limit."),
        job("d", decisionBasis="Basis: earlier measured failures for this device and content class (learned), not a measurement of this file."),
        {"type": "session_summary", "batchId": "b1"},
    ])
    s = summarize(path)
    assert s["probeCertDrift"]["windows"] == 3
    assert abs(s["probeCertDrift"]["mean"]["worst"] - (-0.8)) < 1e-9
    assert abs(s["probeCertDrift"]["p5"]["worst"] - (-1.2)) < 1e-9
    assert s["marginalAttempts"] == {"attempted": 1, "certified": 0}
    assert s["decisionBasis"] == {"cannot be measured": 1, "learned": 1}


def test_ladders_that_ran_out_of_budget_are_counted_with_their_blind_encodes():
    path = write([
        {"type": "session_start", "batchId": "b1"},
        job("a", probeDetail="probe budget exhausted", certificationStatus="ran_scored", pixelCertified=False),
        job("b", probeDetail="probe budget exhausted"),
        job("c", probeDetail="windows passed at 0.90", certificationStatus="ran_scored", pixelCertified=True),
        {"type": "session_summary", "batchId": "b1"},
    ])
    assert summarize(path)["budgetExhausted"] == {"ladders": 2, "encodedThenFailed": 1}


def test_a_cancelled_batch_is_reported_as_cancelled_not_as_unfinished():
    # b167's 14-second batch was cancelled by the user and wrote session_cancelled; the summary
    # called it an unexplained stop. The terminal record says which it was, so the report must too.
    from parse_session_jsonl import render
    path = write([
        start(batchId="b1"),
        job("a", terminal="CANCELLED"),
        {"type": "session_cancelled", "batchId": "b1", "reason": "user_cancelled",
         "totalElapsedMs": 14800, "cancelled": 1},
    ])
    s = summarize(path)
    assert s["completed"] is False
    assert s["sessionEnd"] == {"kind": "cancelled", "reason": "user_cancelled", "elapsedMs": 14800, "cancelledJobs": 1}
    text = render(s)
    assert "CANCELLED after 14.8 s" in text
    assert "did not finish" not in text


def test_a_failed_batch_names_its_failure():
    from parse_session_jsonl import render
    path = write([
        start(batchId="b1"),
        job("a"),
        {"type": "session_failed", "batchId": "b1", "reason": "IllegalStateException", "elapsedMs": 5000},
    ])
    text = render(summarize(path))
    assert "FAILED after 5.0 s (reason=IllegalStateException)" in text


def test_files_media3_could_not_parse_are_grouped_by_what_the_copy_did():
    path = write([
        start(batchId="b1"),
        job("a", terminal="TRANSCODED_SMALLER", media3Input="platform-normalised copy; copy=1432.4MB in 21000ms"),
        job("b", terminal="UNEXPECTED_REMUX", media3Input="normalisation failed: platform copy ended early"),
        job("c", terminal="UNEXPECTED_REMUX", media3Input="platform-normalised copy also unreadable by Media3 (x)"),
        job("d", terminal="UNEXPECTED_REMUX", media3Input="not normalised: HDR source"),
        job("e", terminal="TRANSCODED_SMALLER"),
        {"type": "session_summary", "batchId": "b1"},
    ])
    m = summarize(path)["media3Input"]
    assert m["normalised"] == {"files": 1, "terminals": {"TRANSCODED_SMALLER": 1}}
    assert m["normalisation failed"]["files"] == 1
    assert m["copy also unreadable"]["files"] == 1
    assert m["not normalised"]["files"] == 1
    assert sum(e["files"] for e in m.values()) == 4


def test_overshoot_prediction_error_is_measured_at_the_proven_rung_only():
    # Rung 0.90 is the proven one: its two windows predict 1.26 on average and the encode came out
    # at 1.235. The 0.80 rung's numbers must not enter the comparison.
    path = write([
        start(batchId="b1"),
        job("a", pixelProvenRatio=0.9,
            probeRateDiag="0.80=rate[req=1kbps,win=1kbps,I=1x1kB,P=1x1kB,steady=1kbps,x1.500];"
                          "0.90=rate[req=1kbps,win=1kbps,I=1x1kB,P=1x1kB,steady=1kbps,x1.250]|"
                          "rate[req=1kbps,win=1kbps,I=1x1kB,P=1x1kB,steady=1kbps,x1.270]",
            encoderConfig="mime=video/avc->video/hevc;vbr=1->1;ratio=1.235;mode=VBR"),
        job("b", pixelProvenRatio=0.9,
            probeRateDiag="0.90=rate[req=1kbps,win=1kbps,I=1x1kB,P=1x1kB,steady=1kbps,x1.100]",
            encoderConfig="ratio=1.000;mode=VBR"),
        {"type": "session_summary", "batchId": "b1"},
    ])
    o = summarize(path)["overshootPrediction"]
    # job b has one window only, below MeasuredOvershoot.MIN_WINDOWS, so it is left out.
    assert o["encodes"] == 1
    assert abs(o["meanError"] - (1.235 - 1.26)) < 1e-9
    assert o["beyondMargin"] == 0


def test_a_size_gated_keep_original_is_its_own_basis_kind():
    from parse_session_jsonl import basis_kind
    assert basis_kind("Basis: pixel probes passed at 0.90; the size prediction decided. At that rate ...") == \
        "probe passed, size predicted"
    assert basis_kind("Basis: heuristic. Probes at 0.95 produced no measurement that could decide it.") == "probed, undecided"


def test_damaged_sources_and_frame_limited_ladders_are_counted_apart():
    path = write([
        start(batchId="b1"),
        job("a", terminal="UNEXPECTED_REMUX", media3Input="not normalised: the source is damaged: 100.0 % of the 64 KiB around byte 5 are zero bytes"),
        job("b", terminal="UNEXPECTED_REMUX", media3Input="normalisation failed: platform copy holds only 12.4 % of the source's bytes: most of its sample data is empty or unreadable, so the file itself is damaged"),
        job("c", terminal="ALREADY_HIGHLY_OPTIMIZED", probeDetail="no candidate ratio passed; 4 rung(s) undecided: a window held fewer than 12 frames, which is not a quality measurement"),
        {"type": "session_summary", "batchId": "b1"},
    ])
    s = summarize(path)
    assert s["media3Input"]["damaged (zero-filled)"]["files"] == 1
    assert s["media3Input"]["damaged (copy too small)"]["files"] == 1
    assert s["framesUndecided"] == 1



def test_a_discarded_candidate_claiming_a_verified_replaceable_output_is_flagged_not_counted():
    # b169 job_478c2fa19100: SKIPPED_WOULD_DEGRADE, outputSize=0, yet verdict/verified/replacementSafe
    # carried the structural pass. It must be flagged, and never counted as an accepted output.
    path = write([
        start(batchId="b1"),
        job("a", schemaVersion=2, terminal="SKIPPED_WOULD_DEGRADE", outputSize=0, sourceSize=16753345,
            verified=True, replacementSafe=True, failedChecks=[], pixelCertified=False,
            verdict="Perceptually Lossless Verified",
            fallbackReason="pixel certification failed (sampled VMAF below thresholds)"),
        job("b", terminal="TRANSCODED_SMALLER", outputSize=246118100, sourceSize=322388270,
            countsAsRealCompression=True, verified=True, replacementSafe=True, failedChecks=[]),
        # Schema v3 writes the same rejection truthfully: nothing to flag.
        job("c", schemaVersion=3, terminal="SKIPPED_WOULD_DEGRADE", outputSize=0, verified=False,
            replacementSafe=False, failedChecks=[], finalAccepted=False, structuralVerified=True,
            verdict="Rejected — Skipped — compression would visibly lose quality",
            fallbackReason="pixel certification failed (sampled VMAF below thresholds)",
            certificationDecision="measured_below_bar", candidateBytes=15123456),
        {"type": "session_summary", "batchId": "b1"},
    ])
    s = summarize(path)
    assert s["acceptanceContradictions"]["count"] == 1
    assert s["acceptanceContradictions"]["jobs"][0]["schemaVersion"] == 2
    assert s["realCompressions"] == 1
    assert s["savedBytes"] == 322388270 - 246118100
    # An explained rejection is not a silent one.
    assert s["silentRejections"] == 0
    assert s["certificationDecisions"] == {"measured_below_bar": 1}
    from parse_session_jsonl import render
    assert "contradictory acceptance: 1 record(s)" in render(s)


def test_safer_rung_retries_are_counted_with_their_attempts():
    path = write([
        start(batchId="b1"),
        job("a", terminal="TRANSCODED_SMALLER", countsAsRealCompression=True, outputSize=15000000, sourceSize=16753345,
            attempts="0.85:measured_below_bar:cand=15123456:encodeMs=9000;0.90:accepted:cand=15600000:encodeMs=9400"),
        job("b", terminal="SKIPPED_WOULD_DEGRADE", outputSize=0,
            attempts="0.97:measured_below_bar:cand=270000000:encodeMs=60000"),
        {"type": "session_summary", "batchId": "b1"},
    ])
    s = summarize(path)
    assert s["saferRungRetries"]["retried"] == 1
    assert s["saferRungRetries"]["certifiedAfterRetry"] == 1



def test_a_v3_capture_reports_its_learned_snapshot_and_stage_events():
    path = write([
        start(batchId="b1"),
        {"type": "learned_state_snapshot", "batchId": "b1", "sha256": "ab" * 32, "profileCount": 27},
        {"type": "learned_state_update", "batchId": "b1", "profileKey": "k", "before": None, "after": "x"},
        {"type": "stage", "batchId": "b1", "stage": "certify", "reasonCode": "cert_passed"},
        {"type": "stage", "batchId": "b1", "stage": "certify", "reasonCode": "cert_passed"},
        job("a", terminal="TRANSCODED_SMALLER", countsAsRealCompression=True, outputSize=5, sourceSize=9),
        {"type": "session_summary", "batchId": "b1"},
    ])
    s = summarize(path)
    assert s["learnedSnapshot"] == {"sha256": "ab" * 32, "profiles": 27, "updates": 1}
    assert s["stageReasons"] == {"certify:cert_passed": 2}
    from parse_session_jsonl import render
    assert "snapshot sha256=abababababababab" in render(s)


if __name__ == "__main__":
    raise SystemExit(_main())


