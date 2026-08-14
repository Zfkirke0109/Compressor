from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest


LAB_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
CLAUDE_ROOT = REPOSITORY_ROOT / "plugins" / "claude" / "compressor-pl-lab"
OPENAI_ROOT = REPOSITORY_ROOT / "plugins" / "openai" / "compressor-pl-lab"
CANONICAL_CONSTITUTION = (
    REPOSITORY_ROOT
    / ".claude"
    / "skills"
    / "android-media3-perceptual-compression"
)

WORKFLOWS = ("pl-calibrate", "pl-benchmark", "pl-investigate")
ROLES = (
    "codec-scientist",
    "perceptual-evaluator",
    "android-device-lab",
    "regression-judge",
)
CONSTITUTION = "android-media3-perceptual-compression"
OPERATIONS = (
    "pl_device_inventory",
    "pl_build_install",
    "pl_encode_candidate",
    "pl_score_pair",
    "pl_score_corpus",
    "pl_hdr_compare",
    "pl_pts_compare",
    "pl_run_boundary_suite",
    "pl_optimize_policy",
    "pl_compare_baseline",
    "pl_generate_report",
)
REFERENCES = (
    "perceptual-compression.md",
    "vmaf-v0-v1.md",
    "mediacodec.md",
    "hdr-color.md",
    "pts-alignment.md",
    "experiment-corpus.md",
    "baseline-reporting.md",
)


def _json(path: Path) -> dict[str, object]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise AssertionError(f"expected an object in {path}")
    return value


def _frontmatter(path: Path) -> tuple[dict[str, str], str]:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    if not lines or lines[0] != "---":
        raise AssertionError(f"missing YAML frontmatter in {path}")
    try:
        end = lines.index("---", 1)
    except ValueError as exc:
        raise AssertionError(f"unterminated YAML frontmatter in {path}") from exc
    metadata: dict[str, str] = {}
    for line in lines[1:end]:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if ":" not in line:
            raise AssertionError(f"invalid frontmatter line in {path}: {line!r}")
        key, value = line.split(":", 1)
        metadata[key.strip()] = value.strip().strip("\"'")
    return metadata, "\n".join(lines[end + 1 :]).strip()


def _files(root: Path) -> dict[str, bytes]:
    result: dict[str, bytes] = {}
    for path in sorted(root.rglob("*")):
        if (
            path.is_file()
            and "__pycache__" not in path.parts
            and path.suffix not in {".pyc", ".pyo"}
        ):
            result[path.relative_to(root).as_posix()] = path.read_bytes()
    return result


def _run_python(
    script: Path,
    *arguments: str,
    stdin: str | None = None,
    timeout: int = 15,
) -> subprocess.CompletedProcess[str]:
    environment = os.environ.copy()
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    return subprocess.run(
        [sys.executable, "-B", str(script), *arguments],
        cwd=script.parents[1],
        env=environment,
        input=stdin,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=False,
    )


class PluginManifestAndLayoutTests(unittest.TestCase):
    def test_host_native_manifests_are_distinct_and_grounded(self) -> None:
        claude_manifest_path = CLAUDE_ROOT / ".claude-plugin" / "plugin.json"
        openai_manifest_path = OPENAI_ROOT / ".codex-plugin" / "plugin.json"
        self.assertTrue(claude_manifest_path.is_file())
        self.assertTrue(openai_manifest_path.is_file())
        self.assertFalse((CLAUDE_ROOT / ".codex-plugin").exists())
        self.assertFalse((OPENAI_ROOT / ".claude-plugin").exists())

        claude = _json(claude_manifest_path)
        openai = _json(openai_manifest_path)
        common_allowed = {
            "name",
            "version",
            "description",
            "author",
            "homepage",
            "repository",
            "license",
            "keywords",
        }
        self.assertLessEqual(set(claude), common_allowed)
        self.assertLessEqual(set(openai), common_allowed | {"skills", "interface"})
        for manifest in (claude, openai):
            with self.subTest(manifest=manifest_path_label(manifest)):
                self.assertEqual("compressor-pl-lab", manifest["name"])
                self.assertRegex(str(manifest["version"]), r"^\d+\.\d+\.\d+$")
                self.assertEqual("MIT", manifest["license"])
                self.assertEqual(
                    "https://github.com/Zfkirke0109/Compressor",
                    manifest["repository"],
                )
                self.assertNotIn("hooks", manifest)
                self.assertNotIn("agents", manifest)
                self.assertNotIn("mcpServers", manifest)
                self.assertNotIn("apps", manifest)
        self.assertEqual("./skills/", openai["skills"])
        self.assertNotEqual(claude_manifest_path.parent.name, openai_manifest_path.parent.name)

    def test_component_layouts_match_each_hosts_supported_surface(self) -> None:
        claude_skills = {
            path.name for path in (CLAUDE_ROOT / "skills").iterdir() if path.is_dir()
        }
        openai_skills = {
            path.name for path in (OPENAI_ROOT / "skills").iterdir() if path.is_dir()
        }
        claude_agents = {
            path.stem for path in (CLAUDE_ROOT / "agents").glob("*.md")
        }
        self.assertEqual(set(WORKFLOWS) | {CONSTITUTION}, claude_skills)
        self.assertEqual(set(ROLES), claude_agents)
        self.assertEqual(set(WORKFLOWS) | set(ROLES) | {CONSTITUTION}, openai_skills)
        self.assertFalse((OPENAI_ROOT / "agents").exists())
        self.assertFalse((CLAUDE_ROOT / "commands").exists())

        for plugin_root in (CLAUDE_ROOT, OPENAI_ROOT):
            with self.subTest(plugin=plugin_root.parent.name):
                self.assertTrue((plugin_root / "hooks" / "hooks.json").is_file())
                self.assertTrue((plugin_root / "scripts" / "pl_lab.py").is_file())
                self.assertTrue((plugin_root / "scripts" / "hook_dispatch.py").is_file())
                self.assertTrue((plugin_root / "runtime" / "pl_lab" / "cli.py").is_file())
                self.assertTrue((plugin_root / "runtime" / "schemas" / "v1").is_dir())
                self.assertTrue((plugin_root / "runtime" / "contracts").is_dir())
                self.assertTrue((plugin_root / "README.md").is_file())
                self.assertTrue((plugin_root / "LICENSE").is_file())
                self.assertFalse(any(path.is_symlink() for path in plugin_root.rglob("*")))

    def test_no_mcp_app_or_openai_plugin_agent_component_is_declared(self) -> None:
        forbidden_names = {".mcp.json", ".app.json", "mcp.json", "app.json"}
        for plugin_root in (CLAUDE_ROOT, OPENAI_ROOT):
            with self.subTest(plugin=plugin_root.parent.name):
                self.assertFalse(
                    any(path.name.casefold() in forbidden_names for path in plugin_root.rglob("*"))
                )
                self.assertFalse((plugin_root / "mcp").exists())
                self.assertFalse((plugin_root / "apps").exists())
        self.assertFalse((OPENAI_ROOT / "agents").exists())
        openai_manifest = _json(OPENAI_ROOT / ".codex-plugin" / "plugin.json")
        self.assertTrue(
            set(openai_manifest).isdisjoint(
                {"agent", "agents", "hooks", "mcp", "mcpServers", "app", "apps"}
            )
        )


def manifest_path_label(manifest: dict[str, object]) -> str:
    interface = manifest.get("interface")
    return "openai" if isinstance(interface, dict) else "claude"


class SkillAndRolePackageTests(unittest.TestCase):
    def test_workflow_skills_have_metadata_documented_contracts_and_native_invocations(self) -> None:
        expected_outputs = {
            "pl-calibrate": ("specification", "calibration"),
            "pl-benchmark": (
                "candidate",
                "evaluation",
                "device",
                "comparison",
                "verdict",
                "report",
            ),
            "pl-investigate": ("investigation", "record"),
        }
        for host_name, plugin_root in (("claude", CLAUDE_ROOT), ("openai", OPENAI_ROOT)):
            for workflow in WORKFLOWS:
                with self.subTest(host=host_name, workflow=workflow):
                    path = plugin_root / "skills" / workflow / "SKILL.md"
                    metadata, body = _frontmatter(path)
                    content = (metadata.get("description", "") + "\n" + body).casefold()
                    self.assertEqual(workflow, metadata.get("name"))
                    self.assertTrue(metadata.get("description"))
                    self.assertIn("require", content)
                    self.assertIn("dry-run", content)
                    self.assertIn("--execute", content)
                    self.assertIn("experiment", content)
                    self.assertTrue(
                        "bounded" in content or "budget" in content,
                        "workflow must document a bounded step or positive budget",
                    )
                    self.assertIn("resume", content)
                    self.assertTrue("fail" in content or "blocked" in content)
                    for output in expected_outputs[workflow]:
                        self.assertIn(output, content)

                    if host_name == "claude":
                        invocation = f"/compressor-pl-lab:{workflow}"
                        self.assertIn(invocation, body)
                        self.assertIn("${CLAUDE_PLUGIN_ROOT}", body)
                        self.assertNotIn("$compressor-pl-lab:", body)
                    else:
                        invocation = f"$compressor-pl-lab:{workflow}"
                        self.assertIn(invocation, body)
                        self.assertRegex(body, r"(?i)ChatGPT.{0,80}`@`.{0,80}(?:select|pick)")
                        self.assertIn("ChatGPT", body)
                        self.assertIn("does not", body)
                        self.assertIn("hooks", body.casefold())
                        self.assertNotIn("${CLAUDE_PLUGIN_ROOT}", body)
                    self.assertIn("PL_STATUS", body)

    def test_claude_agents_have_exact_roles_and_read_only_judge(self) -> None:
        required_terms = {
            "codec-scientist": ("candidate", "threshold", "verdict"),
            "perceptual-evaluator": ("evaluation", "threshold", "verdict"),
            "android-device-lab": ("serial", "device", "verdict"),
            "regression-judge": ("pass", "fail", "blocked", "read"),
        }
        for role in ROLES:
            with self.subTest(role=role):
                metadata, body = _frontmatter(CLAUDE_ROOT / "agents" / f"{role}.md")
                content = (metadata.get("description", "") + "\n" + body).casefold()
                self.assertEqual(role, metadata.get("name"))
                self.assertTrue(metadata.get("description"))
                self.assertIn("tools", metadata)
                self.assertEqual(
                    "[compressor-pl-lab:android-media3-perceptual-compression]",
                    metadata.get("skills"),
                )
                for term in required_terms[role]:
                    self.assertIn(term, content)
                if role != "regression-judge":
                    self.assertIn("Bash", metadata["tools"])
                    self.assertIn("PowerShell", metadata["tools"])
                    self.assertIn("${CLAUDE_PLUGIN_ROOT}", body)
        judge_metadata, _ = _frontmatter(
            CLAUDE_ROOT / "agents" / "regression-judge.md"
        )
        self.assertEqual("Read, Glob, Grep", judge_metadata["tools"])
        self.assertNotIn("Bash", judge_metadata["tools"])
        self.assertNotIn("PowerShell", judge_metadata["tools"])

    def test_openai_workflow_and_role_skills_have_valid_ui_metadata(self) -> None:
        skill_names = set(WORKFLOWS) | set(ROLES) | {CONSTITUTION}
        for skill_name in sorted(skill_names):
            with self.subTest(skill=skill_name):
                skill_root = OPENAI_ROOT / "skills" / skill_name
                metadata, body = _frontmatter(skill_root / "SKILL.md")
                self.assertEqual(skill_name, metadata.get("name"))
                self.assertTrue(metadata.get("description"))
                self.assertTrue(body)

                yaml_path = skill_root / "agents" / "openai.yaml"
                yaml_text = yaml_path.read_text(encoding="utf-8")
                self.assertNotIn("\t", yaml_text)
                top_level = {
                    line.split(":", 1)[0]
                    for line in yaml_text.splitlines()
                    if line and not line[0].isspace()
                }
                self.assertEqual({"interface", "policy"}, top_level)
                for key in ("display_name", "short_description", "default_prompt"):
                    self.assertRegex(yaml_text, rf"(?m)^  {key}:\s*\S")
                implicit = re.search(
                    r"(?m)^  allow_implicit_invocation:\s*(true|false)\s*$",
                    yaml_text,
                )
                self.assertIsNotNone(implicit)
                expected_implicit = "true" if skill_name == CONSTITUTION else "false"
                self.assertEqual(expected_implicit, implicit.group(1))
                self.assertIn(f"$compressor-pl-lab:{skill_name}", yaml_text)

    def test_openai_role_skills_preserve_separation_contracts(self) -> None:
        required_terms = {
            "codec-scientist": ("candidate", "threshold", "verdict"),
            "perceptual-evaluator": ("evaluation", "threshold", "overall"),
            "android-device-lab": ("serial", "device", "verdict"),
            "regression-judge": ("pass", "fail", "blocked", "read-only"),
        }
        for role, terms in required_terms.items():
            with self.subTest(role=role):
                metadata, body = _frontmatter(
                    OPENAI_ROOT / "skills" / role / "SKILL.md"
                )
                content = (metadata["description"] + "\n" + body).casefold()
                for term in terms:
                    self.assertIn(term, content)

    def test_constitution_uses_progressive_disclosure_for_every_reference(self) -> None:
        metadata, body = _frontmatter(CANONICAL_CONSTITUTION / "SKILL.md")
        self.assertEqual(CONSTITUTION, metadata.get("name"))
        self.assertIn("Load only the reference needed", body)
        for reference in REFERENCES:
            with self.subTest(reference=reference):
                self.assertTrue((CANONICAL_CONSTITUTION / "references" / reference).is_file())
                self.assertEqual(1, body.count(f"references/{reference}"))
                route_line = next(
                    line for line in body.splitlines() if f"references/{reference}" in line
                )
                self.assertIn(":", route_line)


class GeneratedPayloadTests(unittest.TestCase):
    def test_runtime_contracts_and_schemas_match_canonical_bytes(self) -> None:
        canonical_trees = {
            "pl_lab": _files(LAB_ROOT / "pl_lab"),
            "schemas": _files(LAB_ROOT / "schemas"),
            "contracts": _files(LAB_ROOT / "contracts"),
        }
        for plugin_root in (CLAUDE_ROOT, OPENAI_ROOT):
            for tree_name, expected in canonical_trees.items():
                with self.subTest(plugin=plugin_root.parent.name, tree=tree_name):
                    actual = _files(plugin_root / "runtime" / tree_name)
                    self.assertEqual(set(expected), set(actual))
                    for relative in sorted(expected):
                        self.assertEqual(
                            expected[relative],
                            actual[relative],
                            f"generated payload drift: {plugin_root.parent.name}/{tree_name}/{relative}",
                        )

    def test_constitution_and_references_match_canonical_bytes(self) -> None:
        expected_skill = (CANONICAL_CONSTITUTION / "SKILL.md").read_bytes()
        expected_references = _files(CANONICAL_CONSTITUTION / "references")
        for plugin_root in (CLAUDE_ROOT, OPENAI_ROOT):
            packaged = plugin_root / "skills" / CONSTITUTION
            with self.subTest(plugin=plugin_root.parent.name):
                self.assertEqual(expected_skill, (packaged / "SKILL.md").read_bytes())
                self.assertEqual(expected_references, _files(packaged / "references"))

    def test_generated_wrappers_and_license_match_between_packages(self) -> None:
        for relative in (
            Path("scripts/pl_lab.py"),
            Path("scripts/hook_dispatch.py"),
            Path("LICENSE"),
        ):
            with self.subTest(path=relative.as_posix()):
                self.assertEqual(
                    (CLAUDE_ROOT / relative).read_bytes(),
                    (OPENAI_ROOT / relative).read_bytes(),
                )
                if relative.suffix == ".py":
                    self.assertIn(
                        b"sys.dont_write_bytecode = True",
                        (CLAUDE_ROOT / relative).read_bytes(),
                    )
        self.assertEqual(
            (REPOSITORY_ROOT / "LICENSE").read_bytes(),
            (CLAUDE_ROOT / "LICENSE").read_bytes(),
        )


class WrapperAndHookPackageTests(unittest.TestCase):
    def test_every_operation_has_usable_help_through_both_wrappers(self) -> None:
        for plugin_root in (CLAUDE_ROOT, OPENAI_ROOT):
            wrapper = plugin_root / "scripts" / "pl_lab.py"
            for operation in OPERATIONS:
                with self.subTest(plugin=plugin_root.parent.name, operation=operation):
                    result = _run_python(wrapper, operation, "--help")
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertIn(operation, result.stdout)
                    for option in ("--repository", "--request", "--experiment", "--execute"):
                        self.assertIn(option, result.stdout)
                    if operation == "pl_generate_report":
                        self.assertIn("--gate", result.stdout)
                    else:
                        self.assertNotIn("--gate", result.stdout)

    def test_claude_and_openai_hooks_use_separate_native_command_shapes(self) -> None:
        claude = _json(CLAUDE_ROOT / "hooks" / "hooks.json")
        openai = _json(OPENAI_ROOT / "hooks" / "hooks.json")
        self.assertNotEqual(claude, openai)
        self.assertEqual({"PostToolUse", "Stop"}, set(claude["hooks"]))
        self.assertEqual({"PostToolUse", "Stop"}, set(openai["hooks"]))

        claude_post = claude["hooks"]["PostToolUse"][0]
        openai_post = openai["hooks"]["PostToolUse"][0]
        self.assertEqual("Edit|Write", claude_post["matcher"])
        self.assertIn("apply_patch", openai_post["matcher"])

        for event in ("PostToolUse", "Stop"):
            claude_command = claude["hooks"][event][0]["hooks"][0]
            openai_command = openai["hooks"][event][0]["hooks"][0]
            self.assertEqual("python", claude_command["command"])
            self.assertIsInstance(claude_command["args"], list)
            self.assertEqual("-B", claude_command["args"][0])
            self.assertIn("${CLAUDE_PLUGIN_ROOT}", claude_command["args"][1])
            self.assertNotIn("commandWindows", claude_command)

            self.assertIsInstance(openai_command["command"], str)
            self.assertIn("python -B", openai_command["command"])
            self.assertIn("${PLUGIN_ROOT}", openai_command["command"])
            self.assertIn("python -B", openai_command["commandWindows"])
            self.assertIn("%PLUGIN_ROOT%", openai_command["commandWindows"])
            self.assertNotIn("args", openai_command)
            self.assertNotIn("CLAUDE_PLUGIN_ROOT", json.dumps(openai_command))
            expected_timeout = 900 if event == "PostToolUse" else 600
            self.assertEqual(expected_timeout, claude_command["timeout"])
            self.assertEqual(expected_timeout, openai_command["timeout"])

    def test_hook_wrappers_accept_irrelevant_and_truthful_blocked_inputs(self) -> None:
        irrelevant = json.dumps(
            {"cwd": str(REPOSITORY_ROOT), "tool_input": {"file_path": "README.md"}}
        )
        blocked = json.dumps(
            {
                "stop_hook_active": False,
                "last_assistant_message": (
                    "PL_STATUS: BLOCKED\nPL_MISSING: Android device access"
                ),
            }
        )
        for plugin_root in (CLAUDE_ROOT, OPENAI_ROOT):
            wrapper = plugin_root / "scripts" / "hook_dispatch.py"
            with self.subTest(plugin=plugin_root.parent.name, mode="irrelevant"):
                result = _run_python(wrapper, "post-tool-use", stdin=irrelevant)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("", result.stdout)
                self.assertEqual("", result.stderr)
            with self.subTest(plugin=plugin_root.parent.name, mode="blocked"):
                result = _run_python(wrapper, "stop", stdin=blocked)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("", result.stdout)
                self.assertEqual("", result.stderr)

    def test_hook_wrappers_reject_malformed_edits_and_unsupported_success(self) -> None:
        unsupported = json.dumps(
            {
                "stop_hook_active": False,
                "last_assistant_message": "The benchmark passed.",
            }
        )
        for plugin_root in (CLAUDE_ROOT, OPENAI_ROOT):
            wrapper = plugin_root / "scripts" / "hook_dispatch.py"
            with self.subTest(plugin=plugin_root.parent.name, mode="malformed"):
                malformed = _run_python(wrapper, "post-tool-use", stdin="{")
                self.assertEqual(2, malformed.returncode)
                self.assertIn("rejected malformed input", malformed.stderr)
                self.assertEqual("", malformed.stdout)
            with self.subTest(plugin=plugin_root.parent.name, mode="unsupported"):
                result = _run_python(wrapper, "stop", stdin=unsupported)
                self.assertEqual(0, result.returncode, result.stderr)
                decision = json.loads(result.stdout)
                self.assertEqual("block", decision["decision"])
                self.assertIn("report", decision["reason"].casefold())

    def test_wrappers_do_not_write_bytecode_into_a_plugin_copy(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            copied_root = Path(directory) / "plugin"
            (copied_root / "scripts").mkdir(parents=True)
            shutil.copytree(CLAUDE_ROOT / "runtime", copied_root / "runtime")
            shutil.copy2(CLAUDE_ROOT / "scripts" / "pl_lab.py", copied_root / "scripts" / "pl_lab.py")
            environment = os.environ.copy()
            environment.pop("PYTHONDONTWRITEBYTECODE", None)
            result = subprocess.run(
                [sys.executable, str(copied_root / "scripts" / "pl_lab.py"), "pl_pts_compare", "--help"],
                cwd=copied_root,
                env=environment,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=15,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse(any(copied_root.rglob("__pycache__")))
            self.assertFalse(any(copied_root.rglob("*.pyc")))


class PackagingClaimAuditTests(unittest.TestCase):
    def test_no_todo_or_private_absolute_path_is_packaged(self) -> None:
        placeholder = re.compile(r"(?i)\b(?:TODO|FIXME|TBD)\b")
        windows_private = re.compile(r"(?i)\b[A-Z]:[\\/](?:Users|Documents and Settings)[\\/]")
        posix_private = re.compile(r"(?i)(?<![\w])/(?:Users|home)/[A-Za-z0-9._-]+/")
        text_suffixes = {".json", ".md", ".py", ".yaml", ".yml", ".txt"}
        for plugin_root in (CLAUDE_ROOT, OPENAI_ROOT):
            for path in plugin_root.rglob("*"):
                if not path.is_file() or path.suffix.casefold() not in text_suffixes:
                    continue
                with self.subTest(plugin=plugin_root.parent.name, path=path.name):
                    text = path.read_text(encoding="utf-8")
                    self.assertIsNone(placeholder.search(text))
                    self.assertIsNone(windows_private.search(text))
                    self.assertIsNone(posix_private.search(text))
                    self.assertNotIn(str(REPOSITORY_ROOT), text)

    def test_invocation_claims_are_host_specific(self) -> None:
        # Generated architecture/schema documents intentionally compare both
        # hosts. Invocation exclusivity applies to host-facing entrypoints and
        # role adapters, not those shared cross-host verification records.
        claude_paths = [CLAUDE_ROOT / "README.md"]
        claude_paths.extend(CLAUDE_ROOT / "skills" / name / "SKILL.md" for name in WORKFLOWS)
        claude_paths.extend(CLAUDE_ROOT / "agents" / f"{name}.md" for name in ROLES)
        openai_paths = [OPENAI_ROOT / "README.md"]
        openai_paths.extend(OPENAI_ROOT / "skills" / name / "SKILL.md" for name in WORKFLOWS)
        openai_paths.extend(OPENAI_ROOT / "skills" / name / "SKILL.md" for name in ROLES)
        claude_docs = "\n".join(path.read_text(encoding="utf-8") for path in claude_paths)
        openai_docs = "\n".join(path.read_text(encoding="utf-8") for path in openai_paths)
        for workflow in WORKFLOWS:
            self.assertIn(f"/compressor-pl-lab:{workflow}", claude_docs)
            self.assertIn(f"$compressor-pl-lab:{workflow}", openai_docs)
        self.assertNotIn("$compressor-pl-lab:", claude_docs)
        self.assertNotIn("${CLAUDE_PLUGIN_ROOT}", openai_docs)
        self.assertNotIn("/compressor-pl-lab:", openai_docs)
        self.assertNotRegex(
            claude_docs,
            r"(?m)(?<![\w:-])/(?:pl-calibrate|pl-benchmark|pl-investigate)\b",
        )
        self.assertNotRegex(
            openai_docs,
            r"(?m)(?<![\w:-])/(?:pl-calibrate|pl-benchmark|pl-investigate)\b",
        )
        self.assertRegex(openai_docs, r"(?i)ChatGPT.{0,200}does not")
        self.assertRegex(openai_docs, r"(?i)does not.{0,200}hooks")
        self.assertRegex(openai_docs, r"(?i)Claude slash commands are not valid")


if __name__ == "__main__":
    unittest.main()
