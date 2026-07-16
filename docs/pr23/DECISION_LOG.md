# Decision log — PR #23 line of work

Chronological record of the key decisions and their evidence. Categories: [M]=measured local,
[C]=derived, [S]=source-proven, [X]=external primary source.

1. **Adaptive search over single-estimate** — [M] 64-file capture showed 32/33 remuxes had zero
   trial encodes. → `f10d968`: bpp-classed probe ladders, retreat rungs (≤0.97), bisection,
   same-codec probe-gating, noise-threshold savings (0.5% & 256 KiB), floor recovery.

2. **Undershoot tolerance 0.06 → 0.15** — [M] QTI HEVC VBR delivered 0.86–0.93× requested (n=11);
   old 0.06 tolerance discarded structurally-perfect encodes. → `03b8a2f`. Certification remains
   the quality gate.

3. **Decaying probe-skip latch** — [M] 84/172 probe ladders measured-failed; skipping saves battery.
   Chosen decaying (2 rejections → skip 3 → forced re-probe) over a permanent flag, because a
   permanent flag reinstates the prediction-only denial this whole effort removes. → `03b8a2f`.

4. **Relabel learning-latched remuxes** — [M] 28/33 UNEXPECTED_REMUX were the learning `preferRemux`
   latch (expected, not a malfunction). → `75d2624`: new REMUX_PREFERRED_BY_EVIDENCE terminal;
   unverified latched copies still fail closed.

5. **Known-winnable latch exemption** — [M] capture 141019 measured the latch denying probes to 26
   files in 3 classes that ALSO verified compressions (coarse buckets; compressibility is content-
   dependent). → `034b23f`: `everCompressed` flag, set on verified success, exempts the class from
   the skip. Only adds probes; no quality-bar change.

6. **Do NOT lower VMAF thresholds** — [M] 38/39 probed failures are clear-fail (median rejected p5
   79 vs 91 bar); [X] VMAF v1 is *stricter* (banding/chroma), not looser. The high remux rate is
   largely genuine, not threshold miscalibration. No threshold change made.

7. **Do NOT swap VMAF v0.6.1 → v1 in this line** — [S] app runs real libvmaf 3.0.0 / v0.6.1; [X] v1
   would reject more, not compress more; it is a large, separate integration (VMAF_V1_INTEGRATION_
   PLAN.md). Deferred.

8. **Throughput: gate cooldown on `encodeAttempt != null`** — [M] inter-item gaps were fixed 10s/30s
   thermal cooldowns applied after 116 zero-encode remux items (~29 min/172). → `f1e2fbb`. Timing-
   only; no decision changed. Pre-item thermal pause + cooldown-after-real-encode preserved.

9. **Fresh-store validation confounds warm A/B** — [M] 83/83 latch suppressions in 141019 inherited
   prior-run state. Ran a fresh-store validation (193710) to isolate first-encounter behavior.

10. **Conclusion from fresh-store run (193710)** — [M] both fixes work; latch fix gave 30 more files
    their measured trial and they correctly failed (SKIPPED 43→84); cooldown now only after the 17
    encode items (6.3 min). The dataset is genuinely mostly-incompressible; the user's skepticism
    was worth testing — the latch was one real defect (fixed), but the thresholds and the overall
    remux rate are honest. 9 compressed / 627.8 MB / 0 regressions.

11. **Next target = remux copy cost** — [M] remuxes = 40.3 min/run, max 267 s for a 0-byte save;
    r=0.80 with file size ⇒ stream-copy I/O. Queued: surface-original-instead-of-copy fix behind
    privacy/container guards (REMUX_ACCELERATION_INVESTIGATION.md).
