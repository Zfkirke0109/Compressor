#!/usr/bin/env python3
"""Research-only evidence replay, reviewed-family partitioning and ABX confidence bounds.
Never changes Android gates, imports legacy aggregates as labels, or equates non-significance with equivalence.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path


def replay_ledger(events, epoch):
    reserved, applied, final_profiles = {}, set(), {}
    result = dict(accepted=[], uncertain=[], quarantined=[], errors=[], ignored=0, finalProfiles=final_profiles)
    for index, raw in enumerate(events):
        event = raw.get('fields', raw)
        kind, identity = event.get('ledgerEvent'), event.get('evidenceId')
        if kind == 'RESET':
            result['uncertain'].extend(reserved.values()); reserved.clear(); applied.clear(); final_profiles.clear()
            continue
        if kind in ('IGNORED', 'DUPLICATE'):
            result['ignored'] += 1; continue
        if kind not in ('RESERVED','APPLIED'): continue
        if not identity or event.get('durablyJournaled') is False:
            result['errors'].append(dict(index=index,reason='missing identity or journal write failed')); continue
        if kind == 'RESERVED':
            if identity in reserved or identity in applied:
                result['errors'].append(dict(index=index,reason='duplicate reservation',evidenceId=identity))
            else: reserved[identity] = event
            continue
        reservation = reserved.pop(identity, None)
        if reservation is None or identity in applied:
            result['errors'].append(dict(index=index,reason='unreserved or repeated effect',evidenceId=identity)); continue
        applied.add(identity)
        observation = event.get('observation') or {}
        if observation != reservation.get('observation') or event.get('after') != reservation.get('proposedAfter'):
            result['errors'].append(dict(index=index,reason='reservation/effect mismatch',evidenceId=identity)); continue
        if observation.get('kind') not in ('VISUAL_PASS','VISUAL_REJECT','SIZE'):
            result['errors'].append(dict(index=index,reason='non-training evidence changed aggregate',evidenceId=identity)); continue
        row=dict(evidenceId=identity,**observation)
        if observation.get('policyEpoch') != epoch: result['quarantined'].append(row)
        else:
            result['accepted'].append(row)
            final_profiles[event['profileKey']] = event['after']
    result['uncertain'].extend(reserved.values())
    result['status'] = 'REQUIRES_REVIEW' if result['errors'] or result['uncertain'] else 'REPLAYED'
    result['legacyAggregateIsHumanEvidence'] = False
    return result


def family_partition(source_id, reviewed_families):
    family = reviewed_families.get(source_id)
    if not isinstance(family,str) or not family.strip(): raise ValueError('source needs a reviewed video-family identity')
    bucket=int(hashlib.sha256(('compressor-family-split-v1:'+family).encode()).hexdigest()[:16],16)%10
    return 'calibration' if bucket<6 else 'validation' if bucket<8 else 'holdout'


def upper_binomial(correct, total, alpha=.05):
    """One-sided exact Clopper-Pearson bound; requires pre-specified independent Bernoulli trials.
    Repeated trials clustered within viewers/videos need a hierarchical/cluster analysis instead.
    """
    if not (0<=correct<=total and 0<total<=10000 and 0<alpha<1): raise ValueError('invalid binomial design')
    if correct==total: return 1.0
    def cdf(p):
        terms=[math.lgamma(total+1)-math.lgamma(i+1)-math.lgamma(total-i+1)+i*math.log(p)+(total-i)*math.log1p(-p) for i in range(correct+1)]
        peak=max(terms)
        return math.exp(peak)*sum(math.exp(v-peak) for v in terms)
    low,high=0.0,1.0
    for _ in range(70):
        middle=(low+high)/2
        if cdf(middle)>alpha: low=middle
        else: high=middle
    return (low+high)/2


def hard_windows(rows):
    """Source-only pilot selector. Scores below are source features, NEVER candidate quality.
    Chooses motion, detail, smooth-gradient risk extremes and a representative median; no production gate.
    """
    rows=sorted(rows,key=lambda r:r['startUs'])
    for row in rows:
        if row['endUs']<=row['startUs'] or any(not math.isfinite(row[k]) for k in ('motion','detail','bandingRisk')):
            raise ValueError('invalid source features')
    result=[]
    def add(row,basis):
        if not any(row['startUs']<r['endUs'] and row['endUs']>r['startUs'] for r in result):
            result.append(dict(row,selectionBasis=basis))
    for key in ('motion','detail','bandingRisk'):
        for row in sorted(rows,key=lambda r:(-r[key],r['startUs'])):
            old=len(result);add(row,key)
            if len(result)>old: break
    if rows:
        median=sorted(rows,key=lambda r:(r['motion']+r['detail'],r['startUs']))[len(rows)//2]
        add(median,'representative-median')
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__);s=p.add_subparsers(dest='command',required=True)
    a=s.add_parser('ledger');a.add_argument('jsonl',type=Path);a.add_argument('--epoch',required=True)
    a=s.add_parser('partition');a.add_argument('rows',type=Path);a.add_argument('--families',required=True,type=Path)
    a=s.add_parser('abx-bound');a.add_argument('correct',type=int);a.add_argument('total',type=int);a.add_argument('--alpha',type=float,default=.05)
    a=s.add_parser('windows');a.add_argument('features',type=Path)
    args=p.parse_args()
    if args.command=='ledger': out=replay_ledger([json.loads(l) for l in args.jsonl.read_text().splitlines() if l.strip()],args.epoch)
    elif args.command=='partition':
        families=json.loads(args.families.read_text());out=[];seen=set()
        for line in args.rows.read_text().splitlines():
            row=json.loads(line);source=row.get('sourceSha256') or row.get('sourceId');identity=row.get('evidenceId') or (source,row.get('attempt'),row.get('windowId'),row.get('policyEpoch'))
            if identity in seen:continue
            seen.add(identity);row['reviewedFamilyId']=families.get(source);row['split']=family_partition(source,families);out.append(row)
    elif args.command=='abx-bound':out=dict(upperCorrectProbability=upper_binomial(args.correct,args.total,args.alpha),alpha=args.alpha,assumption='pre-specified independent trials; clustered repeats need separate analysis',acceptancePolicyChanged=False)
    else:out=hard_windows(json.loads(args.features.read_text()))
    print(json.dumps(out,indent=2,allow_nan=False))

if __name__=='__main__':main()
