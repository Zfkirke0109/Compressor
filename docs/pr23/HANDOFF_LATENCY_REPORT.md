# Inter-item handoff latency — measurement, root cause, and fix

## 1. Exact root cause (measured, not the encoder)
Baseline from the authoritative capture `batch_20260715_141019` (measured inter-item gap = wall
time between consecutive job-record emissions minus the next item's own `elapsedMs`):

| Metric | value |
|---|---|
| median inter-item gap | **10,010 ms (~10 s)** |
| p90 / p95 | ~30,016 / ~30,025 ms |
| max | 30,043 ms |
| after SKIPPED items | ~0–3 ms median (already bypass cooldown) |
| total inter-item time | **~29 min of the 95.4-min batch** (item work = 66.5 min) |

The gaps are fixed round numbers (10 s / 30 s / 5 s) — the signature of `delay()`, not
orchestration overhead. Traced to **`BatchCompressorViewModel.kt:1185`**, a post-item thermal
cooldown from `ThermalBatchGovernor.snapshot()` (`baseCooldown` 10 s BALANCED + user cooldown +
thermal/battery extras), applied after **every** non-skip item — **including the 116
REMUX_PREFERRED_BY_EVIDENCE + ALREADY_HIGHLY_OPTIMIZED items that ran no encoder at all.** A
stream-copy/remux generates no encoder heat, so cooling down after it protects nothing.

Other `delay()` sites audited and **preserved** (they protect real invariants): pre-item thermal
pause `delay(30_000)`/`delay(preItemDelayMs)` in `waitForThermalWindow` (only fires when the
device is actually hot/slowed), and a 200 ms UI settle. None were changed.

## 2. Fix (commit — see below)
- `ThermalBatchGovernor.postItemCooldownMs(ranFullEncode, snapshot)` — pure gate returning the
  full governor cooldown when an encode ran, else 0.
- `BatchCompressorViewModel` cooldown block: `ranFullEncode = encodeAttempt != null` (non-null iff
  a full Media3 Transformer encode executed); apply `postItemCooldownMs(...)`. Skips already
  return before this block. **Cooldown after real transcodes/discarded encodes is preserved
  exactly.**
- Telemetry: `precedingCooldownMs` on each job record (the cooldown applied before that item) +
  the existing `totalCooldownMs` summary field — so the next on-device run measures the real
  before/after per item and in aggregate.

## 3. Before vs projected-after (measured baseline → projection)
- Inter-item time removed (after no-encode items): **26.6 min**.
- Inter-item time preserved (after the 11 real encodes): 2.3 min.
- Projected batch wall: **95.4 → ~68.8 min** (item work 66.5 min unchanged).
- **This is a projection from the measured baseline gaps; the authoritative number comes from the
  fresh-store on-device rerun (NEXT_VALIDATION_PLAN.md).**

## 4. Why compression integrity is unchanged
The change is downstream of every decision: the item's terminal, output, verification, learned
state, and record are all finalized before the cooldown block. Gating a `delay()` alters only
wall-clock. For the same input order, learning store, encoder outputs, and device, every
decision-path classification, probe result, verification outcome, learning update, output file,
progress count, and ordering is byte-for-byte identical.

## 5. Confirmed NOT changed
VMAF thresholds/model/config, PL quality bars, probe pass/fail criteria, ladder ratios,
undershoot tolerance, floor recovery, retreat/bisection, remux thresholds, learning confidence,
the `everCompressed` latch exemption (034b23f), audio/metadata preservation, verification
requirements, original-file/atomic-write safety. No safe remux/skip can become an unverified
compression — the cooldown never touched that path.

## 6. Resource & concurrency audit
- **No new concurrency:** items still processed strictly sequentially via `forEachIndexed`; at
  most one full encode pipeline at a time (unchanged). No parallel transcode introduced.
- **No busy-wait:** skipping the cooldown is a plain absence of `delay()` — the coroutine advances
  to the next item; no polling/spin.
- **No new resource acquisition:** the change allocates nothing; no MediaCodec/Extractor/Muxer/
  Transformer/Surface/PFD lifecycle touched. Teardown paths unchanged.
- **Thermal safety intact:** pre-item `waitForThermalWindow` still reads `currentThermalStatus`
  and pauses/slows a genuinely hot device before the next encode; the full cooldown still follows
  every encode. Removing cooldown only after zero-heat operations cannot raise device temperature.
- **Cancellation:** unchanged — the `CancellationException` handlers around the item body still
  run; a shorter/absent cooldown only shortens the window, never starts the next item during it.
