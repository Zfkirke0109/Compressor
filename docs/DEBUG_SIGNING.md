# Stable debug signing

Android updates an installed APK only when the replacement APK uses the same application ID and signing certificate.

For this fork the application ID is `io.github.zfkirke0109.galaxycompressor` (renamed from upstream's `compress.joshattic.us`, so this fork installs alongside the published upstream app instead of colliding with it). The important part is to keep the same debug signing key across APK artifacts.

## Local builds

Create one debug keystore once and keep it private:

```bash
keytool -genkeypair \
  -v \
  -keystore ci-debug.keystore \
  -storepass compressor-debug \
  -alias compressor-debug \
  -keypass compressor-debug \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=Compressor Debug,O=Compressor,C=US"
```

Place `ci-debug.keystore` at the repository root before running `./gradlew :app:assembleDebug`.

The Gradle file automatically uses that key when the file exists. If the file is missing, Android's normal generated debug key is used.

## GitHub Actions builds

Do not commit the keystore. Store it in repository secrets instead.

The workflow reads `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` and `EXPECTED_SIGNER_SHA256`. Its Gradle environment variables are documented with the setup steps. [Stable CI signing](STABLE_SIGNING.md)

## Important install note

Android requires a matching certificate for an in-place update. To retain settings and learned profiles on a stable-debug install, continue with the app APK from Android CI. Use the release-signed APK for fresh installs and existing release-signed installations. [GitHub Releases](https://github.com/Zfkirke0109/Compressor/releases/latest)
