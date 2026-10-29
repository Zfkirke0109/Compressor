# v1.6.271 startup/reset follow-up (2026-10-29)

Baseline: `5c2341bcbd173ea74ba4a4b5cb47fbb0c019392c`, PR #44.

## Evidence boundary

The requested Secure Folder capture is `Compressor-v1.6.271-20261029-161040-Everything.7z` (603,864,807 bytes). Google Drive metadata confirmed the file. The connector rejected it at its 268,435,456-byte download limit. A browser download completed, but the resulting file was unavailable through the documented shared-files path. **No sessions, settings, errors, timings, winners or scores from this archive have been inspected.** The findings below are verified against the code and PR review; they are not findings reconstructed from the new capture.

## Confirmed fixes

1. **Startup cancellation skipped cleanup.** Hashing the scoring libraries and enumerating encoder capabilities suspended on the IO dispatcher before the batch's try/finally. Cancellation there left the UI busy, the decision log attached and the session without a terminal record. That setup now runs inside the protected try/finally, with the same cancelled-session and foreground/wake-lock teardown as encoding.
2. **The reset button bypassed the active learning ledger.** It created a separate SharedPreferences-only engine, clearing aggregates but retaining in-memory and persisted evidence reservations. Reset now goes through the ViewModel's active evidence-aware engine on the IO dispatcher. Starting a batch is rejected while reset is pending, and reset is rejected while a batch is pending/running. The UI reports the actual result and refreshes its fingerprint after completion.
3. **Reset persistence failures were reported as success.** The ledger now durably writes RESET_STARTED, clears aggregate storage with a checked SharedPreferences commit, and writes RESET only on completion. An incomplete reset pauses aggregate learning on restart and appears as uncertain in offline replay. This preserves the audit history and avoids silently applying old evidence again after an interrupted reset. No reset is performed automatically.

These changes improve cancellation and repeatability of experiments. Compression gains and comparative speed for the supplied runs remain unmeasured. Existing PL thresholds, codec search, audio/timeline/color checks and smaller-file requirements are unchanged.

## Log-only export

The diagnostics panel now offers **Save log-only ZIP to Downloads** alongside the full archive. It uses the selected run scope, preserves the original session and decision logs plus the same crash/device/profile/learning records, and does not enumerate or read retained video and per-frame evidence directories. Its filename ends in `-LogsOnly.zip`; the manifest declares `includesEvidenceFiles=false`. The existing full export remains available. Exporting does not delete or alter the retained evidence.

## Verification

- Regression coverage: explicit reset permits the same measurement to train again after restart; duplicate protection resumes after retraining; a journal failure preserves profiles and reports failure; an interrupted aggregate reset pauses further learning after restart; offline replay distinguishes incomplete, failed-journal and completed resets. Log-only export tests verify scope/filename/manifest and evidence exclusion without altering retained files.
- Validate the Android build, JVM tests, instrumentation compile, 16 KB native packaging and DEX budget through Android CI on the updated PR head. The PR description carries the final run links and results.
- Device check still needed: cancel during initialization, then start another batch; explicitly confirm reset while idle, repeat a small fixed source set, and verify new training is not rejected as pre-reset duplication. Do not reset learning merely to compare toggle settings without first preserving its exported state.

## Needed to finish the capture analysis

A log-only archive below 256 MiB is sufficient for the first pass. Preserve the existing directory structure and include:

- `manifest.json`
- `runs/*/session.jsonl`
- `runs/*/decisions.log` (including self-check runs)
- `device/logcat.txt` and `device/learned_profiles.json`
- `crashes/`

Retained encoded candidates and `frames/` traces can be supplied separately if a specific quality/parity investigation needs them. For the original `.7z`, extracting these paths and recompressing them as a new ZIP keeps the original capture intact. Alternatively, provide consecutive archive volumes under 200 MB each.

With the updated APK, the easier route is to open the same Secure Folder installation, select **Diagnostics → Everything → Save log-only ZIP to Downloads**, and provide that ZIP. Installing an update preserves the prior recorded runs; there is no need to rerun the videos to export them.

Once those logs are available, reconstruct each batch independently, identify its recorded toggle values and source identities, compare matched sources, classify measured failures separately from unavailable evidence, and trace the actual time spent in decode, scoring, encoding, verification, hashing and cooldown. No setting is credited with a speed or yield change before that comparison.
