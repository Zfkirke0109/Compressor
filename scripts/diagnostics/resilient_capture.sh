#!/bin/bash
# Self-healing logcat capture for Galaxy Compressor's flaky wireless ADB.
# Keeps trying mDNS + known endpoints; once connected, appends the Compressor* decision
# tags to the baseline file (no -c, so each reconnect also recovers the on-device buffer).
OUT="/c/Users/zachk/Compressor/validation/sf_baseline_20260710_230802/logcat_full.txt"
TAGS="CompressorBatch:V CompressorEncoderPlan:V CompressorLearning:V CompressorVerification:V CompressorCodecCaps:V CompressorDiag:V *:S"
touch "$OUT"
while true; do
  D=$(adb mdns services 2>/dev/null | grep _adb-tls-connect | head -1 | awk '{print $NF}')
  if [ -n "$D" ]; then adb connect "$D" >/dev/null 2>&1; fi
  if [ -z "$D" ]; then
    for ep in 192.168.1.158:43721 192.168.1.127:42623; do
      timeout 6 adb connect "$ep" >/dev/null 2>&1 && D="$ep" && break
    done
  fi
  if [ -n "$D" ] && [ "$(adb -s "$D" get-state 2>/dev/null)" = "device" ]; then
    echo "[capture $(date +%H:%M:%S)] attached to $D" >> "$OUT.log"
    adb -s "$D" logcat -G 5M >/dev/null 2>&1
    adb -s "$D" logcat -v threadtime $TAGS >> "$OUT" 2>>"$OUT.log"
    echo "[capture $(date +%H:%M:%S)] detached" >> "$OUT.log"
  fi
  sleep 4
done
