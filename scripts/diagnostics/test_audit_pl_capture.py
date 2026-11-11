import csv
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile
import audit_pl_capture
from audit_pl_capture import load_run, window_rows, classify, source_split

HEVC, AVC = 'video/hevc', 'video/avc'
QTI_HEVC, QTI_AVC = 'c2.qti.hevc.encoder', 'c2.qti.avc.encoder'
WINDOW = '216489000-217689000'
PLANNED = WINDOW + ';541222500-542422500;865956000-867156000'


def rung(seq, ratio, mime, encoder, scores, job='job_c9ba3d6425be', attempt=14, config=None, window=WINDOW,
         planned=PLANNED, verdict='failed', frames=36, **extra):
    """One single-window probe_rung stage as the app writes it. [scores] is (mean, p5, min)."""
    config = config or 'cfg%03d' % seq
    stage = dict(type='stage', stage='probe_rung', reasonCode='rung_measured', jobId=job, sequence=seq, attempt=attempt,
                 attemptIndex=0, rungIndex=0, rungId='%.2f@%s' % (ratio, config), ratio=ratio, verdict=verdict,
                 requestedVideoBitrate=int(840000 * ratio), configId=config, rungMs=4000, plannedWindowIds=planned,
                 probeWindows=1, probeMean=repr(scores[0]), probeP5=repr(scores[1]), probeMin=repr(scores[2]),
                 probeFrames=str(frames), probeWindowIds=window)
    if mime is not None: stage['outputMime'] = mime
    if encoder is not None: stage['actualEncoderNames'] = encoder if isinstance(encoder, str) and encoder.startswith('[') else [encoder]
    stage.update(extra)
    return stage


def alternate_search(seq, job='job_c9ba3d6425be', attempt=14, selected=HEVC):
    return dict(type='stage', stage='plan', reasonCode='alternate_codec_search', jobId=job, sequence=seq, attempt=attempt,
                attemptIndex=0, primaryMime=HEVC, alternateMime=AVC, selectedMime=selected)


def job(job_id='job_c9ba3d6425be', terminal='SKIPPED_WOULD_DEGRADE', mime=HEVC, **extra):
    record = dict(type='job', jobId=job_id, terminal=terminal, plannedOutputMime=mime, sourceMime=AVC, w=1436, h=720, fps=30,
                  sourceSize=130991290, sourceTotalBitrate=968114, audioBitrate=127996, outputSize=0, savedBytes=0)
    record.update(extra)
    return record


# Secure Folder b287, batch_1794292368590: the rungs of the one job the pre-fix join got wrong,
# full precision, identities redacted to what the export itself calls them.
C9_HEVC_97 = (92.12102064338865, 87.35402034199576, 85.66153079422766)
C9_AVC_85 = (91.17531700319024, 86.50559130621542, 85.33840274582292)
C9_AVC_95 = (92.0329917270356, 88.06286973944133, 86.65938879350034)
C9_AVC_97 = (92.5264868730533, 88.4619045894384, 86.93059320279369)


def cross_codec_job():
    return [rung(212, 0.97, HEVC, QTI_HEVC, C9_HEVC_97, config='dbead95cd424'),
            rung(216, 0.85, AVC, QTI_AVC, C9_AVC_85, config='431657fdd69c'),
            rung(217, 0.95, AVC, QTI_AVC, C9_AVC_95, config='eb2790cf906f', rungIndex=1),
            rung(218, 0.97, AVC, QTI_AVC, C9_AVC_97, config='fb3968ba227a', rungIndex=2),
            alternate_search(219), job()]


class AuditTest(unittest.TestCase):
    def test_overlapping_exports_are_one_run_and_conflicts_fail(self):
        with tempfile.TemporaryDirectory() as d:
            paths = [Path(d) / (str(i) + '.zip') for i in range(2)]
            records = [{'type': 'session_start', 'mode': 'Perceptually Lossless'}, {'type': 'session_summary'}]
            for p in paths:
                with ZipFile(p, 'w') as z:
                    z.writestr('runs/batch_1/session.jsonl', '\n'.join(map(json.dumps, records)))
            self.assertEqual(len(load_run(paths, 'batch_1')[0]), 2)
            with ZipFile(paths[1], 'w') as z:
                z.writestr('runs/batch_1/session.jsonl', '{}')
            with self.assertRaises(ValueError): load_run(paths, 'batch_1')

    def test_missing_frame_counts_do_not_become_measurements(self):
        stage = dict(stage='probe_rung', probeMean='94', probeP5='93', probeMin='90')
        self.assertEqual(window_rows(stage), [])
        self.assertEqual(classify({'terminal': 'SKIPPED_WOULD_DEGRADE'}, [], []), 'unexplained')

    def test_window_series_length_mismatch_fails(self):
        with self.assertRaises(ValueError):
            window_rows(dict(stage='probe_rung', probeMean='94;95', probeP5='93', probeMin='90', probeFrames='36'))

    def test_source_disjoint_split_is_stable(self):
        self.assertEqual(source_split('a'*64), source_split('a'*64))
        self.assertIsNone(source_split(None))

    def test_full_precision_rate_and_timing_join_stays_per_window(self):
        stage=dict(stage='probe_rung',probeMean='96;97',probeP5='93;94',probeMin='90;91',probeFrames='36;36',
                   probeWindowIds='1-2;3-4',requestedVideoBitrate=1000000,rateFactors='1.012345;0.987654',probeTiming='first;second')
        rows=window_rows(stage)
        self.assertAlmostEqual(rows[1]['observedSteadyVideoBitrateDerived'],987654)
        self.assertEqual(rows[0]['timingRaw'],'first')
        stage['rateFactors']='1.01'
        with self.assertRaises(ValueError): window_rows(stage)


class LadderSeparationTest(unittest.TestCase):
    """October 2026 S26 capture: the audit compared an AVC rung with an HEVC rung as one ladder."""

    def test_the_captured_avc_95_hevc_97_pair_is_not_a_ladder_step(self):
        tables = audit_pl_capture.analyze_tables(cross_codec_job())
        # Pre-fix: lower AVC 0.95 (seq 217) against higher HEVC 0.97 (seq 212) on one window,
        # deltas mean +0.08803 / p5 -0.70885 / min -0.99786, counted as non-monotonic.
        self.assertEqual(tables['nonmonotonic'], [])
        self.assertEqual(tables['summary']['nonMonotonicComparisons'], 0)
        crossed = [e for e in tables['excluded'] if e['wouldHaveCountedAsNonMonotonic']]
        self.assertEqual(len(crossed), 1)
        pair = crossed[0]
        self.assertEqual((pair['reason'], pair['lowerSequence'], pair['higherSequence']), ('cross_codec', 217, 212))
        self.assertEqual((pair['lowerRungCodec'], pair['higherRungCodec']), (AVC, HEVC))
        self.assertEqual((pair['lowerEncoder'], pair['higherEncoder']), (QTI_AVC, QTI_HEVC))
        self.assertAlmostEqual(pair['deltas']['mean'], 0.08802891635305343, places=12)
        self.assertAlmostEqual(pair['deltas']['p5'], -0.7088493974455616, places=12)
        self.assertAlmostEqual(pair['deltas']['min'], -0.9978579992726822, places=12)
        # Pooled by ratio: AVC .85, AVC .95, HEVC .97, AVC .97. One adjacent pair crossed ladders.
        self.assertEqual(tables['summary']['legacyPooledJoin'],
                         {'nonMonotonicComparisons': 1, 'crossLadderPairs': 1, 'crossLadderPairsCountedNonMonotonic': 1})

    def test_each_window_keeps_the_codec_and_encoder_of_its_own_rung(self):
        tables = audit_pl_capture.analyze_tables(cross_codec_job())
        by_seq = {w['sequence']: w for w in tables['windows']}
        self.assertEqual({s: (w['rungCodec'], w['encoder']) for s, w in by_seq.items()},
                         {212: (HEVC, QTI_HEVC), 216: (AVC, QTI_AVC), 217: (AVC, QTI_AVC), 218: (AVC, QTI_AVC)})
        # The job planned HEVC; that is the job's codec and no longer overwrites what a rung measured.
        self.assertEqual({w['requestedOutputCodec'] for w in tables['windows']}, {HEVC, AVC})
        self.assertEqual({w['jobPlannedOutputCodec'] for w in tables['windows']}, {HEVC})
        self.assertEqual(by_seq[217]['rungId'], '0.95@eb2790cf906f')
        self.assertEqual(by_seq[217]['configId'], 'eb2790cf906f')
        self.assertEqual(by_seq[217]['windowId'], WINDOW)
        self.assertEqual((by_seq[212]['ladderRole'], by_seq[218]['ladderRole']), ('primary', 'alternate'))

    def test_highest_rung_margins_are_reported_per_codec_and_never_pooled(self):
        # Same top ratio in both ladders. HEVC is better on the mean, AVC on p5: the pooled minimum
        # per gate described a rung that does not exist.
        records = [rung(1, 0.97, HEVC, QTI_HEVC, (95.0, 86.0, 85.0)), rung(2, 0.97, AVC, QTI_AVC, (93.0, 90.0, 86.0)),
                   alternate_search(3), job()]
        tables = audit_pl_capture.analyze_tables(records)
        ladders = {l['rungCodec']: l for l in tables['ladders']}
        self.assertEqual(ladders[HEVC]['highestRungMargins'], {'mean': -0.5, 'p5': -5.0, 'min': 1.0})
        self.assertEqual(ladders[AVC]['highestRungMargins'], {'mean': -2.5, 'p5': -1.0, 'min': 2.0})
        self.assertEqual((ladders[HEVC]['worstMargin'], ladders[AVC]['worstMargin']), (-5.0, -2.5))
        source = tables['sources'][0]
        # The job-level margin is the ladder the plan came from, named, not a mixture.
        self.assertEqual((source['marginLadderCodec'], source['marginLadderRole']), (HEVC, 'primary'))
        self.assertEqual(source['highestRungMargins'], {'mean': -0.5, 'p5': -5.0, 'min': 1.0})
        self.assertEqual((source['worstMargin'], source['limitingGate']), (-5.0, 'p5'))
        self.assertEqual(source['measuredLadderCodecs'], [HEVC, AVC])
        self.assertEqual(tables['nonmonotonic'], [])
        self.assertEqual(tables['repeats'], [])
        per_codec = tables['summary']['perCodec']
        self.assertEqual(per_codec[HEVC]['withinPointsOfAllGates']['5'], 1)
        self.assertEqual(per_codec[HEVC]['withinPointsOfAllGates']['2.5'], 0)
        self.assertEqual(per_codec[AVC]['withinPointsOfAllGates']['2.5'], 1)
        self.assertEqual(per_codec[AVC]['limitingGate'], {'mean': 1})

    def test_a_same_codec_step_hidden_by_the_pooled_order_is_now_compared(self):
        # Pooled by ratio the HEVC 0.90 rung sat between the two AVC rungs, so AVC 0.85 -> 0.95 was
        # never compared. Within the AVC ladder it is adjacent, and here it is a real drop.
        records = [rung(1, 0.90, HEVC, QTI_HEVC, (93.0, 89.0, 88.0)), rung(2, 0.85, AVC, QTI_AVC, (94.0, 90.0, 89.0)),
                   rung(3, 0.95, AVC, QTI_AVC, (93.1, 89.2, 88.5)), alternate_search(4), job()]
        tables = audit_pl_capture.analyze_tables(records)
        self.assertEqual(tables['summary']['legacyPooledJoin']['nonMonotonicComparisons'], 1)  # AVC .85 -> HEVC .90
        self.assertEqual(len(tables['nonmonotonic']), 1)
        step = tables['nonmonotonic'][0]
        self.assertEqual((step['rungCodec'], step['encoder'], step['lowerRatio'], step['higherRatio']), (AVC, QTI_AVC, 0.85, 0.95))
        self.assertEqual((step['lowerSequence'], step['higherSequence'], step['lineage']), (2, 3, 'recorded'))
        self.assertEqual(tables['summary']['nonMonotonicByCodec'], {AVC: 1})

    def test_a_genuine_same_codec_drop_is_still_reported_with_its_identity(self):
        # Same capture, job_5a3c65f9d8fb: AVC 0.95 -> 0.97, deltas -0.4557 / -0.2255 / -2.2503.
        low, high = (93.2, 90.1, 89.75028), (92.7443, 89.8745, 87.5)
        records = [rung(290, 0.95, AVC, QTI_AVC, low, job='job_5a3c65f9d8fb', config='aaa'),
                   rung(291, 0.97, AVC, QTI_AVC, high, job='job_5a3c65f9d8fb', config='bbb'), job('job_5a3c65f9d8fb')]
        step, = audit_pl_capture.analyze_tables(records)['nonmonotonic']
        self.assertEqual((step['jobId'], step['windowId'], step['repeatRequired']), ('job_5a3c65f9d8fb', WINDOW, True))
        self.assertEqual((step['lowerRungId'], step['higherRungId']), ('0.95@aaa', '0.97@bbb'))
        self.assertEqual((step['lowerConfigId'], step['higherConfigId']), ('aaa', 'bbb'))
        self.assertAlmostEqual(step['deltas']['min'], -2.25028, places=9)
        self.assertEqual(step['ladderRole'], 'primary')

    def test_an_improving_same_codec_ladder_is_the_negative_control(self):
        records = [rung(216, 0.85, AVC, QTI_AVC, C9_AVC_85), rung(217, 0.95, AVC, QTI_AVC, C9_AVC_95),
                   rung(218, 0.97, AVC, QTI_AVC, C9_AVC_97), job()]
        tables = audit_pl_capture.analyze_tables(records)
        self.assertEqual((tables['nonmonotonic'], tables['excluded'], tables['repeats']), ([], [], []))

    def test_a_ratio_measured_twice_is_a_repeat_and_not_a_step(self):
        records = [rung(1, 0.95, AVC, QTI_AVC, (93.0, 90.0, 89.0), config='same'),
                   rung(2, 0.95, AVC, QTI_AVC, (92.0, 89.0, 88.0), config='same'),
                   rung(3, 0.97, AVC, QTI_AVC, (92.2, 89.1, 88.1)), job()]
        tables = audit_pl_capture.analyze_tables(records)
        repeat, = tables['repeats']
        self.assertEqual((repeat['ratio'], repeat['firstSequence'], repeat['secondSequence']), (0.95, 1, 2))
        self.assertEqual(repeat['sameConfig'], True)
        self.assertAlmostEqual(repeat['deltas']['mean'], -1.0)
        # Only the first 0.95 observation is more than half a point above the 0.97 one.
        step, = tables['nonmonotonic']
        self.assertEqual((step['lowerSequence'], step['higherSequence'], step['repeatedRatio']), (1, 3, True))

    def test_a_changed_encoder_within_one_codec_is_a_separate_ladder(self):
        records = [rung(1, 0.90, HEVC, QTI_HEVC, (94.0, 91.0, 90.0)),
                   rung(2, 0.95, HEVC, 'c2.android.hevc.encoder', (93.0, 90.0, 89.0)), job()]
        tables = audit_pl_capture.analyze_tables(records)
        self.assertEqual(tables['nonmonotonic'], [])
        self.assertEqual([e['reason'] for e in tables['excluded']], ['cross_encoder'])
        self.assertEqual(len(tables['ladders']), 2)

    def test_a_window_with_too_few_frames_or_another_window_is_never_paired(self):
        records = [rung(1, 0.90, AVC, QTI_AVC, (95.0, 92.0, 91.0)),
                   rung(2, 0.95, AVC, QTI_AVC, (90.0, 85.0, 84.0), frames=11),
                   rung(3, 0.97, AVC, QTI_AVC, (90.0, 85.0, 84.0), window='541222500-542422500'), job()]
        self.assertEqual(audit_pl_capture.analyze_tables(records)['nonmonotonic'], [])


class LegacyLineageTest(unittest.TestCase):
    def test_rungs_from_before_the_codec_was_recorded_are_grouped_and_say_so(self):
        # Sept 27 - Oct 1 exports: no outputMime, no actualEncoderNames, and no alternate search existed.
        records = [rung(1, 0.90, None, None, (94.0, 91.0, 90.0)), rung(2, 0.95, None, None, (93.0, 90.0, 89.0)), job()]
        tables = audit_pl_capture.analyze_tables(records)
        step, = tables['nonmonotonic']
        self.assertEqual((step['lineage'], step['rungCodec'], step['encoder']), ('legacy_unrecorded', None, None))
        self.assertEqual(tables['summary']['nonMonotonicByLineage'], {'legacy_unrecorded': 1})
        # The job's planned codec is not written onto windows that never recorded one.
        self.assertEqual({w['requestedOutputCodec'] for w in tables['windows']}, {None})
        self.assertEqual({w['rungCodecBasis'] for w in tables['windows']}, {'not recorded (legacy export)'})
        self.assertEqual({w['jobPlannedOutputCodec'] for w in tables['windows']}, {HEVC})
        self.assertEqual(tables['ladders'][0]['lineage'], 'legacy_unrecorded')

    def test_an_encoder_name_alone_identifies_the_ladder(self):
        # Oct 1 - Oct 29 exports: the actual encoder is recorded, the rung's MIME is not.
        records = [rung(1, 0.90, None, QTI_HEVC, (94.0, 91.0, 90.0)), rung(2, 0.95, None, QTI_HEVC, (93.0, 90.0, 89.0)), job()]
        step, = audit_pl_capture.analyze_tables(records)['nonmonotonic']
        self.assertEqual((step['lineage'], step['rungCodec'], step['encoder']), ('recorded', None, QTI_HEVC))

    def test_unrecorded_rungs_beside_an_alternate_search_are_not_compared(self):
        records = [rung(1, 0.90, None, None, (94.0, 91.0, 90.0)), rung(2, 0.95, None, None, (93.0, 90.0, 89.0)),
                   alternate_search(3), job()]
        tables = audit_pl_capture.analyze_tables(records)
        self.assertEqual(tables['nonmonotonic'], [])
        self.assertEqual([e['reason'] for e in tables['excluded']], ['encoder_not_recorded'])
        self.assertEqual(tables['ladders'][0]['lineage'], None)

    def test_the_b271_stringified_encoder_list_reads_like_the_json_array(self):
        self.assertEqual(audit_pl_capture.encoder_names('[c2.qti.hevc.encoder]'), [QTI_HEVC])
        self.assertEqual(audit_pl_capture.encoder_names('[c2.qti.hevc.encoder, c2.android.hevc.encoder]'),
                         [QTI_HEVC, 'c2.android.hevc.encoder'])
        self.assertEqual(audit_pl_capture.encoder_names([QTI_HEVC]), [QTI_HEVC])
        self.assertIsNone(audit_pl_capture.encoder_names(None))
        self.assertIsNone(audit_pl_capture.encoder_names('[]'))
        self.assertIsNone(audit_pl_capture.encoder_names([]))
        records = [rung(1, 0.90, HEVC, '[c2.qti.hevc.encoder]', (94.0, 91.0, 90.0)),
                   rung(2, 0.95, HEVC, QTI_HEVC, (93.0, 90.0, 89.0)), job()]
        step, = audit_pl_capture.analyze_tables(records)['nonmonotonic']
        self.assertEqual(step['encoder'], QTI_HEVC)

    def test_an_explicit_lineage_from_a_newer_build_wins_over_inference(self):
        records = [rung(1, 0.90, HEVC, QTI_HEVC, (94.0, 91.0, 90.0), ladderRole='primary', encodeShapeId='s1'),
                   rung(2, 0.95, HEVC, QTI_HEVC, (93.0, 90.0, 89.0), ladderRole='primary', encodeShapeId='s2'), job()]
        tables = audit_pl_capture.analyze_tables(records)
        # Same codec and encoder, different request shape: two ladders.
        self.assertEqual(tables['nonmonotonic'], [])
        self.assertEqual([e['reason'] for e in tables['excluded']], ['cross_encodeShape'])


class ReportingTest(unittest.TestCase):
    def test_scope_says_whether_the_highest_rung_measured_every_planned_window(self):
        complete = dict(probeWindows=3, probeMean='96.0;96.5;97.0', probeP5='92.0;92.5;93.0', probeMin='91.0;91.5;92.0',
                        probeFrames='36;36;36', probeWindowIds=PLANNED)
        records = [rung(1, 0.97, HEVC, QTI_HEVC, C9_HEVC_97), rung(2, 0.97, AVC, QTI_AVC, (0, 0, 0), **complete),
                   alternate_search(3), job()]
        ladders = {l['rungCodec']: l for l in audit_pl_capture.analyze_tables(records)['ladders']}
        self.assertEqual((ladders[HEVC]['highestRungScope'], ladders[AVC]['highestRungScope']), ('early_exit', 'complete'))
        self.assertEqual((ladders[HEVC]['highestRungScoredWindows'], ladders[HEVC]['highestRungPlannedWindows']), (1, 3))
        legacy = [rung(1, 0.97, None, None, C9_HEVC_97, planned=None), job()]
        self.assertEqual(audit_pl_capture.analyze_tables(legacy)['ladders'][0]['highestRungScope'], 'unknown')

    def test_certification_windows_take_the_codec_of_the_rung_they_were_encoded_from(self):
        cert = dict(type='stage', stage='certify', reasonCode='cert_passed', jobId='job_c9ba3d6425be', sequence=9, attempt=14,
                    attemptIndex=1, configId='fb3968ba227a', probeRungId='0.97@fb3968ba227a', certWindows=1,
                    certMean='96.0', certP5='92.0', certMin='91.0', certFrames='36', certWindowIds=WINDOW)
        records = cross_codec_job()[:-1] + [cert, job()]
        windows = audit_pl_capture.analyze_tables(records)['windows']
        row, = [w for w in windows if w['stage'] == 'certify']
        self.assertEqual((row['rungCodec'], row['rungCodecBasis']), (AVC, 'joined by configId to the probe rung'))

    def test_summary_counts_rungs_and_time_per_codec(self):
        summary = audit_pl_capture.analyze_tables(cross_codec_job())['summary']
        self.assertEqual(summary['perCodec'][AVC]['rungs'], 3)
        self.assertEqual(summary['perCodec'][AVC]['rungMs'], 12000)
        self.assertEqual(summary['perCodec'][HEVC]['rungVerdicts'], {'failed': 1})
        self.assertEqual(summary['rejectMeasuredLadderCodecs'], {'video/hevc+video/avc': 1})
        self.assertEqual(summary['rejectRequestedOutputCodecs'], {HEVC: 1})
        self.assertEqual(summary['classifications'], {'measured_probe_failure': 1})

    def test_the_cli_keeps_its_arguments_and_files_and_adds_the_new_tables(self):
        records = [dict(type='session_start', mode='Perceptually Lossless')] + cross_codec_job() + [dict(type='session_summary')]
        with tempfile.TemporaryDirectory() as d:
            archive = Path(d) / 'capture.zip'
            with ZipFile(archive, 'w') as z:
                z.writestr('runs/batch_7/session.jsonl', '\n'.join(map(json.dumps, records)))
            out = Path(d) / 'out'
            script = Path(audit_pl_capture.__file__)
            done = subprocess.run([sys.executable, str(script), str(archive), '--batch', 'batch_7', '--out', str(out)],
                                  capture_output=True, text=True)
            self.assertEqual(done.returncode, 0, done.stderr)
            for name in ['sources.jsonl', 'sources.csv', 'windows.jsonl', 'windows.csv', 'nonmonotonic.jsonl', 'events.jsonl',
                         'attempts.jsonl', 'summary.json', 'ladders.jsonl', 'ladders.csv', 'excluded_comparisons.jsonl',
                         'repeats.jsonl']:
                self.assertTrue((out / name).exists(), name)
            summary = json.loads((out / 'summary.json').read_text())
            for key in ['jobs', 'classifications', 'terminals', 'savedBytes', 'originalsHashVerifiedUnchanged',
                        'uniqueFullSourceHashes', 'rejectSourceCodecs', 'rejectRequestedOutputCodecs', 'limitingGate',
                        'medianWorstMargin', 'withinPointsOfAllGates', 'bppBins', 'nonMonotonicComparisons',
                        'calibrationStatus', 'runIdentity', 'batchId', 'provenance']:
                self.assertIn(key, summary)
            self.assertEqual(summary['nonMonotonicComparisons'], 0)
            self.assertEqual(json.loads(done.stdout)['nonMonotonicComparisons'], 0)
            with (out / 'windows.csv').open(newline='') as f:
                rows = list(csv.DictReader(f))
            self.assertEqual([r['rungCodec'] for r in rows], [HEVC, AVC, AVC, AVC])
            self.assertEqual(len((out / 'excluded_comparisons.jsonl').read_text().splitlines()), 1)

    def test_analyze_still_returns_the_four_tables_callers_unpack(self):
        sources, windows, monotonic, summary = audit_pl_capture.analyze(cross_codec_job())
        self.assertEqual((len(sources), len(windows), monotonic, summary['jobs']), (1, 4, [], 1))


if __name__ == '__main__': unittest.main()
