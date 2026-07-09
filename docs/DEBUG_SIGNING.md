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

Suggested secrets:

- `COMPRESSOR_DEBUG_KEYSTORE_B64`: base64 text of `ci-debug.keystore`
- `COMPRESSOR_DEBUG_KEYSTORE_PASSWORD`: keystore password
- `COMPRESSOR_DEBUG_KEY_ALIAS`: key alias
- `COMPRESSOR_DEBUG_KEY_PASSWORD`: key password

Then restore the secret to `ci-debug.keystore` in the APK build workflow before running Gradle. The app module is already prepared to use that file when it exists.

## Important install note

If the phone currently has an APK signed with a different debug certificate, Android will require one uninstall. After installing an APK signed by the stable key, future APKs signed by that same key should update in place.
