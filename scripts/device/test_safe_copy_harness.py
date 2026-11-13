#!/usr/bin/env python3
"""The safe-copy harness against a fake phone: a temporary directory stands in for shared storage,
the app's data and its media index, and a fake adb answers as the S26's does. Nothing here talks to
a device. What is checked is what protects a personal original: it is only read, B is an exact
copy made without overwriting anything, every case is bracketed by hash checks of A and B, a change
stops the run, and the destructive cases need an explicit flag and never receive A's path.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "safe-copy-harness.sh"
PKG = "io.github.zfkirke0109.galaxycompressor"

FAKE_ADB = textwrap.dedent(r'''
    #!/usr/bin/env python3
    """A fake adb for test_safe_copy_harness.py. State lives under $FAKE_ROOT."""
    import json, os, re, shutil, subprocess, sys
    from pathlib import Path
    root = Path(os.environ["FAKE_ROOT"])
    dev = root / "device"            # stands for /storage/emulated/0
    app = root / "app"               # the app's data directory
    state = json.loads((root / "state.json").read_text())
    calls = root / "calls.txt"
    args = sys.argv[1:]
    serial = "none"
    if args[:1] == ["-s"]:
        if args[1] not in state["devices"]:
            sys.exit(1)
        serial, args = args[1], args[2:]
    if args[:1] != ["devices"]:
        with (root / "serials.txt").open("a") as fh:
            fh.write(serial + "\n")
    with calls.open("a") as fh:
        fh.write(" ".join(args) + "\n")

    def device_path(text):
        return text.replace("/storage/emulated/0", str(dev))

    def save():
        (root / "state.json").write_text(json.dumps(state))

    if args[0] == "devices":
        print("List of devices attached")
        for d in state["devices"]:
            print(f"{d}\tdevice")
        sys.exit(0)
    if args[0] == "push":
        dst = Path(device_path(args[2])); dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(args[1], dst); sys.exit(0)
    if args[0] == "exec-out":
        rest = args[1:]
        if rest[:3] == ["run-as", state["pkg"], "cat"]:
            f = app / rest[3]
            if f.is_file():
                sys.stdout.buffer.write(f.read_bytes()); sys.exit(0)
            sys.exit(1)
        sys.exit(1)
    if args[0] == "logcat":
        print(state.get("logcat", "")); sys.exit(0)
    if args[0] != "shell":
        sys.exit(1)
    cmd = " ".join(args[1:])
    if cmd.startswith("getprop "):
        print({"ro.product.model": "SM-S948U", "ro.build.version.sdk": "37", "ro.build.version.release": "17"}.get(cmd.split()[1], "")); sys.exit(0)
    if cmd.startswith("dumpsys package"):
        print("    versionName=1.6.320-test"); sys.exit(0)
    if cmd.startswith("pm list instrumentation"):
        if state.get("testApk", True):
            print(f"instrumentation:{state['pkg']}.test/androidx.test.runner.AndroidJUnitRunner (target={state['pkg']})")
        sys.exit(0)
    if cmd.startswith("run-as "):
        parts = cmd.split()
        if not state.get("debuggable", True):
            print("run-as: package not debuggable"); sys.exit(1)
        if parts[2] == "id":
            print("uid=10234(u0_a234)"); sys.exit(0)
        if parts[2] == "ls":
            d = app / parts[-1]
            if d.is_dir():
                for f in sorted(d.iterdir()): print(f.name)
            sys.exit(0)
        sys.exit(1)
    if cmd.startswith("content query"):
        where = re.search(r'--where "([^"]*)"', cmd).group(1)
        for row in sorted(state["media"], key=lambda r: r["size"]):
            if where != "1=1" and where != f"_id={row['id']}":
                continue
            print(f"Row: 0 _id={row['id']}, _size={row['size']}, duration={row['ms']}, width={row['w']}, height={row['h']}, "
                  f"mime_type={row['mime']}, _data={row['path']}")
        sys.exit(0)
    if cmd.startswith("df -k"):
        print("Filesystem 1K-blocks Used Available Use% Mounted on")
        print(f"/dev/fuse 500000000 1000 {state['freeKb']} 1% /storage/emulated")
        sys.exit(0)
    if cmd.startswith("am instrument"):
        n = state.get("instruments", 0) + 1
        state["instruments"] = n; save()
        tamper = state.get("tamperOnInstrument", {}).get(str(n))
        if tamper:
            with open(device_path(tamper), "ab") as fh:
                fh.write(b"\0")
        if "pllabPhase die" in cmd:
            print("INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0")
        else:
            print(state.get("instrumentResult", "OK (7 tests)"))
        sys.exit(0)
    if cmd.startswith("sha256sum "):
        n = state.get("hashes", 0) + 1
        state["hashes"] = n; save()
        mutate = state.get("mutateBeforeHash", {}).get(str(n))
        if mutate:
            with open(device_path(mutate), "ab") as fh:
                fh.write(b"\1")
    # Everything else is an ordinary shell command over the stand-in storage.
    proc = subprocess.run(["bash", "-c", device_path(cmd)], capture_output=True, text=True)
    sys.stdout.write(proc.stdout.replace(str(dev), "/storage/emulated/0"))
    sys.exit(proc.returncode)
''').lstrip()


class SafeCopyHarnessTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.fake = self.tmp / "fake"
        (self.fake / "device" / "DCIM" / "Camera").mkdir(parents=True)
        (self.fake / "app" / "cache" / "batch_compressed_videos").mkdir(parents=True)
        (self.fake / "app" / "files" / "replace_recovery").mkdir(parents=True)
        (self.fake / "app" / "files" / "pllab" / "ui-evidence").mkdir(parents=True)
        (self.fake / "app" / "files" / "pllab" / "ui-evidence" / "retry.png").write_bytes(b"\x89PNG fake")
        self.bin = self.tmp / "bin"; self.bin.mkdir()
        adb = self.bin / "adb"; adb.write_text(FAKE_ADB); adb.chmod(0o755)
        self.home = self.tmp / "home"
        # A personal original with a name that needs quoting on the device's shell.
        self.a_rel = "DCIM/Camera/Mom's birthday, take 2 (final).mp4"
        self.a = self.fake / "device" / self.a_rel
        self.a.write_bytes(os.urandom(300_000))
        self.a_sha = hashlib.sha256(self.a.read_bytes()).hexdigest()
        self.state = {
            "pkg": PKG, "devices": ["emulator-5554"], "freeKb": 50_000_000,
            "media": [
                {"id": 41, "size": self.a.stat().st_size, "ms": 4000, "w": 1920, "h": 1080, "mime": "video/hevc",
                 "path": "/storage/emulated/0/" + self.a_rel},
                {"id": 7, "size": 6_000_000_000, "ms": 60000, "w": 3840, "h": 2160, "mime": "video/avc",
                 "path": "/storage/emulated/0/DCIM/Camera/big.mp4"},
            ],
        }
        self.save()

    def save(self):
        (self.fake / "state.json").write_text(json.dumps(self.state))

    def load(self):
        self.state = json.loads((self.fake / "state.json").read_text())

    def run_harness(self, *args):
        env = {**os.environ, "PATH": f"{self.bin}:{os.environ['PATH']}", "FAKE_ROOT": str(self.fake),
               "PLLAB_HOME": str(self.home), "HOME": str(self.tmp)}
        return subprocess.run(["bash", str(SCRIPT), *args], env=env, text=True, capture_output=True,
                              stdin=subprocess.DEVNULL, check=False)

    def calls(self):
        f = self.fake / "calls.txt"
        return f.read_text().splitlines() if f.exists() else []

    def stage(self):
        proc = self.run_harness("stage", "--source-id", "41")
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        run = next(line.split(":", 1)[1].strip() for line in proc.stdout.splitlines() if line.startswith("run "))
        return run

    def runs_dir(self):
        return self.fake / "device" / "Documents" / "PLLabValidation"

    # ---- read-only inventory ---------------------------------------------------------------

    def test_inventory_reads_the_index_and_keeps_names_and_paths_off_the_screen(self):
        proc = self.run_harness("inventory")
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("videos       : 2 in this profile's media index", proc.stdout)
        self.assertNotIn("birthday", proc.stdout + proc.stderr)
        private = next(self.home.glob("inventory-*.private.tsv")).read_text()
        self.assertIn("birthday", private)
        # Nothing was hashed or copied, and nothing was written on the device.
        self.assertFalse(any(c.startswith("shell sha256sum") or "cat " in c for c in self.calls()))
        self.assertFalse(self.runs_dir().exists())
        self.assertEqual(self.a_sha, hashlib.sha256(self.a.read_bytes()).hexdigest())

    # ---- staging A -> B ----------------------------------------------------------------------

    def test_a_dry_run_writes_nothing(self):
        proc = self.run_harness("stage", "--source-id", "41", "--dry-run")
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("would copy   : A1 (media id 41", proc.stdout)
        self.assertIn("dry run      : nothing was written", proc.stdout)
        self.assertFalse(self.runs_dir().exists())
        self.assertFalse(self.home.exists() and any(self.home.iterdir()))

    def test_staging_makes_an_exact_copy_and_records_both_without_touching_a(self):
        run = self.stage()
        b = self.runs_dir() / run / "B-1.mp4"
        self.assertEqual(self.a.read_bytes(), b.read_bytes())
        self.assertTrue((self.runs_dir() / run / ".nomedia").exists())
        manifest = (self.home / run / "manifest.tsv").read_text().splitlines()
        a_line = next(l for l in manifest if l.startswith("A\t")).split("\t")
        b_line = next(l for l in manifest if l.startswith("B\t")).split("\t")
        self.assertEqual(["A", "1", "41", str(self.a.stat().st_size), self.a_sha], a_line[:5])
        self.assertEqual(["B", "1", str(self.a.stat().st_size), self.a_sha], b_line[:4])
        # A was hashed before and after the copy, and only ever read.
        sums = [c for c in self.calls() if c.startswith("shell sha256sum") and "birthday" in c]
        self.assertEqual(2, len(sums))
        self.assertEqual(self.a_sha, hashlib.sha256(self.a.read_bytes()).hexdigest())
        # The copy is an exclusive create.
        self.assertTrue(any(c.startswith("shell set -C; cat ") for c in self.calls()))
        self.assertTrue((self.runs_dir() / run / "manifest.tsv").exists())

    def test_a_copy_is_never_made_over_an_existing_file(self):
        run = self.stage()
        b = self.runs_dir() / run / "B-1.mp4"
        before = b.read_bytes()
        env = {**os.environ, "FAKE_ROOT": str(self.fake)}
        cmd = f"set -C; cat '/storage/emulated/0/{self.a_rel.replace(chr(39), chr(39) + chr(92) + chr(39) + chr(39))}' > '/storage/emulated/0/Documents/PLLabValidation/{run}/B-1.mp4'"
        proc = subprocess.run([str(self.bin / "adb"), "shell", cmd], env=env, capture_output=True, text=True)
        self.assertNotEqual(0, proc.returncode)
        self.assertEqual(before, b.read_bytes())

    def test_too_little_free_space_copies_nothing(self):
        self.state["freeKb"] = 100; self.save()
        proc = self.run_harness("stage", "--source-id", "41")
        self.assertEqual(2, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("not enough free space", proc.stderr)
        self.assertFalse(self.runs_dir().exists())

    def test_an_original_that_changes_while_it_is_copied_stops_the_run(self):
        # The second hash of A (after the copy) sees one more byte.
        self.state["mutateBeforeHash"] = {"3": "/storage/emulated/0/" + self.a_rel}; self.save()
        proc = self.run_harness("stage", "--source-id", "41")
        self.assertEqual(3, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("A1 changed while it was being copied", proc.stderr)

    def test_an_unknown_or_ambiguous_source_is_refused(self):
        proc = self.run_harness("stage", "--source-id", "999")
        self.assertEqual(2, proc.returncode)
        self.assertIn("is not one video", proc.stderr)
        proc = self.run_harness("stage", "--source-id", "4 1")
        self.assertEqual(64, proc.returncode)

    # ---- protection checks around every case -----------------------------------------------

    def test_verification_passes_unchanged_files_and_stops_on_any_change(self):
        run = self.stage()
        proc = self.run_harness("verify-protection", "--run", run)
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("protected    : every A and B unchanged (check)", proc.stdout)
        with (self.runs_dir() / run / "B-1.mp4").open("ab") as fh:
            fh.write(b"x")
        proc = self.run_harness("verify-protection", "--run", run, "--final")
        self.assertEqual(3, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("B1 does not match the manifest at final", proc.stderr)
        self.assertIn("CHANGED-OR-UNREADABLE", (self.home / run / "protection.log").read_text())

    def test_replacement_cases_need_the_explicit_flag(self):
        run = self.stage()
        proc = self.run_harness("run-replacement-tests", "--run", run)
        self.assertEqual(64, proc.returncode)
        self.assertFalse(any("am instrument" in c for c in self.calls()))

    def test_replacement_cases_run_on_b_only_bracketed_by_checks_and_end_with_the_death_sequence(self):
        run = self.stage()
        before = len(self.calls())
        proc = self.run_harness("run-replacement-tests", "--run", run, "--disposable-only")
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        calls = self.calls()[before:]
        instruments = [c for c in calls if c.startswith("shell am instrument")]
        self.assertEqual(4, len(instruments))
        self.assertIn("SafeCopyReplacementDeviceTest", instruments[0])
        self.assertIn("pllabPhase die", instruments[1])
        self.assertIn("pllabPhase recover-1", instruments[2])
        self.assertIn("pllabPhase recover-2", instruments[3])
        for c in instruments:
            # The tests get B's path in the run folder, and never A's.
            self.assertIn(f"-e pllabBaseline /storage/emulated/0/Documents/PLLabValidation/{run}/B-1.mp4", c)
            self.assertNotIn("birthday", c)
            self.assertIn(f"-e pllabBaselineSha256 {self.a_sha}", c)
        log = (self.home / run / "protection.log").read_text()
        for label in ("before-replacement-B1", "after-replacement-B1", "after-death-B1", "after-recovery-B1"):
            self.assertIn(f"{label}\tA1\tunchanged", log)
            self.assertIn(f"{label}\tB1\tunchanged", log)
        verdicts = (self.home / run / "verdicts.tsv").read_text()
        self.assertIn("death-B1\tcrashed", verdicts)
        self.assertIn("report       :", proc.stdout)

    def test_a_case_that_changes_a_protected_file_stops_everything_after_it(self):
        run = self.stage()
        self.state = json.loads((self.fake / "state.json").read_text())
        self.state["tamperOnInstrument"] = {"1": f"/storage/emulated/0/Documents/PLLabValidation/{run}/B-1.mp4"}; self.save()
        before = len(self.calls())
        proc = self.run_harness("run-replacement-tests", "--run", run, "--disposable-only")
        self.assertEqual(3, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("after-replacement-B1", proc.stderr)
        self.assertEqual(1, len([c for c in self.calls()[before:] if c.startswith("shell am instrument")]))

    # ---- batch cases ---------------------------------------------------------------------

    def test_a_batch_case_never_starts_over_unsaved_outputs_or_leftover_rollback_copies(self):
        run = self.stage()
        (self.fake / "app" / "cache" / "batch_compressed_videos" / "x_Compressed.mp4").write_bytes(b"1")
        proc = self.run_harness("run-copy-tests", "--run", run, "--cases", "padding-repair")
        self.assertEqual(2, proc.returncode)
        self.assertIn("unsaved output", proc.stderr)
        (self.fake / "app" / "cache" / "batch_compressed_videos" / "x_Compressed.mp4").unlink()
        (self.fake / "app" / "files" / "replace_recovery" / "replace_recovery_job_1_1.mp4").write_bytes(b"1")
        proc = self.run_harness("run-copy-tests", "--run", run, "--cases", "padding-repair")
        self.assertEqual(2, proc.returncode)
        self.assertIn("rollback cop", proc.stderr)
        self.assertFalse(any("am instrument" in c for c in self.calls()))

    def test_without_run_as_the_app_state_is_not_assumed_empty(self):
        run = self.stage()
        self.state = json.loads((self.fake / "state.json").read_text())
        self.state["debuggable"] = False; self.save()
        proc = self.run_harness("run-copy-tests", "--run", run, "--cases", "padding-repair")
        self.assertEqual(2, proc.returncode)
        self.assertIn("run-as", proc.stderr)

    def test_the_corpus_case_hands_every_baseline_and_no_original(self):
        run = self.stage()
        before = len(self.calls())
        proc = self.run_harness("run-copy-tests", "--run", run, "--cases", "corpus")
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        instrument = next(c for c in self.calls()[before:] if c.startswith("shell am instrument"))
        self.assertIn("CorpusBatchDeviceTest", instrument)
        size = self.a.stat().st_size
        self.assertIn(f"-e pllabBaselines /storage/emulated/0/Documents/PLLabValidation/{run}/B-1.mp4:{self.a_sha}:{size}", instrument)
        self.assertNotIn("birthday", instrument)

    def test_the_fixture_cases_need_the_fixture(self):
        run = self.stage()
        proc = self.run_harness("run-copy-tests", "--run", run, "--cases", "padding-repair")
        self.assertEqual(2, proc.returncode)
        self.assertIn("no fixture in this run", proc.stderr)

    @unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg is not installed")
    def test_the_fixture_has_exactly_the_audio_edit_the_repair_needs(self):
        run = self.stage()
        proc = self.run_harness("fixture", "--run", run)
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        fixture = self.home / run / "F-padding.mp4"
        self.assertEqual(fixture.read_bytes(), (self.runs_dir() / run / "F-padding.mp4").read_bytes())
        delay, presented, padding = audio_edit(fixture.read_bytes())
        self.assertEqual((1024, 575488, 0), (delay, presented, padding))
        before = len(self.calls())
        proc = self.run_harness("run-copy-tests", "--run", run, "--cases", "padding-repair,batch-replace")
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        instruments = [c for c in self.calls()[before:] if c.startswith("shell am instrument")]
        self.assertIn("PaddingRepairBatchDeviceTest#a_", instruments[0])
        self.assertIn("PaddingRepairBatchDeviceTest#b_", instruments[1])
        self.assertIn(f"-e pllabFixture /storage/emulated/0/Documents/PLLabValidation/{run}/F-padding.mp4", instruments[0])

    def test_an_incomplete_case_is_not_reported_as_passed(self):
        run = self.stage()
        self.state = json.loads((self.fake / "state.json").read_text())
        self.state["instrumentResult"] = "INSTRUMENTATION_STATUS_CODE: -4\nOK (7 tests)"; self.save()
        proc = self.run_harness("run-replacement-tests", "--run", run, "--disposable-only")
        self.assertEqual(2, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("replacement-B1 -> incomplete", proc.stdout)

    def test_several_devices_need_a_serial(self):
        self.state["devices"] = ["emulator-5554", "192.168.1.9:37001"]; self.save()
        proc = self.run_harness("inventory")
        self.assertEqual(2, proc.returncode)
        self.assertIn("pass --serial", proc.stderr)
        proc = self.run_harness("--serial", "emulator-5554", "inventory")
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        # Every device command went to the device named, never to adb's default.
        self.assertEqual({"emulator-5554"}, set((self.fake / "serials.txt").read_text().split()))
        proc = self.run_harness("--serial", "10.0.0.9:5555", "inventory")
        self.assertEqual(2, proc.returncode)
        self.assertIn("is not a connected device", proc.stderr)

    @unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg is not installed")
    def test_all_runs_every_case_in_order_between_protection_checks(self):
        proc = self.run_harness("all", "--source-id", "41")
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        order = [line.split(":", 1)[1].strip() for line in proc.stdout.splitlines() if line.startswith(("case ", "protected ", "fixture ", "staged "))]
        labels = [o.split(" ")[0] if o.startswith(("app-checks", "padding", "batch", "corpus", "replacement", "death", "recover")) else o for o in order]
        self.assertTrue(labels[0].startswith("A1 -> B1"), labels)
        self.assertIn("every A and B unchanged (initial)", labels[1])
        self.assertTrue(labels[2].startswith("F-padding.mp4"), labels)
        names = ("app-checks", "padding-repair", "batch-replace", "corpus", "replacement-B1", "death-B1", "recover-1-B1", "recover-2-B1")
        cases = [l for l in labels if l in names]
        self.assertEqual(list(names), cases)
        self.assertIn("every A and B unchanged (final)", labels[-1])
        # The rendered states the card test saved are collected beside the report.
        self.assertEqual(["retry.png"], sorted(p.name for p in (self.home / proc.stdout.split("run          : ")[1].split()[0] / "ui-evidence").iterdir()))
        self.assertEqual(self.a_sha, hashlib.sha256(self.a.read_bytes()).hexdigest())


def audio_edit(data):
    """(delay, presented samples, padding) of the first sound track's edit list."""
    import struct

    def boxes(start, end):
        i = start
        while i + 8 <= end:
            size, typ = struct.unpack_from(">I4s", data, i)
            hdr = 8
            if size == 1:
                size = struct.unpack_from(">Q", data, i + 8)[0]; hdr = 16
            yield typ, i + hdr, i + size
            i += size

    def child(start, end, name):
        return next(((b, e) for t, b, e in boxes(start, end) if t == name), None)

    moov = child(0, len(data), b"moov")
    mvhd = child(*moov, b"mvhd")
    movie_ts = struct.unpack_from(">I", data, mvhd[0] + 4 + (16 if data[mvhd[0]] == 1 else 8))[0]
    for t, b, e in boxes(*moov):
        if t != b"trak":
            continue
        mdia = child(b, e, b"mdia")
        hdlr = child(*mdia, b"hdlr")
        if data[hdlr[0] + 8:hdlr[0] + 12] != b"soun":
            continue
        mdhd = child(*mdia, b"mdhd")
        ts, duration = struct.unpack_from(">II", data, mdhd[0] + 12)
        elst = child(*child(b, e, b"edts"), b"elst")
        seg, media_time = struct.unpack_from(">Ii", data, elst[0] + 8)
        presented = seg * ts // movie_ts
        return media_time, presented, duration - media_time - presented
    raise AssertionError("no sound track")


if __name__ == "__main__":
    unittest.main()
