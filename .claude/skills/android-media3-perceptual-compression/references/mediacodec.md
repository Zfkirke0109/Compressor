# Android MediaCodec and Media3

Inspect `BatchCompressorViewModel.kt`, `CompressorViewModel.kt`, `EncoderCapabilityDiagnostics.kt`, `DeviceCapabilityProfile.kt`, `ExperimentalEncoderControls.kt`, and the Media3 dependency declaration before changing the encoder path.

## Required evidence

- Source codec/profile/level, dimensions, FPS, bit depth, HDR/color fields, bitrate, audio tracks, and container.
- Requested MIME, profile/level, bitrate mode and target, GOP/I-frame interval, B-frame behavior, frame rate, resolution, color format, and HDR mode.
- Actual selected encoder and observed output properties. A capability-list advertisement is not proof that the requested configuration ran.
- App/source/APK hash, package/version/signer, Android build fingerprint, firmware, device alias, user/profile, thermals, and confounders.

AV1 is opt-in. H.264 is not a safe implicit HDR substitute. For hardware experiments, send `-s <serial>` on every ADB command and verify the same device/user/package identity before and after any update-only install. A mismatch is a device error; unavailable or thermally confounded evidence is `BLOCKED`.
