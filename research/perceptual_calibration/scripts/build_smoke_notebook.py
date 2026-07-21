#!/usr/bin/env python3
"""Generate the self-contained Colab smoke-test notebook for build_corpus.py.

Why this exists: build_corpus.py's real work happens through ffmpeg/ffprobe/libvmaf,
and those paths cannot be exercised by unit tests. The first time they were run for
real (2026-07-21) they surfaced three bugs that static review had missed - including
one that recorded failed measurements as quality-negatives. The generated notebook is
how that gets re-checked whenever the tool changes.

The notebook is emitted self-contained: build_corpus.py and the repo's VMAF harness
measure_quality.py are embedded as base64, so it runs on a fresh Colab with nothing to
upload. It acquires an ffmpeg with libx264+libx265+libvmaf (system, else a static
build), generates two synthetic SDR masters, runs plan -> build -> label, and asserts:

  * the build path produced clips and measured actual_bpp for all of them,
  * the label path produced at least one real verdict,
  * the measure_quality coupling yields parseable VMAF numbers.

Usage (from anywhere):
    python build_smoke_notebook.py [--out Compressor_Corpus_SmokeTest.ipynb]

Then upload the .ipynb to Colab and Runtime -> Run all. A PASS means the encode command
construction and the measure_quality coupling are sound. It changes no policy.
"""
from __future__ import annotations

import argparse
import base64
import json
from pathlib import Path

HERE = Path(__file__).resolve()


def default_measure_quality_path() -> str:
    """Best-effort path to the repo's VMAF harness; never raises.

    Mirrors build_corpus.default_measure_quality_path - a bare parents[3] lookup blows
    up whenever this script is copied out of its in-repo location.
    """
    parents = HERE.parents
    if len(parents) > 3:  # <repo>/research/perceptual_calibration/scripts/this.py
        candidate = parents[3] / "scripts" / "diagnostics" / "measure_quality.py"
        if candidate.exists():
            return str(candidate)
    sibling = HERE.parent / "measure_quality.py"
    return str(sibling) if sibling.exists() else "measure_quality.py"


def b64_lines(data: bytes, var: str) -> list[str]:
    payload = base64.b64encode(data).decode("ascii")
    chunk = 20000
    parts = [payload[i:i + chunk] for i in range(0, len(payload), chunk)]
    return [f"{var} = (\n"] + [f"    '{p}'\n" for p in parts] + [")\n"]


def code(lines: list[str]) -> dict:
    return {"cell_type": "code", "metadata": {}, "execution_count": None, "outputs": [], "source": lines}


def md(text: str) -> dict:
    return {"cell_type": "markdown", "metadata": {}, "source": text.splitlines(keepends=True)}


INTRO = """# Corpus-builder smoke test (real ffmpeg + libvmaf)

Exercises `research/perceptual_calibration/scripts/build_corpus.py` end to end on a
genuine ffmpeg/libvmaf toolchain - the paths that could not run on the authoring
machine (no ffmpeg there). Both `build_corpus.py` and the repo's VMAF harness
`measure_quality.py` are embedded below; nothing to upload.

**Run:** Runtime -> Run all. It will:
1. get an ffmpeg with libx264 + libx265 + libvmaf (system, else a static build),
2. generate two short synthetic SDR masters (testsrc2, mandelbrot),
3. `plan` -> `build` (3-rung bpp ladder x 2 codecs) -> `label` (offline VMAF),
4. assert the build and label paths actually worked, and print a PASS/FAIL summary.

A green run means the encode command construction and the measure_quality coupling
are sound. It does NOT change any policy - it validates the tool that gathers data."""


def build_notebook(build_corpus: Path, measure_quality: Path) -> dict:
    cells = [md(INTRO)]

    cells.append(code([
        "# 1) Ensure an ffmpeg with libx264 + libx265 + libvmaf\n",
        "import subprocess, os\n",
        "def _out(args):\n",
        "    return subprocess.run(args, capture_output=True, text=True).stdout\n",
        "def has_vmaf(ff):\n",
        "    try: return 'libvmaf' in _out([ff, '-hide_banner', '-filters'])\n",
        "    except Exception: return False\n",
        "FF, FP = 'ffmpeg', 'ffprobe'\n",
        "if not has_vmaf(FF):\n",
        "    print('System ffmpeg lacks libvmaf; fetching a static GPL build (~80 MB)...')\n",
        "    url = 'https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-linux64-gpl.tar.xz'\n",
        "    subprocess.run(['wget', '-q', url, '-O', '/tmp/ff.tar.xz'], check=True)\n",
        "    os.makedirs('/tmp/ffbin', exist_ok=True)\n",
        "    subprocess.run(['tar', '-xf', '/tmp/ff.tar.xz', '-C', '/tmp/ffbin', '--strip-components=1'], check=True)\n",
        "    FF, FP = '/tmp/ffbin/bin/ffmpeg', '/tmp/ffbin/bin/ffprobe'\n",
        "assert has_vmaf(FF), 'no libvmaf-capable ffmpeg available'\n",
        "enc = _out([FF, '-hide_banner', '-encoders'])\n",
        "for e in ('libx264', 'libx265'):\n",
        "    assert e in enc, f'ffmpeg missing encoder {e}'\n",
        "print('ffmpeg OK ->', FF)\n",
        "print(_out([FF, '-version']).splitlines()[0])\n",
    ]))

    cells.append(code(["# 2) Embedded build_corpus.py\n"] + b64_lines(build_corpus.read_bytes(), "_BUILD_CORPUS_B64")))
    cells.append(code(["# 3) Embedded measure_quality.py (repo VMAF harness)\n"]
                      + b64_lines(measure_quality.read_bytes(), "_MEASURE_QUALITY_B64")))

    cells.append(code([
        "# 4) Unpack the embedded tools and compile-check them\n",
        "import base64, os, py_compile\n",
        "os.makedirs('tools', exist_ok=True)\n",
        "open('tools/build_corpus.py', 'wb').write(base64.b64decode(_BUILD_CORPUS_B64))\n",
        "open('tools/measure_quality.py', 'wb').write(base64.b64decode(_MEASURE_QUALITY_B64))\n",
        "for f in ('tools/build_corpus.py', 'tools/measure_quality.py'):\n",
        "    py_compile.compile(f, doraise=True)\n",
        "def run(args, timeout=1800):\n",
        "    p = subprocess.run(args, capture_output=True, text=True, timeout=timeout)\n",
        "    if p.stdout: print(p.stdout)\n",
        "    if p.returncode != 0: print('STDERR:', p.stderr[-2000:])\n",
        "    return p\n",
        "print('tools unpacked and compiled')\n",
    ]))

    cells.append(code([
        "# 5) Generate two short synthetic SDR masters (no downloads needed)\n",
        "os.makedirs('masters', exist_ok=True)\n",
        "def gen(src, name):\n",
        "    subprocess.run([FF, '-hide_banner', '-y', '-f', 'lavfi', '-i',\n",
        "                    f'{src}=size=1280x720:rate=30', '-t', '10',\n",
        "                    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-preset', 'veryfast',\n",
        "                    f'masters/{name}.mp4'], check=True)\n",
        "gen('testsrc2', 'master_testsrc')\n",
        "gen('mandelbrot', 'master_mandelbrot')\n",
        "print('masters:', os.listdir('masters'))\n",
    ]))

    cells.append(code([
        "# 6) plan (dry-run estimate)\n",
        "run(['python', 'tools/build_corpus.py', 'plan', '--masters', 'masters',\n",
        "     '--ffmpeg', FF, '--ffprobe', FP, '--size-budget-gb', '1', '--max-clips', '40',\n",
        "     '--segments-per-master', '1', '--bpp-ladder', '0.03,0.05,0.10'])\n",
    ]))

    cells.append(code([
        "# 7) build the source clips (real ffmpeg encodes)\n",
        "run(['python', 'tools/build_corpus.py', 'build', '--masters', 'masters', '--out', 'corpus',\n",
        "     '--ffmpeg', FF, '--ffprobe', FP, '--segments-per-master', '1',\n",
        "     '--bpp-ladder', '0.03,0.05,0.10', '--codecs', 'hevc,h264', '--resolutions', '1280x720',\n",
        "     '--size-budget-gb', '1', '--max-clips', '40'])\n",
    ]))

    cells.append(code([
        "# 8) label via the repo VMAF harness (real libvmaf)\n",
        "run(['python', 'tools/build_corpus.py', 'label', '--out', 'corpus',\n",
        "     '--measure-quality', 'tools/measure_quality.py', '--ffmpeg', FF, '--ffprobe', FP,\n",
        "     '--pl-ratios', '0.95', '--keep-work'], timeout=3600)\n",
    ]))

    cells.append(code([
        "# 9) Assertions - did the build and label paths actually work?\n",
        "import csv, json, glob\n",
        "rows = list(csv.DictReader(open('corpus/corpus_manifest.csv')))\n",
        "built = len(rows)\n",
        "with_bpp = [r for r in rows if r.get('actual_bpp') not in (None, '')]\n",
        "labeled = [r for r in rows if str(r.get('compressible')) in ('0', '1')]\n",
        "wins = [r for r in rows if str(r.get('compressible')) == '1']\n",
        "# prove the measure_quality coupling produced parseable VMAF numbers\n",
        "reports = glob.glob('corpus/_label_work/*.json')\n",
        "sample_vmaf = None\n",
        "statuses = {}\n",
        "for rp in reports:\n",
        "    d = json.load(open(rp))\n",
        "    st = str(d.get('status')) + ('' if not d.get('error') else ' :: ' + str(d.get('error'))[:80])\n",
        "    statuses[st] = statuses.get(st, 0) + 1\n",
        "    if sample_vmaf is None and all(k in d for k in ('vmaf_mean', 'vmaf_5pct_low', 'vmaf_min')):\n",
        "        sample_vmaf = {k: d[k] for k in ('vmaf_mean', 'vmaf_5pct_low', 'vmaf_min')}\n",
        "print('vmaf report statuses:', statuses)  # self-diagnosing if the coupling breaks\n",
        "notes = {}\n",
        "for r in rows:\n",
        "    n = r.get('label_note') or ''\n",
        "    if n: notes[n] = notes.get(n, 0) + 1\n",
        "print('clips built        :', built)\n",
        "print('with actual_bpp    :', len(with_bpp))\n",
        "print('labeled (0/1)      :', len(labeled))\n",
        "print('compressible wins  :', len(wins))\n",
        "print('vmaf report keys OK:', sample_vmaf)\n",
        "if notes: print('label notes        :', notes)\n",
        "print()\n",
        "checks = {\n",
        "    'build path (>=4 clips built)': built >= 4,\n",
        "    'bpp measured for all clips': len(with_bpp) == built and built > 0,\n",
        "    'label path (>=1 labeled)': len(labeled) >= 1,\n",
        "    'vmaf keys parseable (coupling)': sample_vmaf is not None,\n",
        "}\n",
        "for name, ok in checks.items():\n",
        "    print(('PASS' if ok else 'FAIL'), '-', name)\n",
        "ok_all = all(checks.values())\n",
        "print()\n",
        "print('SMOKE TEST:', 'PASS' if ok_all else 'FAIL')\n",
        "assert ok_all, 'smoke test failed - see checks above'\n",
    ]))

    cells.append(code([
        "# 10) Bundle the manifest + a sample VMAF report and download\n",
        "import shutil, zipfile\n",
        "with zipfile.ZipFile('corpus_smoketest_results.zip', 'w', zipfile.ZIP_DEFLATED) as z:\n",
        "    z.write('corpus/corpus_manifest.csv', 'corpus_manifest.csv')\n",
        "    for rp in glob.glob('corpus/_label_work/*.json')[:3]:\n",
        "        z.write(rp, 'sample_reports/' + os.path.basename(rp))\n",
        "try:\n",
        "    from google.colab import files\n",
        "    files.download('corpus_smoketest_results.zip')\n",
        "except Exception as e:\n",
        "    print('download unavailable (%s); grab corpus_smoketest_results.zip from the Files pane' % e)\n",
    ]))

    return {
        "nbformat": 4, "nbformat_minor": 5,
        "metadata": {"colab": {"name": "Compressor_Corpus_SmokeTest.ipynb"},
                     "kernelspec": {"name": "python3", "display_name": "Python 3"},
                     "language_info": {"name": "python"}},
        "cells": cells,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--build-corpus", default=str(HERE.parent / "build_corpus.py"))
    parser.add_argument("--measure-quality", default=default_measure_quality_path())
    parser.add_argument("--out", default="Compressor_Corpus_SmokeTest.ipynb")
    args = parser.parse_args()

    build_corpus = Path(args.build_corpus)
    measure_quality = Path(args.measure_quality)
    for label, p in (("build_corpus.py", build_corpus), ("measure_quality.py", measure_quality)):
        if not p.exists():
            parser.error(f"{label} not found at {p}")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    nb = build_notebook(build_corpus, measure_quality)
    out.write_text(json.dumps(nb, indent=1), encoding="utf-8")
    print(f"wrote {out} ({out.stat().st_size} bytes; {len(nb['cells'])} cells)")
    print(f"  build_corpus.py    : {build_corpus}")
    print(f"  measure_quality.py : {measure_quality}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
