# Remux-path acceleration — investigation (report only; implement AFTER the running validation)

Read-only analysis; no device interaction (the fresh-store validation is running). Per-remux
timing for the CURRENT run is captured automatically via each job's `elapsedMs`; the sub-stage
breakdown below is derived from the authoritative `batch_20260715_141019` capture.

## 1. Measured remux-time breakdown (from batch_20260715_141019)

| Terminal | n | median | p90 | max | total wall |
|---|---:|---:|---:|---:|---:|
| REMUX_PREFERRED_BY_EVIDENCE | 63 | 19.2 s | 41.5 s | 119.4 s | 25.2 min |
| ALREADY_HIGHLY_OPTIMIZED | 54 | 13.8 s | 45.1 s | 151.3 s | 18.0 min |
| UNEXPECTED_REMUX | 2 | 139.1 s | — | 247.2 s | 4.6 min |
| **All remux** | **119** | — | — | — | **47.9 min** |
| (compare: TRANSCODED_SMALLER) | 9 | 30.3 s | — | 185 s | 10.1 min |

**The user is right:** no-savings remuxes consume ~48 min — 4.7× the time of the 9 real
compressions — and a single 1 GB "already optimal" file took **247 s** to save 0 bytes.

## 2. Exact bottleneck (measured + code)
- **Remux time vs source size: Pearson r = 0.80 (n=119).** The dominant cost scales with file
  size ⇒ it is the **full stream-copy I/O**, not fixed overhead.
- **~5.1 s fixed overhead** per remux (smallest-quartile median) ⇒ verification/finalization.
- Code: `BatchCompressorViewModel.remuxOnlyOne` (line 2023) → `Mp4MetadataRemuxer.
  remuxSourceWithoutReencode` copies **every sample** of the source through MediaExtractor→
  MediaMuxer to a cache file, then `OutputVerifier` re-probes that copy (MediaMetadataRetriever +
  frame count) — the ~5 s. This runs for **every** remux, including keep-original decisions
  (`preferRemux == true`, terminals ALREADY_HIGHLY_OPTIMIZED / REMUX_PREFERRED_BY_EVIDENCE).

## 3. Why the copy exists (must be preserved for these cases)
The copy is **not discarded** — line 1140-1141 surfaces it as the item's saveable/shareable
output (`outputUri`/`outputPath`). And `remuxSourceWithoutReencode` applies
`snapshot.filteredForPrivacy(privacyMode)`, so when privacy mode strips location/date the copy is
the **mechanism** that removes metadata. It also normalizes odd containers to app-standard MP4.

## 4. Proposed safe fix (to validate before implementing)
**Surface the original instead of copying it, only when the copy would be a no-op.** For a
keep-original remux terminal, skip the physical copy + its verification and present the source
itself as the output, IFF ALL hold:
1. terminal is a keep-original remux (source already-optimal / remux-preferred) — **not**
   user-selected Remux Only;
2. `privacyMode == PRESERVE_ALL` (nothing to strip — otherwise the copy is required);
3. source container is already app-compatible (opens, standard MP4/MOV) — no normalization needed.

Then: `outputUri = source`, `savedBytes = 0`, message "Already optimal — original kept", and **do
not** write or verify a copy. The original is bit-for-bit safe **by construction** (never opened
for write). Everything else keeps the current full copy + full verification:
- **Remux Only (user-chosen)** → full copy+verify+replace (the remux IS the deliverable).
- **Privacy strip requested** → full copy (removes metadata) + full verify.
- **Container normalization needed** → full remux.
- **Remux fallback after a failed encode** → full verified kept output.

Secondary (bigger, later): even when privacy stripping IS requested, a metadata-only rewrite
(moov/udta boxes) can often avoid copying every sample — flag as a separate investigation.

## 5. Expected user-facing improvement
- Keep-original remuxes of already-optimal files: **~15–247 s → ~1–3 s** (source probe + decision
  only). The 247 s / 1 GB case → ~2 s.
- Total remux wall could drop from ~48 min toward **~5–8 min** (only genuine remux-needed cases
  remain full-copy). Combined with the cooldown fix, an already-optimal library finishes in a
  fraction of today's time.
- No change to what the user receives: already-optimal items still show a saveable result (their
  original), honestly labeled 0 bytes saved.

## 6. Integrity — nothing weakened
Downstream of every decision: PL/HQ/Data-Saver, VMAF thresholds, probe/learning/compression logic,
and remux *decisions* are untouched — only the *materialization* of an already-decided keep-original
outcome changes. Bit-for-bit original integrity is stronger (file never opened for write). Atomic
output safety, cancellation, and all verification requirements for real remuxes are preserved.

## 7. Tests required before implementation
1. Keep-original + PRESERVE_ALL: **no copy written**, original SHA-256 unchanged, output surfaced =
   source URI, savedBytes = 0, terminal unchanged.
2. Privacy strip (location/date): STILL full copy + verify; output metadata stripped; original
   untouched.
3. Remux Only (user-chosen): STILL full copy + verify + replacement path intact.
4. Remux fallback after failed encode: STILL a verified kept output.
5. Non-standard/incompatible container: STILL remuxed (normalized) + verified.
6. Cancellation during the skipped-copy window: clean, no partial/.tmp output, original intact.
7. Verification not weakened: real remux copies still fully verified (res/fps/duration/audio/
   codec/HDR parity).
8. Counts: selected = processed = captured unchanged; ordering stable.
9. UI: keep-original items still expose a saveable/shareable result.
10. No leaked FDs / temp files for the skip path.
11. The `everCompressed` latch fix and cooldown fix regression tests still pass.

## 8. What THIS run adds
The fresh-store capture records per-remux `elapsedMs` (total) + `precedingCooldownMs`. After it
completes I will re-run this breakdown on the new capture to confirm the size-correlation and the
fixed-overhead estimate on a fresh learning store, then implement the fix behind the guards above.
