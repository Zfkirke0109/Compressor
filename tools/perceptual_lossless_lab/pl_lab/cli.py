"""Public CLI for the eleven stable operations and workflow plan/resume."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from typing import Sequence

from .contracts import OPERATION_CONTRACTS
from .envelope import exit_for_envelope
from .error_envelope import build_error_result
from .errors import InternalFailure, InvalidInput, LabError
from .freeze import freeze_specification
from .jsonio import load_json
from .operations.runtime import execute_operation
from .workflow import NAMED_WORKFLOWS, execute_named_workflow, plan_workflow, resume_workflow


class _Parser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise InvalidInput(message)


def _operation_parser(subparsers: argparse._SubParsersAction, operation_id: str) -> None:
    parser = subparsers.add_parser(operation_id, help=f"run {operation_id}")
    parser.add_argument("--repository", required=True, help="explicit Compressor repository root")
    parser.add_argument("--request", required=True, help="versioned JSON request path")
    parser.add_argument("--experiment", required=True, help="new or lab-owned experiment directory")
    parser.add_argument("--execute", action="store_true", help="perform bounded work; default is dry-run")
    if operation_id == "pl_generate_report":
        parser.add_argument("--gate", action="store_true", help="exit 2 for an evidence-backed quality FAIL")


def build_parser() -> argparse.ArgumentParser:
    parser = _Parser(prog="compressor-pl-lab", description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    for operation_id in OPERATION_CONTRACTS:
        _operation_parser(subparsers, operation_id)
    spec = subparsers.add_parser("spec", help="freeze a canonical experiment specification")
    spec_sub = spec.add_subparsers(dest="spec_command", required=True)
    freeze = spec_sub.add_parser("freeze")
    freeze.add_argument("--repository", required=True)
    freeze.add_argument("--request", required=True)
    freeze.add_argument("--experiment", required=True)
    freeze.add_argument("--execute", action="store_true")
    workflow = subparsers.add_parser("workflow", help="plan or resume a canonical workflow")
    workflow_sub = workflow.add_subparsers(dest="workflow_command", required=True)
    for name in NAMED_WORKFLOWS:
        named = workflow_sub.add_parser(name, help=f"freeze/verify and plan the {name} workflow")
        named.add_argument("--repository", required=True)
        named.add_argument("--request", required=True)
        named.add_argument("--experiment", required=True)
        named.add_argument("--execute", action="store_true")
        if name == "benchmark":
            named.add_argument("--gate", action="store_true", help="bind gate behavior to the final report step")
    plan = workflow_sub.add_parser("plan")
    plan.add_argument("--request", required=True)
    plan.add_argument("--experiment", required=True)
    plan.add_argument("--execute", action="store_true")
    resume = workflow_sub.add_parser("resume")
    resume.add_argument("--request", required=True)
    resume.add_argument("--experiment", required=True)
    resume.add_argument("--run-id", required=True)
    resume.add_argument("--experiment-spec-sha256", required=True)
    return parser


def _print_json(value: object, stream: object | None = None) -> None:
    print(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")), file=stream if stream is not None else sys.stdout)


def main(argv: Sequence[str] | None = None) -> int:
    try:
        args = build_parser().parse_args(argv)
        if args.command in OPERATION_CONTRACTS:
            request = load_json(Path(args.request))
            if not isinstance(request, dict):
                raise InvalidInput("operation request root must be an object")
            envelope = execute_operation(args.command, request, repository=Path(args.repository), experiment=Path(args.experiment), execute=args.execute)
            _print_json(envelope)
            if envelope["status"] == "DRY_RUN":
                return 0
            if envelope["status"] in ("COMPLETED", "BLOCKED"):
                return exit_for_envelope(
                    args.command,
                    envelope["status"],
                    envelope.get("quality_verdict"),
                    gate=bool(getattr(args, "gate", False)),
                )
            errors = envelope.get("errors", [])
            if errors and isinstance(errors[0].get("exit_code"), int):
                return errors[0]["exit_code"]
            return InternalFailure.exit_code
        if args.command == "spec":
            request = load_json(Path(args.request))
            if not isinstance(request, dict):
                raise InvalidInput("spec-freeze request root must be an object")
            result = freeze_specification(Path(args.repository), Path(args.experiment), request, execute=args.execute)
            _print_json(result)
            return 0
        request = load_json(Path(args.request))
        if not isinstance(request, dict):
            raise InvalidInput("workflow request root must be an object")
        if args.workflow_command in NAMED_WORKFLOWS:
            result = execute_named_workflow(
                args.workflow_command,
                repository=Path(args.repository),
                experiment=Path(args.experiment),
                request=request,
                execute=args.execute,
                gate=bool(getattr(args, "gate", False)),
            )
            _print_json(result)
            return result["exit_code"]
        elif args.workflow_command == "plan":
            from datetime import datetime, timezone
            created_at = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
            result = plan_workflow(Path(args.experiment), request, execute=args.execute, created_at=created_at)
        else:
            result = resume_workflow(Path(args.experiment), args.run_id, request=request, experiment_spec_sha256=args.experiment_spec_sha256)
        _print_json(result)
        return 0
    except LabError as exc:
        _print_json(build_error_result(exc), sys.stderr)
        return exc.exit_code
    except (OSError, ValueError, TypeError) as exc:
        failure = InternalFailure(f"unhandled runtime failure: {type(exc).__name__}")
        _print_json(build_error_result(failure), sys.stderr)
        return failure.exit_code


if __name__ == "__main__":
    raise SystemExit(main())
