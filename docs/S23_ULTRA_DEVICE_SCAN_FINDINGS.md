# S23 Ultra Device Scan Findings

## Verification Wording

Android may expose the source frame rate while returning no output frame-rate metadata through `MediaMetadataRetriever`. In that case the verification UI should show a neutral transition, for example:

```text
FPS: 60 -> not exposed
```

It should not append `ok` unless both source and output values are exposed and match. For Remux Only replacement safety, missing output FPS is stricter because copied-track verification is required before replacing an original.

## Samsung/OEM Graphics Warnings

The S23 Ultra/One UI scan showed benign platform messages similar to:

```text
Unable to open libpenguin.so
Access denied finding property "vendor.display.enable_optimal_refresh_rate"
```

These messages came from Samsung surface, display, or MediaCodec paths during normal app startup/playback. Compressor does not depend on `libpenguin.so`, should not bundle a fake or stub vendor library, and should not request vendor display-property access. Treat these warnings as benign OEM noise unless they appear with a real Compressor failure such as a crash, ANR, playback failure, or corrupt output.

PR regression grep:

```bash
grep -RInE 'System\.loadLibrary|System\.load|SystemProperties|getprop|libpenguin|vendor\.display' app/src/main || true
```
