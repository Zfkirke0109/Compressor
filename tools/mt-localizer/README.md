# MT English Overlay

A deterministic Android accessibility overlay that places English labels over non-English text exposed by MT Manager.

## Goals

- No cloud AI, no LLM, no translation API, and no internet permission.
- Restricted to MT Manager package names (`bin.mt.plus` and `bin.mt.plus.canary`).
- Reads visible accessibility text and renders non-touchable English overlays using `TYPE_ACCESSIBILITY_OVERLAY`.
- Prioritizes exact MT Manager phrases, then performs longest-term-first deterministic translation-memory replacement.
- Stores unknown visible phrases only on-device so the dictionary can be expanded later.

## Limits

This is localization assistance, not universal machine translation. Text that Android does not expose through accessibility—such as text drawn into bitmaps, protected surfaces, some canvas views, or inaccessible WebViews—cannot be translated by this build. A universal fallback would require OCR and a translation engine, which are intentionally excluded from the no-AI design.

The overlay does not modify MT Manager, its APK, plugin files, or plugin-store data. It only draws English text above the visible source labels.

## Build

The branch workflow builds a debug APK and uploads it as the `mt-english-overlay-apk` GitHub Actions artifact.

Local build:

```bash
gradle :app:assembleDebug
```

## Install and enable

1. Install `app-debug.apk`.
2. Open **MT English Overlay**.
3. Tap **Enable accessibility service**.
4. Enable **MT English Overlay Service**.
5. Open MT Manager.

No overlay permission is required because the app uses an accessibility overlay window.
