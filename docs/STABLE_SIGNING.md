# Stable Debug Signing

Phase 4 lets GitHub Actions PR APKs use the same debug signing key every time.

That means future APKs can install as updates instead of forcing uninstall/reinstall.

## GitHub Secrets needed

Add these under:

Settings -> Secrets and variables -> Actions -> New repository secret

Required secrets (these are the names `.github/workflows/android-ci.yml` actually reads):

- KEYSTORE_BASE64
- KEYSTORE_PASSWORD
- KEY_ALIAS
- KEY_PASSWORD
- EXPECTED_SIGNER_SHA256

Do not confuse these with the `COMPRESSOR_DEBUG_*` names below. Those are the **environment
variables** the workflow sets for Gradle, not secret names — `app/build.gradle.kts` reads
`COMPRESSOR_DEBUG_KEYSTORE_PASSWORD` / `COMPRESSOR_DEBUG_KEY_ALIAS` /
`COMPRESSOR_DEBUG_KEY_PASSWORD`, and the workflow feeds them from the secrets above. An earlier
version of this document listed the env-var names as the secret names; setting those as secrets
would leave the real ones empty and fail the build at the "Required secret is not configured"
check.

`EXPECTED_SIGNER_SHA256` is the debug certificate's SHA-256 fingerprint:

    keytool -exportcert -keystore ci-debug.keystore -alias compressor-debug | sha256sum

For **release** signing — a separate key, separate secrets, and a workflow that never runs on
pull requests — see [RELEASE_SIGNING.md](RELEASE_SIGNING.md).

## Generate a stable debug keystore later

Do this later in a safe local terminal. Do not commit the keystore.

    keytool -genkeypair -v -storetype JKS -keystore ci-debug.keystore -alias compressor-debug -keyalg RSA -keysize 2048 -validity 10000 -storepass "choose-a-private-password" -keypass "choose-a-private-password" -dname "CN=Compressor Debug,O=Zfkirke0109,C=US"

Encode it for GitHub Secrets:

    base64 -w 0 ci-debug.keystore

Put that output into:

    COMPRESSOR_DEBUG_KEYSTORE_BASE64

Use the same password for:

    COMPRESSOR_DEBUG_KEYSTORE_PASSWORD
    COMPRESSOR_DEBUG_KEY_PASSWORD

Use this alias unless changed:

    compressor-debug

## Safety

Never commit:

- ci-debug.keystore
- raw passwords
- decoded secret files

The repo .gitignore already excludes ci-debug.keystore.
