"""Hardcoded operation and record ownership contracts."""

from __future__ import annotations

from dataclasses import dataclass

from .errors import InvalidInput


RECORD_AUTHORITIES = {
    "experiment_spec": "deterministic-core",
    "corpus": "deterministic-core",
    "artifact": "deterministic-core",
    "baseline": "deterministic-core",
    "candidate": "codec-scientist",
    "evaluation": "perceptual-evaluator",
    "device": "android-device-lab",
    "comparison": "deterministic-core",
    "calibration": "deterministic-core",
    "investigation": "deterministic-core",
    "verdict": "regression-judge",
    "report": "regression-judge",
}


@dataclass(frozen=True)
class OperationContract:
    operation_id: str
    module: str
    producer_role: str
    output_record_types: tuple[str, ...]
    request_schema: str = ""
    result_schema: str = "v1/result-envelope.schema.json"
    dry_run_default: bool = True
    expensive: bool = False
    device_changing: bool = False

    @property
    def record_type(self) -> str:
        """Return the primary/final output type for legacy callers."""
        return self.output_record_types[-1]


def _contract(
    operation_id: str,
    module: str,
    role: str,
    output_record_types: str | tuple[str, ...],
    **kwargs: bool,
) -> OperationContract:
    if isinstance(output_record_types, str):
        output_record_types = (output_record_types,)
    if not output_record_types or len(set(output_record_types)) != len(output_record_types):
        raise RuntimeError(f"invalid built-in output record types for {operation_id}")
    if any(RECORD_AUTHORITIES.get(record_type) != role for record_type in output_record_types):
        raise RuntimeError(f"invalid built-in authority for {operation_id}")
    return OperationContract(
        operation_id,
        module,
        role,
        output_record_types,
        request_schema=f"v1/requests/{operation_id}.schema.json",
        **kwargs,
    )


OPERATION_CONTRACTS = {
    "pl_device_inventory": _contract("pl_device_inventory", "pl_lab.operations.device", "android-device-lab", "device"),
    "pl_build_install": _contract(
        "pl_build_install", "pl_lab.operations.device", "android-device-lab", "device",
        expensive=True, device_changing=True,
    ),
    "pl_encode_candidate": _contract(
        "pl_encode_candidate", "pl_lab.operations.codec", "codec-scientist", "candidate", expensive=True,
    ),
    "pl_score_pair": _contract(
        "pl_score_pair", "pl_lab.operations.quality", "perceptual-evaluator", "evaluation", expensive=True,
    ),
    "pl_score_corpus": _contract(
        "pl_score_corpus", "pl_lab.operations.quality", "perceptual-evaluator", "evaluation", expensive=True,
    ),
    "pl_hdr_compare": _contract("pl_hdr_compare", "pl_lab.operations.quality", "perceptual-evaluator", "evaluation"),
    "pl_pts_compare": _contract("pl_pts_compare", "pl_lab.operations.quality", "perceptual-evaluator", "evaluation"),
    "pl_run_boundary_suite": _contract(
        "pl_run_boundary_suite", "pl_lab.operations.boundary", "deterministic-core", "artifact", expensive=True,
    ),
    "pl_optimize_policy": _contract(
        "pl_optimize_policy", "pl_lab.operations.codec", "codec-scientist", "candidate", expensive=True,
    ),
    "pl_compare_baseline": _contract(
        "pl_compare_baseline", "pl_lab.operations.compare", "deterministic-core", "comparison",
    ),
    "pl_generate_report": _contract(
        "pl_generate_report", "pl_lab.operations.report", "regression-judge", ("verdict", "report"),
    ),
}


def operation_contract(operation_id: str) -> OperationContract:
    try:
        return OPERATION_CONTRACTS[operation_id]
    except KeyError as exc:
        raise InvalidInput(f"unknown operation ID: {operation_id}") from exc
