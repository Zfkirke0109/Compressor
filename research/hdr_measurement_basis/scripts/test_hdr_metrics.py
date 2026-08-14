#!/usr/bin/env python3
"""Tests for the HDR metric math.

These check identities that follow from the standards themselves (BT.2100 / BT.2124 /
ST 2084), so they catch a transposed matrix or a swapped constant — the failure mode
that would otherwise produce plausible-looking numbers and a wrong conclusion about
whether HDR re-encoding is transparent.

Run: python3 -m pytest test_hdr_metrics.py   (or: python3 test_hdr_metrics.py)
"""

from __future__ import annotations

import math

import numpy as np

from hdr_metrics import (
    DELTA_E_ITP_SCALE,
    PQ_PEAK_NITS,
    bt2020_luminance,
    delta_e_itp,
    linear_rgb_to_ictcp,
    pq_eotf,
    pq_inverse_eotf,
    pu21_encode,
    pu21_psnr,
    summarize_delta_e,
)


def test_pq_round_trip():
    """EOTF and its inverse must invert each other across the full PQ range."""
    nits = np.array([0.0, 0.005, 0.1, 1.0, 10.0, 100.0, 203.0, 1000.0, 4000.0, 10000.0])
    recovered = pq_eotf(pq_inverse_eotf(nits))
    assert np.allclose(recovered, nits, rtol=1e-9, atol=1e-6), recovered


def test_pq_anchors():
    """The endpoints fixed by ST 2084.

    Note the low end is not analytically zero: at Y=0 the formula reduces to c1^m2 =
    0.8359375^78.84375 ~= 7.3e-7. That is far below a 10-bit code step (1/1023 ~= 1e-3),
    so it is zero for every practical purpose — but asserting `== 0.0` would be asserting
    something the standard does not actually say.
    """
    zero = float(pq_inverse_eotf(np.array(0.0)))
    assert 0.0 <= zero < 1e-5, zero
    assert math.isclose(float(pq_inverse_eotf(np.array(PQ_PEAK_NITS))), 1.0, abs_tol=1e-12)
    # 100 cd/m^2 (the SDR reference white level) sits near the middle of the PQ curve.
    mid = float(pq_inverse_eotf(np.array(100.0)))
    assert 0.50 < mid < 0.52, mid


def test_pq_is_monotonic():
    nits = np.linspace(0.0, PQ_PEAK_NITS, 5000)
    encoded = pq_inverse_eotf(nits)
    assert np.all(np.diff(encoded) >= 0.0)


def test_neutral_grey_is_exactly_achromatic():
    """R=G=B must give Ct=Cp=0 exactly.

    This is an exact consequence of BT.2100: every row of the RGB->LMS matrix sums to
    4096/4096, and the Ct and Cp rows of the LMS->ICtCp matrix each sum to zero. If a
    matrix were transposed or mistyped, this test fails immediately.
    """
    for level in (0.1, 1.0, 100.0, 1000.0, 10000.0):
        ictcp = linear_rgb_to_ictcp(np.array([level, level, level]))
        assert abs(float(ictcp[1])) < 1e-12, (level, ictcp)
        assert abs(float(ictcp[2])) < 1e-12, (level, ictcp)
        # I of a neutral equals the PQ encoding of that luminance.
        assert math.isclose(float(ictcp[0]), float(pq_inverse_eotf(np.array(level))), abs_tol=1e-12)


def test_matrix_rows_sum_as_the_standard_requires():
    from hdr_metrics import LMS_TO_ICTCP, RGB_TO_LMS

    assert np.allclose(RGB_TO_LMS.sum(axis=1), [1.0, 1.0, 1.0], atol=1e-12)
    assert math.isclose(LMS_TO_ICTCP[0].sum(), 1.0, abs_tol=1e-12)
    assert abs(LMS_TO_ICTCP[1].sum()) < 1e-12
    assert abs(LMS_TO_ICTCP[2].sum()) < 1e-12


def test_identical_images_have_zero_difference():
    rng = np.random.default_rng(20260813)
    img = rng.uniform(0.0, 4000.0, size=(16, 16, 3))
    assert float(np.max(delta_e_itp(img, img))) == 0.0
    assert pu21_psnr(bt2020_luminance(img), bt2020_luminance(img)) == math.inf


def test_delta_e_is_symmetric():
    rng = np.random.default_rng(7)
    a = rng.uniform(0.0, 2000.0, size=(8, 8, 3))
    b = a + rng.normal(0.0, 5.0, size=a.shape)
    b = np.clip(b, 0.0, None)
    assert np.allclose(delta_e_itp(a, b), delta_e_itp(b, a), atol=1e-12)


def test_delta_e_grows_with_error():
    """Monotonicity: a bigger perturbation must not score as a smaller difference."""
    base = np.full((8, 8, 3), 200.0)
    previous = -1.0
    for delta in (0.0, 0.5, 2.0, 10.0, 50.0):
        score = float(np.mean(delta_e_itp(base, base + delta)))
        assert score >= previous, (delta, score, previous)
        previous = score


def test_delta_e_scale_is_in_jnd_units():
    """A visible perturbation should land in single/low-double-digit JNDs.

    Guards the 720 factor: dropping it would put obvious errors far below 1 JND and
    make everything look transparent, which is the dangerous direction.
    """
    base = np.full((4, 4, 3), 100.0)
    # A 10% luminance error on a neutral patch is plainly visible.
    score = float(np.mean(delta_e_itp(base, base * 1.10)))
    assert 1.0 < score < 100.0, score


def test_delta_e_scale_constant_matches_bt2124():
    assert DELTA_E_ITP_SCALE == 720.0


def test_summary_reports_the_tail_not_just_the_mean():
    """A localized defect must be visible in the summary.

    A frame that is perfect except for a small bad region has a tiny mean. If the summary
    reported only the mean, that frame would read as transparent — exactly the
    banding/hue-shift case this metric exists to catch.

    This also pins what each statistic can and cannot see, which matters when someone
    later picks a threshold: a defect smaller than 0.1% of the frame is BELOW the 99.9th
    percentile and only `max` and `fraction_over_1_jnd` will report it.
    """
    tiny = np.zeros(10000)
    tiny[:5] = 40.0  # 0.05% of pixels — finer than p99.9 can resolve
    summary = summarize_delta_e(tiny)
    assert summary["mean"] < 0.05
    assert summary["max"] == 40.0
    assert summary["p999"] == 0.0, "p99.9 cannot see a 0.05% defect; max must carry it"
    assert math.isclose(summary["fraction_over_1_jnd"], 5 / 10000)
    assert summary["pixels"] == 10000

    # At 0.5% of the frame the percentile does pick it up.
    bigger = np.zeros(10000)
    bigger[:50] = 40.0
    wider = summarize_delta_e(bigger)
    assert wider["p999"] == 40.0
    assert wider["mean"] < 0.25


def test_summary_rejects_empty_input():
    try:
        summarize_delta_e(np.array([]))
    except ValueError:
        return
    raise AssertionError("empty field must raise rather than report a bogus summary")


def test_pu21_is_monotonic_and_bounded():
    nits = np.logspace(math.log10(0.005), math.log10(10000.0), 2000)
    encoded = pu21_encode(nits)
    assert np.all(np.diff(encoded) > 0.0)
    assert float(encoded[0]) >= 0.0
    # Clamping, not extrapolation, outside the fitted domain.
    assert float(pu21_encode(np.array(1e-9))) == float(pu21_encode(np.array(0.005)))
    assert float(pu21_encode(np.array(1e9))) == float(pu21_encode(np.array(10000.0)))


def test_pu21_psnr_decreases_as_error_grows():
    rng = np.random.default_rng(11)
    ref = rng.uniform(1.0, 1000.0, size=(32, 32))
    previous = math.inf
    for sigma in (0.5, 5.0, 50.0):
        noisy = np.clip(ref + rng.normal(0.0, sigma, ref.shape), 0.005, None)
        score = pu21_psnr(ref, noisy)
        assert score < previous, (sigma, score, previous)
        previous = score


def test_bt2020_luminance_coefficients_sum_to_one():
    white = np.array([500.0, 500.0, 500.0])
    assert math.isclose(float(bt2020_luminance(white)), 500.0, rel_tol=1e-12)


def _main() -> int:
    failures = 0
    for name, fn in sorted(globals().items()):
        if not name.startswith("test_") or not callable(fn):
            continue
        try:
            fn()
            print(f"PASS {name}")
        except Exception as exc:  # noqa: BLE001 - test harness
            failures += 1
            print(f"FAIL {name}: {exc!r}")
    print(f"\n{'FAILED' if failures else 'OK'} ({failures} failure(s))")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(_main())
