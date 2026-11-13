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
  (`b287-fallback …`), skipped or not.
- **Only the full probe ladder can select one,** so the clip must be at most 1920 × 1088 pixels.
  A larger source gets the short ladder, which does not bisect after a pass: when its first rung
  passes there is no marginal rung below a passing one to try.
- It budgets two full encodes of the clip per batch. Use a disposable copy; replace-original must
  be off and is asserted off; the copy's SHA-256 is compared before and after every batch.
- It turns the opt-in safer-rung retry switch on (off for the control) and restores it afterwards.
  The user's value is recorded on disk before the first change (`TestOwnedSettings`), and the
  scripts run `TestOwnedSettingsDeviceTest` on its own after the test, so the switch is put back
  even when the test's process dies first (an ADB drop ends `am instrument`, and the process).
- Starting a batch clears the app's cached outputs, as it does in the app: run it when no results
  are waiting to be saved.
- The real second encode's result is learned exactly as the app would learn it, into a store of
  the test's own (`HarnessScope`, since `1d9881f`): the batch plans from the user's learned state
  and never writes the user's profile. The runs recorded in sections 8 to 10 predate that, and
  their accepted attempts taught the user's profile. A
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
original.

**Decided (November 12, the owner's call delegated): a candidate that holds every sample the
source presents does not pass while the source's table holds more.** A Perceptually Lossless
output has to hold every video sample the platform's reader lists for its source. The reasons, in
the order they weigh:

- On the S26 the platform extractor lists all 168 samples (§8). That reader is the one the
  verifier compares with and the one the app decodes a source with when it scores, and Android's
  own player reads through the same extractor. So a player on this phone can show the two
  samples, and to it a candidate without them is two frames shorter. Which players do show them
  was not tested.
- What a file presents depends on the reader: Media3 applies the edit list, and the platform does
  not trim by it. What a file holds does not. The check should not turn on which player is used.
- Frames a candidate does not hold were compared by nothing. Passing it would claim transparency
  for content no measurement looked at.
- With Replace Originals on, the two samples would be gone for good.
- The captured job's cause is still unproven, and it is one file in the captured batches. Keeping
  its original costs that file's saving and nothing else.

If such sources turn out to matter, the way to compress them is to carry the samples: hand the
encoder every sample the table holds and write the source's edit list back, so that the candidate
passes this same check. That is pipeline work for after an exact-source retest shows a real file
is built this way. It is not a change to the verifier.
`ScientificEvidenceSafetyTest` holds the rule at the verifier, with the candidate as Media3 writes
it and with its first timestamp put back.

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
repair gives the source's timeline back (§8). It prints the same for the audio end padding repair
(`b287-timeline … endPaddingRepair …`, §8): whether a copied track differs from its source only
in its end padding, and what the repair's rewrite then gives.

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
relaxed. §8 has what the S26 showed, and the rule then decided on it.

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
- Nothing was relaxed, and the rule below keeps it so.

**Decided (November 12, the owner's call delegated): a copy that differs from its source only in
end padding does not pass as it is. It is repaired instead.** Identical audio keeps meaning the
same packets under the configuration the platform reads, the padding included, because a decoder
that honours the padding plays such a copy that many samples short. Two samples are 42 µs at
48 kHz, which nobody hears, but a tolerance would need a bound and a reason for the bound, and the
repair needs neither: the phone showed that the platform's muxer writes the same track with the
source's padding. So:

- When a candidate's video timeline matches and its audio differs from the source's in nothing but
  the end padding, the batch rewrites the file once with the platform's muxer, the rewrite the
  first timestamp repair already makes, and judges the rewritten file by the unchanged rules. If
  it still differs, the original is kept, as before.
- The first timestamp repair, which required identical audio before it started, also starts from
  a copy that differs only in end padding: the one rewrite puts both right.
- A padding difference is a starting point only on Media3's own file. When the platform's muxer
  has already written the candidate (a source with a location, whose metadata remux ran), its
  padding is what that muxer writes, and writing it again changes nothing: such a candidate is
  judged as it is. The first timestamp repair still runs on such a file when its audio is
  identical, as it always did.
- The record says what was done: a `finalize` stage with operation `restore_audio_end_padding`,
  or `restoredAudioEndPadding` on the first timestamp repair's, each with the padding before the
  rewrite and the source's. A copy the verifier still refuses reads "end padding differs (source
  N, output M samples)" and carries `differsOnlyInEndPadding`.

What holds this, and where. On the build machine, `Media3MuxerObservationTest` runs Media3's own
muxer and the app's comparison calls its copy of the tail file's track different in the end
padding and nothing else, and its copy of the control's track identical. `AudioEndPaddingTest`
holds what counts as only the end padding and when a rewrite is tried, and
`ScientificEvidenceSafetyTest` that the verifier still refuses such a copy. **The repair itself
had not run on a phone** at this build. The S26 run above shows the padding restored by the
metadata remux, which is the same muxer; the default device checks now print what the repair's own
rewrite gives (`b287-timeline … endPaddingRepair …`), and on v1.6.311 it gave identical audio (§9).

**The fallback check** (`--fallback`, the same phone and build, a clip from the normal profile).
All three batches made a real first full encode (encoded, finalized and structurally verified)
and the test refused it; in each, the refusal was recorded as `test_rejected`, nothing
was learned from it, every attempt was closed and the source was unchanged. Control 2 passed: with
the retry experiment off, production kept the original after that one encode, left no candidate
in the cache and started no second attempt. Items 1 and 3 were skipped: the clip's ladder measured
one rung, 0.75, and it passed, so there was no marginal trial and no measured safer rung, and no
retry was recorded after the refusal. One passing rung is what the short ladder gives a source above
1920 × 1088; the report did not say the clip's size, because the script dropped the tests' printed
lines from a run it judged incomplete. It now keeps them for every outcome. The encode that follows
a refused trial at the safer measured ratio has still not run on a device under the test; §9 has
production making it twice after a real certification failure, and §10 the test passing.

## 9. v1.6.311 on the S26, normal profile (November 12)

The matched v1.6.311 app and test APKs from Android CI #311 (`e284cb5`) were installed over ADB on
the SM-S948U (Android 17) as updates, in the normal profile.

**Default checks.** All 12 tests passed, in 72 s. The real Perceptually Lossless batch that the
production-evidence test runs over the supplied clip, a 4K one this time, accepted a smaller
pixel-certified output on its first attempt, at 0.75, with the audio a bit-identical copy of the
source's (342 packets compared). The edit-list timeline test counted what it counted on v1.6.299
(§8) at every stage, and the first timestamp repair again gave back the control file's timeline
(first sample at 97,500 µs against the source's 97,488 µs) with identical audio.

The same test made the audio end padding repair's rewrite on a phone for the first time. With the
video's own start left out of the comparison, Media3's copy of the tail file's audio differed from
the source's in nothing but the end padding the platform reads, 2 samples against 0, so the repair
was proposed; after the rewrite through the platform's muxer, made as a batch makes it, the same
comparison found the audio identical (329 packets, 0 µs apart). The control file's copy was
identical to begin with, and no rewrite was proposed. In a batch the tail file still keeps its
original, because its video lost two samples (168 → 166). This was the synthetic source inside a
test; no batch has needed the rewrite yet (below).

**The fallback check, run a second time,** on a 25.5 s clip of 0.7 Mbit/s. In all three batches
every rung failed on both ladders (HEVC 0.90, 0.95 and 0.97; AVC 0.85, 0.95 and 0.97), so no batch
reached a full encode, there was nothing to refuse, and all three tests were skipped (`attempt 1
never reached certification (terminal SKIPPED_WOULD_DEGRADE): nothing to refuse`). The encode at
the safer measured ratio after a refused trial had still not run under the test; it has since (§10).

**A batch over the phone's videos.** The owner then ran v1.6.311 in Perceptually Lossless over the
phone's videos, in the normal profile with Replace Originals off: 58 items from 54 distinct
sources, in 35.5 minutes. Read with the audit tool (§7):

| Outcome | Items |
|---|---|
| Smaller pixel-certified output, accepted | 13, saving 673,227,786 bytes (4.7 % of the 14.19 GB read) |
| Original kept: every measured rung missed the bar | 35 (the mean was the limiting gate in 27, p5 in 8) |
| Original kept as already highly optimized | 5 |
| Original kept: the only output was a copy that bought nothing (`UNEXPECTED_REMUX`) | 5, each 2 s long |

Every original's SHA-256 was unchanged after the run (58 of 58), and no replacement was requested
(13 of 13 `replace_not_requested`).

- **The retry at the safer measured ratio, in production, twice.** `job_89baf1b00ad3` and
  `job_1ecc4136ae36`, 15 and 18 minutes long, each selected the 0.77 marginal trial under a
  passing 0.95. Each full encode at 0.77 passed structural verification and then failed
  certification on its measured scores (`cert_measured_failure`); the retry was allowed, and the
  full encode at 0.95 passed verification and certification on its own and was accepted, saving
  25,811,833 and 29,292,688 bytes. Each ledger holds two attempts, 0.77 `measured_below_bar` and
  0.95 `accepted`. This is the path `SaferRungFallbackDeviceTest` drives with a refusal, taken
  here after a real certification failure. Whether the first candidates' files were removed is not
  in the record. Four more items certified the marginal trial they selected on the first attempt:
  `job_a26e9d113c47` at 0.75 under 0.80, `job_1c02059b9ca2` at 0.80 under 0.85, and
  `job_2142a16a7282` and `job_b0edd46ad770` at 0.77 under 0.95.
- **The first timestamp repair, on a real file.** For `job_7ecd37d63ba4`, a 17.5 s 4K source, the
  repair restored the first video timestamp by 3,600 µs (`restore_first_video_timestamp`, no
  padding restored), and the repaired file passed verification and certification and was accepted.
- **The end padding repair had nothing to do.** Three items, each a 2 s clip, failed the audio
  check with "end padding differs" and nothing else (`differsOnlyInEndPadding`), but their
  candidates were 3.1, 3.1 and 6.0 times the size of their sources. The repair rewrites only a
  candidate smaller than its source, so it was rightly not tried, and the originals were kept.
- **What the AVC search cost.** It ran on 34 of the 58 items, all 34 then kept as
  `SKIPPED_WOULD_DEGRADE`, measured 102 rungs and found none usable: all 102 failed, with a median
  worst margin of −4.5 points, 1 ladder within half a point of every gate and 7 within one. It took
  148.6 s, 8.0 % of the batch's item time and 55.5 % of those 34 items' time; in 27 of them the
  HEVC ladder had stopped below the ratio the AVC ladder reached. With the Secure Folder run of §7,
  55 AVC searches on this phone have found no usable rung. No default was changed: that waits for
  a matched benchmark and the owner's decision.
- **The 4K items** that made a full encode (4) took 4.19 s per second of media, 85.2 % of it pixel
  scoring. They are not the sources of §7, so the two are not a comparison.
- **The one crash record** in the export, about five hours before the batch and just after the
  install, was the test instrumentation's process: when ADB dropped in the middle of a test run,
  androidx.test's runner failed on UiAutomation's `DeadObjectException`, and no frame of the app's
  code was in it. The other 20 records are exits (11 other, 7 user requested, 1 package updated,
  1 low memory), all before the batch.

## 10. The remaining checks on v1.6.311 (November 13, 03:38 UTC)

The same phone, build and profile as §9, with the APKs already installed. The default and fallback
checks ran through `scripts/device/run-device-checks.sh --installed` from `e284cb5`, and
`ScoringParityDeviceTest` with the controls `make_scoring_controls.py` writes at that commit,
pushed to `/data/local/tmp/compressor-controls` and checked file by file against their SHA-256 on
the phone before the test.

**The fallback check passed**, all three tests, in 103 s, on the 55 s 1080 × 1920 clip of
`job_a26e9d113c47`. Its ladder measured what it measured in §9's batch: 0.70 failed, 0.80 passed,
0.75 marginal.

- With the retry experiment on, the test's veto refused the first full encode, at 0.75. It was
  recorded as `test_rejected`, certified nothing (`cert_test_rejected`) and wrote nothing to the
  learned profile. Production allowed the retry and encoded the measured safer rung, 0.80, from the
  same ladder, on the same planned windows and with its own encoder configuration. That encode
  passed structural verification and its own certification on all three planned windows, 36 frames
  each (means 96.37, 97.54 and 96.54; p5 93.47, 95.41 and 92.90; minimums 90.85, 95.27 and 91.83),
  and was accepted after one retry; its result alone reached the learned profile.
- With the experiment off, the plan was the passing 0.80. Refused, it left the original in place
  after that one encode, with no retry, no output and nothing left in the cache.
- With both attempts refused, the original was kept after the second, and no third encode started.

In each batch every attempt was closed in the ledger, the batch recorded that a veto ran, Replace
Originals was off and the source's SHA-256 was unchanged. This is the encode after a refused trial
at the safer measured ratio that §6 and §8 left open.

The clip has the same source fingerprint as `job_e95e8acc950f`, the 1080 × 1920 Secure Folder
source of `docs/reviews/S26_HARDWARE_PL_20261029.md`: its winner stayed at 0.80 and 35,604,009
bytes on v1.6.275, its 0.75 probe missed only the probe mean margin, the size model estimated
33,366,670 bytes at 0.75 and 35,531,863 at 0.80, and the notes asked for that item to be run again
with the safer-rung retry on and replacement off. Both encodes have now run. In §9's batch the 0.75
refinement was selected and certified on its first attempt, on three windows of 36 frames each
(means 95.91, 96.98 and 96.06; p5 92.85, 95.08 and 91.84; minimums 90.98, 94.56 and 91.69), at
33,448,361 bytes, 2,155,648 fewer than the v1.6.275 winner. Here, with it refused, the 0.80
fallback came out at 35,604,009 bytes, the same size to the byte as that winner. Each is within
0.25 % of the model's estimate.

**The scoring parity controls passed**, regenerated at `e284cb5`: each of the 11 gave its expected
outcome. Identity, remux, lossless, PTS origin, VFR jitter and motion context decoded to identical
frames and scored `PASSED`; tag mutation did the same, with its color tags shown to differ;
internal deletion was `MISALIGNED`; corrupt was `UNAVAILABLE`; and the one-frame offset and the
chroma mutation decoded to different frames. The one-frame offset scored `MEASURED_FAILURE`. The
chroma mutation, which shifts only U and V, scored `PASSED`, because VMAF v0.6.1, the gates' metric,
reads only luma; the control checks what it is built to show, that its frames differ from the
source's.

**The default checks**, on the same clip, passed, 12 of 12, in 63 s. The production-evidence batch
accepted the 0.75 trial with a bit-identical audio copy, and the timeline test printed what §9
recorded.

The three fallback batches' own records, collected afterwards, say the same. Each run identity
records the veto, Replace Originals off and the retry switch as the test set it (on, off, on). The
ledgers read 0.75 `test_rejected` then 0.80 `accepted`; 0.80 `test_rejected`; and 0.75 then 0.80
`test_rejected`. Every refused attempt passed structural verification and then certified nothing
(`cert_test_rejected`, no windows), the accepted 0.80 output is the one the batch kept, and the
only learned-state update in the three batches is that attempt's.

**Not covered.** The 168 → 166 clip is not on the phone, so there was no exact-source retest. No
crash was recorded; the three exit records are the force-stops `am instrument` makes when it starts
and finishes.

## 11. The safe-copy harness (November 13)

From here, every device check that writes to a video writes only to a disposable copy, and every
personal original is only ever read. `scripts/device/safe-copy-harness.sh` runs in Termux on the
phone, over the phone's own Wireless debugging connection, and keeps four roles apart:

| | What it is | What may happen to it |
|---|---|---|
| A | a personal original, chosen by its media id from a read-only inventory | hashed (full SHA-256 and length) and copied from; never written, moved, renamed or handed to a test |
| B | a byte-for-byte copy of A in `Documents/PLLabValidation/<run>/` (with `.nomedia`) | made once by exclusive create, verified against A, which is hashed before and after the copy; only read afterwards, by path |
| C | disposable copies the device tests make from B, as the app's own rows in `Movies/PLLabValidation/<run>/` | written to, replaced, rolled back, interrupted and recovered, after an allowlist, owner, folder and digest check |
| F | a synthetic fixture the harness makes with ffmpeg | the end padding repair and a full replacement batch |

A and B are checked against the run's manifest before and after every case, and once at the end;
any difference, or a hash that cannot be taken, stops the run (exit 3) with everything left in
place. The tests are never given A's path. A run refuses to start a batch while the app holds
unsaved outputs or an earlier replacement's rollback copies, and it deletes nothing: the run's files
stay until the owner approves their cleanup. The private manifest, inventory and report stay in
Termux's home folder (`~/pllab-runs/<run>/`).

Each batch a harness test starts runs under a `HarnessScope`: planned from the user's learned
state, learning into a store of its own, journalling into its own file, marked `testHarnessRun` in
its record, and not settling the user's rollback copies when it starts. The fallback test now runs
the same way.

What runs, and what each case shows:

- `SafeCopyReplacementDeviceTest`, on C copies of each B, through the app's own replacement IO
  (`ContentResolverReplacementIo`, now shared by the app and the tests) and journal: a confirmed
  replacement; a write that stops part way, a write of the right length but wrong bytes, and a write
  whose size cannot be read back, each rolled back to B's bytes; a copy changed before staging,
  never opened; a rollback that fails, whose copy is kept and then saved once by the next start; and
  another writer after staging, overwritten as the coordinator now documents.
- `SafeCopyProcessDeathDeviceTest`: the process is killed a third of the way into overwriting a C,
  and the two starts after it save the rollback copy once, read back as the staged bytes, and then
  find nothing. This is the one case that passes by crashing (`crashed-as-intended`): one whose
  process survives the write fails, and a crash in any other case fails the run.
- `PaddingRepairBatchDeviceTest` on F: a smaller candidate whose copied AAC differs only in end
  padding must be repaired, verified, certified and accepted; and a batch with Replace Originals and
  Backup before replace on must publish and read back the backup before it replaces its C.
- `CorpusBatchDeviceTest`: one copy-mode batch over copies of every B, recording what each item
  ended as, with no expectation that any compresses.
- `SaferRungFallbackDeviceTest` (`--fallback-baseline K`), on a B that selects a marginal trial.
  It changes the user's safer-rung retry switch, so it records the user's value on disk first
  (`TestOwnedSettings`). `TestOwnedSettingsDeviceTest` then runs on its own and puts back a value
  a test whose process died before its `@After` could not, and the harness compares the switch
  before the case and after it (`retry-switch`); a difference fails the run and says so.
- `app-checks`, needing no media: `GalleryPublicationDeviceTest` (a save counts only when one row
  is published and reads back whole; a refused save leaves no row) and `BatchItemCardDeviceTest`
  (the row's states at 300 dp and 100-200 % text, saved as PNGs of synthetic rows for the report).

The fixture is 12 s of 1080p30 H.264 without B-frames and mono AAC whose 575,488 samples are a
whole number of frames, written on a 48 kHz movie timescale: encoder delay 1,024 and end padding 0
exactly, which `test_safe_copy_harness.py` reads back from the file. Media3's muxer writes such a
track's edit on a 1/10,000 s timescale, which the platform reads as 2 samples of padding.

The harness itself is tested against a fake phone (`scripts/device/test_safe_copy_harness.py`):
staging, exclusive create, a source changing mid-copy, free space, verification, the explicit
`--disposable-only` flag, the order of cases between checks, a case that changes a protected file,
unsaved outputs and leftover rollback copies, a missing `run-as`, device selection, and that a
crash fails the run everywhere but the death case, which fails if its process lives.

    safe-copy-harness.sh inventory
    safe-copy-harness.sh stage --source-id ID --dry-run
    safe-copy-harness.sh all --source-id ID [--source-id ID ...] [--fallback-baseline K]

## 12. The safe-copy harness on the S26 (v1.6.322, November 13, 22:16 UTC)

The owner ran `safe-copy-harness.sh all --fallback-baseline 1` in Termux on the S26 (SM-S948U,
Android 17, normal profile) with the matched v1.6.322 APKs from `bfa0cf9`, over ADB to the phone
itself, on three of the phone's videos chosen from the read-only inventory: the 55 s 1080 × 1920
clip of the fallback check (first, so the fallback ran on it), a 720 × 1280 clip, and a
2160 × 3840 clip. File names, paths, media ids and sizes stay in the run's private manifest.

**Protection.** Each original (A) was copied to its baseline (B) by exclusive create and hashed
before and after the copy. All 13 cases passed, the process-death case by crashing as intended.
Every A and B was checked against the manifest at 20 points (before and after each case, and at
the start and end): 120 checks, all unchanged. No original was written, moved or renamed.

**Gallery publication (R1) on the real MediaStore.** `GalleryPublicationDeviceTest`, 5 of 5: a
save counts only when one row is published and reads back whole; a publish that changes no row, a
write that stops part way, a read-back that differs and bytes other than the expected ones each
leave no row behind. `BatchItemCardDeviceTest`, 5 of 5, rendered the row's states (below).

**The end padding repair, in a batch, on a smaller candidate.** On the synthetic fixture
(12 s 1080p30, AAC delay 1,024 and end padding 0) Perceptually Lossless accepted the 0.60 rung:
certified on three windows (means 98.66, 99.89 and 99.58) and passed structural verification.
Media3's copy of the audio read 2 samples of end padding against the source's 0; the batch
rewrote the file once (`restore_audio_end_padding`) and the audio then compared bit-identical
with the source (563 packets). The output was 13,035,031 bytes from 21,180,786. This is the first
batch on a phone that needed the repair; the fixture is synthetic, so a real camera file needing
it is still unobserved.

**A replacement batch with a proven backup.** The same fixture, through a disposable copy with
Replace Originals and Backup before replace on: the backup was published and read back as the
fixture before the copy was replaced (`replace_committed`), and the copy then held the accepted
output.

**The corpus, copy mode.** One Perceptually Lossless batch over copies of the three baselines,
under `HarnessScope`: the 1080 × 1920 clip compressed at 0.75, 23.6 % smaller, the same size to
the byte as in the v1.6.311 batch; the 720 × 1280 clip was kept (`SKIPPED_WOULD_DEGRADE`); the
2160 × 3840 clip was already efficient (a verified stream copy, not counted as compression).

**The fallback, again, under `HarnessScope`.** 3 of 3, with the clip's ladder as before (0.70
failed, 0.80 passed, 0.75 marginal). The test's veto refused the 0.75 encode; production retried
at 0.80, which certified with the same window means as on November 13 (96.37, 97.54, 96.54) and
the same output size, and was accepted. The row said, to the end: "Attempt 2 of 2: an
instrumented test refused the first candidate (0.75); not a quality measurement, so it was
discarded; encoding again at the safer measured ratio 0.80, which is larger". Without the retry
experiment the original was kept after one encode; with both refused, no third encode started.
The safer-rung retry switch read on before the case and after it, and `TestOwnedSettingsDeviceTest`
found nothing to put back.

**Replacement on disposable copies**, `SafeCopyReplacementDeviceTest` 7 of 7 on a copy of each of
the three baselines: a replacement committed and confirmed by content; a write that stopped part
way, a write of the right length but wrong bytes and a write whose size could not be read back,
each restored to the baseline's bytes; a copy changed before staging refused and never opened; a
rollback that failed left the copy at risk, and the next start preserved it once, the start after
that finding nothing; another writer after staging overwritten and not kept, the documented
limitation.

**Process death.** The process was killed a third of the way into overwriting a copy of the first
baseline. The next start preserved the rollback copy once (published, read back as the staged
bytes, deleted); the start after it found nothing.

**The row's rendered states.** Seven images of synthetic rows: verifying after finalizing, the
retry at 300 dp and 100, 130 and 200 % text, a copy ready, an open suggestion, and a replacement
that left the original incomplete. The last showed a defect: a row named by its status read
"Copy saved" over a line saying the intact copy was held inside the app, and a production row,
whose terminal result is set before its replacement runs, read "Compressed". Fixed in `01b4605`
(the badge reads "Original at risk", "Replaced", "Copy saved" only for a proven save, otherwise
"Not replaced"). At 200 % text the row is taller than the test's window, so that image is cut at
the bottom; in the app the row scrolls.

Not covered: an exact-source retest of the 168 to 166 case (the original is not on the phone),
a real camera file needing the end padding repair, and the S23.
