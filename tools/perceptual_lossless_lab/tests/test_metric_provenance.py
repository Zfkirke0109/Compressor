from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import tempfile
import unittest

from pl_lab.canonical import seal_document
from pl_lab.errors import InvalidInput
from pl_lab.specification import build_experiment_spec, validate_experiment_spec
from tests.test_records_spec_judge import spec_definition, write_authority_fixture


class HostMetricProvenanceTests(unittest.TestCase):
    def test_resealed_host_metric_cannot_fabricate_name_or_version(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_authority_fixture(root)
            (root / "scripts" / "diagnostics" / "measure_quality.py").write_text(
                'SCHEMA_VERSION = 3\nVMAF_MODEL = "version=vmaf_v0.6.1"\n',
                encoding="utf-8",
            )
            definition = spec_definition(root)
            definition["metrics"] = [{
                "metric_id": "vmaf",
                "version": "3.0.0",
                "model_id": "vmaf_v0.6.1",
                "model_source": "host_libvmaf_builtin",
            }]
            spec = build_experiment_spec(
                root,
                run_id="metric-provenance-001",
                created_at="2026-08-14T00:00:00Z",
                definition=definition,
                require_all_authorities=False,
            )
            self.assertEqual(3, spec["metrics"][0]["provenance"]["harness_schema_version"])

            for key, forged in (("metric_id", "fabricated-metric"), ("version", "fabricated-version")):
                with self.subTest(key=key):
                    body = deepcopy(spec)
                    body.pop("content_sha256")
                    body["metrics"][0][key] = forged
                    resealed = seal_document("record:experiment_spec", body)
                    with self.assertRaises(InvalidInput):
                        validate_experiment_spec(resealed, require_complete_authority=False)


if __name__ == "__main__":
    unittest.main()
