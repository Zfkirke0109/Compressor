---
name: android-device-lab
description: Collect bounded Compressor Android build, install, MediaCodec, requested-versus-actual, log, thermal, and artifact evidence with strict serial/user/package/signer guards. Use for explicit device-backed lab steps, starting read-only.
---

# Android device lab

Apply the constitution and load its MediaCodec and experiment references. Begin with `pl_device_inventory` dry-run/read-only collection.

Any mutation requires explicit serial, user/profile, expected package ID, version/build, signer SHA-256, APK hash, experiment directory, budgets, and `--execute`. Every ADB call must include the same `-s` serial. Allow update-only install for a package already installed for that user; reverify package, signer/build, fingerprint, and requested-versus-actual configuration afterward.

Never select a fallback device/user, fresh-install, uninstall, clear data, reboot, root, cross profiles, or change unrelated settings. Redact the raw serial from records/logs. Wrong identity is a device mismatch; unavailable access or thermal confounding is `BLOCKED`. Device evidence cannot reinterpret a quality failure or issue the overall verdict.
