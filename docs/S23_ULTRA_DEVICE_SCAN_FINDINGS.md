# S23 Ultra Device Scan Findings

This note tracks findings from Wireless ADB validation on a Samsung Galaxy S23 Ultra.

## Verification Labels

Android may expose the source frame rate while returning no output frame-rate metadata through `MediaMetadataRetriever`. In that case the verification UI should show the neutral transition, for example:

```text
FPS: 60 -> not exposed
```

It should not append `ok` unless both source and output values are exposed and match. For Remux Only replacement safety, missing output FPS is stricter because copied-track verification is required before replacing an original.

## Samsung/OEM Graphics Warnings

The scan observed Samsung graphics/display warnings similar to:

```text
Unable to open libpenguin.so
Access denied finding property "vendor.display.enable_optimal_refresh_rate"
```

These messages came from Samsung surface, display, or MediaCodec paths during normal app startup/playback. Compressor does not depend on `libpenguin.so`, should not bundle a fake or stub vendor library, and should not request vendor display-property access. Treat these warnings as benign OEM noise unless they appear with a real Compressor failure such as a crash, ANR, playback failure, or corrupt output.

## Recheck Notes

After startup optimizations, recheck ADB logs during a cold launch and one sample compression. Document any change in warning frequency only if it correlates with app-visible behavior.
