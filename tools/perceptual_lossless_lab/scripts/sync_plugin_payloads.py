"""Generate self-contained Claude/OpenAI plugin payloads from canonical sources."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import secrets
import sys


SCRIPT = Path(__file__).resolve()
LAB_ROOT = SCRIPT.parents[1]
REPOSITORY_ROOT = SCRIPT.parents[3]
PLUGIN_ROOTS = (
    REPOSITORY_ROOT / "plugins" / "claude" / "compressor-pl-lab",
    REPOSITORY_ROOT / "plugins" / "openai" / "compressor-pl-lab",
)
CONSTITUTION = REPOSITORY_ROOT / ".claude" / "skills" / "android-media3-perceptual-compression"

RUNTIME_TREES = (
    (LAB_ROOT / "pl_lab", Path("runtime/pl_lab")),
    (LAB_ROOT / "schemas", Path("runtime/schemas")),
    (LAB_ROOT / "contracts", Path("runtime/contracts")),
)

PLUGIN_ENTRYPOINT = '''"""Generated relocation-safe entrypoint for the bundled PL lab runtime."""

from __future__ import annotations

from pathlib import Path
import sys


sys.dont_write_bytecode = True
RUNTIME_ROOT = Path(__file__).resolve().parents[1] / "runtime"
if str(RUNTIME_ROOT) not in sys.path:
    sys.path.insert(0, str(RUNTIME_ROOT))

from pl_lab.cli import main  # noqa: E402


if __name__ == "__main__":
    raise SystemExit(main())
'''.encode("utf-8")

PLUGIN_HOOK_ENTRYPOINT = '''"""Generated relocation-safe entrypoint for the bundled PL lab hooks."""

from __future__ import annotations

from pathlib import Path
import sys


sys.dont_write_bytecode = True
RUNTIME_ROOT = Path(__file__).resolve().parents[1] / "runtime"
if str(RUNTIME_ROOT) not in sys.path:
    sys.path.insert(0, str(RUNTIME_ROOT))

from pl_lab.hooks import dispatch_main  # noqa: E402


if __name__ == "__main__":
    raise SystemExit(dispatch_main())
'''.encode("utf-8")


def _is_reparse_point(path: Path) -> bool:
    try:
        attributes = getattr(path.lstat(), "st_file_attributes", 0)
    except OSError:
        return False
    return path.is_symlink() or bool(attributes & 0x400)


def _assert_safe_chain(root: Path, target: Path, *, require_target: bool) -> None:
    root_resolved = root.resolve(strict=True)
    try:
        relative = target.relative_to(root)
    except ValueError as exc:
        raise RuntimeError(f"path is outside its declared root: {target}") from exc
    if relative.is_absolute() or any(part in ("", ".", "..") for part in relative.parts):
        raise RuntimeError(f"unsafe generated path: {relative}")
    current = root
    for index, part in enumerate(relative.parts):
        current = current / part
        exists = current.exists() or current.is_symlink()
        if not exists:
            if require_target or index < len(relative.parts) - 1:
                raise RuntimeError(f"required path is unavailable: {current}")
            break
        if _is_reparse_point(current):
            raise RuntimeError(f"path must not contain a reparse point: {current}")
        if index < len(relative.parts) - 1 and not current.is_dir():
            raise RuntimeError(f"path ancestor is not a directory: {current}")
    existing = target if target.exists() else target.parent
    try:
        existing.resolve(strict=True).relative_to(root_resolved)
    except (OSError, ValueError) as exc:
        raise RuntimeError(f"path escaped its declared root: {target}") from exc


def _stable_bytes(path: Path, *, root: Path) -> bytes:
    _assert_safe_chain(root, path, require_target=True)
    before = path.lstat()
    if not path.is_file() or getattr(before, "st_nlink", 1) != 1:
        raise RuntimeError(f"generated/source file must be a single-link regular file: {path}")
    with path.open("rb") as handle:
        opened = os.fstat(handle.fileno())
        data = handle.read()
    after = path.lstat()
    identity_before = (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
    identity_opened = (opened.st_dev, opened.st_ino, opened.st_size, opened.st_mtime_ns)
    identity_after = (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
    if identity_before != identity_opened or identity_before != identity_after or len(data) != before.st_size:
        raise RuntimeError(f"file changed while it was captured: {path}")
    return data


def _source_files(root: Path) -> dict[Path, bytes]:
    repository_root = REPOSITORY_ROOT.resolve(strict=True)
    _assert_safe_chain(repository_root, root, require_target=True)
    result: dict[Path, bytes] = {}
    for path in sorted(root.rglob("*")):
        if _is_reparse_point(path):
            raise RuntimeError(f"source tree must not contain a reparse point: {path}")
        if not path.is_file() or "__pycache__" in path.parts or path.suffix in {".pyc", ".pyo"}:
            continue
        result[path.relative_to(root)] = _stable_bytes(path, root=root)
    return result


def _expected() -> dict[Path, bytes]:
    expected: dict[Path, bytes] = {}
    for source, relative_destination in RUNTIME_TREES:
        for relative, data in _source_files(source).items():
            expected[relative_destination / relative] = data
    expected[Path("scripts/pl_lab.py")] = PLUGIN_ENTRYPOINT
    expected[Path("scripts/hook_dispatch.py")] = PLUGIN_HOOK_ENTRYPOINT
    expected[Path("LICENSE")] = _stable_bytes(REPOSITORY_ROOT / "LICENSE", root=REPOSITORY_ROOT)
    expected[Path("docs/DESIGN.md")] = _stable_bytes(LAB_ROOT / "DESIGN.md", root=REPOSITORY_ROOT)
    expected[Path("docs/SCHEMA_VERIFICATION.md")] = _stable_bytes(LAB_ROOT / "SCHEMA_VERIFICATION.md", root=REPOSITORY_ROOT)
    expected[Path("docs/PHASE5_VERIFICATION.md")] = _stable_bytes(LAB_ROOT / "PHASE5_VERIFICATION.md", root=REPOSITORY_ROOT)
    expected[Path("skills/android-media3-perceptual-compression/SKILL.md")] = _stable_bytes(CONSTITUTION / "SKILL.md", root=REPOSITORY_ROOT)
    for relative, data in _source_files(CONSTITUTION / "references").items():
        expected[Path("skills/android-media3-perceptual-compression/references") / relative] = data
    return expected


def _generated_paths(plugin: Path) -> set[Path]:
    plugin_root = _verified_plugin_root(plugin)
    paths: set[Path] = set()
    for root_name in ("runtime", "docs"):
        root = plugin / root_name
        if root.exists() or root.is_symlink():
            _assert_safe_chain(plugin_root, root, require_target=True)
            for path in root.rglob("*"):
                if _is_reparse_point(path):
                    raise RuntimeError(f"generated tree must not contain a reparse point: {path}")
                if path.is_file() and "__pycache__" not in path.parts:
                    paths.add(path.relative_to(plugin))
    for fixed in (Path("scripts/pl_lab.py"), Path("scripts/hook_dispatch.py"), Path("LICENSE"), Path("skills/android-media3-perceptual-compression/SKILL.md")):
        target = plugin / fixed
        if target.exists() or target.is_symlink():
            _assert_safe_chain(plugin_root, target, require_target=True)
        if target.is_file():
            paths.add(fixed)
    references = plugin / "skills" / "android-media3-perceptual-compression" / "references"
    if references.exists() or references.is_symlink():
        _assert_safe_chain(plugin_root, references, require_target=True)
        for path in references.rglob("*"):
            if _is_reparse_point(path):
                raise RuntimeError(f"generated references must not contain a reparse point: {path}")
            if path.is_file():
                paths.add(path.relative_to(plugin))
    return paths


def _check(plugin: Path, expected: dict[Path, bytes]) -> list[str]:
    problems: list[str] = []
    actual_paths = _generated_paths(plugin)
    expected_paths = set(expected)
    for relative in sorted(expected_paths - actual_paths):
        problems.append(f"missing {plugin.name}/{relative.as_posix()}")
    for relative in sorted(actual_paths - expected_paths):
        problems.append(f"extra {plugin.name}/{relative.as_posix()}")
    for relative in sorted(expected_paths & actual_paths):
        if _stable_bytes(plugin / relative, root=plugin) != expected[relative]:
            problems.append(f"drift {plugin.name}/{relative.as_posix()}")
    return problems


def _verified_plugin_root(plugin: Path) -> Path:
    """Resolve a package root without allowing generated cleanup to escape it."""

    packages_root = (REPOSITORY_ROOT / "plugins").resolve(strict=True)
    for segment in (
        REPOSITORY_ROOT / "plugins",
        plugin.parent,
        plugin,
    ):
        if _is_reparse_point(segment):
            raise RuntimeError(f"plugin path must not contain a reparse point: {segment}")
    resolved = plugin.resolve(strict=True)
    if not resolved.is_dir() or not resolved.is_relative_to(packages_root):
        raise RuntimeError(f"plugin root is outside the repository package tree: {plugin}")
    expected_relative = plugin.relative_to(REPOSITORY_ROOT).as_posix()
    if expected_relative not in {
        "plugins/claude/compressor-pl-lab",
        "plugins/openai/compressor-pl-lab",
    }:
        raise RuntimeError(f"refusing an unknown plugin root: {plugin}")
    return resolved


def _safe_target(plugin: Path, relative: Path, *, create_parents: bool) -> Path:
    plugin_root = _verified_plugin_root(plugin)
    if relative.is_absolute() or any(part in ("", ".", "..") for part in relative.parts):
        raise RuntimeError(f"unsafe generated target: {relative}")
    parent = plugin_root
    for part in relative.parts[:-1]:
        parent = parent / part
        if parent.exists() or parent.is_symlink():
            if _is_reparse_point(parent) or not parent.is_dir():
                raise RuntimeError(f"generated parent is unsafe: {parent}")
        elif create_parents:
            parent.mkdir()
            if _is_reparse_point(parent) or not parent.is_dir():
                raise RuntimeError(f"generated parent is unsafe after creation: {parent}")
        else:
            raise RuntimeError(f"generated parent is unavailable: {parent}")
    if not parent.resolve(strict=True).is_relative_to(plugin_root):
        raise RuntimeError(f"generated parent escaped the plugin root: {parent}")
    target = parent / relative.name
    if target.exists() or target.is_symlink():
        _assert_safe_chain(plugin_root, target, require_target=True)
    return target


def _write(plugin: Path, expected: dict[Path, bytes]) -> None:
    plugin_root = _verified_plugin_root(plugin)
    previous_paths = _generated_paths(plugin)
    for relative, data in sorted(expected.items()):
        target = _safe_target(plugin, relative, create_parents=True)
        if target.exists():
            _stable_bytes(target, root=plugin_root)
        temporary = target.with_name(f".{target.name}.{os.getpid()}.{secrets.token_hex(8)}.tmp")
        try:
            descriptor = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(descriptor, "wb") as handle:
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
            if _is_reparse_point(temporary):
                raise RuntimeError(f"generated temporary file became a reparse point: {temporary}")
            os.replace(temporary, target)
            _stable_bytes(target, root=plugin_root)
        finally:
            if temporary.exists() and not temporary.is_symlink():
                temporary.unlink()
    for relative in sorted(previous_paths - set(expected), reverse=True):
        target = _safe_target(plugin, relative, create_parents=False)
        _stable_bytes(target, root=plugin_root)
        target.unlink()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if generated plugin payloads differ")
    args = parser.parse_args(argv)
    problems: list[str] = []
    try:
        expected = _expected()
    except (OSError, RuntimeError) as exc:
        print(f"cannot capture canonical plugin payload: {exc}", file=sys.stderr)
        return 1
    for plugin in PLUGIN_ROOTS:
        if not plugin.is_dir():
            problems.append(f"missing plugin root: {plugin.name}")
            continue
        try:
            if args.check:
                problems.extend(_check(plugin, expected))
            else:
                _write(plugin, expected)
                problems.extend(_check(plugin, expected))
        except (OSError, RuntimeError) as exc:
            problems.append(f"unsafe or incomplete {plugin.name} payload: {exc}")
    try:
        if _expected() != expected:
            problems.append("canonical plugin payload changed during synchronization; rerun from a stable checkout")
    except (OSError, RuntimeError) as exc:
        problems.append(f"cannot revalidate canonical plugin payload: {exc}")
    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1
    print("plugin payloads are synchronized" if args.check else "plugin payloads generated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
