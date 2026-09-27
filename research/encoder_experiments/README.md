# Encoder operating-point experiments (b177 WP3)

A frozen manifest plus a runner that reads the app's Diagnostics exports. The phone runs the
batches; nothing here encodes, uploads or touches media on the device.

- `pilot_manifest.json`: the frozen production gate, the b177 baseline runs (batch ids, build,
  learned-snapshot hashes), the ten pilot sources, four one-factor arms (A0 baseline, A1 long GOP
  x2, A2 B-frames off, A3 shadow calibration on), a distinct A0_REPEAT repeatability arm, the separate HDR track, and the arms that are
  blocked, each with its reason (complexity and CQ are not reachable through Media3 1.11
  Transformer as used here; alternate encoders wait for the capture's encoder inventory).
- `run_experiment.py`: `validate`, `plan`, `hash`, `ingest`, `table`.

```sh
python3 run_experiment.py validate pilot_manifest.json
python3 run_experiment.py plan pilot_manifest.json           # the run sheet
python3 run_experiment.py hash pilot_manifest.json /path/to/originals --out private-hashes.csv   # stays local
python3 run_experiment.py ingest pilot_manifest.json --arm A0 --capture Everything.zip --batch batch_… --out results/
python3 run_experiment.py ingest pilot_manifest.json --arm A0_REPEAT --capture Everything.zip --batch batch_… --out results/
python3 run_experiment.py table pilot_manifest.json --results results/ --baseline A0 --markdown table.md
```

Savings are counted as the app's summariser counts them: a record that claims a real compression
and a kept output smaller than its source. Remuxes, retained originals, rejected candidates and
avoided copies are zero. Arms are compared on sources present in every arm; nothing is projected
to sources that were not run.

Experimental app settings learn under their own profile-key suffix (`EncoderExperiments.
learningKeySuffix`): an arm never reads or writes the baseline's learned ratios. Record the
`learned_state_snapshot` hash at each arm's start; do not clear app data.

Ingestion requires a completed PL batch with exactly the manifest sources, a unique job record
per source, matching name hash/size, a sampled source fingerprint, the captured production gate,
arm settings, build identity, and starting learned-state snapshot. The table requires every arm,
the same build and exact source fingerprints across arms. It reports batch wall time separately
from summed per-job elapsed time and prints every arm's starting learned snapshot; differing
snapshots confound any claimed arm effect. Each arm result is written once; a
repeated baseline uses `A0_REPEAT` rather than replacing A0. Keep the separate private full-hash
sheet: sampled device fingerprints cannot prove full-file identity. The older b177 captures lack
these fields and remain observational evidence, not validated pilot arms.

Historical b177 PL-A and PL-B exports gave the recorded observational totals
(818,443,848 and 819,538,365 bytes; the only changed outcome is `job_478c2fa19100`). They
predate captured gate and source fingerprints, so strict pilot ingestion rejects them. See
`docs/B177_REVIEW_RESPONSE.md` §4.
