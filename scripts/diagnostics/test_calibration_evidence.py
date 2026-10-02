import unittest
from calibration_evidence import replay_ledger, family_partition, upper_binomial, hard_windows

class EvidenceTest(unittest.TestCase):
    def event(self, event='APPLIED', kind='VISUAL_PASS', epoch='current'):
        return dict(ledgerEvent=event, evidenceId='a'*64, profileKey='p', proposedAfter='next', after='next',
                    observation=dict(kind=kind,policyEpoch=epoch,sourceId='b'*64))
    def test_reserved_is_uncertain_not_a_completed_observation(self):
        r=replay_ledger([self.event('RESERVED')], 'current')
        self.assertEqual(r['accepted'],[]);self.assertEqual(len(r['uncertain']),1)
    def test_duplicates_and_nonvisual_evidence_cannot_be_training_rows(self):
        r=replay_ledger([self.event('RESERVED'),self.event(),self.event()], 'current')
        self.assertEqual(len(r['accepted']),1);self.assertEqual(len(r['errors']),1)
        r=replay_ledger([self.event('RESERVED','PIPELINE'),self.event(kind='PIPELINE')], 'current')
        self.assertEqual(r['accepted'],[])
    def test_epochs_are_quarantined_and_unreserved_effects_are_invalid(self):
        r=replay_ledger([self.event('RESERVED',epoch='old'),self.event(epoch='old')], 'current')
        self.assertEqual(len(r['quarantined']),1)
        self.assertTrue(replay_ledger([self.event()], 'current')['errors'])
    def test_family_split_requires_reviewed_identity(self):
        self.assertEqual(family_partition('a',{'a':'family','b':'family'}),family_partition('b',{'a':'family','b':'family'}))
        with self.assertRaises(ValueError): family_partition('c',{'a':'family'})
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
