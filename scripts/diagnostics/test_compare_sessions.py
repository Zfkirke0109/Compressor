#!/usr/bin/env python3
"""Tests for the paired session comparison."""
from __future__ import annotations

from compare_sessions import compare, controls_present, saved_bytes


def job(h, terminal="ALREADY_HIGHLY_OPTIMIZED", src=1000, out=0, real=False, **kw):
    d = {"nameHash": h, "terminal": terminal, "sourceSize": src, "outputSize": out,
         "countsAsRealCompression": real}
    d.update(kw)
    return d


def test_saved_bytes_requires_both_a_claim_and_a_smaller_output():
    # A claim alone is not a saving; the output must really be smaller.
    assert saved_bytes(job("a", src=1000, out=800, real=True)) == 200
    assert saved_bytes(job("a", src=1000, out=800, real=False)) == 0
    assert saved_bytes(job("a", src=1000, out=1200, real=True)) == 0
    assert saved_bytes(job("a", src=1000, out=1000, real=True)) == 0
    assert saved_bytes(job("a", src=1000, out=0, real=True)) == 0


def test_only_shared_files_are_paired():
    r = compare([job("a"), job("b")], [job("b"), job("c")])
    assert r["pairedFiles"] == 1
    assert r["baselineOnly"] == ["a"]
    assert r["candidateOnly"] == ["c"]


def test_paired_totals_exclude_unshared_files():
    # A big win on a file the baseline never saw must not inflate the paired delta.
    base = [job("a", src=1000, out=900, real=True)]
    cand = [job("a", src=1000, out=900, real=True), job("z", src=10_000, out=1, real=True)]
    r = compare(base, cand)
    assert r["pairedBaselineSavedBytes"] == 100
    assert r["pairedCandidateSavedBytes"] == 100
    assert r["candidateSavedBytes"] > r["pairedCandidateSavedBytes"]


def test_improvement_and_regression_are_counted_per_file():
    # Chosen so the aggregate is IDENTICAL while two files moved in opposite directions:
    #   a: saves 100 -> 300 (+200)    b: saves 500 -> 300 (-200)
    # An aggregate-only report would call this "no change". It is not.
    base = [job("a", src=1000, out=900, real=True), job("b", src=1000, out=500, real=True)]
    cand = [job("a", src=1000, out=700, real=True), job("b", src=1000, out=700, real=True)]
    r = compare(base, cand)
    assert r["pairedBaselineSavedBytes"] == 600
    assert r["pairedCandidateSavedBytes"] == 600
    assert r["filesImproved"] == 1
    assert r["filesRegressed"] == 1
    assert r["filesChangedOutcome"] == 2


def test_a_changed_terminal_with_equal_savings_is_still_reported():
    base = [job("a", terminal="SKIPPED_WOULD_DEGRADE")]
    cand = [job("a", terminal="OUTPUT_VALIDATION_FAILED")]
    r = compare(base, cand)
    assert r["filesChangedOutcome"] == 1
    assert r["changed"][0]["terminal"] == ["SKIPPED_WOULD_DEGRADE", "OUTPUT_VALIDATION_FAILED"]
    assert r["filesImproved"] == 0 and r["filesRegressed"] == 0


def test_identical_runs_report_no_changes():
    base = [job("a"), job("b")]
    r = compare(base, list(base))
    assert r["filesChangedOutcome"] == 0
    assert r["changed"] == []


def test_diagnostic_fields_are_carried_into_the_change_record():
    base = [job("a", terminal="OUTPUT_VALIDATION_FAILED", failedChecks=[], certificationStatus=None)]
    cand = [job("a", terminal="OUTPUT_VALIDATION_FAILED",
                failedChecks=["mediaStoreDateMatches"],
                certificationStatus="skipped_output_already_failed_verification",
                encoderConfig="mime=video/hevc->video/avc;FALLBACK=mime")]
    # Same terminal AND same savings -> not reported; that is intended, the pair is unchanged.
    assert compare(base, cand)["filesChangedOutcome"] == 0
    # But when the outcome does change, the diagnostics travel with it.
    cand2 = [dict(cand[0], terminal="ALREADY_HIGHLY_OPTIMIZED")]
    ch = compare(base, cand2)["changed"][0]
    assert ch["failedChecks"] == [[], ["mediaStoreDateMatches"]]
    assert "FALLBACK=mime" in ch["encoderConfig"][1]


def test_missing_controls_are_reported_so_no_cause_can_be_claimed():
    missing = controls_present({}, {})
    assert any("learned-profile state" in m for m in missing)
    assert any("thermal" in m for m in missing)
    assert any("order" in m for m in missing)


def test_differing_learned_state_is_named_as_uncontrolled():
    missing = controls_present(
        {"learnedStateIdentity": "empty", "thermalStart": "cool"},
        {"learnedStateIdentity": "n=12,h=abc", "thermalStart": "cool"},
    )
    assert any("learned-profile state differs" in m for m in missing)


def test_order_is_always_uncontrolled_for_single_runs():
    # Even with everything else evidenced, one run per arm cannot control for order.
    missing = controls_present(
        {"learnedStateIdentity": "empty", "thermalStart": "cool"},
        {"learnedStateIdentity": "empty", "thermalStart": "cool"},
    )
    assert missing == ["execution order / counterbalancing (single run per arm)"]


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


if __name__ == "__main__":
    raise SystemExit(_main())
