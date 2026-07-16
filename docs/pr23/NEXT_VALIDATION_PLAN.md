# Next controlled validation plan

Goal: measure the **known-winnable latch exemption** on a **fresh learning store** so the result
is not confounded by cross-run persistence, and confirm the 26 previously-denied probes now run.

## 1. Build & install the exemption APK
- Merge/keep the latch-exemption commit on `fix/downloaded-video-pl-diagnostics`; PR #23 CI builds
  the signed artifact.
- `gh run download <run> --dir <tmp>` then `adb -s 192.168.1.67:<port> install -r <apk>`.
- Confirm: `adb ... shell am broadcast -a io.github.zfkirke0109.galaxycompressor.VMAF_SELFTEST -n
  io.github.zfkirke0109.galaxycompressor/compress.joshattic.us.quality.VmafSelfTestReceiver`
  → expect `VMAF_SELFTEST: PASS`.

## 2. Reset ONLY the learning store (not all app data — preserves grants/Secure Folder)
```
adb -s 192.168.1.67:<port> shell "run-as io.github.zfkirke0109.galaxycompressor \
  rm -f shared_prefs/smart_perceptual_profiles_v3.xml"
adb -s 192.168.1.67:<port> shell am force-stop io.github.zfkirke0109.galaxycompressor
```
This gives first-encounter probing behavior. (If `run-as` is blocked, note it and accept the
warmed-store confound, documenting it.)

## 3. Arm capture (PowerShell, one line — resolve the current port from `adb mdns services` first)
```
pwsh -NoProfile -File <repo>\scripts\diagnostics\Start-CompressorBatchCapture.ps1 -Serial 192.168.1.67:<port> -Environment Normal -SessionDetectionTimeoutSeconds 0 -ClearLogcat
```

## 4. In-app procedure
- Open the app in the **Normal profile** (not Secure Folder).
- Tap **Select videos with write access** → select the **same 172-file set** (Download + Camera +
  Movies) so results are matched against `batch_20260715_141019`.
- Leave **Video mode = Perceptually Lossless** (default). Start.

## 5. Device state
- Battery ≥ 50%, on charger preferred (66 min run). Let it run uninterrupted; capture survives app
  backgrounding.

## 6. Required telemetry (already emitted by this build)
probedRatios, pixelProvenRatio, probeDetail, probeWindowScores, certWindowScores, thermalStart/End,
plannedTargetRatio, discardedVideoBitrate, terminal, savedBytes.

## 6b. Handoff-timing telemetry (added by the throughput patch)
Each job record now carries `precedingCooldownMs` (the thermal cooldown applied before that item);
the session summary carries `totalCooldownMs`. After the run, measure BOTH dimensions separately:
- **Algorithm result:** compressions / bytes saved / terminals / per-file transitions (as before).
- **Handoff result:** median/p90/max inter-item gap (from job `timestampMs` deltas minus
  `elapsedMs`), `totalCooldownMs` (expect a large drop vs 141019), and confirm
  `precedingCooldownMs == 0` on every record that follows a REMUX_PREFERRED_BY_EVIDENCE or
  ALREADY_HIGHLY_OPTIMIZED item, while remaining full after TRANSCODED_SMALLER / UNEXPECTED_REMUX.
Do not conflate the two: a shorter batch is a throughput result, not a compression result.

## 7. Completion criteria
- session_summary present, complete=true, 172/172.
- **Primary check:** in the three known-winnable classes (`video/avc|720p|24|sdr|lt10m`,
  `video/avc|720p|30|sdr|lt10m`, `video/avc|1080p|30|sdr|25-50m`) **no job shows
  `probeDetail="probes skipped…"` after the first verified compression in that class.**
- Compare accepted-compression count and bytes saved vs `batch_20260715_141019` (expect ≥, driven
  by the recovered probes, with 0 regressions and no drop in certification scores).
- Re-run `pr23_forensic.py` against the new capture for the matched diff.
