#!/usr/bin/env python3
"""HDR/WCG full-reference difference metrics for Compressor research.

Why this module exists
----------------------
`scripts/diagnostics/measure_quality.py` deliberately REJECTS HDR and wide-gamut
material (`HDR_OR_WIDE_GAMUT_UNSUPPORTED_FAIL_CLOSED`), because VMAF is trained on
SDR BT.709 and there is no validated PQ/HLG VMAF model. The app mirrors that: the
Perceptually Lossless gates require `!source.isHdr`, so HDR clips can only ever be
stream-copied.

That is the correct behavior *given no HDR measurement*. This module supplies the
missing measurement so the question can eventually be settled with evidence instead
of being permanently closed.

What it computes
----------------
**DeltaE_ITP (ITU-R BT.2124)** — a standards-defined colour difference for HDR/WCG.
It needs no trained model and no tone map, which is precisely why it is usable here
where VMAF is not. Its scale is calibrated in JNDs: **1.0 = 1 just-noticeable
difference**, so "perceptually lossless" has a defensible meaning on this axis.

Pipeline per BT.2124, applied to both reference and distorted frames:

    linear BT.2020 RGB -> LMS -> PQ (ST 2084) -> ICtCp -> (I, T=Ct/2, P=Cp)
    DeltaE_ITP = 720 * sqrt(dI^2 + dT^2 + dP^2)

**PU21-PSNR** is provided as a secondary, independent axis. PU21 (Mantiuk) maps HDR
luminance into a perceptually uniform space so that ordinary SDR-style metrics become
meaningful on HDR signals. Two metrics that disagree is a signal worth investigating;
one metric alone is a single point of failure.

Scope and honesty
-----------------
This is RESEARCH tooling. It establishes a measurement basis; it does NOT set a
threshold and it changes nothing in production. No HDR clip may be re-encoded by
Smart PL on the strength of this module alone — that requires a calibrated threshold,
device validation, and an explicit, separately-reviewed decision. See README.md.
"""

from __future__ import annotations

import math
from typing import Any

try:
    import numpy as np
except ImportError as exc:  # pragma: no cover - environment guard
    raise SystemExit("numpy is required: pip install numpy") from exc


# --- SMPTE ST 2084 (PQ) constants -------------------------------------------------
# Exact rational forms from the standard; do not "simplify" these to decimals.
PQ_M1 = 2610.0 / 16384.0
PQ_M2 = 2523.0 / 4096.0 * 128.0
PQ_C1 = 3424.0 / 4096.0
PQ_C2 = 2413.0 / 4096.0 * 32.0
PQ_C3 = 2392.0 / 4096.0 * 32.0

# PQ is defined against a 10,000 cd/m^2 peak.
PQ_PEAK_NITS = 10000.0

# BT.2124 scales the ICtCp difference by 720 so that 1.0 == 1 JND.
DELTA_E_ITP_SCALE = 720.0

# BT.2100 linear BT.2020 RGB -> LMS (rational /4096 forms from the standard).
RGB_TO_LMS = np.array(
    [
        [1688.0, 2146.0, 262.0],
        [683.0, 2951.0, 462.0],
        [99.0, 309.0, 3688.0],
    ]
) / 4096.0

# BT.2100 PQ-LMS -> ICtCp.
LMS_TO_ICTCP = np.array(
    [
        [2048.0, 2048.0, 0.0],
        [6610.0, -13613.0, 7003.0],
        [17933.0, -17390.0, -543.0],
    ]
) / 4096.0


def pq_inverse_eotf(linear_nits: "np.ndarray") -> "np.ndarray":
    """Absolute luminance in cd/m^2 -> PQ non-linear signal in [0, 1] (ST 2084)."""
    y = np.clip(np.asarray(linear_nits, dtype=np.float64) / PQ_PEAK_NITS, 0.0, 1.0)
    y_m1 = np.power(y, PQ_M1)
    return np.power((PQ_C1 + PQ_C2 * y_m1) / (1.0 + PQ_C3 * y_m1), PQ_M2)


def pq_eotf(signal: "np.ndarray") -> "np.ndarray":
    """PQ non-linear signal in [0, 1] -> absolute luminance in cd/m^2 (ST 2084)."""
    e = np.clip(np.asarray(signal, dtype=np.float64), 0.0, 1.0)
    e_m2 = np.power(e, 1.0 / PQ_M2)
    numerator = np.maximum(e_m2 - PQ_C1, 0.0)
    denominator = PQ_C2 - PQ_C3 * e_m2
    return PQ_PEAK_NITS * np.power(numerator / denominator, 1.0 / PQ_M1)


def linear_rgb_to_ictcp(rgb_nits: "np.ndarray") -> "np.ndarray":
    """Linear BT.2020 RGB in cd/m^2 -> ICtCp (BT.2100, PQ variant).

    Input shape (..., 3); output shape (..., 3) as (I, Ct, Cp).
    """
    rgb = np.asarray(rgb_nits, dtype=np.float64)
    if rgb.shape[-1] != 3:
        raise ValueError(f"expected trailing dimension 3, got {rgb.shape}")
    lms = rgb @ RGB_TO_LMS.T
    lms_pq = pq_inverse_eotf(lms)
    return lms_pq @ LMS_TO_ICTCP.T


def delta_e_itp(ref_rgb_nits: "np.ndarray", dist_rgb_nits: "np.ndarray") -> "np.ndarray":
    """Per-pixel DeltaE_ITP (BT.2124) between two linear BT.2020 RGB images.

    1.0 is one just-noticeable difference. Inputs are absolute luminance (cd/m^2),
    shape (..., 3); the result drops the trailing colour axis.
    """
    ref = linear_rgb_to_ictcp(ref_rgb_nits)
    dist = linear_rgb_to_ictcp(dist_rgb_nits)
    d_i = ref[..., 0] - dist[..., 0]
    # BT.2124 uses T = Ct / 2, which is the 0.25 weight when written in Ct terms.
    d_t = 0.5 * (ref[..., 1] - dist[..., 1])
    d_p = ref[..., 2] - dist[..., 2]
    return DELTA_E_ITP_SCALE * np.sqrt(d_i * d_i + d_t * d_t + d_p * d_p)


# --- PU21 ------------------------------------------------------------------------
# Mantiuk & Azimi's PU21 "banding_glare" fit: maps absolute luminance to a
# perceptually uniform code value so SDR-style metrics apply to HDR signals.
PU21_BANDING_GLARE = (
    0.353487901, 0.3734658629, 8.277049286e-05, 0.9062562627,
    0.09150303166, 0.9099517204, 596.3148142,
)

# Lower bound of the PU21 fit's valid domain (cd/m^2).
PU21_MIN_NITS = 0.005


def pu21_encode(luminance_nits: "np.ndarray") -> "np.ndarray":
    """Absolute luminance (cd/m^2) -> PU21 perceptually uniform values.

        V = p6 * ( ((p0 + p1 * Y^p3) / (1 + p2 * Y^p3))^p4 - p5 )

    Note the parameter roles: **p3 is the exponent on luminance and p6 is the output
    scale**, not the other way round. Reading p6 as an exponent overflows instantly at
    HDR luminances (10000^596), so the ordering here is load-bearing, not cosmetic.

    The fit is defined over roughly [0.005, 10000] cd/m^2; inputs are clamped into that
    domain rather than extrapolated, because extrapolating a perceptual fit produces
    confident-looking nonsense at the extremes.
    """
    p = PU21_BANDING_GLARE
    y = np.clip(np.asarray(luminance_nits, dtype=np.float64), PU21_MIN_NITS, PQ_PEAK_NITS)
    y_p3 = np.power(y, p[3])
    inner = (p[0] + p[1] * y_p3) / (1.0 + p[2] * y_p3)
    return np.maximum(p[6] * (np.power(inner, p[4]) - p[5]), 0.0)


def pu21_peak() -> float:
    """PU21 value at the 10,000 cd/m^2 peak — the dynamic range PU21-PSNR is taken against."""
    return float(pu21_encode(np.array(PQ_PEAK_NITS)))


def pu21_psnr(ref_luminance_nits: "np.ndarray", dist_luminance_nits: "np.ndarray") -> float:
    """PSNR computed in PU21 space, in dB. Returns inf for identical inputs."""
    ref = pu21_encode(ref_luminance_nits)
    dist = pu21_encode(dist_luminance_nits)
    mse = float(np.mean((ref - dist) ** 2))
    if mse <= 0.0:
        return math.inf
    peak = pu21_peak()
    return 10.0 * math.log10((peak * peak) / mse)


def bt2020_luminance(rgb_nits: "np.ndarray") -> "np.ndarray":
    """Linear BT.2020 RGB -> luminance Y in cd/m^2 (BT.2020 luma coefficients)."""
    rgb = np.asarray(rgb_nits, dtype=np.float64)
    return 0.2627 * rgb[..., 0] + 0.6780 * rgb[..., 1] + 0.0593 * rgb[..., 2]


def summarize_delta_e(per_pixel: "np.ndarray") -> dict[str, Any]:
    """Frame-level summary of a per-pixel DeltaE_ITP field.

    Reports the tail, not just the mean. A frame can average well under 1 JND and
    still have a visibly wrong region: banding and hue shifts are localized, so the
    99.9th percentile and max are the numbers that matter for a transparency claim.
    """
    flat = np.asarray(per_pixel, dtype=np.float64).ravel()
    if flat.size == 0:
        raise ValueError("empty DeltaE field")
    return {
        "pixels": int(flat.size),
        "mean": float(np.mean(flat)),
        "p99": float(np.percentile(flat, 99.0)),
        "p999": float(np.percentile(flat, 99.9)),
        "max": float(np.max(flat)),
        # Share of the frame at or beyond 1 JND — the directly interpretable figure.
        "fraction_over_1_jnd": float(np.mean(flat >= 1.0)),
    }
