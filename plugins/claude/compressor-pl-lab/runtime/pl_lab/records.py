"""Strict, content-addressed immutable evidence records."""

from __future__ import annotations

from pathlib import Path
import re
from typing import Any

from .authority import AuthoritySnapshot
from .canonical import domain_hash, seal_document, verify_sealed_document
from .contracts import RECORD_AUTHORITIES
from .errors import EvidenceBlocked, ImmutableConflict, InvalidInput
from .experiment import ExperimentStore
from .jsonio import canonical_json_file_bytes, load_json
from .sanitize import assert_sanitized
from .schema import SchemaRegistry
from .storage import atomic_publish_new
from .validation import require_run_id, require_sha256, require_utc_timestamp


_FORBIDDEN_NON_JUDGE_KEYS = frozenset({"verdict", "overall_verdict", "quality_verdict", "winner", "winning_candidate", "approved"})
_FORBIDDEN_NORMALIZED_KEYS = frozenset({"verdict", "overallverdict", "qualityverdict", "winner", "winningcandidate", "approved"})

_PAYLOAD_FIELDS: dict[str, tuple[frozenset[str], frozenset[str]]] = {
    "corpus": (frozenset({"manifest_sha256", "source_identities_sha256", "pair_identities_sha256", "item_count", "manifest_alias"}), frozenset()),
    "artifact": (frozenset({"artifact_id", "logical_path", "sha256", "size_bytes", "status"}), frozenset({"tool_versions", "warnings", "confounders"})),
    "baseline": (frozenset({"baseline_id", "artifact_sha256", "status", "confounders"}), frozenset()),
    "candidate": (frozenset({"candidate_id", "configuration", "artifact_sha256", "status", "warnings"}), frozenset({"requested_configuration", "actual_configuration"})),
    "evaluation": (frozenset({"evaluation_kind", "evaluation_id", "subject_sha256", "metric_results", "alignment", "hdr", "provenance", "status", "confounders"}), frozenset({"window_results"})),
    "device": (frozenset({"device_alias", "android_user", "package_id", "build_identity", "signer_sha256", "firmware_fingerprint_sha256", "requested_configuration", "actual_configuration", "thermals", "status", "confounders"}), frozenset()),
    "comparison": (frozenset({"baseline_sha256", "candidate_sha256", "baseline_evaluation_sha256s", "candidate_evaluation_sha256s", "device_sha256", "deltas", "constraint_checks", "status", "confounders"}), frozenset()),
    "calibration": (frozenset({"calibration_id", "resolved_constraints", "authority_sha256", "status", "confounders"}), frozenset({"proposal"})),
    "investigation": (frozenset({"investigation_id", "subject_sha256", "finding_codes", "status", "confounders"}), frozenset({"hypotheses"})),
    "verdict": (frozenset({"verdict", "reasons", "evidence_sha256s", "constraint_checks"}), frozenset()),
    "report": (frozenset({"verdict_sha256", "verdict", "summary", "evidence_sha256s"}), frozenset({"numeric_tolerances"})),
}

_LINK_FIELDS: dict[str, tuple[frozenset[str], frozenset[str]]] = {
    "corpus": (frozenset({"experiment_spec"}), frozenset()),
    "artifact": (frozenset({"experiment_spec"}), frozenset({"candidate", "evaluation", "device"})),
    "baseline": (frozenset({"experiment_spec"}), frozenset({"artifact", "device"})),
    "candidate": (frozenset({"experiment_spec"}), frozenset({"artifact", "device", "source_artifact"})),
    "evaluation": (frozenset({"experiment_spec"}), frozenset({"subject", "artifact", "device", "corpus"})),
    "device": (frozenset({"experiment_spec"}), frozenset({"artifacts"})),
    "comparison": (frozenset({"experiment_spec", "baseline", "candidate", "baseline_evaluations", "candidate_evaluations", "device"}), frozenset()),
    "calibration": (frozenset({"experiment_spec", "corpus"}), frozenset({"evaluations", "boundary_artifact"})),
    "investigation": (frozenset({"experiment_spec", "subject"}), frozenset({"evidence"})),
    "verdict": (frozenset({"experiment_spec", "corpus", "calibration", "calibration_evaluations", "boundary_artifact", "baseline", "candidate", "baseline_evaluations", "candidate_evaluations", "device", "comparison"}), frozenset()),
    "report": (frozenset({"experiment_spec", "verdict"}), frozenset()),
}


def _assert_hash(value: Any, path: str) -> None:
    require_sha256(value, path)


def _walk_forbidden(value: Any, path: str = "$") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = re.sub(r"[^a-z0-9]", "", key.casefold())
            if key.casefold() in _FORBIDDEN_NON_JUDGE_KEYS or normalized in _FORBIDDEN_NORMALIZED_KEYS or "threshold" in normalized:
                raise InvalidInput(f"non-judge record contains verdict/approval field at {path}.{key}")
            _walk_forbidden(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _walk_forbidden(child, f"{path}[{index}]")


def _validate_fields(record_type: str, value: dict[str, Any], contracts: dict[str, tuple[frozenset[str], frozenset[str]]], label: str) -> None:
    required, optional = contracts[record_type]
    missing = sorted(required - set(value))
    unknown = sorted(set(value) - required - optional)
    if missing or unknown:
        raise InvalidInput(f"invalid {record_type} {label}", details={"missing": missing, "unknown": unknown})


def _validate_payload(record_type: str, payload: dict[str, Any]) -> None:
    if record_type not in _PAYLOAD_FIELDS:
        raise InvalidInput(f"unsupported record payload type: {record_type}")
    if not isinstance(payload, dict):
        raise InvalidInput("record payload must be an object")
    _validate_fields(record_type, payload, _PAYLOAD_FIELDS, "payload")
    if record_type not in ("verdict", "report"):
        _walk_forbidden(payload)
    status = payload.get("status")
    if status is not None and status not in ("COMPLETE", "BLOCKED"):
        raise InvalidInput("evidence status must be COMPLETE or BLOCKED")
    verdict = payload.get("verdict")
    if record_type in ("verdict", "report") and verdict not in ("PASS", "FAIL", "BLOCKED"):
        raise InvalidInput("judge verdict must be PASS, FAIL, or BLOCKED")
    for key in ("artifact_sha256", "subject_sha256", "device_sha256", "baseline_sha256", "candidate_sha256", "verdict_sha256", "signer_sha256", "firmware_fingerprint_sha256", "authority_sha256"):
        if key in payload:
            _assert_hash(payload[key], f"payload.{key}")
    for key in ("evaluation_sha256s", "baseline_evaluation_sha256s", "candidate_evaluation_sha256s", "evidence_sha256s"):
        if key in payload:
            if not isinstance(payload[key], list) or any(not isinstance(item, str) or re.fullmatch(r"[0-9a-f]{64}", item) is None for item in payload[key]):
                raise InvalidInput(f"invalid hash list at payload.{key}")
    if record_type == "corpus":
        for key in ("manifest_sha256", "source_identities_sha256", "pair_identities_sha256"):
            _assert_hash(payload[key], f"payload.{key}")
        if not isinstance(payload["item_count"], int) or isinstance(payload["item_count"], bool) or payload["item_count"] <= 0:
            raise InvalidInput("corpus item count must be a positive integer")
        alias = payload["manifest_alias"]
        if not isinstance(alias, str) or not alias or Path(alias).name != alias or "/" in alias or "\\" in alias:
            raise InvalidInput("corpus manifest alias must be a filename")
    if record_type == "artifact":
        if not isinstance(payload["artifact_id"], str) or not payload["artifact_id"]:
            raise InvalidInput("artifact ID is invalid")
        from .paths import logical_path
        logical_path(payload["logical_path"])
        _assert_hash(payload["sha256"], "payload.sha256")
        if not isinstance(payload["size_bytes"], int) or isinstance(payload["size_bytes"], bool) or payload["size_bytes"] < 0:
            raise InvalidInput("artifact size is invalid")
    if record_type == "evaluation":
        evaluation_kind = payload["evaluation_kind"]
        if evaluation_kind not in ("subject_quality", "corpus_calibration"):
            raise InvalidInput("evaluation kind is invalid")
        metrics = payload["metric_results"]
        if not isinstance(metrics, dict) or not metrics or any(not isinstance(key, str) or not isinstance(value, (int, float)) or isinstance(value, bool) for key, value in metrics.items()):
            raise InvalidInput("evaluation metric_results must be a numeric object")
        if not isinstance(payload["alignment"], dict) or payload["alignment"].get("status") not in ("ALIGNED", "MISALIGNED", "BLOCKED", "NOT_APPLICABLE"):
            raise InvalidInput("evaluation alignment is invalid")
        if not isinstance(payload["hdr"], dict) or payload["hdr"].get("status") not in ("MATCH", "MISMATCH", "BLOCKED", "NOT_APPLICABLE"):
            raise InvalidInput("evaluation HDR result is invalid")
        if not isinstance(payload["provenance"], dict) or not payload["provenance"]:
            raise InvalidInput("evaluation provenance must be a nonempty object")
        if evaluation_kind == "corpus_calibration":
            if payload["alignment"] != {"status": "NOT_APPLICABLE"} or payload["hdr"] != {"status": "NOT_APPLICABLE"}:
                raise InvalidInput("corpus calibration alignment/HDR must be not applicable")
            required_provenance = {
                "metric_id", "metric_version", "metric_model_sha256", "corpus_manifest_sha256",
                "corpus_pair_identities_sha256", "pair_id", "scorer_authority_sha256",
                "scorer_report_sha256", "python_executable_sha256", "ffmpeg_executable_sha256",
                "ffprobe_executable_sha256", "ffmpeg_version_sha256", "ffprobe_version_sha256",
                "source_path", "source_content_sha256", "source_size_bytes",
                "output_path", "output_content_sha256", "output_size_bytes",
                "source_frame_count", "output_frame_count", "source_duration_ms", "output_duration_ms",
            }
            if set(payload["provenance"]) != required_provenance:
                raise InvalidInput("corpus calibration provenance is incomplete or unknown")
            for key in required_provenance - {
                "metric_id", "metric_version", "pair_id", "source_path", "source_size_bytes",
                "output_path", "output_size_bytes", "source_frame_count", "output_frame_count",
                "source_duration_ms", "output_duration_ms",
            }:
                _assert_hash(payload["provenance"][key], f"payload.provenance.{key}")
            for key in ("metric_id", "metric_version", "pair_id"):
                if not isinstance(payload["provenance"][key], str) or not payload["provenance"][key]:
                    raise InvalidInput(f"corpus calibration {key} is invalid")
            from .paths import logical_path
            source_path = logical_path(payload["provenance"]["source_path"])
            output_path = logical_path(payload["provenance"]["output_path"])
            if source_path == output_path:
                raise InvalidInput("corpus calibration source/output paths must differ")
            for key in (
                "source_size_bytes", "output_size_bytes", "source_frame_count", "output_frame_count",
                "source_duration_ms", "output_duration_ms",
            ):
                if not isinstance(payload["provenance"][key], int) or isinstance(payload["provenance"][key], bool) or payload["provenance"][key] <= 0:
                    raise InvalidInput(f"corpus calibration {key} is invalid")
    if record_type == "device":
        build = payload["build_identity"]
        if not isinstance(build, dict) or set(build) - {"version_code", "version_name", "apk_sha256", "source_sha256"}:
            raise InvalidInput("device build identity contains unknown fields")
        if "version_code" not in build or not isinstance(build["version_code"], int) or isinstance(build["version_code"], bool):
            raise InvalidInput("device build version_code is required")
    if record_type == "verdict":
        if not isinstance(payload["reasons"], list) or not payload["reasons"] or not all(isinstance(item, str) and item for item in payload["reasons"]):
            raise InvalidInput("verdict reasons must be nonempty strings")
        if not isinstance(payload["evidence_sha256s"], list) or not payload["evidence_sha256s"] or len(set(payload["evidence_sha256s"])) != len(payload["evidence_sha256s"]):
            raise InvalidInput("verdict evidence identities must be nonempty and unique")
        if not isinstance(payload["constraint_checks"], list) or (payload["verdict"] in ("PASS", "FAIL") and not payload["constraint_checks"]):
            raise InvalidInput("PASS/FAIL verdict requires constraint checks")
    if record_type == "report":
        if not isinstance(payload["summary"], dict) or not payload["summary"]:
            raise InvalidInput("report summary or verdict identity is invalid")
        if not isinstance(payload["evidence_sha256s"], list) or not payload["evidence_sha256s"]:
            raise InvalidInput("report evidence identities must be nonempty")


def _validate_links(record_type: str, links: dict[str, Any], experiment_spec_sha256: str, payload: dict[str, Any]) -> None:
    if not isinstance(links, dict):
        raise InvalidInput("record links must be an object")
    _validate_fields(record_type, links, _LINK_FIELDS, "links")
    if record_type == "evaluation":
        expected = (
            {"experiment_spec", "corpus"}
            if payload.get("evaluation_kind") == "corpus_calibration"
            else {"experiment_spec", "subject", "artifact", "device"}
        )
        if set(links) != expected:
            raise InvalidInput("evaluation links do not match its kind")
    if links.get("experiment_spec") != experiment_spec_sha256:
        raise InvalidInput("record experiment-spec link does not match")
    for key, value in links.items():
        if isinstance(value, list):
            if not value or any(not isinstance(item, str) or re.fullmatch(r"[0-9a-f]{64}", item) is None for item in value):
                raise InvalidInput(f"invalid record link list: {key}")
            if len(set(value)) != len(value):
                raise InvalidInput(f"duplicate record link identity: {key}")
        else:
            _assert_hash(value, f"links.{key}")


def _validate_link_payload_consistency(record_type: str, links: dict[str, Any], payload: dict[str, Any]) -> None:
    if record_type == "evaluation":
        subject_link = links.get("corpus") if payload["evaluation_kind"] == "corpus_calibration" else links.get("subject")
        if payload["subject_sha256"] != subject_link:
            raise InvalidInput("evaluation payload subject does not match its link")
    if record_type == "report" and payload["verdict_sha256"] != links["verdict"]:
        raise InvalidInput("report payload verdict does not match its link")
    if record_type == "comparison":
        mapping = {
            "baseline_sha256": "baseline",
            "candidate_sha256": "candidate",
            "baseline_evaluation_sha256s": "baseline_evaluations",
            "candidate_evaluation_sha256s": "candidate_evaluations",
            "device_sha256": "device",
        }
        if any(payload[payload_key] != links[link_key] for payload_key, link_key in mapping.items()):
            raise InvalidInput("comparison payload identities do not match links")


def _validate_lifecycle(
    record_type: str,
    links: dict[str, Any],
    payload: dict[str, Any],
    supersedes_sha256: str | None,
) -> None:
    if record_type != "calibration":
        return
    status = payload.get("status")
    if status == "BLOCKED":
        if set(links) != {"experiment_spec", "corpus"} or supersedes_sha256 is not None:
            raise InvalidInput("pending calibration must link only its frozen specification and corpus")
        if payload.get("confounders") != ["corpus_evaluation_pending"]:
            raise InvalidInput("pending calibration must name its incomplete corpus evaluation")
        return
    if status == "COMPLETE":
        if set(links) != {"experiment_spec", "corpus", "evaluations", "boundary_artifact"} or supersedes_sha256 is None:
            raise InvalidInput("complete calibration must supersede pending evidence and link corpus evaluations")
        if payload.get("confounders") != []:
            raise InvalidInput("complete calibration cannot retain confounders")
        return
    raise InvalidInput("calibration status is invalid")


def validate_calibration_coverage(corpus: dict[str, Any], evaluations: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Rebuild the canonical corpus identities from COMPLETE calibration evidence."""

    corpus_payload = corpus.get("payload") if isinstance(corpus, dict) else None
    if not isinstance(corpus_payload, dict) or len(evaluations) != corpus_payload.get("item_count"):
        raise InvalidInput("calibration evaluation cardinality does not match the frozen corpus")
    items: list[dict[str, Any]] = []
    for evaluation in evaluations:
        payload = evaluation.get("payload") if isinstance(evaluation, dict) else None
        provenance = payload.get("provenance") if isinstance(payload, dict) else None
        if (
            not isinstance(provenance, dict)
            or payload.get("evaluation_kind") != "corpus_calibration"
            or payload.get("status") != "COMPLETE"
            or payload.get("confounders")
            or provenance.get("corpus_manifest_sha256") != corpus_payload.get("manifest_sha256")
            or provenance.get("corpus_pair_identities_sha256") != corpus_payload.get("pair_identities_sha256")
        ):
            raise InvalidInput("calibration contains incomplete or non-corpus evaluation evidence")
        items.append({
            "pair_id": provenance.get("pair_id"),
            "source_path": provenance.get("source_path"),
            "source_sha256": provenance.get("source_content_sha256"),
            "source_size_bytes": provenance.get("source_size_bytes"),
            "output_path": provenance.get("output_path"),
            "output_sha256": provenance.get("output_content_sha256"),
            "output_size_bytes": provenance.get("output_size_bytes"),
        })
    pair_ids = [item["pair_id"] for item in items]
    if (
        any(not isinstance(pair_id, str) or not pair_id for pair_id in pair_ids)
        or len(set(pair_ids)) != len(pair_ids)
    ):
        raise InvalidInput("calibration pair identities are missing or duplicated")
    items.sort(key=lambda item: item["pair_id"])
    source_paths = {item["source_path"] for item in items}
    output_paths = [item["output_path"] for item in items]
    if len(set(output_paths)) != len(output_paths) or source_paths & set(output_paths):
        raise InvalidInput("calibration pair paths are duplicated or overlap source/output roles")
    source_identities = [
        {
            "source_path": source_path,
            "source_sha256": next(item["source_sha256"] for item in items if item["source_path"] == source_path),
            "source_size_bytes": next(item["source_size_bytes"] for item in items if item["source_path"] == source_path),
        }
        for source_path in sorted(source_paths)
    ]
    for source_path in source_paths:
        identities = {
            (item["source_sha256"], item["source_size_bytes"])
            for item in items
            if item["source_path"] == source_path
        }
        if len(identities) != 1:
            raise InvalidInput("calibration source path has conflicting identities")
    if (
        domain_hash("corpus-source-identities", source_identities) != corpus_payload.get("source_identities_sha256")
        or domain_hash("corpus-pair-identities", items) != corpus_payload.get("pair_identities_sha256")
    ):
        raise InvalidInput("calibration evaluations are not exact members of the frozen corpus")
    return items


def create_record(
    record_type: str,
    *,
    producer_role: str,
    run_id: str,
    created_at: str,
    experiment_spec_sha256: str,
    links: dict[str, Any],
    payload: dict[str, Any],
    supersedes_sha256: str | None = None,
) -> dict[str, Any]:
    expected_role = RECORD_AUTHORITIES.get(record_type)
    if expected_role is None or record_type == "experiment_spec":
        raise InvalidInput(f"unsupported record type: {record_type}")
    if producer_role != expected_role:
        raise InvalidInput(f"producer role {producer_role!r} cannot create {record_type}")
    require_run_id(run_id)
    require_utc_timestamp(created_at)
    _assert_hash(experiment_spec_sha256, "experiment_spec_sha256")
    _validate_payload(record_type, payload)
    _validate_links(record_type, links, experiment_spec_sha256, payload)
    _validate_link_payload_consistency(record_type, links, payload)
    body: dict[str, Any] = {
        "schema_version": "1",
        "record_type": record_type,
        "producer_role": producer_role,
        "run_id": run_id,
        "created_at": created_at,
        "experiment_spec_sha256": experiment_spec_sha256,
        "links": links,
        "payload": payload,
    }
    if supersedes_sha256 is not None:
        _assert_hash(supersedes_sha256, "supersedes_sha256")
        body["supersedes_sha256"] = supersedes_sha256
    _validate_lifecycle(record_type, links, payload, supersedes_sha256)
    assert_sanitized(body)
    return seal_document(f"record:{record_type}", body)


def validate_record(record: dict[str, Any]) -> None:
    if not isinstance(record, dict):
        raise InvalidInput("record must be an object")
    allowed = {"schema_version", "record_type", "producer_role", "run_id", "created_at", "experiment_spec_sha256", "links", "payload", "supersedes_sha256", "content_sha256"}
    if set(record) - allowed:
        raise InvalidInput("record contains unknown top-level fields", details={"unknown": sorted(set(record) - allowed)})
    required = allowed - {"supersedes_sha256"}
    if required - set(record):
        raise InvalidInput("record is missing required top-level fields", details={"missing": sorted(required - set(record))})
    record_type = record.get("record_type")
    if record.get("schema_version") != "1" or record_type not in _PAYLOAD_FIELDS:
        raise InvalidInput("unknown record schema version or type")
    expected_role = RECORD_AUTHORITIES[record_type]
    if record.get("producer_role") != expected_role:
        raise InvalidInput("record producer role violates authority contract")
    require_run_id(record.get("run_id"))
    require_utc_timestamp(record.get("created_at"))
    _assert_hash(record.get("experiment_spec_sha256"), "experiment_spec_sha256")
    _validate_payload(record_type, record.get("payload"))
    _validate_links(record_type, record.get("links"), record["experiment_spec_sha256"], record["payload"])
    _validate_link_payload_consistency(record_type, record["links"], record["payload"])
    if "supersedes_sha256" in record:
        _assert_hash(record["supersedes_sha256"], "supersedes_sha256")
    _validate_lifecycle(record_type, record["links"], record["payload"], record.get("supersedes_sha256"))
    if not verify_sealed_document(f"record:{record_type}", record):
        raise InvalidInput("record content hash mismatch")
    SchemaRegistry(Path(__file__).resolve().parent.parent / "schemas").validate(
        f"v1/{record_type}.schema.json",
        record,
    )
    assert_sanitized(record)


class RecordStore:
    def __init__(self, experiment: Path) -> None:
        self.experiment = ExperimentStore(experiment)

    def _path(self, record_type: str, sha256: str) -> Path:
        return self.experiment.owned_path("records", record_type, f"{sha256}.json")

    def put(self, record: dict[str, Any]) -> Path:
        validate_record(record)
        if record["record_type"] == "verdict" and record["payload"]["verdict"] in ("PASS", "FAIL"):
            raise ImmutableConflict("PASS/FAIL verdicts require verified judge publication")
        return self._put_validated(record)

    def put_verified_verdict(
        self,
        record: dict[str, Any],
        *,
        experiment_spec: dict[str, Any],
        evidence: list[dict[str, Any]],
        authority_snapshot: AuthoritySnapshot,
    ) -> Path:
        """Publish only a byte-exact deterministic judge result.

        A sealed record proves integrity, not that its verdict was calculated by
        the judge.  This publication boundary recomputes the verdict from the
        supplied, live-authority-validated evidence before storing it.
        """

        validate_record(record)
        if record["record_type"] != "verdict":
            raise ImmutableConflict("verified verdict publication requires a verdict record")
        from .judge import judge_evidence

        expected = judge_evidence(
            experiment_spec,
            evidence,
            created_at=record["created_at"],
            authority_snapshot=authority_snapshot,
        )
        if expected != record:
            raise ImmutableConflict("verdict differs from deterministic judge recomputation")
        return self._put_validated(record)

    def _put_validated(self, record: dict[str, Any]) -> Path:
        record_type = record["record_type"]
        if record_type == "calibration":
            try:
                corpus = self.find(record["links"]["corpus"])
            except (InvalidInput, ImmutableConflict) as exc:
                raise ImmutableConflict("calibration corpus is unavailable or invalid") from exc
            if (
                corpus["record_type"] != "corpus"
                or corpus["run_id"] != record["run_id"]
                or corpus["experiment_spec_sha256"] != record["experiment_spec_sha256"]
            ):
                raise ImmutableConflict("calibration corpus crosses a run or specification")
        supersedes = record.get("supersedes_sha256")
        if supersedes is not None:
            try:
                prior_record = self.get(record_type, supersedes)
            except (InvalidInput, ImmutableConflict) as exc:
                raise ImmutableConflict("superseded record is invalid") from exc
            if prior_record["run_id"] != record["run_id"] or prior_record["experiment_spec_sha256"] != record["experiment_spec_sha256"]:
                raise ImmutableConflict("supersession must stay in the same run and experiment specification")
            calibration_completion = (
                record_type == "calibration"
                and prior_record["links"].keys() == {"experiment_spec", "corpus"}
                and record["links"].keys() == {"experiment_spec", "corpus", "evaluations", "boundary_artifact"}
                and prior_record["links"]["experiment_spec"] == record["links"]["experiment_spec"]
                and prior_record["links"]["corpus"] == record["links"]["corpus"]
                and prior_record["payload"].get("status") == "BLOCKED"
                and prior_record["payload"].get("confounders") == ["corpus_evaluation_pending"]
                and record["payload"].get("status") == "COMPLETE"
                and record["payload"].get("confounders") == []
                and prior_record["payload"].get("resolved_constraints") == record["payload"].get("resolved_constraints")
                and prior_record["payload"].get("authority_sha256") == record["payload"].get("authority_sha256")
                and prior_record["payload"].get("proposal") == record["payload"].get("proposal")
            )
            if prior_record["links"] != record["links"] and not calibration_completion:
                raise ImmutableConflict("supersession cannot change immutable lineage links")
            identity_fields = {
                "artifact": "artifact_id",
                "baseline": "baseline_id",
                "candidate": "candidate_id",
                "evaluation": "evaluation_id",
                "device": "device_alias",
                "calibration": "calibration_id",
                "investigation": "investigation_id",
            }
            identity_field = identity_fields.get(record_type)
            if identity_field is not None and prior_record["payload"].get(identity_field) != record["payload"].get(identity_field):
                raise ImmutableConflict("supersession cannot change the logical record identity")
            if calibration_completion:
                try:
                    corpus = self.find(record["links"]["corpus"])
                    boundary_artifact = self.find(record["links"]["boundary_artifact"])
                    evaluations = [self.find(identity) for identity in record["links"]["evaluations"]]
                    from .specification import ExperimentSpecStore
                    experiment_spec = ExperimentSpecStore(self.experiment.root).get(record["experiment_spec_sha256"])
                except (InvalidInput, ImmutableConflict, EvidenceBlocked) as exc:
                    raise ImmutableConflict("calibration completion evidence is unavailable or invalid") from exc
                if (
                    corpus["record_type"] != "corpus"
                    or corpus["run_id"] != record["run_id"]
                    or corpus["experiment_spec_sha256"] != record["experiment_spec_sha256"]
                    or boundary_artifact["record_type"] != "artifact"
                    or boundary_artifact["run_id"] != record["run_id"]
                    or boundary_artifact["experiment_spec_sha256"] != record["experiment_spec_sha256"]
                    or boundary_artifact["payload"].get("status") != "COMPLETE"
                    or boundary_artifact["payload"].get("confounders")
                    or any(
                        evaluation["record_type"] != "evaluation"
                        or evaluation["run_id"] != record["run_id"]
                        or evaluation["experiment_spec_sha256"] != record["experiment_spec_sha256"]
                        or evaluation["payload"].get("evaluation_kind") != "corpus_calibration"
                        or evaluation["payload"].get("status") != "COMPLETE"
                        or evaluation["payload"].get("confounders")
                        or evaluation["links"]
                        != {
                            "experiment_spec": record["experiment_spec_sha256"],
                            "corpus": record["links"]["corpus"],
                        }
                        for evaluation in evaluations
                    )
                ):
                    raise ImmutableConflict("calibration completion evidence violates its frozen lineage")
                try:
                    validate_calibration_coverage(corpus, evaluations)
                    from .operations.boundary import validate_boundary_artifact_record
                    from .operations.report import verify_artifact_bytes
                    validate_boundary_artifact_record(boundary_artifact, experiment_spec)
                    verify_artifact_bytes(self, [boundary_artifact])
                except (InvalidInput, EvidenceBlocked) as exc:
                    raise ImmutableConflict("calibration completion does not exactly cover the frozen corpus")
            record_directory = self.experiment.owned_path("records", record_type)
            if record_directory.is_dir():
                for candidate_path in record_directory.glob("*.json"):
                    candidate = load_json(candidate_path)
                    validate_record(candidate)
                    if candidate.get("supersedes_sha256") == supersedes and candidate["content_sha256"] != record["content_sha256"]:
                        raise ImmutableConflict("supersession cannot create divergent successor branches")
        if record_type in ("verdict", "report"):
            self._validate_judge_links(record)
        target = self._path(record_type, record["content_sha256"])
        atomic_publish_new(target, canonical_json_file_bytes(record), root=self.experiment.root)
        return target

    def _validate_judge_links(self, record: dict[str, Any]) -> None:
        if record["record_type"] == "verdict":
            evidence_sha256s = record["payload"]["evidence_sha256s"]
            try:
                evidence = [self.find(identity) for identity in evidence_sha256s]
            except (InvalidInput, ImmutableConflict) as exc:
                raise ImmutableConflict("verdict evidence link is unavailable or invalid") from exc
            evidence_set = set(evidence_sha256s)
            linked = {
                value
                for key, value in record["links"].items()
                if key != "experiment_spec"
                for value in (value if isinstance(value, list) else [value])
            }
            if not linked <= evidence_set:
                raise ImmutableConflict("verdict links are not contained in its evidence identities")
            for evidence_record in evidence:
                if evidence_record["run_id"] != record["run_id"] or evidence_record["experiment_spec_sha256"] != record["experiment_spec_sha256"]:
                    raise ImmutableConflict("verdict evidence crosses a run or specification")
            return
        try:
            verdict = self.get("verdict", record["payload"]["verdict_sha256"])
        except (InvalidInput, ImmutableConflict) as exc:
            raise ImmutableConflict("report verdict link is unavailable or invalid") from exc
        if verdict["run_id"] != record["run_id"] or verdict["experiment_spec_sha256"] != record["experiment_spec_sha256"]:
            raise ImmutableConflict("report verdict crosses a run or specification")
        if verdict["payload"]["verdict"] != record["payload"]["verdict"] or verdict["payload"]["evidence_sha256s"] != record["payload"]["evidence_sha256s"]:
            raise ImmutableConflict("report semantics differ from its linked verdict")

    def get(self, record_type: str, sha256: str) -> dict[str, Any]:
        _assert_hash(sha256, "record hash")
        path = self._path(record_type, sha256)
        try:
            exists = path.is_file()
        except OSError as exc:
            raise ImmutableConflict("cannot inspect immutable record") from exc
        if not exists:
            raise ImmutableConflict("immutable record does not exist")
        record = load_json(path)
        validate_record(record)
        if record["content_sha256"] != sha256:
            raise ImmutableConflict("immutable record filename/hash mismatch")
        return record

    def find(self, sha256: str) -> dict[str, Any]:
        """Resolve one content hash without trusting a caller-supplied type."""

        _assert_hash(sha256, "record hash")
        matches: list[dict[str, Any]] = []
        for record_type in sorted(_PAYLOAD_FIELDS):
            path = self._path(record_type, sha256)
            try:
                exists = path.is_file()
            except OSError as exc:
                raise ImmutableConflict("cannot inspect immutable record") from exc
            if exists:
                matches.append(self.get(record_type, sha256))
        if not matches:
            raise ImmutableConflict("immutable record does not exist")
        if len(matches) != 1:
            raise ImmutableConflict("record identity is ambiguous across record types")
        return matches[0]

    def all_of_type(self, record_type: str) -> list[dict[str, Any]]:
        """Load a deterministic, validated snapshot of one record type."""

        if record_type not in _PAYLOAD_FIELDS:
            raise InvalidInput("unsupported record type")
        directory = self.experiment.owned_path("records", record_type)
        try:
            if not directory.exists():
                return []
            paths = sorted(directory.iterdir(), key=lambda item: item.name)
        except OSError as exc:
            raise ImmutableConflict("cannot enumerate immutable records") from exc
        records: list[dict[str, Any]] = []
        for path in paths:
            if path.suffix != ".json" or re.fullmatch(r"[0-9a-f]{64}\.json", path.name) is None:
                raise ImmutableConflict("record directory contains an unexpected entry")
            records.append(self.get(record_type, path.stem))
        return records

    def load_evidence(self, sha256s: list[str]) -> list[dict[str, Any]]:
        if not isinstance(sha256s, list) or not sha256s:
            raise InvalidInput("at least one evidence record hash is required")
        if len(set(sha256s)) != len(sha256s):
            raise InvalidInput("duplicate evidence record hash")
        return [self.find(sha256) for sha256 in sha256s]
