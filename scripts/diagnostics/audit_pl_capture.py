#!/usr/bin/env python3
"""Forensic tables from a named PL run. No calibration labels are inferred from policy decisions.

This describes recorded decisions, not a substitute implementation of production Kotlin.
Missing evidence stays null; repeated exports must agree byte for byte. Requires no dependencies.
"""
import argparse
import csv
import hashlib
import json
import math
import statistics
from collections import Counter, defaultdict
from pathlib import Path
from zipfile import ZipFile

GATES = {'mean': 95.5, 'p5': 91.0, 'min': 84.0}


def load_run(paths, batch):
    name = f'runs/{batch}/session.jsonl'
    payload = None
    provenance = []
    for path in paths:
        with ZipFile(path) as archive:
            if name not in archive.namelist(): continue
            data = archive.read(name)
            if payload is not None and data != payload:
                raise ValueError(f'conflicting copies of {batch}: {path}')
            payload = data
            provenance.append({'archive': Path(path).name, 'sessionSha256': hashlib.sha256(data).hexdigest()})
    if payload is None: raise ValueError(f'no such batch: {batch}')
    records = [json.loads(line) for line in payload.splitlines() if line.strip()]
    starts = [r for r in records if r.get('type') == 'session_start']
    if len(starts) != 1 or starts[0].get('mode') != 'Perceptually Lossless':
        raise ValueError('expected exactly one Perceptually Lossless session_start')
    if sum(r.get('type') == 'session_summary' for r in records) != 1:
        raise ValueError('a completed run is required; partial data cannot be a completed denominator')
    return records, provenance


def window_rows(stage):
    prefix = 'probe' if stage.get('stage') == 'probe_rung' else 'cert'
    keys = ['Mean', 'P5', 'Min', 'Frames']
    if not all(stage.get(prefix + k) for k in keys): return []
    series = {k: str(stage[prefix + k]).split(';') for k in keys}
    if len({len(s) for s in series.values()}) != 1: raise ValueError('inconsistent window series lengths')
    ids = str(stage.get(prefix + 'WindowIds') or '').split(';')
    rates = str(stage.get('rateFactors') or '').split(';') if stage.get('rateFactors') else []
    timings = str(stage.get(prefix + 'Timing') or '').split(';') if stage.get(prefix + 'Timing') else []
    if rates and len(rates) != len(series['Mean']): raise ValueError('rate evidence does not match scored windows')
    if timings and len(timings) != len(series['Mean']): raise ValueError('timing evidence does not match scored windows')
    rows = []
    for i in range(len(series['Mean'])):
        values = {k.lower(): float(series[k][i]) for k in keys}
        if not all(math.isfinite(x) for x in values.values()): raise ValueError('non-finite recorded score')
        row = {k: stage.get(k) for k in ['jobId', 'attempt', 'attemptIndex', 'sequence', 'stage', 'configId', 'rungId', 'ratio', 'requestedVideoBitrate']}
        row.update(values)
        factor = float(rates[i]) if rates else None
        if factor is not None and (not math.isfinite(factor) or factor <= 0): raise ValueError('invalid observed rate factor')
        requested = stage.get('requestedVideoBitrate')
        row.update(encoderOvershootFactor=factor,
                   observedSteadyVideoBitrateDerived=requested * factor if requested and factor else None,
                   observedRateBasis='recorded steady-window video rate factor times requested bitrate; not full-output bitrate' if factor else None,
                   timingRaw=timings[i] if timings else None,
                   actualEncoderNames=stage.get('actualEncoderNames'))
        row['windowId'] = ids[i] if i < len(ids) and ids[i] else None
        row['adequateFrames'] = row['frames'] >= 12
        row['margins'] = {k: row[k] - v for k, v in GATES.items()}
        row['limitingGate'] = min(row['margins'], key=row['margins'].get)
        row['barMargin'] = min(row['margins'].values())
        row['belowRecordedGate'] = row['adequateFrames'] and row['barMargin'] < 0
        row['humanLabel'] = None
        rows.append(row)
    return rows


def classify(job, stages, windows):
    if job.get('countsAsRealCompression') and job.get('pixelCertified') and 0 < job.get('outputSize', 0) < job.get('sourceSize', 0):
        return 'certified_smaller'
    if any(w['stage'] == 'certify' and w['belowRecordedGate'] for w in windows): return 'measured_certification_failure'
    if job.get('terminal') == 'SKIPPED_WOULD_DEGRADE' and any(w['stage'] == 'probe_rung' and w['belowRecordedGate'] for w in windows):
        return 'measured_probe_failure'
    why = ' '.join(str(job.get(k) or '') for k in ['decisionBasis', 'fallbackReason', 'media3Input', 'probeDetail', 'plannedDecisionReason']).lower()
    if 'source is damaged' in why: return 'damaged_source'
    if any(s.get('reasonCode') == 'structural_failed' for s in stages): return 'structural_failure'
    if 'not smaller' in why and job.get('candidateBytes'): return 'measured_not_smaller'
    if any(s.get('reasonCode') == 'gate_keep_original' for s in stages): return 'predicted_not_smaller'
    if job.get('hdr'): return 'hdr_unsupported'
    if job.get('w', 0) * job.get('h', 0) > 3840 * 2176: return 'geometry_unsupported'
    if 'downgrade' in why or job.get('sourceMime') in ['video/av01', 'video/x-vnd.on2.vp9']: return 'codec_search_excluded'
    if windows and any(not w['adequateFrames'] for w in windows): return 'insufficient_evidence'
    if 'learned' in why or 'profile class' in why: return 'learned_shortcut'
    if 'unavailable' in why or 'unmeasurable' in why: return 'pipeline_unavailable'
    if job.get('terminal') == 'ALREADY_HIGHLY_OPTIMIZED': return 'heuristic_keep_original'
    return 'unexplained'


def source_split(source_hash):
    """Proposed split only: a family map must override this when related edits have different hashes."""
    if not source_hash: return None
    bucket = int(hashlib.sha256(('pl-holdout-v1:' + source_hash).encode()).hexdigest()[:8], 16) % 100
    return 'calibration' if bucket < 60 else 'validation' if bucket < 80 else 'holdout'


def write_rows(path, rows):
    if path.suffix == '.jsonl':
        path.write_text(''.join(json.dumps(r, sort_keys=True, allow_nan=False) + '\n' for r in rows))
    else:
        keys = list(dict.fromkeys(k for r in rows for k in r))
        with path.open('w', newline='') as f:
            w = csv.DictWriter(f, fieldnames=keys)
            w.writeheader()
            w.writerows({k: json.dumps(v) if isinstance(v, (list, dict)) else v for k, v in r.items()} for r in rows)


def analyze(records):
    jobs = [r for r in records if r.get('type') == 'job']
    if len({r['jobId'] for r in jobs}) != len(jobs): raise ValueError('duplicate terminal job records')
    by_job = defaultdict(list)
    for r in records:
        if r.get('jobId'): by_job[r['jobId']].append(r)
    windows, sources, monotonic = [], [], []
    for j in jobs:
        events = by_job[j['jobId']]
        stages = [r for r in events if r.get('type') == 'stage']
        ws = [w for r in stages if r.get('stage') in ['probe_rung', 'certify'] for w in window_rows(r)]
        hashes = {r.get('phase'): r for r in events if r.get('type') == 'source_hash'}
        before, after = hashes.get('before', {}), hashes.get('after', {})
        identity = before.get('sha256')
        unchanged = bool(identity and identity == after.get('sha256') and before.get('bytes') == after.get('bytes') == j.get('sourceSize'))
        vb = max(0, j.get('sourceTotalBitrate', 0) - j.get('audioBitrate', 0))
        pixels_per_s = j.get('w', 0) * j.get('h', 0) * j.get('fps', 0)
        probes = [w for w in ws if w['stage'] == 'probe_rung' and w['adequateFrames']]
        highest = max((w['ratio'] for w in probes if w['ratio'] is not None), default=None)
        safest = [w for w in probes if w['ratio'] == highest]
        margins = {k: min((w[k] - gate for w in safest), default=None) for k, gate in GATES.items()}
        row = dict(j)
        row.update(sourceSha256=identity, sourceUnchanged=unchanged, sourceVideoBitrateEstimate=vb,
                   sourceBppEstimate=vb / pixels_per_s if pixels_per_s else None,
                   bitrateBasis='recorded total minus recorded audio; not packet-measured',
                   classification=classify(j, stages, ws), highestMeasuredRatio=highest,
                   highestRungMargins=margins, humanLabel=None, proposedSourceSplit=source_split(identity),
                   splitRequiresFamilyReview=True,
                   learningEvents=[r for r in events if r.get('type') == 'learned_state_update'],
                   encoderName=(j.get('encoderConfig') or '').partition('encoder=')[2].split(';')[0] or None,
                   colorMetadata=None, rawPerFrameEvidenceAvailable=False)
        valid = {k: v for k, v in margins.items() if v is not None}
        row['worstMargin'] = min(valid.values()) if valid else None
        row['limitingGate'] = min(valid, key=valid.get) if valid else None
        # Nonmonotonicity requires the SAME window. Different early-stop prefixes are not paired trials.
        groups = defaultdict(list)
        for w in probes:
            if w['windowId']: groups[(w['attempt'], w['windowId'])].append(w)
        for same in groups.values():
            ordered = sorted(same, key=lambda w: w['ratio'])
            for a, b in zip(ordered, ordered[1:]):
                if b['ratio'] <= a['ratio']: continue
                deltas = {k: b[k] - a[k] for k in GATES}
                if min(deltas.values()) < -0.5:
                    monotonic.append(dict(jobId=j['jobId'], windowId=a['windowId'], lowerRatio=a['ratio'],
                                          higherRatio=b['ratio'], deltas=deltas, repeatRequired=True))
        for w in ws:
            w.update(sourceSha256=identity, sourceSplit=row['proposedSourceSplit'],
                     sourceCodec=j.get('sourceMime'), sourceWidth=j.get('w'), sourceHeight=j.get('h'),
                     sourceFps=j.get('fps'), sourceBppEstimate=row['sourceBppEstimate'], sourceHdr=j.get('hdr'),
                     requestedOutputCodec=j.get('plannedOutputMime'), sourceAudioCodec=j.get('audioMime'),
                     sourceAudioBitrate=j.get('audioBitrate'))
        sources.append(row)
        windows.extend(ws)
    rejects = [s for s in sources if s['classification'] == 'measured_probe_failure']
    bins = [(0, .03), (.03, .04), (.04, .05), (.05, .06), (.06, .08), (.08, .12), (.12, .20), (.20, math.inf)]
    hist = []
    for lo, hi in bins:
        group = [s for s in sources if s['sourceBppEstimate'] is not None and lo <= s['sourceBppEstimate'] < hi]
        hist.append({'lowerInclusive': lo, 'upperExclusive': hi if math.isfinite(hi) else None,
                     'all': len(group), 'winners': sum(s['classification'] == 'certified_smaller' for s in group),
                     'measuredProbeRejects': sum(s['classification'] == 'measured_probe_failure' for s in group)})
    summary = {'jobs': len(sources), 'classifications': dict(Counter(s['classification'] for s in sources)),
               'terminals': dict(Counter(s['terminal'] for s in sources)),
               'savedBytes': sum(s['savedBytes'] for s in sources if s['classification'] == 'certified_smaller'),
               'originalsHashVerifiedUnchanged': sum(s['sourceUnchanged'] for s in sources),
               'uniqueFullSourceHashes': len({s['sourceSha256'] for s in sources if s['sourceSha256']}),
               'rejectSourceCodecs': dict(Counter(s['sourceMime'] for s in rejects)),
               'rejectRequestedOutputCodecs': dict(Counter(s['plannedOutputMime'] for s in rejects)),
               'limitingGate': dict(Counter(s['limitingGate'] for s in rejects)),
               'medianWorstMargin': statistics.median(s['worstMargin'] for s in rejects) if rejects else None,
               'withinPointsOfAllGates': {str(d): sum(-d <= s['worstMargin'] < 0 for s in rejects)
                                        for d in [.25, .5, 1, 2, 2.5, 5]},
               'bppBins': hist, 'nonMonotonicComparisons': len(monotonic),
               'calibrationStatus': 'observational policy outcomes, no independent human labels',
               'runIdentity': next((r for r in records if r.get('type') == 'run_identity'), None)}
    return sources, windows, monotonic, summary


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('archives', nargs='+', type=Path)
    ap.add_argument('--batch', required=True)
    ap.add_argument('--out', required=True, type=Path)
    a = ap.parse_args()
    records, provenance = load_run(a.archives, a.batch)
    sources, windows, monotonic, summary = analyze(records)
    summary.update(batchId=a.batch, provenance=provenance)
    a.out.mkdir(parents=True, exist_ok=True)
    for name, rows in [('sources', sources), ('windows', windows), ('nonmonotonic', monotonic),
                       ('events', records), ('attempts', [r for r in records if r.get('type') == 'stage'])]:
        write_rows(a.out / (name + '.jsonl'), rows)
        if name in ['sources', 'windows']: write_rows(a.out / (name + '.csv'), rows)
    (a.out / 'summary.json').write_text(json.dumps(summary, indent=2, allow_nan=False) + '\n')
    print(json.dumps({k: v for k, v in summary.items() if k != 'runIdentity'}, indent=2))


if __name__ == '__main__': main()
