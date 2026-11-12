# Device validation (b169 and b177 review rounds)

What JVM tests and the observational replay cannot establish, and how to establish it on the
S23 Ultra. Nothing here uploads a source video: files are matched locally by name hash, pushed
to the phone over ADB, and deleted from `/data/local/tmp` after use.

## 1. What is already verified off-device

| Claim | Evidence |
|---|---|
| An encode at 100 % is not a finished item; phases, stale callbacks, cancellation, retry handover | `ItemPipelineTest` (JVM, fake stages) |
| The encoder request, the estimate and the plan log are one value | `ResolvedEncodePlanTest` (row 110: 2,197,440 bps, audio copied at 128 kbps, estimate 247,616,391 B) |
| A discarded candidate never carries a success verdict or replacement permission | `FinalAcceptanceTest`; summariser flags the two b169 records |
| Too few frames is not a quality failure and teaches nothing | `CertificationFailureTest` (real `SmartPerceptualProfileEngine`) |
| The retry is offered only after a measured failure with a measured safer rung | `SaferRungRetryTest` |
| b169 totals, all 23 winners, all 29 size gates and every recorded encode request replay through the production Kotlin | `B169ObservationalReplayTest` (partial observational: rounded scores, no frame counts, no initial learned state) |
| Production scoring is v0.6.1 without the phone transform | `DiagnosticEventsTest.productionScoringIsPlainV061WithoutThePhoneTransform` |
| Media3's MP4 parser hands on only the samples a source's edit list presents, and the app's reading of an edit list matches it sample for sample | `Media3ParserParityTest` (Media3 1.11.0's own `Mp4Extractor` run on the JVM over 1,558 edit shapes of a synthetic source) |
| With audio present Media3's muxer moves the first video timestamp to zero and no other, which is the pattern the first timestamp repair recognises and undoes | `Media3MuxerObservationTest` (Media3 1.11.0's own `MuxerWrapper` and default muxer run on the JVM, read back, and held against `MediaTimelineEvidence.compare`) |
| A copied AAC track keeps every packet, its AudioSpecificConfig and its encoder delay through Media3's muxer; its padding follows a rounded edit | `Media3MuxerObservationTest` |

## 2. What only the phone can show

1. A real Media3/MediaCodec export drives the phases as designed (no "100 %" before finalizing).
2. The native scorer certifies a source against itself. The separate frame-parity control checks
   that decoded frames are byte-identical even when raw VMAF is below 100.
3. The source is byte-identical after a discarded candidate.
4. A superseded export's callbacks cannot move a retried row.
5. On a batch: the row text, the indeterminate bar while finalizing/verifying, "Certifying pixels
   k of n windows", MiB labels, the provisional estimate turning into the size-gate number.
6. On a batch: `job_478c2fa19100` and `job_c92a4ca7e1be` recorded with `verdict=Rejected — …`,
   `verified=false`, `replacementSafe=false`, `structuralVerified=true`, `candidateBytes>0`.
7. With the safer-rung retry ON: `job_478c2fa19100` retried at 0.90 and what certification says
   (unknown until run); `job_c92a4ca7e1be` denied with `reason=no_measured_safer_rung`.
8. `learned_state_snapshot` at session start and `learned_state_update` records in order.

## 3. Commands

**Smoke (items 1-4), about 5 minutes:**

```sh
scripts/device/run-device-checks.sh /path/to/short_sdr_clip.mp4   # the normal profile
```

On the S26 (Android 17) the Secure Folder user refuses the shell, so the checks cannot run there
(§8). Give an `ANDROID_USER` only for a profile that lets the shell start tests.

The default command builds and installs the local debug and test APKs for that user. When testing
an Android CI build, install the matched app and test APKs from its `pl-device-controls-ci-<run>`
artifact, then run the checks against that installed pair:

```sh
scripts/device/run-device-checks.sh --installed /path/to/short_sdr_clip.mp4 0
```

The command runs the pipeline, production-evidence, native frame-packing and edit-list timeline
controls. It writes `short_sdr_clip.device-checks.txt` and reads the relevant log entries from the
start of the run. A short clip is required. Any skipped test makes the run incomplete.

**Find the 44 target files locally (names never leave the machine):**

```sh
python3 scripts/device/map_targets.py /path/to/originals --out targets-local.csv
```

It matches `nameHash` from `scripts/device/b169_targets.csv`; check `sizeMatches` before using a
match, since a name hash identifies a name, not content.

**Target batches (items 5-8).** Start `scripts/diagnostics/resilient_capture.sh` (or the Windows
capture task) first, then in the app, in the Secure Folder profile:

1. Keep learned profiles (do not reset). Note the build number in Diagnostics.
2. Smoke subset: `job_d127463b57d6` (row 110), `job_478c2fa19100`, `job_c92a4ca7e1be`,
   `job_458aa0663c3e`, `job_732f7ecfb699`, and one damaged file (`job_364d10590ff5`). Retry OFF.
   Screen-record row 110 through certification.
3. The same subset with Experiment: safer-rung retry ON. B-frames setting as in b169 (on).
4. Then all 44 targets, retry OFF, then retry ON, same order, device on charge, similar starting
   thermal state. Export "Everything" after each.

**Summarise:**

```sh
python3 scripts/diagnostics/parse_session_jsonl.py Compressor-…-Everything.zip --batch <batchId>
```

Look for `contradictory acceptance: 0` on the new build, `cert decisions`, `safer-rung retry`, and
compare the 23 winners' `savedBytes` against b169 per job (`compare_sessions.py`).

## 4. What a result means

- One run per arm is observational. Do not report a speed change from it; learning state, order,
  temperature and charge all move timing.
- A retry that certifies is one measured recovery of that file under this build, not evidence that
  0.90 is safe for its class. A retry that fails is also evidence, recorded per attempt.
- Nothing in these runs validates HDR, perceptual equivalence (see PERCEPTUALLY_LOSSLESS.md §4.3),
  or VMAF v1 as a gate.

## 5. b177 round

What changed off-device, and its evidence, is in `docs/B177_REVIEW_RESPONSE.md`. What only the
phone can show now:

1. **`ProductionEvidenceDeviceTest`** (run by `run-device-checks.sh` with the same clip):
   - certification windows on `zero_track_duration.mp4` and `fragmented_no_duration.mp4`
     (synthetic, in the test APK's assets; made by `scripts/device/make_missing_duration_fixture.py`)
     are spread through the clip, not collapsed on 0. The run log prints what the platform reported
     for each file's duration (`b177-fixture …`);
   - a real Perceptually Lossless batch through `BatchCompressorViewModel` writes `run_identity`, a
     64-hex `sourceFingerprint`, an `attemptLedger` whose length equals `attemptsStarted` with no
     attempt left open, `audioRequested`, `encode`/`verify` (and, when probed, `probe_rung`) stage
     events with `attemptIndex`, learned-state updates that name their job, and leaves the source's
     SHA-256 unchanged with replace-original off.
2. **The pilot**: `python3 research/encoder_experiments/run_experiment.py plan
   research/encoder_experiments/pilot_manifest.json`. Arms in order A0, A1, A2, A3, A0_REPEAT,
   each in Exhaustive mode with replace-original off; export after each; `ingest` and `table`.
   The step-by-step runbook, with thermal notes and what each measure means, is
   `docs/PREINSTALL_REVIEW_RESPONSE.md` §5.
3. **Timing**: a Perfetto trace with CPU frequency, idle states and scheduling, plus a screen
   recording, on `job_458aa0663c3e` in the first A0. New builds log `timing[...]` per window
   (queue waits, v0, flush, CAMBI, v1, decoder and process CPU).
4. **Encoder inventory**: the `run_identity` record lists every video encoder's advertised
   bitrate modes and complexity and quality ranges. CQ and complexity arms stay blocked until it
   shows support and a CQ quality search exists.
5. Settings to confirm before each arm (Settings, Experiments): safer-rung retry, B-frames, longer
   keyframe interval, VMAF v1 shadow calibration, and (on for every arm when no computer can hash
   the originals) Pilot: full source hash. The `run_identity` record repeats them.

## 6. The fallback after a rejected first encode (v1.6.287 round)

The Secure Folder run of v1.6.287 selected one marginal trial (`job_1e25c8805630`: 0.75 only
cleared the bar, 0.80 passed with the selection margin) and its 0.75 encode certified. The encode
that follows a rejected trial, at the safer measured ratio, has therefore never run on a device.
A real first-encode failure cannot be produced on demand, so `SaferRungFallbackDeviceTest` refuses
the first candidate with a test-only `CandidateVeto` and lets production do everything after it:

```sh
scripts/device/run-device-checks.sh --fallback /path/to/disposable_copy.mp4
scripts/device/run-device-checks.sh --installed --fallback /path/to/disposable_copy.mp4
```

It runs three real Perceptually Lossless batches over a copy of the clip in the app's cache and
writes `disposable_copy.fallback-checks.txt`:

1. the first encode is refused, the retry is decided by `SaferRungRetry`, and the second encode is
   verified, certified and accepted or kept as the original by the unchanged rules. The test does
   not decide that verdict: it checks that an acceptance rests on every planned window with at
   least 12 compared frames and mean 95.5 / p5 91 / min 84, and that either way the attempt ledger
   has two closed attempts and no refused candidate is left in the cache;
2. control: with the retry experiment off, a refusal keeps the original after one encode;
3. control: a second refusal keeps the original and never starts a third encode.

What to know before running it:

- **The clip decides whether anything is tested.** Items 1 and 3 need a clip whose ladder selects
  a marginal trial on that device with its current learned state. Otherwise they are skipped and
  the script exits 2 (incomplete), never 0. The run log prints the rungs and verdicts it saw
  (`b287-fallback …`).
- It budgets two full encodes of the clip per batch. Use a disposable copy; replace-original must
  be off and is asserted off; the copy's SHA-256 is compared before and after every batch.
- It turns the opt-in safer-rung retry switch on (off for the control) and restores it afterwards.
- Starting a batch clears the app's cached outputs, as it does in the app: run it when no results
  are waiting to be saved.
- The real second encode's result reaches the learned profile exactly as it would in the app. A
  refused attempt writes nothing there, and its records say what it was: `run_identity` carries
  `batchSettings.testCandidateVeto`, the attempt's `certify` stage has reason `cert_test_rejected`
  and its ledger outcome is `test_rejected`. `parse_session_jsonl.py` opens such a session's
  report with `!! TEST CAPTURE`. Do not pool these sessions with field captures.

The veto cannot make a candidate pass and the app never installs one: the app's view model is
built with the veto that refuses nothing, `CandidateVetoTest` fails the unit tests if the main
source set gains another, and the CI build reads the packaged app's DEX for a second
implementation (`scripts/ci/verify_no_test_veto.py`).

Not covered by this check: the retry after a candidate fails structural verification (the other
trigger of the same fallback), and the learning write a real measured failure makes. Both remain
covered by JVM tests only.

### Where 168 samples became 166

The regular-profile run of v1.6.287 rejected one 4K candidate (`job_782a717d3217`) because its
source held 168 video samples and the output 166, with the source's first video timestamp at
97,488 µs. The capture has one count, taken after the metadata remux, and the original is no
longer available. Media3 1.11.0's MP4 parser applies the edit list and leaves out samples that
start after an edit ends, while `MediaExtractor` returns the whole sample table, so a source whose
media runs two frames past its presentation edit would give those numbers with no stage losing a
frame.

The parser's half of that is observed, on the build machine. `Media3ParserParityTest` runs
Media3 1.11.0's own `Mp4Extractor`, the class the app ships, over the synthetic source built to
that shape. It hands on 166 of the 168 samples, the first at 97,488 µs; with edit lists ignored
the same extractor returns all 168; and the control file, whose samples are all inside the edit,
is read as 168. The count therefore changes at Media3's read of such a source, before a decoder,
an encoder, a muxer or the remux has run. The same test holds the app's own reading of an edit
list (`Mp4EditTimeline`, which names the stage in a capture) against Media3's, sample for sample
and to the microsecond, over 1,558 edit shapes: an edit ending and starting one unit before, at
and after every sample, with and without a sync table, single and repeated edits. That found one
difference, now fixed: a sample kept from before an edit's start was timed one microsecond early
(Media3 rounds toward zero; the reading floored). No count differed.

The 97,488 µs is observed there too. `Media3MuxerObservationTest` feeds the samples the parser
hands on into Media3's own `MuxerWrapper` and default muxer, the last stage of every Transformer
export, with no codec in between. With an audio track present the wrapper moves the first video
sample's timestamp to zero and logs, with the number,

    MuxerWrapper: Shifting first video timestamp from 97488 to zero.

No other frame moves by more than a microsecond, no frame is added or dropped (166 in, 166 out;
168 in, 168 out for the control), and the video track is written without an edit list, so nothing
in the file compensates. That is exactly the pattern `MediaTimelineEvidence.compare` looks for
before it proposes the first timestamp repair, and the test holds the comparator against what
Media3 really writes: it proposes restoring 97,488 µs, and with only that timestamp put back the
two timelines match. Written without an audio track, the same muxer moves the whole video
timeline to start at zero, which the comparator counts as the same timeline: the reason the
repair applies only to a source with audio. The app's log export now collects the `MuxerWrapper`
tag, so a capture carries Media3's own line.

So for a source built like the synthetic one, both numbers the capture recorded are Media3 doing
what it is written to do, in this order: its parser leaves out the two samples outside the
presentation, and its muxer zeroes the first video timestamp. No stage drops a frame it was
handed.

A host run leaves three things open. The phone has since answered two of them (§8):
`MediaExtractor` lists all 168 samples of such a source, and a real export on the S26, with its
decoder and encoder, carries 166 and starts its video at zero, which the app's remux then copies
sample for sample. The third is still unknown: whether the job's original is built like the
synthetic file, since the original is not available.
So no acceptance rule and no pipeline stage was changed. The verifier
still compares every sample of the source's table, and such a source is still kept as the
original. Whether a candidate that holds every sample the source *presents* should be accepted is
a decision about what a complete timeline means, not a defect to patch: the two extra samples are
outside the file's own presentation, yet they are in the sample table the platform extractor
lists, so a player built on that listing may show them.

`EditListTimelineDeviceTest` is the device observation. It runs with the default device checks,
needs no clip, and takes two synthetic sources from the test APK
(`scripts/device/make_edit_list_timeline_fixture.py`: 168 samples with 166 inside the edit, and
the same streams with all 168 inside it) through the platform extractor, a real Media3 export and
the app's metadata remux. The run log prints what
each stage counted (`b287-timeline …`) and the stage `TimelineLocalization` names from them. If
it fails, the device does not behave as the reading says and the message carries the counts; that
is the evidence to act on. From this build a batch also records these counts itself whenever a
candidate's sample count differs from its source's (`timelineLocalization` on the `verify` stage).

The same run prints, without asserting anything, whether the first timestamp repair's pattern is
seen in Media3's file on the phone and whether the repair's own remux then gives back the
source's timeline (`b287-timeline … firstTimestampRepair …`). On the S26 it is seen, and the
repair gives the source's timeline back (§8).

### The earlier AAC "decoder configuration differs"

A separate case, and not explained by the two findings above. b213 `job_6d1173f4c0f9` kept its
original on its copied audio. What that capture recorded: the video timelines matched frame for
frame (438 and 438, both starting at zero); 342 audio packets were compared; the reason given
was "decoder configuration differs"; the two files' payload digests differed as well; the first
packet already mismatched; and the largest audio-against-video difference was 21,334 µs. That
build recorded neither side's packet count nor which configuration field differed, and the
original is not available, so the cause is not proven. What can be said:

- Both video timelines start at zero, so Media3's move of the first video timestamp has no part
  in it.
- 21,334 µs is one AAC frame at 48 kHz (1,024 samples is 21,333.3 µs). Payloads that differ from
  the first packet on, a one-frame timing difference and a different configuration are together
  what one packet more or fewer at the start of a track looks like when the two streams are read
  in step. From this round a mismatch records which: both packet counts, both first timestamps,
  what the first differing packet differed in, whether one stream is the other shifted by one
  packet, and both sides' configuration values (the hash covers the codec, channels, sample
  rate, the configuration bytes, and the encoder delay and padding the platform reports).
- One AAC frame is also exactly what Media3 moves. On the build machine
  (`Media3MuxerObservationTest`), an AAC track that reaches Media3's muxer wrapper with a
  1,024-sample encoder delay, as FFmpeg writes one, has every timestamp handed to the muxer
  21,333 µs early, the first of them negative, and the muxer writes an edit that starts after the
  delay. Read back by Media3 nothing is lost: every packet, the AudioSpecificConfig and the
  delay are unchanged, 329 packets in and 329 out. The edit is rewritten on the muxer's movie
  timescale of 1/10,000 s, so a track whose presented length is not a whole number of those
  units gains end padding (2 samples for the 329-packet synthetic track, none for the 376-packet
  one). A track without an encoder delay goes through untouched and gets no edit.

So a copied AAC track that carries an encoder delay is a place where a source and its copy can
come to differ in what the capture recorded, depending on how the platform extractor applies
each file's edit: whether it drops the delay's packet, moves the timestamps, and what delay and
padding it reports. None of that runs off a phone, and whether the b213 source carried such a
delay is not known. The device run therefore prints the app's audio comparison of the synthetic
sources, which do carry one, for Media3's file, for the first timestamp repair and for the
remuxed file (`b287-timeline … audio …`), with both sides' values when they differ. Nothing was
relaxed. If the phone shows a copy that differs only in how its delay is written, whether that
should pass is a decision about what identical audio means, to be taken on that observation. §8
has what the S26 showed.

## 7. What the alternate search and the 4K pipeline cost (v1.6.287 captures)

No default was changed on these numbers. They are what the two v1.6.287 device runs recorded,
read with `audit_pl_capture.py` (`summary.json`: `alternateSearch`, `stageTime`); each source ran
once, so they describe the policy on those sources and are not a controlled comparison.

```sh
python3 scripts/diagnostics/audit_pl_capture.py EXPORT.zip --batch batch_<id> --out audit/
```

**Alternate AVC search** (Secure Folder run, 30 jobs). It runs only after the HEVC ladder found no
smaller measured plan, on the same source and planned windows, so its time is added to the item
and its yield is a plan the primary did not give:

| | |
| --- | --- |
| jobs where it ran | 21 of 30 |
| AVC rungs measured | 63, all failed |
| rung time | 194.9 s, against 169.9 s for the HEVC ladders of the same jobs |
| share of those 21 items' time | 53.1 % |
| share of the whole batch's item time | 12.5 % |
| ladders with a passed or marginal rung | 0 |
| plans taken from it | 0 (all 21 jobs kept the original) |
| highest AVC rung within 1.0 / 2.0 points of every gate | 4 / 6 of 21 |
| HEVC ladder stopped below the ratio AVC reached | 16 of 21 (HEVC 0.95, AVC 0.97) |

Across every Perceptually Lossless batch retained in the two exports (builds b213 to b287, 14
batches with an alternate ladder, many of them repeats of one 13-file set) it ran 119 times over
357 rungs and 1,100.9 s and never produced a passed or marginal rung. The regular-profile run of
7 jobs never needed it.

Before changing its default, a matched device run should answer what these captures cannot: in 16
of the 21 jobs the HEVC ladder stopped at 0.95 and never measured 0.97, so the two codecs were not
compared at the same top ratio; four AVC ladders missed by less than a point; and every run here
is one phone (SM-S948U). The S23's encoders are a separate question.

**Stage time by resolution** (jobs that made a full encode):

| | 4K, regular profile | 1080p, Secure Folder |
| --- | --- | --- |
| jobs / media | 5 / 69.2 s | 7 / 8,893 s |
| probe ladder | 192.1 s | 174.8 s |
| full encode | 38.6 s (0.56 s per media second) | 870.1 s (0.10 s per media second) |
| verification | 3.2 s | 62.8 s |
| certification | 121.1 s | 41.1 s |
| whole item | 359.1 s (5.19 s per media second) | 1,149.5 s (0.13 s per media second) |
| scoring (probe + certification) share | 87.2 % | 18.8 % |
| one scored window, mean wall time | 11.3 s probe, 11.9 s certification | 2.0 s probe, 1.8 s certification |

On these short 4K clips the pipeline's time is pixel scoring, not encoding: a 4K window costs
about six times a 1080p one and each clip is scored on every ladder rung and again at
certification. What inside a 4K window takes the time (decode, frame preparation, libvmaf) is not
separable from these records; the Perfetto trace in §5.3 is the measurement for that. The 1080p
certification windows include the opt-in VMAF v1 shadow (about 0.8 s of each 1.8 s); 4K windows do
not run it.

## 8. v1.6.299 on the S26, normal profile (November 12)

The matched v1.6.299 app and test APKs from Android CI #299 were installed over ADB on the
SM-S948U (Android 17), and the default checks were run with `--installed` in the normal profile.
All 12 tests passed, in 166 s: the four pipeline tests, the three production-evidence tests, the
two frame-packing tests and the three edit-list timeline tests. The fallback check (§6) was not
part of that run.

The Secure Folder could not be used. Asked to start the tests for user 150, the phone answered
`SecurityException: Shell does not have permission to access user 150` and nothing ran. The
script stopped there without a verdict; it now reports such a run as incomplete and says why.
Behaviour in the Secure Folder is checked by using the app there and exporting its log.

The real Perceptually Lossless batch that the production-evidence test runs over the supplied clip
kept the original, measured below the PL quality gate, so that clip made no full encode.

**What the phone counted** (`b287-timeline`), Media3 re-encoding the video to HEVC and copying
the audio:

| | `edit_tail_168.mp4` | `edit_whole_168.mp4` |
|---|---|---|
| Platform extractor, source | 168 samples, first at 97,488 µs | 168, first at 97,488 µs |
| Presented by the source's edit list | 166 (2 after the edit ends) | 168 |
| Reported by Media3's export | 166 frames | 168 |
| Media3's file | 166 samples, first at 0 | 168, first at 0 |
| App's remux, read / written | 166 / 166 | 168 / 168 |
| Final file | 166, first at 0 | 168, first at 0 |
| Stage named | `source_edit_list` | `none` |

As on the build machine, the count changes where Media3 reads what the source's edit list
presents, and Media3's file starts its video at zero. The platform extractor, which the verifier
compares against, lists all 168 samples of the source. No stage after Media3's read loses a frame.
This is the synthetic source: the job's original has not been retested.

**The first timestamp repair.** For the control file the comparator proposed restoring 97,488 µs.
After the repair's remux the timelines matched: 168 samples, the first at 97,500 µs (12 µs from
the source, inside the 1,000 µs tolerance), and the audio compared identical, at most 12 µs apart.
For the tail file no repair was proposed (168 → 166), as intended.

**The copied AAC track.** For the tail file, Media3's file differs from the source in one decoder
configuration field: the platform reads its encoder padding as 2 samples where the source has 0.
The AudioSpecificConfig bytes (`119056e500`), the 1,024-sample delay, the packet count (329), the
first timestamp (−21,333 µs) and the first packet's payload are the same. The comparison stops at
the first difference, so the line does not show the later packets; on the build machine every
packet was kept. The app reports this as "decoder configuration differs". After the app's
metadata remux the padding reads 0 again and the configuration matches. The control file's track
(376 packets) reads padding 0 throughout. These are the paddings the build machine predicted (§6).

- So a copied AAC track can fail the audio check as "decoder configuration differs" with nothing
  changed but the end padding Media3's muxer writes, when the app does not remux the file
  afterwards.
- That is not shown to be the cause of b213's case (`job_6d1173f4c0f9`) or its November 9 repeat,
  which reported this reason after a Media3 transmux with no metadata remux. In b213 the payload
  digests also differed and the audio was one AAC frame (21,334 µs) out against the video, which a
  padding difference alone does not produce.
- Nothing was relaxed. Whether a copy that differs from its source only in the padding written
  for it may pass is a decision about what identical audio means.
