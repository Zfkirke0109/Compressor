import unittest
from calibration_evidence import replay_ledger, family_partition, partition_rows, upper_binomial, hard_windows

class EvidenceTest(unittest.TestCase):
    def event(self, event='APPLIED', kind='VISUAL_PASS', epoch='current'):
        return dict(ledgerEvent=event, evidenceId='a'*64, profileKey='p', proposedAfter='next', after='next',
                    observation=dict(kind=kind,policyEpoch=epoch,sourceId='b'*64))
    def test_reserved_is_uncertain_not_a_completed_observation(self):
        r=replay_ledger([self.event('RESERVED')], 'current')
        self.assertEqual(r['accepted'],[]);self.assertEqual(len(r['uncertain']),1)
    def test_an_incomplete_reset_is_uncertain_until_completion(self):
        events=[self.event('RESERVED'),self.event(),dict(ledgerEvent='RESET_STARTED')]
        r=replay_ledger(events, 'current')
        self.assertEqual(r['status'],'REQUIRES_REVIEW')
        self.assertEqual(len(r['uncertain']),1)
        completed=replay_ledger(events+[dict(ledgerEvent='RESET')], 'current')
        self.assertEqual(completed['status'],'REPLAYED')
        self.assertEqual(completed['finalProfiles'],{})
    def test_a_failed_reset_record_cannot_authorize_replay_or_clear_profiles(self):
        events=[self.event('RESERVED'),self.event(),dict(ledgerEvent='RESET_STARTED'),
                dict(ledgerEvent='RESET',durablyJournaled=False)]
        r=replay_ledger(events, 'current')
        self.assertEqual(r['status'],'REQUIRES_REVIEW')
        self.assertEqual(r['finalProfiles'],{'p':'next'})
        self.assertEqual(len(r['uncertain']),1)
        self.assertEqual(r['errors'][0]['reason'],'reset journal write failed')
    def test_duplicates_and_nonvisual_evidence_cannot_be_training_rows(self):
        r=replay_ledger([self.event('RESERVED'),self.event(),self.event()], 'current')
        self.assertEqual(len(r['accepted']),1);self.assertEqual(len(r['errors']),1)
        r=replay_ledger([self.event('RESERVED','PIPELINE'),self.event(kind='PIPELINE')], 'current')
        self.assertEqual(r['accepted'],[])
    def test_same_encoder_point_on_different_device_profiles_replays_separately(self):
        events=[]
        for profile in ('samsung|sm-s918u1|37', 'samsung|sm-s948u|37'):
            events.extend([dict(self.event('RESERVED'), profileKey=profile),
                           dict(self.event(), profileKey=profile)])
        r=replay_ledger(events, 'current')
        self.assertEqual(r['errors'], [])
        self.assertEqual(len(r['accepted']), 2)
        self.assertEqual(set(r['finalProfiles']), {'samsung|sm-s918u1|37', 'samsung|sm-s948u|37'})
    def test_another_devices_reservation_cannot_authorize_an_effect(self):
        r=replay_ledger([self.event('RESERVED'),dict(self.event(), profileKey='other-device')], 'current')
        self.assertTrue(r['errors'])
        self.assertEqual(r['accepted'], [])
    def test_epochs_are_quarantined_and_unreserved_effects_are_invalid(self):
        r=replay_ledger([self.event('RESERVED',epoch='old'),self.event(epoch='old')], 'current')
        self.assertEqual(len(r['quarantined']),1)
        self.assertTrue(replay_ledger([self.event()], 'current')['errors'])
    def test_family_split_requires_reviewed_identity(self):
        self.assertEqual(family_partition('a',{'a':'family','b':'family'}),family_partition('b',{'a':'family','b':'family'}))
        with self.assertRaises(ValueError): family_partition('c',{'a':'family'})
    def test_partition_preserves_device_measurements_without_source_family_leakage(self):
        row=dict(sourceId='source',evidenceId='point',profileKey='s23')
        rows=partition_rows([row,dict(row),dict(row,profileKey='s26')],{'source':'same-video-family'})
        self.assertEqual(len(rows),2)
        self.assertEqual({r['profileKey'] for r in rows},{'s23','s26'})
        self.assertEqual(len({r['split'] for r in rows}),1)
        self.assertNotIn('split',row)
    def test_non_significance_is_not_equivalence(self):
        self.assertGreater(upper_binomial(30,60),.60)
        self.assertLess(upper_binomial(60,120),.60)
        self.assertEqual(upper_binomial(5,5),1)
    def test_hard_windows_select_different_risks_and_never_read_candidate_scores(self):
        rows=[dict(startUs=i*10000000,endUs=i*10000000+3000000,motion=float(i),detail=float(9-i),bandingRisk=float(i==4)) for i in range(10)]
        result=hard_windows(rows)
        self.assertEqual({r['startUs'] for r in result[:3]},{90000000,0,40000000})
        self.assertEqual(result,hard_windows(list(reversed(rows))))

    def test_source_features_detect_motion_and_edges_without_candidate_inputs(self):
        from source_window_features import features
        a=bytes([0,0,0,0]);b=bytes([0,100,0,100])
        self.assertEqual(features(a,a,2)['motion'],0)
        self.assertEqual(features(a,b,2)['motion'],50)
        self.assertEqual(features(a,b,2)['detail'],100)

if __name__=='__main__': unittest.main()
