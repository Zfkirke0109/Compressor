#!/usr/bin/env python3
"""Forensic tables from a named PL run. No calibration labels are inferred from policy decisions.

This describes recorded decisions, not a substitute implementation of production Kotlin.
Missing evidence stays null; repeated exports must agree byte for byte. Requires no dependencies.

A job can hold more than one probe ladder: Auto measures HEVC and, when that leaves no smaller
plan, an AVC alternate, over the same attempt and the same planned windows. Rungs are compared
only inside one ladder (attempt, attempt index, rung codec, actual encoder, request shape) and on
the exact same window. Until November 2026 the join ordered every rung of an attempt by ratio
alone, so an AVC 0.95 rung was read as the step below an HEVC 0.97 rung, and each window was
labelled with the job's planned codec whatever its rung had encoded.
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


def encoder_names(value):
    """actualEncoderNames as a list of names, or None when none was recorded.

    v1.6.271 wrote Kotlin's List.toString() ("[a, b]"); later builds write a JSON array.
    """
    if value is None: return None
    if isinstance(value, (list, tuple)): names = [str(v).strip() for v in value]
    else: names = [part.strip().strip('"') for part in str(value).strip().strip('[]').split(',')]
    return [n for n in names if n] or None


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
    # What the RUNG encoded with, from the rung's own record. A certification window has no codec
    # field; analyze_tables joins it to its rung by configId.
    probe = prefix == 'probe'
    names = encoder_names(stage.get('actualEncoderNames'))
    codec = stage.get('outputMime') if probe else None
    planned = [p for p in str(stage.get('plannedWindowIds') or '').split(';') if p]
    lineage = dict(rungCodec=codec, encoderNames=names, encoder=names[0] if names and len(names) == 1 else None,
                   rungCodecBasis=('recorded on the rung' if codec else 'not recorded (legacy export)') if probe else None,
                   rungIndex=stage.get('rungIndex'), rungVerdict=stage.get('verdict') if probe else None,
                   rungReason=stage.get('rungReason'), ladderRole=stage.get('ladderRole'),
                   encodeShapeId=stage.get('encodeShapeId'), plannedWindowCount=len(planned) or None,
                   scoredWindowCount=len(series['Mean']))
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
        row.update(lineage)
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


POINTS = [.25, .5, 1, 2, 2.5, 5]
LADDER_PARTS = ['attempt', 'attemptIndex', 'codec', 'encoder', 'encodeShape']


def ladder_identity(w):
    """The search a probe rung belongs to. Two rungs are steps of one ladder only when all of it is equal."""
    return (w['attempt'], w['attemptIndex'], w['rungCodec'], tuple(w['encoderNames'] or ()), w['encodeShapeId'])


def separation(a, b):
    """Why two rungs are not one ladder: the first identity part that differs, or None when they are."""
    for part, x, y in zip(LADDER_PARTS, ladder_identity(a), ladder_identity(b)):
        if x != y: return 'cross_' + part
    return None


def ladder_lineage(identity, alternate_search, any_recorded):
    """(lineage, why not comparable). One actual encoder names a ladder ('recorded'). An export from
    before either field existed also predates the alternate search, so its rungs were one ladder
    ('legacy_unrecorded', and said so on every row). Anything else is not compared."""
    codec, encoders = identity[2], identity[3]
    if len(encoders) == 1: return 'recorded', None
    if len(encoders) > 1: return None, 'ambiguous_encoder'
    if codec is None and alternate_search is None and not any_recorded: return 'legacy_unrecorded', None
    return None, 'encoder_not_recorded'


def ladder_role(rows, codec, alternate_search, ladder_count):
    """'primary' or 'alternate': the rung's own field when a build wrote one, else the job's alternate-search record."""
    explicit = {w['ladderRole'] for w in rows}
    if len(explicit) == 1 and None not in explicit: return explicit.pop()
    if alternate_search is not None and codec is not None:
        if codec == alternate_search.get('primaryMime'): return 'primary'
        if codec == alternate_search.get('alternateMime'): return 'alternate'
    return 'primary' if alternate_search is None and ladder_count == 1 else 'unknown'


def order(w):
    return w['sequence'] if w['sequence'] is not None else -1


def ladder_summary(job_id, identity, rows, role, lineage, reason, classification):
    """One ladder's highest adequately sampled rung against the gates, and how much of its plan that rung measured."""
    attempt, attempt_index, codec, encoders, shape = identity
    adequate = [w for w in rows if w['adequateFrames'] and w['ratio'] is not None]
    highest = max((w['ratio'] for w in adequate), default=None)
    margins = {k: min((w[k] - gate for w in adequate if w['ratio'] == highest), default=None) for k, gate in GATES.items()}
    valid = {k: v for k, v in margins.items() if v is not None}
    top = sorted((w for w in rows if highest is not None and w['ratio'] == highest), key=order)
    last = top[-1] if top else None
    if not top: scope = None
    elif any(w['plannedWindowCount'] is None for w in top): scope = 'unknown'
    elif any(w['scoredWindowCount'] < w['plannedWindowCount'] for w in top): scope = 'early_exit'
    else: scope = 'complete' if all(w['adequateFrames'] for w in top) else 'insufficient_frames'
    return dict(jobId=job_id, attempt=attempt, attemptIndex=attempt_index, rungCodec=codec,
                encoderNames=list(encoders) or None, encoder=encoders[0] if len(encoders) == 1 else None,
                encodeShapeId=shape, ladderRole=role, lineage=lineage, notComparableBecause=reason,
                jobClassification=classification, firstSequence=min(map(order, rows)),
                rungs=len({order(w) for w in rows}), scoredWindows=len(rows),
                inadequateWindows=sum(not w['adequateFrames'] for w in rows),
                ratios=sorted({w['ratio'] for w in rows if w['ratio'] is not None}),
                rungIds=list(dict.fromkeys(w['rungId'] for w in sorted(rows, key=order))),
                highestMeasuredRatio=highest, highestRungMargins=margins,
                worstMargin=min(valid.values()) if valid else None,
                limitingGate=min(valid, key=valid.get) if valid else None,
                highestRungVerdict=last['rungVerdict'] if last else None, highestRungScope=scope,
                highestRungScoredWindows=last['scoredWindowCount'] if last else None,
                highestRungPlannedWindows=last['plannedWindowCount'] if last else None, humanLabel=None)


def ladder_comparisons(rows):
    """Within ONE ladder: (lower, higher, a level was repeated) for every pair of observations on
    adjacent ratios of the same window, and (first, second) for a ratio measured more than once.
    Adequately sampled windows only; different early-stop prefixes are not paired trials."""
    by_window = defaultdict(list)
    for w in rows:
        if w['adequateFrames'] and w['windowId'] and w['ratio'] is not None: by_window[w['windowId']].append(w)
    steps, repeats = [], []
    for same in by_window.values():
        levels = defaultdict(list)
        for w in sorted(same, key=lambda w: (w['ratio'], order(w))): levels[w['ratio']].append(w)
        ratios = sorted(levels)
        for ratio in ratios: repeats.extend(zip(levels[ratio], levels[ratio][1:]))
        for lo, hi in zip(ratios, ratios[1:]):
            repeated = len(levels[lo]) > 1 or len(levels[hi]) > 1
            steps.extend((a, b, repeated) for a in levels[lo] for b in levels[hi])
    return steps, repeats


def pooled_pairs(probes):
    """Adjacent pairs as the join formed them before ladders were separated: per attempt and window, by ratio alone."""
    groups = defaultdict(list)
    for w in probes:
        if w['windowId'] and w['ratio'] is not None: groups[(w['attempt'], w['windowId'])].append(w)
    for same in groups.values():
        ordered = sorted(same, key=lambda w: w['ratio'])
        for a, b in zip(ordered, ordered[1:]):
            if b['ratio'] > a['ratio']: yield a, b


def pair_row(job_id, a, b, **extra):
    """Two rungs on one window, each side keeping the identity of its own rung."""
    row = dict(jobId=job_id, windowId=a['windowId'], lowerRatio=a['ratio'], higherRatio=b['ratio'],
               deltas={k: b[k] - a[k] for k in GATES}, attempt=a['attempt'], attemptIndex=a['attemptIndex'],
               lowerSequence=a['sequence'], higherSequence=b['sequence'], lowerRungId=a['rungId'], higherRungId=b['rungId'],
               lowerConfigId=a['configId'], higherConfigId=b['configId'],
               lowerRequestedVideoBitrate=a['requestedVideoBitrate'], higherRequestedVideoBitrate=b['requestedVideoBitrate'])
    row.update(extra)
    return row


def sides(a, b):
    return dict(lowerRungCodec=a['rungCodec'], higherRungCodec=b['rungCodec'], lowerEncoder=a['encoder'],
                higherEncoder=b['encoder'], lowerEncoderNames=a['encoderNames'], higherEncoderNames=b['encoderNames'])


def drops(a, b):
    return min(b[k] - a[k] for k in GATES) < -0.5


def codec_key(codec):
    return codec or 'unrecorded'


def analyze_tables(records):
    """Every table of the audit, by name. [analyze] returns the four the earlier callers unpack."""
    jobs = [r for r in records if r.get('type') == 'job']
    if len({r['jobId'] for r in jobs}) != len(jobs): raise ValueError('duplicate terminal job records')
    by_job = defaultdict(list)
    for r in records:
        if r.get('jobId'): by_job[r['jobId']].append(r)
    windows, sources, monotonic, ladders, excluded, repeats = [], [], [], [], [], []
    pooled = dict(nonMonotonicComparisons=0, crossLadderPairs=0, crossLadderPairsCountedNonMonotonic=0)
    per_codec = {}
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
        classification = classify(j, stages, ws)
        for r in stages:
            if r.get('stage') != 'probe_rung': continue
            entry = per_codec.setdefault(codec_key(r.get('outputMime')), dict(rungs=0, rungMs=0, rungVerdicts=Counter()))
            entry['rungs'] += 1
            entry['rungMs'] += r.get('rungMs') or 0
            entry['rungVerdicts'][r.get('verdict') or r.get('reasonCode') or 'unknown'] += 1
        # One ladder per search. The alternate-search record names which codec was the primary.
        alternate_search = next((r for r in stages if r.get('reasonCode') == 'alternate_codec_search'), None)
        probe_rows = [w for w in ws if w['stage'] == 'probe_rung']
        any_recorded = any(w['rungCodec'] or w['encoderNames'] for w in probe_rows)
        by_ladder = defaultdict(list)
        for w in probe_rows: by_ladder[ladder_identity(w)].append(w)
        job_ladders = []
        for ladder, rows in by_ladder.items():
            lineage, reason = ladder_lineage(ladder, alternate_search, any_recorded)
            role = ladder_role(rows, ladder[2], alternate_search, len(by_ladder))
            for w in rows: w.update(ladderRole=role, ladderLineage=lineage)
            job_ladders.append(ladder_summary(j['jobId'], ladder, rows, role, lineage, reason, classification))
            steps, again = ladder_comparisons(rows)
            same = dict(rungCodec=ladder[2], encoder=rows[0]['encoder'], encoderNames=rows[0]['encoderNames'],
                        encodeShapeId=ladder[4], ladderRole=role, lineage=lineage)
            for a, b in again:
                repeats.append(dict(same, jobId=j['jobId'], windowId=a['windowId'], ratio=a['ratio'], attempt=a['attempt'],
                                    attemptIndex=a['attemptIndex'], firstSequence=a['sequence'], secondSequence=b['sequence'],
                                    firstRungId=a['rungId'], secondRungId=b['rungId'], sameConfig=a['configId'] == b['configId'],
                                    deltas={k: b[k] - a[k] for k in GATES}))
            for a, b, repeated in steps:
                if not drops(a, b): continue
                if lineage: monotonic.append(pair_row(j['jobId'], a, b, repeatedRatio=repeated, repeatRequired=True, **same))
                else: excluded.append(pair_row(j['jobId'], a, b, reason=reason, wouldHaveCountedAsNonMonotonic=True, **sides(a, b)))
        job_ladders.sort(key=lambda l: l['firstSequence'])
        ladders.extend(job_ladders)
        # What the pooled join compared, so earlier counts can be reconciled with the ladders above.
        for a, b in pooled_pairs([w for w in probe_rows if w['adequateFrames']]):
            pooled['nonMonotonicComparisons'] += drops(a, b)
            reason = separation(a, b)
            if not reason: continue
            pooled['crossLadderPairs'] += 1
            pooled['crossLadderPairsCountedNonMonotonic'] += drops(a, b)
            excluded.append(pair_row(j['jobId'], a, b, reason=reason, wouldHaveCountedAsNonMonotonic=drops(a, b), **sides(a, b)))
        # A certification window is of the encode made from one rung: it takes that rung's codec.
        rung_codecs = defaultdict(set)
        for w in probe_rows:
            if w['configId']: rung_codecs[w['configId']].add(w['rungCodec'])
        encodes = {(r.get('attempt'), r.get('attemptIndex')): r.get('encoderName') for r in stages
                   if r.get('reasonCode') == 'encode_completed' and r.get('encoderName')}
        for w in ws:
            if w['stage'] != 'certify': continue
            found = rung_codecs.get(w['configId'], set())
            w['rungCodec'] = next(iter(found)) if len(found) == 1 else None
            w['rungCodecBasis'] = ('joined by configId to the probe rung' if w['rungCodec']
                                   else 'no probe rung recorded a codec for this configId')
            name = encodes.get((w['attempt'], w['attemptIndex']))
            if name: w.update(encoderNames=[name], encoder=name)
        # The job's own margin is the ladder its plan came from; `ladders` holds every one of them.
        selected = alternate_search.get('selectedMime') if alternate_search else None
        chosen = (next((l for l in job_ladders if selected and l['rungCodec'] == selected), None)
                  or next((l for l in job_ladders if l['ladderRole'] == 'primary'), None)
                  or (job_ladders[0] if job_ladders else None))
        row = dict(j)
        row.update(sourceSha256=identity, sourceUnchanged=unchanged, sourceVideoBitrateEstimate=vb,
                   sourceBppEstimate=vb / pixels_per_s if pixels_per_s else None,
                   bitrateBasis='recorded total minus recorded audio; not packet-measured',
                   classification=classification,
                   highestMeasuredRatio=chosen['highestMeasuredRatio'] if chosen else None,
                   highestRungMargins=chosen['highestRungMargins'] if chosen else {k: None for k in GATES},
                   worstMargin=chosen['worstMargin'] if chosen else None,
                   limitingGate=chosen['limitingGate'] if chosen else None,
                   marginLadderCodec=chosen['rungCodec'] if chosen else None,
                   marginLadderRole=chosen['ladderRole'] if chosen else None,
                   measuredLadderCodecs=list(dict.fromkeys(l['rungCodec'] for l in job_ladders)),
                   ladders=job_ladders, humanLabel=None, proposedSourceSplit=source_split(identity),
                   splitRequiresFamilyReview=True,
                   learningEvents=[r for r in events if r.get('type') == 'learned_state_update'],
                   encoderName=(j.get('encoderConfig') or '').partition('encoder=')[2].split(';')[0] or None,
                   colorMetadata=None, rawPerFrameEvidenceAvailable=False)
        for w in ws:
            # requestedOutputCodec is what THIS window's rung encoded with. It used to repeat the
            # job's planned codec on every window, AVC rungs included; that is jobPlannedOutputCodec.
            w.update(sourceSha256=identity, sourceSplit=row['proposedSourceSplit'],
                     sourceCodec=j.get('sourceMime'), sourceWidth=j.get('w'), sourceHeight=j.get('h'),
                     sourceFps=j.get('fps'), sourceBppEstimate=row['sourceBppEstimate'], sourceHdr=j.get('hdr'),
                     requestedOutputCodec=w['rungCodec'], jobPlannedOutputCodec=j.get('plannedOutputMime'),
                     sourceAudioCodec=j.get('audioMime'), sourceAudioBitrate=j.get('audioBitrate'))
        sources.append(row)
        windows.extend(ws)
    rejects = [s for s in sources if s['classification'] == 'measured_probe_failure']
    measured = [s for s in rejects if s['worstMargin'] is not None]
    bins = [(0, .03), (.03, .04), (.04, .05), (.05, .06), (.06, .08), (.08, .12), (.12, .20), (.20, math.inf)]
    hist = []
    for lo, hi in bins:
        group = [s for s in sources if s['sourceBppEstimate'] is not None and lo <= s['sourceBppEstimate'] < hi]
        hist.append({'lowerInclusive': lo, 'upperExclusive': hi if math.isfinite(hi) else None,
                     'all': len(group), 'winners': sum(s['classification'] == 'certified_smaller' for s in group),
                     'measuredProbeRejects': sum(s['classification'] == 'measured_probe_failure' for s in group)})
    for codec, entry in per_codec.items():
        group = [l for l in ladders if codec_key(l['rungCodec']) == codec]
        failed = [l for l in group if l['jobClassification'] == 'measured_probe_failure' and l['worstMargin'] is not None]
        entry.update(rungVerdicts=dict(entry['rungVerdicts']), ladders=len(group), jobs=len({l['jobId'] for l in group}),
                     rejectLadders=len(failed), limitingGate=dict(Counter(l['limitingGate'] for l in failed)),
                     medianWorstMargin=statistics.median(l['worstMargin'] for l in failed) if failed else None,
                     withinPointsOfAllGates={str(d): sum(-d <= l['worstMargin'] < 0 for l in failed) for d in POINTS},
                     highestRungScope=dict(Counter(str(l['highestRungScope']) for l in failed)),
                     nonMonotonicComparisons=sum(codec_key(m['rungCodec']) == codec for m in monotonic))
    summary = {'jobs': len(sources), 'classifications': dict(Counter(s['classification'] for s in sources)),
               'terminals': dict(Counter(s['terminal'] for s in sources)),
               'savedBytes': sum(s['savedBytes'] for s in sources if s['classification'] == 'certified_smaller'),
               'originalsHashVerifiedUnchanged': sum(s['sourceUnchanged'] for s in sources),
               'uniqueFullSourceHashes': len({s['sourceSha256'] for s in sources if s['sourceSha256']}),
               'rejectSourceCodecs': dict(Counter(s['sourceMime'] for s in rejects)),
               'rejectRequestedOutputCodecs': dict(Counter(s['plannedOutputMime'] for s in rejects)),
               'rejectMeasuredLadderCodecs': dict(Counter('+'.join(map(codec_key, s['measuredLadderCodecs'])) or 'none'
                                                          for s in rejects)),
               'marginBasis': "each job's selected ladder (the alternate when its plan was taken, else the primary) at its "
                              'highest adequately sampled ratio; perCodec covers every ladder of the rejected jobs. A rung '
                              'that stopped at its first failing window (highestRungScope early_exit) scored only the '
                              'windows before it, so its margin is an upper bound on the margin over the whole plan',
               'limitingGate': dict(Counter(s['limitingGate'] for s in measured)),
               'medianWorstMargin': statistics.median(s['worstMargin'] for s in measured) if measured else None,
               'withinPointsOfAllGates': {str(d): sum(-d <= s['worstMargin'] < 0 for s in measured) for d in POINTS},
               'rejectsWithoutMeasuredMargin': len(rejects) - len(measured),
               'perCodec': per_codec, 'bppBins': hist,
               'nonMonotonicComparisons': len(monotonic),
               'nonMonotonicBasis': 'same ladder (attempt, attempt index, rung codec, actual encoder, request shape) and same window',
               'nonMonotonicByCodec': dict(Counter(codec_key(m['rungCodec']) for m in monotonic)),
               'nonMonotonicByLineage': dict(Counter(m['lineage'] for m in monotonic)),
               'nonMonotonicDistinctSteps': len({(m['jobId'], m['attempt'], m['attemptIndex'], m['rungCodec'], m['encoder'],
                                                  m['encodeShapeId'], m['windowId'], m['lowerRatio'], m['higherRatio'])
                                                 for m in monotonic}),
               'repeatObservations': len(repeats),
               'excludedComparisons': dict(Counter(e['reason'] for e in excluded)),
               'legacyPooledJoin': pooled,
               'calibrationStatus': 'observational policy outcomes, no independent human labels',
               'runIdentity': next((r for r in records if r.get('type') == 'run_identity'), None)}
    return dict(sources=sources, windows=windows, nonmonotonic=monotonic, ladders=ladders, excluded=excluded,
                repeats=repeats, summary=summary)


def analyze(records):
    tables = analyze_tables(records)
    return tables['sources'], tables['windows'], tables['nonmonotonic'], tables['summary']


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('archives', nargs='+', type=Path)
    ap.add_argument('--batch', required=True)
    ap.add_argument('--out', required=True, type=Path)
    a = ap.parse_args()
    records, provenance = load_run(a.archives, a.batch)
    tables = analyze_tables(records)
    summary = tables['summary']
    summary.update(batchId=a.batch, provenance=provenance)
    a.out.mkdir(parents=True, exist_ok=True)
    for name, rows in [('sources', tables['sources']), ('windows', tables['windows']), ('nonmonotonic', tables['nonmonotonic']),
                       ('ladders', tables['ladders']), ('excluded_comparisons', tables['excluded']), ('repeats', tables['repeats']),
                       ('events', records), ('attempts', [r for r in records if r.get('type') == 'stage'])]:
        write_rows(a.out / (name + '.jsonl'), rows)
        if name in ['sources', 'windows', 'ladders']: write_rows(a.out / (name + '.csv'), rows)
    (a.out / 'summary.json').write_text(json.dumps(summary, indent=2, allow_nan=False) + '\n')
    print(json.dumps({k: v for k, v in summary.items() if k != 'runIdentity'}, indent=2))


if __name__ == '__main__': main()
