# Unattended batch diagnostics

`Start-CompressorBatchCapture.ps1` records one complete Compressor batch without an agent watching
the run. The corrected app emits a privacy-safe `CompressorDiag` JSON record for every selected
item and a final `session_summary`; the PowerShell script reconnects wireless ADB as needed, stops
on that summary, and runs `parse_batch_logcat.py` automatically.

## One-click

Double-click **`Start-CompressorCapture.cmd`** (or run it from a terminal). It launches the
supervisor with the right execution policy, prefers PowerShell 7 and falls back to Windows
PowerShell 5.1, and keeps the window open at the end so you can read the result. The normal flow:

```text
1. Enable Wireless Debugging on the S23 Ultra.
2. Double-click Start-CompressorCapture.cmd.
3. Wait until it prints  CAPTURE ARMED.
4. Start the Secure Folder batch in Galaxy Compressor.
5. Close Claude, Codex, ChatGPT and any AI browser tabs.
6. Return later to CAPTURE COMPLETE and the validation folder path.
```

Any `Start-CompressorBatchCapture.ps1` switch below can be appended to the `.cmd` and is forwarded.

## PowerShell directly

From a normal PowerShell window at the repository root:

```powershell
pwsh -NoProfile -File .\scripts\diagnostics\Start-CompressorBatchCapture.ps1
```

Wait for `CAPTURE ARMED`, then use the app normally. Codex/Claude can be
closed while the script runs; capture uses no model tokens. Results are written under:

```text
validation\captures\batch_YYYYMMDD_HHmmss\
  session.logcat.txt
  capture.log
  manifest.json
  jobs.jsonl
  summary.csv
  aggregate.json
```

The default captures only structured, salted diagnostics: no raw source URI or display filename.
Use `-IncludeHumanReadableTags` only when deeper encoder/verifier troubleshooting is needed. The
corrected build redacts those tag records too, but structured-only capture is the smallest report.

Useful options:

```powershell
# Select a device when more than one real TCP device is connected.
pwsh -NoProfile -File .\scripts\diagnostics\Start-CompressorBatchCapture.ps1 -Serial 192.168.1.158:44551

# Recover a batch whose session_start is still present in the on-device log buffer.
pwsh -NoProfile -File .\scripts\diagnostics\Start-CompressorBatchCapture.ps1 -RecoverCurrentSession

# Capture without invoking Python afterward.
pwsh -NoProfile -File .\scripts\diagnostics\Start-CompressorBatchCapture.ps1 -NoParse
```

The script does not clear logcat, kill the ADB server, disconnect other transports, stop the app,
or reinstall anything. `-ClearLogcat` exists only as an explicit opt-in and cannot be combined with
recovery. Offline reconnect attempts are unlimited by default because a Wi-Fi drop does not mean a
long encode failed. Press `Ctrl+C` if the app process is killed and no `session_summary` can arrive;
the raw partial capture remains in its timestamped directory.

`manifest.json` is the completion authority. `complete: true` requires all of: the stop was a real
`session_summary` record, the selected/processed/captured job counts agree, AND (unless `-NoParse`)
the parser exited 0. A parser failure, count mismatch, or idle-inference stop yields
`complete: false` with `partial`, `completionSource`, and `failureReason` explaining why.
Remux/copy/skip/cancel/failure outcomes are recorded but never counted as real compression or byte
savings.

## Local self-tests (device-free)

```powershell
# Parser: privacy whitelist, mixed legacy/structured batches, fail-closed on malformed capture.
python -m unittest scripts.diagnostics.test_parse_batch_logcat -v

# Capture supervisor: clock-independent session selection (dot-sources the script with -LibraryOnly).
pwsh -NoProfile -File .\scripts\diagnostics\test_capture_statemachine.ps1
```
