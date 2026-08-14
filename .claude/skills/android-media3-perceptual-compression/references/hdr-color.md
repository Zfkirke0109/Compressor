# HDR and color quality

PL evidence must preserve the declared source color identity: transfer, primaries, matrix/range when available, bit depth, static/dynamic HDR metadata, and the intended Media3 HDR mode.

## Rules

- Never silently tone-map HDR to SDR or treat decoder success as HDR preservation.
- Compare normalized, explicitly declared metadata fields and bind the extractor/tool version.
- Record both requested and actual codec/color configuration; vendor encoders may ignore a request.
- A declared lossy mode may tone-map only when the user selected that behavior and the result is labeled lossy.
- Conflicting, absent, or tool-incompatible HDR evidence is `BLOCKED`; a complete mismatch against a frozen equality requirement is `FAIL`.

Use `pl_hdr_compare` for deterministic metadata fixtures. Pixel claims still require the declared metric pipeline; metadata equality alone is not perceptual proof.
