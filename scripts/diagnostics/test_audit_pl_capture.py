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

    def test_a_device_suite_bundle_is_read_and_must_agree_with_an_export_of_the_same_run(self):
        with tempfile.TemporaryDirectory() as d:
            export, bundle = Path(d) / 'export.zip', Path(d) / 'suite.zip'
            records = [{'type': 'session_start', 'mode': 'Perceptually Lossless'}, {'type': 'session_summary'}]
            payload = '\n'.join(map(json.dumps, records))
            with ZipFile(export, 'w') as z:
                z.writestr('runs/batch_1/session.jsonl', payload)
            with ZipFile(bundle, 'w') as z:
                z.writestr('bundle/runs/batch_1/session.jsonl', payload)
                z.writestr('bundle/runs/batch_1/run-sequence', '1')
            loaded, provenance = load_run([export, bundle], 'batch_1')
            self.assertEqual(len(loaded), 2)
            self.assertEqual([p['member'] for p in provenance], ['runs/batch_1/session.jsonl', 'bundle/runs/batch_1/session.jsonl'])
            self.assertEqual(len({p['sessionSha256'] for p in provenance}), 1)
            with ZipFile(bundle, 'w') as z:
                z.writestr('bundle/runs/batch_1/session.jsonl', payload + '\n{}')
            with self.assertRaises(ValueError): load_run([export, bundle], 'batch_1')
            with self.assertRaises(ValueError) as missing: load_run([bundle], 'batch_2')
            self.assertIn('batch_1', str(missing.exception))

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

    def test_the_alternate_search_reports_what_it_cost_and_that_it_gave_nothing(self):
        # The captured job: one HEVC rung, three AVC rungs at 4,000 ms each, original kept.
        records = cross_codec_job()
        records[-1]['elapsedMs'] = 20000
        other = [rung(300, 0.90, HEVC, QTI_HEVC, (97.0, 93.0, 90.0), job='job_other', verdict='passed', config='c300'),
                 job('job_other', terminal='TRANSCODED_SMALLER', elapsedMs=60000)]
        cost = audit_pl_capture.analyze_tables(records + other)['summary']['alternateSearch']
        self.assertEqual((cost['jobs'], cost['ofJobs'], cost['rungs'], cost['rungMs']), (1, 2, 3, 12000))
        self.assertEqual(cost['primaryRungMsOnThoseJobs'], 4000)
        self.assertEqual(cost['itemMsOnThoseJobs'], 20000)
        self.assertAlmostEqual(cost['shareOfThoseItems'], 0.6)
        self.assertAlmostEqual(cost['shareOfAllItems'], 12000 / 80000)
        self.assertEqual(cost['rungVerdicts'], {'failed': 3})
        self.assertEqual((cost['laddersWithUsableRung'], cost['plansTakenFromAlternate'], cost['certifiedFromAlternate']), (0, 0, 0))
        self.assertEqual(cost['jobTerminals'], {'SKIPPED_WOULD_DEGRADE': 1})
        # AVC at 0.97 missed the mean gate by 2.97 points: outside 2.5, inside 5.
        self.assertAlmostEqual(cost['medianWorstMargin'], C9_AVC_97[0] - 95.5)
        self.assertEqual((cost['withinPointsOfAllGates']['2.5'], cost['withinPointsOfAllGates']['5']), (0, 1))
        # Both ladders reached 0.97 here.
        self.assertEqual(cost['primaryStoppedBelowAlternateTop'], 0)

    def test_an_alternate_plan_that_was_taken_is_counted_as_its_yield(self):
        records = [rung(1, 0.95, HEVC, QTI_HEVC, (94.0, 90.0, 85.0), config='h95'),
                   rung(2, 0.97, AVC, QTI_AVC, (97.0, 93.0, 90.0), config='a97', verdict='passed'),
                   alternate_search(3, selected=AVC),
                   job(terminal='TRANSCODED_SMALLER', mime=AVC, outputSize=100, savedBytes=30, countsAsRealCompression=True,
                       pixelCertified=True, finalAccepted=True, verified=True, elapsedMs=16000)]
        tables = audit_pl_capture.analyze_tables(records)
        cost = tables['summary']['alternateSearch']
        self.assertEqual(tables['sources'][0]['classification'], 'certified_smaller')
        self.assertEqual((cost['jobs'], cost['rungs'], cost['rungMs']), (1, 1, 4000))
        self.assertEqual((cost['laddersWithUsableRung'], cost['plansTakenFromAlternate'], cost['certifiedFromAlternate']), (1, 1, 1))
        # The primary stopped at 0.95 and never measured the ratio the alternate passed at.
        self.assertEqual(cost['primaryStoppedBelowAlternateTop'], 1)
        self.assertEqual(cost['withinPointsOfAllGates'], {str(d): 0 for d in audit_pl_capture.POINTS})

    def test_a_run_without_an_alternate_search_reports_none_and_divides_by_nothing(self):
        records = [rung(1, 0.90, HEVC, QTI_HEVC, (97.0, 93.0, 90.0), verdict='passed'), job(terminal='TRANSCODED_SMALLER')]
        cost = audit_pl_capture.analyze_tables(records)['summary']['alternateSearch']
        self.assertEqual((cost['jobs'], cost['rungs'], cost['rungMs']), (0, 0, 0))
        self.assertIsNone(cost['shareOfThoseItems'])
        self.assertIsNone(cost['shareOfAllItems'])
        self.assertIsNone(cost['medianWorstMargin'])

    def test_stage_time_is_summed_from_recorded_events_by_resolution_class(self):
        def event(stage, reason, attempt_index, elapsed, **extra):
            return dict(type='stage', stage=stage, reasonCode=reason, jobId='job_4k', sequence=elapsed, attempt=7,
                        attemptIndex=attempt_index, elapsedMs=elapsed, **extra)
        timing = 'wall=11000,queue=800,v0=1200+150,cambi=0,v1=0+0'
        records = [
            rung(1, 0.90, HEVC, QTI_HEVC, (97.0, 93.0, 90.0), job='job_4k', verdict='passed', config='p90', rungMs=30000,
                 probeTiming=timing),
            # First attempt: encoded, verified after a first-timestamp repair, certified.
            event('encode', 'encode_completed', 1, 40000, encodeMs=8000),
            event('finalize', 'candidate_finalized', 1, 40100),
            event('finalize', 'candidate_finalized', 1, 42900, operation='restore_first_video_timestamp'),
            event('verify', 'structural_passed', 1, 43000),
            event('certify', 'cert_measured_failure', 1, 55000, certMean='94.0', certP5='92.0', certMin='90.0', certFrames='36',
                  certWindowIds=WINDOW, certTiming='wall=12000,queue=900,v0=1300+100,cambi=0,v1=0+0'),
            # The retry: a failed floor recovery's certification is not the final certification.
            event('encode', 'encode_completed', 2, 64000, encodeMs=9000),
            event('finalize', 'candidate_finalized', 2, 64100),
            event('certify', 'cert_unavailable', 2, 66000, scope='floor_recovery'),
            event('verify', 'structural_failed', 2, 66100),
            # Portrait: the long side decides the class.
            job('job_4k', terminal='UNEXPECTED_REMUX', w=2160, h=3840, fps=60, durationMs=10000, elapsedMs=70000, sourceSize=150000000),
            # A job that never encoded is not in the stage table.
            rung(900, 0.97, HEVC, QTI_HEVC, C9_HEVC_97, job='job_keep', config='k97'),
            job('job_keep', w=3840, h=2160, durationMs=5000, elapsedMs=9000),
        ]
        stage = audit_pl_capture.analyze_tables(records)['summary']['stageTime']
        self.assertEqual(list(stage), ['4K'])
        row = stage['4K']
        self.assertEqual((row['jobs'], row['encodes'], row['mediaSeconds'], row['sourceBytes']), (1, 2, 10.0, 150000000))
        self.assertEqual((row['probeMs'], row['encodeMs'], row['itemMs']), (30000, 17000, 70000))
        # 43000 - 40100 for the first attempt (repair included) and 66100 - 64100 for the retry.
        self.assertEqual(row['verifyMs'], 2900 + 2000)
        # 55000 - 43000; the retry was never certified.
        self.assertEqual(row['certifyMs'], 12000)
        self.assertAlmostEqual(row['itemSecondsPerMediaSecond'], 7.0)
        self.assertAlmostEqual(row['encodeSecondsPerMediaSecond'], 1.7)
        self.assertAlmostEqual(row['scoringShareOfItem'], (30000 + 12000) / 70000)
        self.assertEqual(row['scoringWindows'], {'probe_rung': dict(timedWindows=1, wallMs=11000, meanWallMs=11000.0),
                                                 'certify': dict(timedWindows=1, wallMs=12000, meanWallMs=12000.0)})

    def test_time_a_build_did_not_record_stays_unknown(self):
        self.assertEqual(audit_pl_capture.wall_ms('queue=3,wall=250,v0=1+2'), 250)
        self.assertIsNone(audit_pl_capture.wall_ms('queue=3,firewall=250'))
        self.assertIsNone(audit_pl_capture.wall_ms(None))
        self.assertEqual(audit_pl_capture.resolution_class(dict(w=1080, h=1920)), '1080p')
        self.assertEqual(audit_pl_capture.resolution_class(dict(w=1280, h=720)), '720p_or_less')
        self.assertEqual(audit_pl_capture.resolution_class(dict()), 'unknown')
        # An encode with no finalize, verify or certify events and no item time: nothing is invented.
        records = [dict(type='stage', stage='encode', reasonCode='encode_completed', jobId='job_old', sequence=5, attempt=1,
                        attemptIndex=1, encodeMs=7000),
                   job('job_old', terminal='TRANSCODED_SMALLER', w=1920, h=1080)]
        row = audit_pl_capture.analyze_tables(records)['summary']['stageTime']['1080p']
        self.assertEqual((row['encodeMs'], row['probeMs']), (7000, 0))
        for key in ['verifyMs', 'certifyMs', 'itemMs', 'mediaSeconds', 'itemSecondsPerMediaSecond', 'scoringShareOfItem']:
            self.assertIsNone(row[key], key)

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
                         'repeats.jsonl', 'size_gates.jsonl']:
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


def size_gate(seq, job_id, reason, rung_id, attempt, mime=HEVC, **extra):
    stage = dict(type='stage', stage='size_gate', reasonCode=reason, jobId=job_id, sequence=seq, attempt=attempt,
                 attemptIndex=0, outputMime=mime, provenRatio=None, probeRungId=rung_id)
    stage.update(extra)
    return stage


# Regular profile b287, batch_1794291512031: the one planned window of each rung, full precision.
J82_75 = (95.06946158548124, 93.57089107441205, 92.90193200677668)
J82_95 = (95.55968238949218, 94.00163211764911, 93.93942047542116)
J82_97 = (95.7019398749062, 94.0104548435293, 93.95730819999392)
ONE_WINDOW = '2706000-3906000'


def marginal_size_keep():
    kw = dict(job='job_8227cd6d6a43', attempt=7, window=ONE_WINDOW, planned=ONE_WINDOW, frames=71)
    return [rung(98, 0.75, HEVC, QTI_HEVC, J82_75, config='9aade12672e7', verdict='failed', **kw),
            rung(99, 0.95, HEVC, QTI_HEVC, J82_95, config='1eec1b05b828', verdict='marginal', rungIndex=1, **kw),
            rung(100, 0.97, HEVC, QTI_HEVC, J82_97, config='ed624713bee1', verdict='marginal', rungIndex=2, **kw),
            size_gate(101, 'job_8227cd6d6a43', 'gate_keep_original', '0.97@ed624713bee1', 7,
                      predictedBytes=114912600, sourceBytes=102508481, overshootPreClamp=1.1444063863537028),
            job('job_8227cd6d6a43', terminal='ALREADY_HIGHLY_OPTIMIZED', mime=None, sourceSize=102508481,
                materializationMode='REUSED_SOURCE', copyAvoidedBytes=102508481, pixelCertified=False,
                countsAsRealCompression=False, attemptsStarted=None)]


class SizeGateTest(unittest.TestCase):
    def test_a_marginal_rung_kept_on_predicted_size_keeps_its_verdict_scope_and_bytes(self):
        tables = audit_pl_capture.analyze_tables(marginal_size_keep())
        (gate,) = tables['size_gates']
        self.assertEqual(gate['decision'], 'keep_original')
        self.assertEqual((gate['assessedRatio'], gate['assessedRungId']), (0.97, '0.97@ed624713bee1'))
        self.assertEqual(gate['rungVerdict'], 'marginal')
        self.assertFalse(gate['clearedSelectionMargin'])
        self.assertEqual((gate['measuredWindows'], gate['plannedWindows'], gate['scope']), (1, 1, 'complete'))
        self.assertEqual((gate['predictedBytes'], gate['sourceBytes']), (114912600, 102508481))
        self.assertEqual(gate['rungVerdictSource'], 'joined to the probe rung by id')
        self.assertEqual((gate['sizeBasis'], gate['rungCodec']), ('predicted', HEVC))
        # The plan adopted no ratio: null there never meant "no rung was assessed".
        self.assertIsNone(gate['adoptedRatio'])
        self.assertEqual(tables['summary']['sizeGates'], {'keep_original': {'marginal': 1}, 'encode': {}})

    def test_a_size_keep_is_a_prediction_with_no_attempt_certificate_or_saving(self):
        tables = audit_pl_capture.analyze_tables(marginal_size_keep())
        (source,) = tables['sources']
        self.assertEqual(source['classification'], 'predicted_not_smaller')
        self.assertEqual((source['outputSize'], source['savedBytes'], source['pixelCertified']), (0, 0, False))
        self.assertIsNone(source['attemptsStarted'])
        self.assertEqual(tables['summary']['savedBytes'], 0)
        self.assertEqual([w['stage'] for w in tables['windows']], ['probe_rung'] * 3)
        # The rung that cleared the bar is still below the selection margin on its limiting gate.
        self.assertAlmostEqual(source['worstMargin'], 95.7019398749062 - 95.5)
        self.assertEqual(source['limitingGate'], 'mean')

    def test_a_clean_pass_kept_on_predicted_size_is_told_apart(self):
        # Secure Folder b287 job_31e5faeaf665: 0.90 passed on three of three windows.
        scores = ('99.5743957723856;98.54452288978827;96.99546166108556', '96.80814659903729;96.08925662988143;95.6022449387839',
                  '96.71406798517874;96.0073762166292;95.56249457322518')
        passed = dict(type='stage', stage='probe_rung', jobId='job_31e5faeaf665', sequence=39, attempt=3, attemptIndex=0, ratio=0.9,
                      outputMime=HEVC, actualEncoderNames=[QTI_HEVC], rungId='0.90@e929b108d9e5', configId='e929b108d9e5',
                      verdict='passed', plannedWindowIds=PLANNED, probeWindowIds=PLANNED, probeWindows=3,
                      probeMean=scores[0], probeP5=scores[1], probeMin=scores[2], probeFrames='36;36;36')
        records = [passed, size_gate(40, 'job_31e5faeaf665', 'gate_keep_original', '0.90@e929b108d9e5', 3,
                                     predictedBytes=126265012, sourceBytes=123783185),
                   job('job_31e5faeaf665', terminal='ALREADY_HIGHLY_OPTIMIZED', mime=None, sourceSize=123783185)]
        tables = audit_pl_capture.analyze_tables(records)
        (gate,) = tables['size_gates']
        self.assertEqual((gate['rungVerdict'], gate['clearedSelectionMargin']), ('passed', True))
        self.assertEqual((gate['measuredWindows'], gate['plannedWindows'], gate['scope']), (3, 3, 'complete'))
        self.assertEqual(tables['summary']['sizeGates']['keep_original'], {'passed': 1})

    def test_evidence_recorded_on_the_gate_wins_and_a_gate_with_neither_is_unknown(self):
        recorded = size_gate(5, 'job_new', 'gate_encode', '0.75@abc', 2, assessedRatio=0.75, assessedRungId='0.75@abc',
                             rungVerdict='marginal', measuredWindows=3, plannedWindows=3, ladderRole='primary',
                             predictedBytes=33366670, sourceBytes=44000000, provenRatio=0.75)
        bare = size_gate(6, 'job_old', 'gate_keep_original', '0.95@zzz', 4)
        # Same id and attempt but the other codec's rung: never the gate's own.
        other = rung(4, 0.95, AVC, QTI_AVC, J82_95, job='job_old', attempt=4, config='zzz', verdict='passed')
        tables = audit_pl_capture.analyze_tables(
            [recorded, job('job_new', terminal='TRANSCODED_SMALLER'), other, bare, job('job_old', terminal='ALREADY_HIGHLY_OPTIMIZED')])
        new, old = tables['size_gates']
        self.assertEqual((new['rungVerdictSource'], new['rungVerdict'], new['ladderRole'], new['adoptedRatio']),
                         ('recorded on the size gate', 'marginal', 'primary', 0.75))
        self.assertEqual((old['rungVerdictSource'], old['rungVerdict'], old['scope'], old['clearedSelectionMargin']),
                         ('not recorded', 'unknown', 'unknown', False))
        self.assertEqual(tables['summary']['sizeGates'], {'keep_original': {'unknown': 1}, 'encode': {'marginal': 1}})


if __name__ == '__main__': unittest.main()
