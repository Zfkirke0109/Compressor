import json
from pathlib import Path
from typing import Any

DIAGNOSTICS_DIR = Path(__file__).resolve().parent / "diagnostics_out"
OUTPUT_SUMMARY = Path(__file__).resolve().parent / "claude_telemetry_digest.json"


def nested_get(data: dict[str, Any], *keys: str, default: Any = None) -> Any:
    current: Any = data

    for key in keys:
        if not isinstance(current, dict):
            return default
        current = current.get(key)

        if current is None:
            return default

    return current


def safe_number(value: Any, default: float = 0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def aggregate_telemetry() -> None:
    digest: list[dict[str, Any]] = []
    errors: list[dict[str, str]] = []

    if not DIAGNOSTICS_DIR.exists():
        print(f"[!] Diagnostics directory not found: {DIAGNOSTICS_DIR}")
        print("[!] Update DIAGNOSTICS_DIR in aggregate_telemetry.py to the correct folder.")
        return

    json_files = sorted(DIAGNOSTICS_DIR.glob("*.json"))

    if not json_files:
        print(f"[!] No JSON files found in: {DIAGNOSTICS_DIR}")
        return

    for json_file in json_files:
        if json_file.name in {
            "session_summary.json",
            OUTPUT_SUMMARY.name,
        }:
            continue

        try:
            with json_file.open("r", encoding="utf-8-sig") as file_handle:
                data = json.load(file_handle)

            if not isinstance(data, dict):
                raise ValueError("Top-level JSON value is not an object")

            source_size = safe_number(data.get("source_size"))
            final_size = safe_number(data.get("final_size"))
            thermal_start = safe_number(data.get("thermal_start"))
            thermal_end = safe_number(data.get("thermal_end"))

            compression_ratio = (
                final_size / source_size
                if source_size > 0
                else None
            )

            savings_percent = (
                (1.0 - compression_ratio) * 100.0
                if compression_ratio is not None
                else None
            )

            metrics = {
                "diagnostic_file": json_file.name,
                "filename": nested_get(
                    data,
                    "source_metadata",
                    "name",
                    default=json_file.stem,
                ),
                "input_codec": nested_get(
                    data,
                    "source_metadata",
                    "codec",
                ),
                "input_bitrate": nested_get(
                    data,
                    "source_metadata",
                    "bitrate",
                ),
                "output_codec": nested_get(
                    data,
                    "output_metadata",
                    "codec",
                ),
                "output_bitrate": nested_get(
                    data,
                    "output_metadata",
                    "bitrate",
                ),
                "verdict": data.get("compress_verdict"),
                "vmaf_mean": nested_get(
                    data,
                    "vmaf_scores",
                    "mean",
                ),
                "vmaf_low_percentile": nested_get(
                    data,
                    "vmaf_scores",
                    "low_percentile",
                ),
                "vmaf_min_frame": nested_get(
                    data,
                    "vmaf_scores",
                    "min_frame",
                ),
                "overshoot_detected": bool(
                    data.get("overshoot_detected", False)
                ),
                "thermal_delta": thermal_end - thermal_start,
                "source_size": source_size,
                "final_size": final_size,
                "compression_ratio": compression_ratio,
                "savings_percent": savings_percent,
            }

            digest.append(metrics)

        except (
            OSError,
            UnicodeError,
            json.JSONDecodeError,
            ValueError,
        ) as error:
            errors.append(
                {
                    "file": json_file.name,
                    "error": str(error),
                }
            )
            print(f"[-] Error parsing {json_file.name}: {error}")

    output = {
        "diagnostics_directory": str(DIAGNOSTICS_DIR),
        "records_aggregated": len(digest),
        "records_failed": len(errors),
        "records": digest,
        "errors": errors,
    }

    with OUTPUT_SUMMARY.open("w", encoding="utf-8") as file_handle:
        json.dump(output, file_handle, indent=2, ensure_ascii=False)

    print()
    print(f"[+] Aggregated {len(digest)} records.")
    print(f"[+] Failed records: {len(errors)}")
    print(f"[+] Claude digest: {OUTPUT_SUMMARY}")


if __name__ == "__main__":
    aggregate_telemetry()
